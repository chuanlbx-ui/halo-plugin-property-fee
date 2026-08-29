package run.halo.propertyfee.api;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.content.Builder.contentBuilder;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;
import static org.springdoc.core.fn.builders.requestbody.Builder.requestBodyBuilder;
import static org.springdoc.core.fn.builders.schema.Builder.schemaBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import lombok.AllArgsConstructor;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.Extension;
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.propertyfee.FeeQueryRequest;
import run.halo.propertyfee.FeeRecord;
import run.halo.propertyfee.FeeStandard;
import run.halo.propertyfee.PayOrderRequest;
import run.halo.propertyfee.PaymentConfig;
import run.halo.propertyfee.Property;
import run.halo.propertyfee.PropertyFeeException;
import run.halo.propertyfee.WechatPayService;

/**
 * 前台 API：查费、创建支付订单、支付结果查询、支付回调。
 *
 * @author property-fee
 */
@Component
@AllArgsConstructor
public class PropertyFeeEndpoint implements CustomEndpoint {

    private final ReactiveExtensionClient client;
    private final WechatPayService wechatPayService;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final var tag = "api.propertyfee.halo.run/v1alpha1/PropertyFee";
        return SpringdocRouteBuilder.route()
            // 查费：POST /properties/fee-query  {community, building, room, year}
            .POST("properties/fee-query", this::feeQuery, builder -> builder
                .operationId("FeeQuery").description("Query property fee by community/building/room/year.")
                .tag(tag)
                .requestBody(requestBodyBuilder()
                    .required(true)
                    .content(contentBuilder()
                        .mediaType(MediaType.APPLICATION_JSON_VALUE)
                        .schema(schemaBuilder().implementation(FeeQueryRequest.class))))
                .response(responseBuilder().implementation(Map.class)))
            // 创建支付订单：POST /feerecords/pay
            .POST("feerecords/pay", this::createPayOrder, builder -> builder
                .operationId("CreatePayOrder").description("Create payment order for property fee.")
                .tag(tag)
                .requestBody(requestBodyBuilder()
                    .required(true)
                    .content(contentBuilder()
                        .mediaType(MediaType.APPLICATION_JSON_VALUE)
                        .schema(schemaBuilder().implementation(PayOrderRequest.class))))
                .response(responseBuilder().implementation(Map.class)))
            // 查询支付结果：GET /feerecords/{outTradeNo}/status
            .GET("feerecords/{outTradeNo}/status", this::queryStatus, builder -> builder
                .operationId("QueryPayStatus").description("Query payment status by out_trade_no.")
                .tag(tag)
                .parameter(parameterBuilder()
                    .name("outTradeNo").in(ParameterIn.PATH).required(true)
                    .implementation(String.class))
                .response(responseBuilder().implementation(Map.class)))
            // 支付回调：POST /feerecords/notify
            .POST("feerecords/notify", this::payNotify, builder -> builder
                .operationId("PayNotify").description("WeChat pay notify callback.")
                .tag(tag)
                .requestBody(requestBodyBuilder()
                    .required(true)
                    .content(contentBuilder()
                        .mediaType(MediaType.APPLICATION_JSON_VALUE)
                        .schema(schemaBuilder().implementation(Map.class))))
                .response(responseBuilder().implementation(Map.class)))
            // 小区/楼栋/房号数据（前台下拉联动）
            .GET("properties/options", this::queryOptions, builder -> builder
                .operationId("QueryPropertyOptions").description("List communities/buildings/rooms for dropdowns.")
                .tag(tag)
                .response(responseBuilder().implementation(Map.class)))
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.propertyfee.halo.run/v1alpha1");
    }

    // ============ 查费 ============

    private Mono<ServerResponse> feeQuery(ServerRequest request) {
        return request.bodyToMono(FeeQueryRequest.class)
            .switchIfEmpty(Mono.error(new PropertyFeeException("请求体不能为空")))
            .flatMap(this::calculateFee)
            .flatMap(result -> ServerResponse.ok().bodyValue(result))
            .onErrorResume(PropertyFeeException.class, e -> badRequest(e.getMessage()));
    }

    private Mono<Map<String, Object>> calculateFee(FeeQueryRequest req) {
        if (req.community() == null || req.community().isBlank()) {
            return Mono.error(new PropertyFeeException("请选择小区"));
        }
        if (req.building() == null || req.building().isBlank()) {
            return Mono.error(new PropertyFeeException("请选择楼栋"));
        }
        if (req.room() == null || req.room().isBlank()) {
            return Mono.error(new PropertyFeeException("请选择房号"));
        }
        int year = req.year() == null ? java.time.Year.now().getValue() : req.year();
        return findProperty(req.community(), req.building(), req.room())
            .flatMap(property -> findStandard(req.community(), year)
                .flatMap(standard -> calcFeeAmount(property, standard)
                    .map(calc -> {
                        Map<String, Object> result = new java.util.HashMap<>();
                        result.put("property", toPropertyMap(property));
                        result.put("year", year);
                        result.put("area", calc.area);
                        result.put("unitPrice", calc.unitPrice);
                        result.put("propertyFee", round2(calc.propertyFee));
                        result.put("extraFees", calc.extraFees);
                        result.put("extraFeeTotal", round2(calc.extraFeeTotal));
                        result.put("totalAmount", round2(calc.totalAmount));
                        result.put("paid", calc.paid);
                        result.put("paidAt", calc.paidAt == null ? null : calc.paidAt.toString());
                        result.put("message", calc.paid
                            ? "该房号 " + year + " 年物业费已缴纳，感谢支持！"
                            : "查询成功，请核对费用后完成支付");
                        return result;
                    })));
    }

    private Mono<Property> findProperty(String community, String building, String room) {
        return client.listAll(Property.class, ListOptions.builder().build(),
                org.springframework.data.domain.Sort.unsorted())
            .filter(p -> {
                var s = p.getSpec();
                return s != null && community.equals(s.getCommunity())
                    && building.equals(s.getBuilding()) && room.equals(s.getRoom());
            })
            .next()
            .switchIfEmpty(Mono.error(new PropertyFeeException("未找到该房屋信息，请核对小区/楼栋/房号")));
    }

    private Mono<FeeStandard> findStandard(String community, int year) {
        return client.listAll(FeeStandard.class, ListOptions.builder().build(),
                org.springframework.data.domain.Sort.unsorted())
            .filter(fs -> fs.getSpec() != null && community.equals(fs.getSpec().getCommunity())
                && Integer.valueOf(year).equals(fs.getSpec().getYear()))
            .next()
            .switchIfEmpty(Mono.error(new PropertyFeeException(
                "该小区 " + year + " 年收费标准未配置，请联系物业")));
    }

    /** 计算应缴（含已缴检查）。 */
    private Mono<FeeCalc> calcFeeAmount(Property property, FeeStandard standard) {
        FeeCalc calc = new FeeCalc();
        var ps = property.getSpec();
        var ss = standard.getSpec();
        calc.area = ps.getArea() == null ? 0 : ps.getArea();
        calc.unitPrice = ss.getUnitPrice() == null ? 0 : ss.getUnitPrice();
        calc.propertyFee = calc.area * calc.unitPrice * 12;
        calc.extraFees = new ArrayList<>();
        if (ss.getExtraFees() != null) {
            for (var ef : ss.getExtraFees()) {
                Map<String, Object> m = new java.util.HashMap<>();
                m.put("name", ef.getName());
                m.put("amount", round2(ef.getAmount() == null ? 0 : ef.getAmount()));
                calc.extraFees.add(m);
                calc.extraFeeTotal += ef.getAmount() == null ? 0 : ef.getAmount();
            }
        }
        calc.totalAmount = calc.propertyFee + calc.extraFeeTotal;
        int year = ss.getYear();
        return client.listAll(FeeRecord.class, ListOptions.builder().build(),
                org.springframework.data.domain.Sort.unsorted())
            .filter(r -> r.getSpec() != null
                && "PAID".equals(r.getSpec().getStatus())
                && Integer.valueOf(year).equals(r.getSpec().getYear())
                && property.getMetadata().getName().equals(r.getSpec().getPropertyName()))
            .next()
            .map(rec -> {
                calc.paid = true;
                calc.paidAt = rec.getSpec().getPaidAt();
                return calc;
            })
            .defaultIfEmpty(calc);
    }

    private static class FeeCalc {
        double area;
        double unitPrice;
        double propertyFee;
        List<Map<String, Object>> extraFees;
        double extraFeeTotal;
        double totalAmount;
        boolean paid;
        Instant paidAt;
    }

    // ============ 创建支付订单 ============

    private Mono<ServerResponse> createPayOrder(ServerRequest request) {
        return request.bodyToMono(PayOrderRequest.class)
            .switchIfEmpty(Mono.error(new PropertyFeeException("请求体不能为空")))
            .flatMap(req -> doCreatePayOrder(req))
            .flatMap(result -> ServerResponse.ok().bodyValue(result))
            .onErrorResume(PropertyFeeException.class, e -> badRequest(e.getMessage()));
    }

    private Mono<Map<String, Object>> doCreatePayOrder(PayOrderRequest req) {
        if (req.community() == null || req.community().isBlank()
            || req.building() == null || req.building().isBlank()
            || req.room() == null || req.room().isBlank()) {
            return Mono.error(new PropertyFeeException("请完整选择小区/楼栋/房号"));
        }
        int year = req.year() == null ? java.time.Year.now().getValue() : req.year();
        return findProperty(req.community(), req.building(), req.room())
            .flatMap(property -> findStandard(req.community(), year)
                .flatMap(standard -> wechatPayService.getConfig(req.community())
                    .flatMap(pc -> {
                        return calcFeeAmount(property, standard).flatMap(calc -> {
                            if (calc.paid) {
                                return Mono.error(new PropertyFeeException("该房号 " + year
                                    + " 年物业费已缴纳，无需重复缴费"));
                            }
                            // 生成订单号
                            String outTradeNo = "PF" + System.currentTimeMillis()
                                + (int) (Math.random() * 900 + 100);
                            long totalFen = Math.round(calc.totalAmount * 100);

                            // 写入 FeeRecord（PENDING）
                            FeeRecord rec = new FeeRecord();
                            Metadata meta = new Metadata();
                            meta.setName(outTradeNo);
                            rec.setMetadata(meta);
                            FeeRecord.FeeRecordSpec spec = new FeeRecord.FeeRecordSpec();
                            spec.setCommunity(req.community());
                            spec.setBuilding(req.building());
                            spec.setRoom(req.room());
                            spec.setPropertyName(property.getMetadata().getName());
                            spec.setYear(year);
                            spec.setArea(calc.area);
                            spec.setPropertyFee(round2(calc.propertyFee));
                            spec.setExtraFee(round2(calc.extraFeeTotal));
                            spec.setTotalAmount(round2(calc.totalAmount));
                            spec.setPaidAmount(round2(calc.totalAmount));
                            spec.setStatus("PENDING");
                            spec.setMchId(pc.getSpec().getMchId());
                            spec.setOutTradeNo(outTradeNo);
                            spec.setPayType(req.payType() == null ? "native" : req.payType());
                            spec.setCreatedAt(Instant.now());
                            spec.setOwnerName(property.getSpec().getOwnerName());
                            spec.setOwnerPhone(property.getSpec().getOwnerPhone());
                            rec.setSpec(spec);

                            return client.create(rec).then(createWxOrder(pc, calc, outTradeNo, totalFen, req));
                        });
                    })));
    }

    private Mono<Map<String, Object>> createWxOrder(PaymentConfig pc, FeeCalc calc,
        String outTradeNo, long totalFen, PayOrderRequest req) {
        String community = req.community();
        String description = "物业费-" + community + "-" + req.building() + "-" + req.room()
            + "-" + req.year() + "年";
        String notifyUrl = pc.getSpec().getNotifyUrl();
        if (notifyUrl == null || notifyUrl.isBlank()) {
            notifyUrl = "https://wenbita.cn/apis/api.propertyfee.halo.run/v1alpha1/feerecords/notify";
        }
        if ("jsapi".equals(req.payType())) {
            if (req.openid() == null || req.openid().isBlank()) {
                return Mono.error(new PropertyFeeException("微信内支付缺少openid，请重新进入"));
            }
            return wechatPayService.createJsapiOrder(pc, description, outTradeNo, totalFen,
                    notifyUrl, req.openid())
                .map(wx -> {
                    Map<String, Object> result = new java.util.HashMap<>();
                    result.put("outTradeNo", outTradeNo);
                    result.put("totalAmount", round2(calc.totalAmount));
                    result.put("prepayId", wx.get("prepay_id"));
                    result.put("appId", pc.getSpec().getAppId());
                    result.put("mchId", pc.getSpec().getMchId());
                    result.put("payType", "jsapi");
                    return result;
                });
        }
        // 默认 Native 扫码
        return wechatPayService.createNativeOrder(pc, description, outTradeNo, totalFen, notifyUrl)
            .map(wx -> {
                Map<String, Object> result = new java.util.HashMap<>();
                result.put("outTradeNo", outTradeNo);
                result.put("totalAmount", round2(calc.totalAmount));
                result.put("codeUrl", wx.get("code_url"));
                result.put("mchId", pc.getSpec().getMchId());
                result.put("payType", "native");
                return result;
            });
    }

    // ============ 查询支付状态 ============

    private Mono<ServerResponse> queryStatus(ServerRequest request) {
        String outTradeNo = request.pathVariable("outTradeNo");
        return client.fetch(FeeRecord.class, outTradeNo)
            .switchIfEmpty(Mono.error(new PropertyFeeException("订单不存在")))
            .map(rec -> {
                Map<String, Object> result = new java.util.HashMap<>();
                result.put("outTradeNo", outTradeNo);
                result.put("status", rec.getSpec() == null ? "UNKNOWN" : rec.getSpec().getStatus());
                result.put("paidAt", rec.getSpec() != null && rec.getSpec().getPaidAt() != null
                    ? rec.getSpec().getPaidAt().toString() : null);
                result.put("totalAmount", rec.getSpec() == null ? null
                    : rec.getSpec().getTotalAmount());
                return result;
            })
            .flatMap(result -> ServerResponse.ok().bodyValue(result))
            .onErrorResume(PropertyFeeException.class, e -> badRequest(e.getMessage()));
    }

    // ============ 支付回调 ============

    private Mono<ServerResponse> payNotify(ServerRequest request) {
        return request.bodyToMono(Map.class)
            .flatMap(body -> {
                Object resourceObj = body.get("resource");
                if (resourceObj == null) {
                    return Mono.error(new PropertyFeeException("Missing resource"));
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> resource = (Map<String, Object>) resourceObj;
                String ciphertext = String.valueOf(resource.get("ciphertext"));
                String nonce = String.valueOf(resource.get("nonce"));
                String associatedData = String.valueOf(
                    resource.getOrDefault("associated_data", ""));
                return processNotify(ciphertext, nonce, associatedData);
            })
            .flatMap(result -> ServerResponse.ok().bodyValue(result))
            .onErrorResume(e -> ServerResponse.ok().bodyValue(Map.of("code", "FAIL",
                "message", e.getMessage() == null ? "error" : e.getMessage())));
    }

    private Mono<Map<String, Object>> processNotify(String ciphertext, String nonce,
        String associatedData) {
        // 先从 PENDING 订单找到对应小区（用 out_trade_no 无法直接拿商户配置）
        // 策略：解密需要 apiV3Key，先查全部 PaymentConfig 逐个尝试，或用订单反查
        return client.listAll(FeeRecord.class, ListOptions.builder().build(),
                org.springframework.data.domain.Sort.unsorted())
            .filter(r -> r.getSpec() != null && "PENDING".equals(r.getSpec().getStatus()))
            .collectList()
            .flatMap(pendingList -> {
                if (pendingList.isEmpty()) {
                    return Mono.just(Map.of("code", "SUCCESS", "message", "OK"));
                }
                return wechatPayService.listAllConfigs()
                    .flatMap(configs -> decryptAndProcess(configs, ciphertext, nonce,
                        associatedData));
            });
    }

    private Mono<Map<String, Object>> decryptAndProcess(List<PaymentConfig> configs,
        String ciphertext, String nonce, String associatedData) {
        // 逐个商户配置尝试解密（小区少时可行；后续可优化为按 out_trade_no 前缀定位）
        return Mono.defer(() -> {
            for (PaymentConfig pc : configs) {
                String apiV3Key = pc.getSpec() == null ? null : pc.getSpec().getApiV3Key();
                if (apiV3Key == null || apiV3Key.isBlank()) {
                    continue;
                }
                try {
                    String decrypted = WechatPayService.decryptNotifyResource(
                        apiV3Key, ciphertext, nonce, associatedData);
                    Map<String, Object> wx = MAPPER.readValue(decrypted, Map.class);
                    return handleDecrypted(pc, wx);
                } catch (Exception ignored) {
                    // 密钥不匹配继续尝试下一个
                }
            }
            return Mono.error(new PropertyFeeException("回调解密失败，无法匹配商户配置"));
        });
    }

    private Mono<Map<String, Object>> handleDecrypted(PaymentConfig pc, Map<String, Object> wx) {
        String outTradeNo = String.valueOf(wx.get("out_trade_no"));
        String tradeState = String.valueOf(wx.get("trade_state"));
        String transactionId = String.valueOf(wx.get("transaction_id"));
        if (!"SUCCESS".equals(tradeState)) {
            return Mono.just(Map.of("code", "SUCCESS", "message", "OK"));
        }
        return client.fetch(FeeRecord.class, outTradeNo)
            .switchIfEmpty(Mono.error(new PropertyFeeException("订单不存在")))
            .flatMap(rec -> {
                var spec = rec.getSpec();
                if (spec == null) {
                    return Mono.error(new PropertyFeeException("订单数据不完整"));
                }
                if ("PAID".equals(spec.getStatus())) {
                    return Mono.just(Map.of("code", "SUCCESS", "message", "OK"));
                }
                spec.setStatus("PAID");
                spec.setTransactionId(transactionId);
                spec.setPaidAt(Instant.now());
                spec.setMchId(pc.getSpec().getMchId());
                return client.update(rec)
                    .thenReturn(Map.of("code", "SUCCESS", "message", "OK"));
            });
    }

    // ============ 下拉选项数据 ============

    private Mono<ServerResponse> queryOptions(ServerRequest request) {
        return client.listAll(Property.class, ListOptions.builder().build(),
                org.springframework.data.domain.Sort.unsorted())
            .collectList()
            .map(props -> {
                Map<String, Object> result = new java.util.HashMap<>();
                java.util.LinkedHashSet<String> communities = new java.util.LinkedHashSet<>();
                Map<String, java.util.LinkedHashSet<String>> buildingsByCommunity = new java.util.HashMap<>();
                Map<String, java.util.LinkedHashSet<String>> roomsByBuilding = new java.util.HashMap<>();
                for (Property p : props) {
                    var s = p.getSpec();
                    if (s == null) {
                        continue;
                    }
                    communities.add(s.getCommunity());
                    buildingsByCommunity.computeIfAbsent(s.getCommunity(),
                        k -> new java.util.LinkedHashSet<>()).add(s.getBuilding());
                    roomsByBuilding.computeIfAbsent(s.getCommunity() + "|" + s.getBuilding(),
                        k -> new java.util.LinkedHashSet<>()).add(s.getRoom());
                }
                result.put("communities", new ArrayList<>(communities));
                result.put("buildings", buildingsByCommunity);
                result.put("rooms", roomsByBuilding);
                return result;
            })
            .flatMap(result -> ServerResponse.ok().bodyValue(result))
            .onErrorResume(PropertyFeeException.class, e -> badRequest(e.getMessage()));
    }

    // ============ 工具 ============

    private Map<String, Object> toPropertyMap(Property p) {
        var s = p.getSpec();
        Map<String, Object> m = new java.util.HashMap<>();
        if (s != null) {
            m.put("community", s.getCommunity());
            m.put("building", s.getBuilding());
            m.put("unit", s.getUnit());
            m.put("room", s.getRoom());
            m.put("area", s.getArea());
            m.put("ownerName", s.getOwnerName());
            m.put("ownerPhone", s.getOwnerPhone());
        }
        return m;
    }

    private static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private Mono<ServerResponse> badRequest(String message) {
        return ServerResponse.status(HttpStatus.BAD_REQUEST)
            .bodyValue(Map.of("message", message));
    }
}
