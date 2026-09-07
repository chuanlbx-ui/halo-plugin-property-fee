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
import run.halo.propertyfee.OwnerAuthService;
import run.halo.propertyfee.PayOrderRequest;
import run.halo.propertyfee.PaymentConfig;
import run.halo.propertyfee.Property;
import run.halo.propertyfee.PropertyFeeException;
import run.halo.propertyfee.PropertyHelper;
import run.halo.propertyfee.SystemConfig;
import run.halo.propertyfee.TencentSmsProvider;
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
    private final OwnerAuthService ownerAuthService;
    private final TencentSmsProvider tencentSmsProvider;

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
            // 业主登录：发送短信验证码
            .POST("auth/send-code", this::sendCode, builder -> builder
                .operationId("OwnerSendCode").description("Send SMS verify code to owner phone.")
                .tag(tag)
                .requestBody(requestBodyBuilder().required(true)
                    .content(contentBuilder().mediaType(MediaType.APPLICATION_JSON_VALUE)
                        .schema(schemaBuilder().implementation(Map.class))))
                .response(responseBuilder().implementation(Map.class)))
            // 业主登录：手机号+验证码换 Token
            .POST("auth/login", this::ownerLogin, builder -> builder
                .operationId("OwnerLogin").description("Login owner by phone + verify code, return token.")
                .tag(tag)
                .requestBody(requestBodyBuilder().required(true)
                    .content(contentBuilder().mediaType(MediaType.APPLICATION_JSON_VALUE)
                        .schema(schemaBuilder().implementation(Map.class))))
                .response(responseBuilder().implementation(Map.class)))
            // 名下房屋（业主登录后）——POST（Halo 匿名规则对新增 GET 不生效，统一 POST）
            .POST("owner/houses", this::ownerHouses, builder -> builder
                .operationId("OwnerHouses").description("List houses of logged-in owner.")
                .tag(tag)
                .response(responseBuilder().implementation(Map.class)))
            // 微信一键登录（复用平台服务号网页授权；回调经 aiedu.yn.cn 已配授权域名 → nginx 转 POST 反代到本端点）
            .POST("wx/authorize", this::wxAuthorize, builder -> builder
                .operationId("WxAuthorize").description("Build WeChat OAuth authorize URL (snsapi_base).")
                .tag(tag)
                .response(responseBuilder().implementation(Map.class)))
            .POST("wx/callback", this::wxCallback, builder -> builder
                .operationId("WxCallback").description("WeChat OAuth callback (nginx proxy_method POST): openid -> owner token or first-bind hint.")
                .tag(tag)
                .response(responseBuilder().implementation(Map.class)))
            // 首次微信使用：绑定业主手机号
            .POST("wx/bind", this::wxBind, builder -> builder
                .operationId("WxBind").description("Bind wechat openid to owner phone (phone + sms code).")
                .tag(tag)
                .requestBody(requestBodyBuilder().required(true)
                    .content(contentBuilder().mediaType(MediaType.APPLICATION_JSON_VALUE)
                        .schema(schemaBuilder().implementation(Map.class))))
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
            .flatMap(property -> findStandard(req.community(), property.getSpec() == null
                    ? null : property.getSpec().getPropertyType(), year)
                .flatMap(standard -> calcFeeAmount(property, standard)
                    .flatMap(calc -> wechatPayService.listChannels(req.community())
                        .map(channels -> {
                            Map<String, Object> result = new java.util.HashMap<>();
                            result.put("property", toPropertyMap(property));
                            result.put("year", year);
                            result.put("area", calc.area);
                            result.put("unitPrice", calc.unitPrice);
                            result.put("propertyType", calc.propertyType);
                            result.put("billingCycle", calc.billingCycle);
                            result.put("propertyFee", round2(calc.propertyFee));
                            result.put("extraFees", calc.extraFees);
                            result.put("extraFeeTotal", round2(calc.extraFeeTotal));
                            result.put("discountAmount", round2(calc.discountAmount));
                            result.put("totalAmount", round2(calc.totalAmount));
                            result.put("paid", calc.paid);
                            result.put("paidAt", calc.paidAt == null ? null : calc.paidAt.toString());
                            result.put("channels", channels);
                            result.put("message", calc.paid
                                ? "该房号 " + year + " 年物业费已缴纳，感谢支持！"
                                : "查询成功，请核对费用后完成支付");
                            return result;
                        }))));
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

    private Mono<FeeStandard> findStandard(String community, String propertyType, int year) {
        return client.listAll(FeeStandard.class, ListOptions.builder().build(),
                org.springframework.data.domain.Sort.unsorted())
            .filter(fs -> fs.getSpec() != null && community.equals(fs.getSpec().getCommunity())
                && Integer.valueOf(year).equals(fs.getSpec().getYear())
                && !Boolean.FALSE.equals(fs.getSpec().getEnabled()))
            .collectList()
            .flatMap(list -> {
                if (list.isEmpty()) {
                    return Mono.error(new PropertyFeeException(
                        "该小区 " + year + " 年收费标准未配置，请联系物业"));
                }
                // 优先匹配物业类型（住宅/商铺/车位）；否则取第一条
                if (propertyType != null && !propertyType.isBlank()) {
                    var hit = list.stream().filter(fs -> propertyType.equals(fs.getSpec().getPropertyType()))
                        .findFirst().orElse(null);
                    if (hit != null) {
                        return Mono.just(hit);
                    }
                }
                return Mono.just(list.get(0));
            });
    }

    /** 计算应缴（含已缴检查）。支持：周期换算、优惠减免、物业类型。 */
    private Mono<FeeCalc> calcFeeAmount(Property property, FeeStandard standard) {
        FeeCalc calc = new FeeCalc();
        var ps = property.getSpec();
        var ss = standard.getSpec();
        calc.area = ps.getArea() == null ? 0 : ps.getArea();
        calc.unitPrice = ss.getUnitPrice() == null ? 0 : ss.getUnitPrice();
        calc.propertyType = ss.getPropertyType() == null ? "住宅" : ss.getPropertyType();
        calc.billingCycle = ss.getBillingCycle() == null ? "year" : ss.getBillingCycle();
        // 物业费：面积 × 单价 × 月数（按周期换算，年缴12个月）
        int months = monthsOf(calc.billingCycle);
        calc.propertyFee = calc.area * calc.unitPrice * months;
        calc.extraFees = new ArrayList<>();
        if (ss.getExtraFees() != null) {
            for (var ef : ss.getExtraFees()) {
                Map<String, Object> m = new java.util.HashMap<>();
                m.put("name", ef.getName());
                double amt = ef.getAmount() == null ? 0 : ef.getAmount();
                // 计费方式：fixed 固定金额 / perArea 按面积 / perMonth 按月
                String mode = ef.getChargeMode() == null ? "fixed" : ef.getChargeMode();
                if ("perArea".equals(mode)) {
                    amt = amt * calc.area;
                } else if ("perMonth".equals(mode)) {
                    amt = amt * months;
                }
                m.put("amount", round2(amt));
                m.put("chargeMode", mode);
                calc.extraFees.add(m);
                calc.extraFeeTotal += amt;
            }
        }
        double subtotal = calc.propertyFee + calc.extraFeeTotal;
        // 优惠减免
        calc.discountAmount = 0;
        if (ss.getDiscount() != null) {
            var d = ss.getDiscount();
            if ("amount".equals(d.getType()) && d.getAmount() != null) {
                calc.discountAmount = Math.min(d.getAmount(), subtotal);
            } else if ("percent".equals(d.getType()) && d.getPercent() != null) {
                calc.discountAmount = subtotal * d.getPercent();
            } else if ("firstYear".equals(d.getType())) {
                // 首年优惠：减免一半（可自定义）
                double pct = d.getPercent() == null ? 0.5 : d.getPercent();
                calc.discountAmount = subtotal * pct;
            }
        }
        calc.totalAmount = subtotal - calc.discountAmount;
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

    /** 缴费周期 → 月数。 */
    private int monthsOf(String cycle) {
        if ("half".equals(cycle)) {
            return 6;
        }
        if ("quarter".equals(cycle)) {
            return 3;
        }
        if ("month".equals(cycle)) {
            return 1;
        }
        return 12;
    }

    private static class FeeCalc {
        double area;
        double unitPrice;
        String propertyType;
        String billingCycle;
        double propertyFee;
        List<Map<String, Object>> extraFees;
        double extraFeeTotal;
        double discountAmount;
        double totalAmount;
        boolean paid;
        Instant paidAt;
    }

    // ============ 创建支付订单 ============

    private Mono<ServerResponse> createPayOrder(ServerRequest request) {
        // Halo 2.26 运行时对部分 JSON body（特定长度/内容组合）存在 bodyToMono 返回
        // empty 的竞态（报"请求体不能为空"，与 record/Map 类型无关，实测稳定复现）。
        // 方案：body 为空时自动回退读取 query 参数，保证下单链路可用。
        // V4 业主守卫：在线缴费必须携带业主登录凭证（X-Owner-Token），防止缴错/代缴。
        String payerPhone;
        try {
            payerPhone = ownerAuthService.verifyToken(request.headers().firstHeader("X-Owner-Token"));
        } catch (PropertyFeeException e) {
            return badRequest("请先登录业主账号（微信/手机验证码）后再缴费");
        }
        String ownerPhone = payerPhone;
        return request.bodyToMono(Map.class)
            .defaultIfEmpty(new java.util.HashMap<>())
            .flatMap(m -> {
                // body 字段优先；body 缺失/解析异常时用 query 参数补全（Halo 2.26
                // 对部分 JSON body 存在解析竞态返回 empty/空 Map）
                Map<String, Object> merged = new java.util.HashMap<>((Map<String, Object>) m);
                merged.putAll(fromQueryParams(request));
                return doCreatePayOrder(toPayOrderRequest(merged), ownerPhone);
            })
            .flatMap(result -> ServerResponse.ok().bodyValue(result))
            .onErrorResume(PropertyFeeException.class, e -> badRequest(e.getMessage()));
    }

    private static Map<String, Object> fromQueryParams(ServerRequest request) {
        Map<String, Object> m = new java.util.HashMap<>();
        putIfPresent(m, request, "community");
        putIfPresent(m, request, "building");
        putIfPresent(m, request, "room");
        putIfPresent(m, request, "year");
        putIfPresent(m, request, "payType");
        putIfPresent(m, request, "payChannel");
        putIfPresent(m, request, "openid");
        putIfPresent(m, request, "remark");
        return m;
    }

    private static void putIfPresent(Map<String, Object> m, ServerRequest request, String name) {
        request.queryParam(name).ifPresent(v -> m.put(name, v));
    }

    private static PayOrderRequest toPayOrderRequest(Map<?, ?> m) {
        Integer year = m.get("year") == null ? null
            : Integer.parseInt(String.valueOf(m.get("year")));
        return new PayOrderRequest(
            m.get("community") == null ? null : String.valueOf(m.get("community")),
            m.get("building") == null ? null : String.valueOf(m.get("building")),
            m.get("room") == null ? null : String.valueOf(m.get("room")),
            year,
            m.get("payType") == null ? null : String.valueOf(m.get("payType")),
            m.get("payChannel") == null ? null : String.valueOf(m.get("payChannel")),
            m.get("openid") == null ? null : String.valueOf(m.get("openid")),
            m.get("remark") == null ? null : String.valueOf(m.get("remark")));
    }

    private Mono<Map<String, Object>> doCreatePayOrder(PayOrderRequest req, String payerPhone) {
        if (req.community() == null || req.community().isBlank()
            || req.building() == null || req.building().isBlank()
            || req.room() == null || req.room().isBlank()) {
            return Mono.error(new PropertyFeeException("请完整选择小区/楼栋/房号"));
        }
        int year = req.year() == null ? java.time.Year.now().getValue() : req.year();
        String payType = req.payType() == null ? "native" : req.payType();
        return findProperty(req.community(), req.building(), req.room())
            .flatMap(property -> {
                // V4 业主守卫：登录手机号必须属于该房屋登记业主（多业主任一命中）
                if (payerPhone != null && !payerPhone.isBlank()
                    && !PropertyHelper.isOwnerPhone(property, payerPhone)) {
                    return Mono.error(new PropertyFeeException(
                        "您不是该房屋「" + req.community() + " " + req.building() + " "
                            + req.room() + "」的登记业主，不能为其缴费。如有疑问请联系物业核对业主档案"));
                }
                return findStandard(req.community(), property.getSpec() == null
                        ? null : property.getSpec().getPropertyType(), year)
                    .flatMap(standard -> wechatPayService.getConfig(req.community(), payType,
                            req.payChannel())
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
                                spec.setPayType(payType);
                                spec.setCreatedAt(Instant.now());
                                spec.setOwnerName(property.getSpec().getOwnerName());
                                spec.setOwnerPhone(property.getSpec().getOwnerPhone());
                                spec.setPayerPhone(payerPhone);
                                rec.setSpec(spec);

                                // 线下渠道：直接标记 PAID + 备注（管理员确认收款后）
                                if ("offline".equals(payType)) {
                                    spec.setStatus("PAID");
                                    spec.setPaidAt(Instant.now());
                                    spec.setTransactionId("OFFLINE-" + outTradeNo);
                                    if (req.remark() != null && !req.remark().isBlank()) {
                                        spec.setRemark(req.remark());
                                    }
                                    return client.create(rec)
                                        .thenReturn(Map.of(
                                            "outTradeNo", outTradeNo,
                                            "totalAmount", round2(calc.totalAmount),
                                            "payType", "offline",
                                            "status", "PAID",
                                            "message", "线下收款已登记，缴费完成"));
                                }
                                return client.create(rec).then(createWxOrder(pc, calc, outTradeNo, totalFen, req));
                            });
                        }));
            });
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

    // ============ 业主登录（V4：手机号+短信验证码；后续微信 OAuth 绑定复用同一 Token 体系） ============

    private Mono<ServerResponse> sendCode(ServerRequest request) {
        return parseBody(request, Map.class)
            .flatMap(m -> {
                String phone = m.get("phone") == null ? null : String.valueOf(m.get("phone"));
                if (phone == null || phone.isBlank()) {
                    return Mono.error(new PropertyFeeException("手机号不能为空"));
                }
                // 必须是登记业主手机号（任一房屋 owners/单业主字段命中）
                return listAll(Property.class).flatMap(all -> {
                    boolean isOwner = all.stream()
                        .anyMatch(p -> PropertyHelper.isOwnerPhone(p,
                            PropertyHelper.normalizePhone(phone)));
                    if (!isOwner) {
                        return Mono.error(new PropertyFeeException(
                            "该手机号未登记为业主，请联系物业核对业主档案"));
                    }
                    String norm = PropertyHelper.normalizePhone(phone);
                    return loadSystemConfig().flatMap(cfg -> {
                        // 平台已配置腾讯云短信（复用 LMS lib/sms.ts 同一套凭据/签名/模板）→ 真实发码
                        boolean smsOn = Boolean.TRUE.equals(cfg.getSmsEnabled())
                            && hasText(cfg.getSmsSecretId()) && hasText(cfg.getSmsSdkAppId())
                            && hasText(cfg.getSmsSignName()) && hasText(cfg.getSmsTemplateId());
                        if (smsOn) {
                            String code = ownerAuthService.issueCode(norm);
                            if (code == null) {
                                return Mono.error(new PropertyFeeException("发送失败，请稍后再试"));
                            }
                            String err = tencentSmsProvider.sendCode(
                                cfg.getSmsSecretId(), cfg.getSmsSecretKey(),
                                cfg.getSmsSdkAppId(), cfg.getSmsSignName(),
                                cfg.getSmsTemplateId(), norm, code);
                            if (err != null) {
                                return Mono.error(new PropertyFeeException(err));
                            }
                            return ServerResponse.ok().bodyValue(Map.of(
                                "success", true, "message", "验证码已发送，请查收短信"));
                        }
                        // 未配置真实短信 → 开发通道万能码
                        boolean sent = ownerAuthService.sendCode(norm);
                        return ServerResponse.ok().bodyValue(Map.of(
                            "success", sent, "message", "验证码已发送（测试通道：万能码 123456）"));
                    });
                });
            })
            .onErrorResume(PropertyFeeException.class, e -> badRequest(e.getMessage()));
    }

    private Mono<ServerResponse> ownerLogin(ServerRequest request) {
        return parseBody(request, Map.class)
            .flatMap(m -> {
                String phone = m.get("phone") == null ? null : String.valueOf(m.get("phone"));
                String code = m.get("code") == null ? null : String.valueOf(m.get("code"));
                String token = ownerAuthService.login(phone, code);
                String norm = PropertyHelper.normalizePhone(phone);
                return listAll(Property.class).flatMap(all -> {
                    List<Map<String, Object>> houses = new ArrayList<>();
                    for (Property p : all) {
                        if (PropertyHelper.isOwnerPhone(p, norm)) {
                            var s = p.getSpec();
                            Map<String, Object> h = new java.util.HashMap<>();
                            if (s != null) {
                                h.put("community", s.getCommunity());
                                h.put("building", s.getBuilding());
                                h.put("room", s.getRoom());
                                h.put("propertyType", s.getPropertyType());
                                h.put("area", s.getArea());
                                h.put("owners", ownerSummaries(p));
                            }
                            houses.add(h);
                        }
                    }
                    return ServerResponse.ok().bodyValue(Map.of(
                        "token", token, "phone", norm,
                        "houses", houses, "houseCount", houses.size()));
                });
            })
            .onErrorResume(PropertyFeeException.class, e -> badRequest(e.getMessage()));
    }

    private Mono<ServerResponse> ownerHouses(ServerRequest request) {
        String phone;
        try {
            phone = ownerAuthService.verifyToken(
                request.headers().firstHeader("X-Owner-Token"));
        } catch (PropertyFeeException e) {
            return badRequest(e.getMessage());
        }
        String norm = PropertyHelper.normalizePhone(phone);
        return listAll(Property.class).flatMap(all -> {
            List<Map<String, Object>> houses = new ArrayList<>();
            for (Property p : all) {
                if (PropertyHelper.isOwnerPhone(p, norm)) {
                    var s = p.getSpec();
                    Map<String, Object> h = new java.util.HashMap<>();
                    if (s != null) {
                        h.put("community", s.getCommunity());
                        h.put("building", s.getBuilding());
                        h.put("room", s.getRoom());
                        h.put("propertyType", s.getPropertyType());
                        h.put("area", s.getArea());
                        h.put("ownerName", s.getOwnerName());
                        h.put("owners", ownerSummaries(p));
                    }
                    houses.add(h);
                }
            }
            return ServerResponse.ok().bodyValue(Map.of(
                "phone", norm, "houses", houses, "houseCount", houses.size()));
        }).onErrorResume(PropertyFeeException.class, e -> badRequest(e.getMessage()));
    }

    /** 业主列表摘要（脱敏手机号用于展示，如 138****8000）。 */
    private static List<Map<String, Object>> ownerSummaries(Property p) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (p.getSpec() == null) {
            return out;
        }
        for (Property.Owner o : PropertyHelper.effectiveOwners(p.getSpec())) {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("name", o.getName());
            String ph = PropertyHelper.normalizePhone(o.getPhone());
            m.put("phone", ph == null ? null
                : (ph.length() == 11 ? ph.substring(0, 3) + "****" + ph.substring(7) : ph));
            m.put("type", o.getType());
            m.put("isPrimary", Boolean.TRUE.equals(o.getIsPrimary()));
            out.add(m);
        }
        return out;
    }

    // ============ 工具 ============

    private <E extends run.halo.app.extension.Extension> Mono<List<E>> listAll(Class<E> clazz) {
        return client.listAll(clazz, ListOptions.builder().build(),
                org.springframework.data.domain.Sort.unsorted())
            .collectList();
    }

    private Map<String, Object> toPropertyMap(Property p) {
        var s = p.getSpec();
        Map<String, Object> m = new java.util.HashMap<>();
        if (s != null) {
            m.put("community", s.getCommunity());
            m.put("building", s.getBuilding());
            m.put("unit", s.getUnit());
            m.put("room", s.getRoom());
            m.put("area", s.getArea());
            m.put("propertyType", s.getPropertyType());
            m.put("ownerName", s.getOwnerName());
            m.put("ownerPhone", s.getOwnerPhone());
            m.put("ownerType", s.getOwnerType());
            m.put("houseStatus", s.getHouseStatus());
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

    // ============ 微信一键登录（复用平台服务号 OAuth，凭据在后台系统配置注入） ============

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * Halo 2.26 较长 JSON body 的对象/Map 解码存在偶发空值竞态；
     * 以原始字符串接收再手动反序列化（String 通道稳定）。
     */
    private static <T> Mono<T> parseBody(ServerRequest request, Class<T> clazz) {
        return request.bodyToMono(String.class)
            .defaultIfEmpty("{}")
            .map(raw -> {
                try {
                    return MAPPER.readValue(raw, clazz);
                } catch (Exception e) {
                    throw new PropertyFeeException("请求体解析失败，请重试");
                }
            });
    }

    /** 读取平台级配置（SystemConfig pf-platform-config，短信/微信均复用平台既有配置）。 */
    private Mono<SystemConfig.SystemConfigSpec> loadSystemConfig() {
        return client.fetch(SystemConfig.class, SystemConfig.FIXED_NAME)
            .map(SystemConfig::getSpec)
            .defaultIfEmpty(new SystemConfig.SystemConfigSpec());
    }

    /** 构造微信网页授权 URL（snsapi_base 静默拿 openid）；前端拿 url 后 location 跳转。 */
    private Mono<ServerResponse> wxAuthorize(ServerRequest request) {
        return loadSystemConfig().flatMap(cfg -> {
            if (!Boolean.TRUE.equals(cfg.getWxEnabled()) || !hasText(cfg.getWxAppId())) {
                return badRequest("微信登录未启用，请先在后台系统配置中启用（复用平台服务号）");
            }
            String redirectTarget = request.queryParam("redirect")
                .filter(s -> hasText(s)).orElse("https://wenbita.cn/property-fee.html");
            String base = hasText(cfg.getWxRedirectBase())
                ? cfg.getWxRedirectBase() : "https://aiedu.yn.cn";
            // 回调经 aiedu.yn.cn（公众号后台已配网页授权域名）→ nginx /pf-wx/ 反代回本插件
            String callback = base + "/pf-wx/callback";
            String state = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(redirectTarget.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String url = "https://open.weixin.qq.com/connect/oauth2/authorize"
                + "?appid=" + cfg.getWxAppId()
                + "&redirect_uri=" + urlEncode(callback)
                + "&response_type=code"
                + "&scope=snsapi_base"
                + "&state=" + state
                + "#wechat_redirect";
            return ServerResponse.ok().bodyValue(Map.of("success", true, "url", url));
        }).onErrorResume(PropertyFeeException.class, e -> badRequest(e.getMessage()));
    }

    /** 微信授权回调：code 换 openid → 命中已绑定业主发 Token；未命中提示先绑定手机号。 */
    private Mono<ServerResponse> wxCallback(ServerRequest request) {
        String code = request.queryParam("code").orElse("");
        final String redirectTarget = decodeRedirect(request.queryParam("state").orElse(""));
        if (code.isBlank()) {
            return redirectTo(redirectTarget + (redirectTarget.contains("?") ? "&" : "?")
                + "wx=cb&err=denied");
        }
        return loadSystemConfig().flatMap(cfg -> {
            if (!hasText(cfg.getWxAppId()) || !hasText(cfg.getWxAppSecret())) {
                return redirectTo(redirectTarget + (redirectTarget.contains("?") ? "&" : "?")
                    + "wx=cb&err=not_configured");
            }
            String api = "https://api.weixin.qq.com/sns/oauth2/access_token?appid="
                + cfg.getWxAppId() + "&secret=" + cfg.getWxAppSecret()
                + "&code=" + code + "&grant_type=authorization_code";
            return Mono.fromCallable(() -> {
                    try {
                        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create(api)).GET().build();
                        java.net.http.HttpResponse<String> resp = java.net.http.HttpClient
                            .newHttpClient().send(req,
                                java.net.http.HttpResponse.BodyHandlers.ofString());
                        return resp.body();
                    } catch (Exception e) {
                        return "{\"errcode\":-1,\"errmsg\":\"request fail\"}";
                    }
                })
                .flatMap(body -> {
                    String openidTmp = null;
                    try {
                        openidTmp = MAPPER.readTree(body).path("openid").asText(null);
                    } catch (Exception ignore) {
                        // noop
                    }
                    final String openid = openidTmp;
                    if (openid == null || openid.isBlank()) {
                        return redirectTo(redirectTarget
                            + (redirectTarget.contains("?") ? "&" : "?")
                            + "wx=cb&err=oauth_failed");
                    }
                    // 命中已绑定业主 → 直接发业主 Token
                    return listAll(Property.class).flatMap(all -> {
                        for (Property p : all) {
                            String phone = PropertyHelper.findOwnerPhoneByWxOpenid(
                                p.getSpec(), openid);
                            if (phone != null) {
                                String token = ownerAuthService.issueTokenByPhone(phone);
                                return redirectTo(redirectTarget
                                    + (redirectTarget.contains("?") ? "&" : "?")
                                    + "wx=cb&ok=1&token=" + token + "&phone=" + phone);
                            }
                        }
                        return redirectTo(redirectTarget
                            + (redirectTarget.contains("?") ? "&" : "?")
                            + "wx=cb&first=1&wxopenid=" + openid);
                    });
                });
        });
    }

    /**
     * 首次微信使用绑定：微信 openid + 业主手机号 + 短信验证码。
     * 校验短信码成功后把 openid 写入该业主名下（所有含该手机号的房屋同步绑定），返回业主 Token。
     */
    private Mono<ServerResponse> wxBind(ServerRequest request) {
        return parseBody(request, Map.class)
            .flatMap(m -> {
                String openid = m.get("openid") == null ? null : String.valueOf(m.get("openid"));
                String phoneRaw = m.get("phone") == null ? null : String.valueOf(m.get("phone"));
                String code = m.get("code") == null ? null : String.valueOf(m.get("code"));
                if (!hasText(openid) || !hasText(phoneRaw) || !hasText(code)) {
                    return Mono.error(new PropertyFeeException("openid/手机号/验证码不能为空"));
                }
                String phone = PropertyHelper.normalizePhone(phoneRaw);
                // 校验业主 + 验证码（先确认手机号是登记业主）
                return listAll(Property.class).flatMap(all -> {
                    boolean isOwner = all.stream().anyMatch(p ->
                        PropertyHelper.isOwnerPhone(p, phone));
                    if (!isOwner) {
                        return Mono.error(new PropertyFeeException(
                            "该手机号未登记为业主，请联系物业核对业主档案"));
                    }
                    // 校验短信验证码（与手机登录同一套码；微信绑定前须先走 auth/send-code 发码）
                    String token = ownerAuthService.login(phone, code);
                    // 绑定 openid 到所有含该手机号的房屋
                    boolean bound = false;
                    for (Property p : all) {
                        if (PropertyHelper.isOwnerPhone(p, phone)) {
                            Property.PropertySpec spec = p.getSpec();
                            if (PropertyHelper.setOwnerWxOpenid(spec, phone, openid)) {
                                p.setSpec(spec);
                                try {
                                    client.update(p).block();
                                    bound = true;
                                } catch (Exception ignore) {
                                    // 单条失败不阻断其余
                                }
                            }
                        }
                    }
                    if (!bound) {
                        return Mono.error(new PropertyFeeException("绑定失败，请重试"));
                    }
                    return listAll(Property.class).flatMap(updated -> {
                        String name = updated.stream()
                            .filter(p -> PropertyHelper.isOwnerPhone(p, phone))
                            .map(p -> {
                                Property.Owner o = PropertyHelper.effectiveOwners(p.getSpec())
                                    .stream().filter(x -> phone.equals(
                                        PropertyHelper.normalizePhone(x.getPhone())))
                                    .findFirst().orElse(null);
                                return o == null ? "" : o.getName();
                            }).filter(s -> hasText(s)).findFirst().orElse("");
                        return ServerResponse.ok().bodyValue(Map.of(
                            "success", true, "message", "绑定成功，欢迎回来",
                            "token", token, "phone", phone, "name", name));
                    });
                });
            })
            .onErrorResume(PropertyFeeException.class, e -> badRequest(e.getMessage()));
    }

    private Mono<ServerResponse> redirectTo(String location) {
        return Mono.just(ServerResponse.temporaryRedirect(
            java.net.URI.create(location)).build())
            .flatMap(sr -> sr);
    }

    private static String decodeRedirect(String stateRaw) {
        try {
            return new String(java.util.Base64.getUrlDecoder().decode(stateRaw),
                java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "https://wenbita.cn/property-fee.html";
        }
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }
}
