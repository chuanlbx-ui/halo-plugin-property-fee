package cn.wenbita.propertyfee.api;

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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.Extension;
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import cn.wenbita.propertyfee.FeeQueryRequest;
import cn.wenbita.propertyfee.FeeRecord;
import cn.wenbita.propertyfee.FeeStandard;
import cn.wenbita.propertyfee.OwnerAuthService;
import cn.wenbita.propertyfee.PayOrderRequest;
import cn.wenbita.propertyfee.PaymentConfig;
import cn.wenbita.propertyfee.Property;
import cn.wenbita.propertyfee.PropertyFeeException;
import cn.wenbita.propertyfee.PropertyHelper;
import cn.wenbita.propertyfee.SecretStore;
import cn.wenbita.propertyfee.SystemConfig;
import cn.wenbita.propertyfee.TencentSmsProvider;
import cn.wenbita.propertyfee.WechatPayService;

/**
 * 前台 API：查费、创建支付订单、支付结果查询、支付回调。
 *
 * @author property-fee
 */
@Component
@AllArgsConstructor
public class PropertyFeeEndpoint implements CustomEndpoint {

    private static final org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(PropertyFeeEndpoint.class);

    private final ReactiveExtensionClient client;
    private final WechatPayService wechatPayService;
    private final OwnerAuthService ownerAuthService;
    private final TencentSmsProvider tencentSmsProvider;
    private final SecretStore secretStore;

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
            // 微信登录：一次性票据换取登录结果（令牌不经 URL 传递）
            .POST("wx/exchange", this::wxExchange, builder -> builder
                .operationId("WxExchange").description("Exchange one-time ticket for owner token.")
                .tag(tag)
                .requestBody(requestBodyBuilder().required(true)
                    .content(contentBuilder().mediaType(MediaType.APPLICATION_JSON_VALUE)
                        .schema(schemaBuilder().implementation(Map.class))))
                .response(responseBuilder().implementation(Map.class)))
            // 首次微信使用：绑定业主手机号
            .POST("wx/bind", this::wxBind, builder -> builder
                .operationId("WxBind").description("Bind wechat openid to owner phone (phone + sms code).")
                .tag(tag)
                .requestBody(requestBodyBuilder().required(true)
                    .content(contentBuilder().mediaType(MediaType.APPLICATION_JSON_VALUE)
                        .schema(schemaBuilder().implementation(Map.class))))
                .response(responseBuilder().implementation(Map.class)))
            // 前台缴费页面（收进 jar 的静态页，插件自伺服——迁移到任何 Halo 系统均可用）
            .GET("pages/property-fee", this::serveFrontPage, builder -> builder
                .operationId("ServeFrontPage").description("Serve built-in property fee front page (HTML).")
                .tag(tag)
                .response(responseBuilder().implementation(String.class)))
            .build();
    }

    /** 伺服内置前台缴费页（resources/frontend/property-fee.html）。 */
    private Mono<ServerResponse> serveFrontPage(ServerRequest request) {
        try {
            var in = getClass().getResourceAsStream("/frontend/property-fee.html");
            if (in == null) {
                return ServerResponse.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.TEXT_HTML)
                    .bodyValue("前台页面资源缺失，请检查插件安装完整性");
            }
            String html = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return ServerResponse.ok()
                .contentType(MediaType.TEXT_HTML)
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .bodyValue(html);
        } catch (Exception e) {
            return ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.TEXT_HTML)
                .bodyValue("前台页面加载失败：" + e.getMessage());
        }
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.propertyfee.halo.run/v1alpha1");
    }

    // ============ 查费 ============

    private Mono<ServerResponse> feeQuery(ServerRequest request) {
        // 匿名可查费额；但业主个人资料必须持有有效业主令牌且为该房屋登记业主才返回
        final String verifiedPhone = tryResolveOwnerPhone(request);
        return request.bodyToMono(FeeQueryRequest.class)
            .switchIfEmpty(Mono.error(new PropertyFeeException("请求体不能为空")))
            .flatMap(req -> calculateFee(req, verifiedPhone))
            .flatMap(result -> ServerResponse.ok().bodyValue(result))
            .onErrorResume(PropertyFeeException.class, e -> badRequest(e.getMessage()));
    }

    /** 尝试解析业主令牌；未携带或无效时返回 null（按匿名处理，不报错）。 */
    private String tryResolveOwnerPhone(ServerRequest request) {
        String token = request.headers().firstHeader("X-Owner-Token");
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return ownerAuthService.verifyToken(token);
        } catch (Exception e) {
            return null;
        }
    }

    private Mono<Map<String, Object>> calculateFee(FeeQueryRequest req, String verifiedPhone) {
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
                            result.put("property", toPropertyMap(property, verifiedPhone));
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
        FeeCalc calc = syncCalc(property, standard);
        int year = standard.getSpec().getYear();
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

    /** 纯计算（不查已缴）：面积×单价×月数 + 附加费 − 优惠。 */
    private FeeCalc syncCalc(Property property, FeeStandard standard) {
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
        return calc;
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
                return doCreatePayOrder(toPayOrderRequest(merged), ownerPhone, resolveSiteBase(request));
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

    private Mono<Map<String, Object>> doCreatePayOrder(PayOrderRequest req, String payerPhone,
        String siteBase) {
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

                                // 线下渠道：订单进入「待核实到账」，不得自动记为已缴
                                // 必须由具备收款权限的管理员在后台确认到账后才转为 PAID
                                if ("offline".equals(payType)) {
                                    spec.setStatus("PENDING_CONFIRM");
                                    spec.setPaidAt(null);
                                    if (req.remark() != null && !req.remark().isBlank()) {
                                        spec.setRemark(req.remark());
                                    }
                                    return client.create(rec)
                                        .thenReturn(Map.of(
                                            "outTradeNo", outTradeNo,
                                            "totalAmount", round2(calc.totalAmount),
                                            "payType", "offline",
                                            "status", "PENDING_CONFIRM",
                                            "message", "线下缴费申请已提交，待物业核实到账后确认入账"));
                                }
                                return client.create(rec).then(createWxOrder(pc, calc, outTradeNo, totalFen, req, siteBase));
                            });
                        }));
            });
    }

    private Mono<Map<String, Object>> createWxOrder(PaymentConfig pc, FeeCalc calc,
        String outTradeNo, long totalFen, PayOrderRequest req, String siteBase) {
        String community = req.community();
        String description = "物业费-" + community + "-" + req.building() + "-" + req.room()
            + "-" + req.year() + "年";
        String notifyUrl = pc.getSpec().getNotifyUrl();
        if (notifyUrl == null || notifyUrl.isBlank()) {
            // 未配置通知地址时动态推导当前站点（迁移部署无需改代码）
            notifyUrl = siteBase + "/apis/api.propertyfee.halo.run/v1alpha1/feerecords/notify";
        }
        String payType = req.payType() == null ? "" : req.payType().toLowerCase();
        if (payType.contains("jsapi")) {
            if (req.openid() == null || req.openid().isBlank()) {
                return Mono.error(new PropertyFeeException("微信内支付缺少openid，请重新点击微信登录后再试"));
            }
            return wechatPayService.createJsapiOrder(pc, description, outTradeNo, totalFen,
                    notifyUrl, req.openid())
                .map(wx -> {
                    Map<String, Object> result = new java.util.HashMap<>();
                    result.put("outTradeNo", outTradeNo);
                    result.put("totalAmount", round2(calc.totalAmount));
                    result.put("payType", "jsapi");
                    // 微信内支付：前端用 jsapiParams 调起 WeixinJSBridge 付款
                    result.put("jsapiParams",
                        wechatPayService.buildJsapiParams(pc, strOf(wx.get("prepay_id"))));
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
        // 回调必须先验签（微信支付平台证书）再解密入账；验签/核对失败一律返回 FAIL 让微信重试
        final String timestamp = request.headers().firstHeader("Wechatpay-Timestamp");
        final String nonce = request.headers().firstHeader("Wechatpay-Nonce");
        final String signature = request.headers().firstHeader("Wechatpay-Signature");
        final String serial = request.headers().firstHeader("Wechatpay-Serial");
        return readRawBody(request)
            .flatMap(body -> wechatPayService.listAllConfigs()
                .flatMap(configs -> wechatPayService.verifyNotifySignature(
                    configs, timestamp, nonce, signature, serial, body))
                .flatMap(pc -> processNotify(pc, body)))
            .flatMap(result -> ServerResponse.ok()
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(result))
            // 失败必须返回非 2xx：微信支付以 HTTP 状态码判定投递结果，
            // 返回 200 会被视为「已成功接收」而不再重试，导致丢单。
            .onErrorResume(e -> ServerResponse
                .status(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("code", "FAIL",
                    "message", e.getMessage() == null ? "error" : e.getMessage())));
    }

    /** 原样读取请求体（验签必须使用未经改动的原始报文）。 */
    private static Mono<String> readRawBody(ServerRequest request) {
        return org.springframework.core.io.buffer.DataBufferUtils
            .join(request.bodyToFlux(org.springframework.core.io.buffer.DataBuffer.class))
            .map(buf -> {
                byte[] bytes = new byte[buf.readableByteCount()];
                buf.read(bytes);
                org.springframework.core.io.buffer.DataBufferUtils.release(buf);
                return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            })
            .defaultIfEmpty("");
    }

    /** 解密回调报文（已确认商户配置）。 */
    private Mono<Map<String, Object>> processNotify(PaymentConfig pc, String body) {
        return Mono.fromCallable(() -> {
            Map<String, Object> payload = MAPPER.readValue(body, Map.class);
            Object resourceObj = payload.get("resource");
            if (!(resourceObj instanceof Map<?, ?> resource)) {
                throw new PropertyFeeException("回调报文缺少 resource");
            }
            String apiV3Key = wechatPayService.apiV3KeyOf(pc);
            String plain = WechatPayService.decryptNotifyResource(apiV3Key,
                strOf(resource.get("ciphertext")),
                strOf(resource.get("nonce")),
                strOf(resource.get("associated_data")));
            return MAPPER.readValue(plain, Map.class);
        }).flatMap(wx -> handleDecrypted(pc, wx));
    }

    /**
     * 入账处理：商户号核对 → 金额核对 → 幂等判定 → 状态流转。
     * 任何一项对不上都不入账，返回错误交由微信重试并由人工核查。
     */
    @SuppressWarnings("unchecked")
    private Mono<Map<String, Object>> handleDecrypted(PaymentConfig pc, Map<String, Object> wx) {
        String outTradeNo = strOf(wx.get("out_trade_no"));
        String tradeState = strOf(wx.get("trade_state"));
        String transactionId = strOf(wx.get("transaction_id"));
        String mchId = strOf(wx.get("mchid"));
        if (outTradeNo == null || outTradeNo.isBlank()) {
            return Mono.error(new PropertyFeeException("回调缺少 out_trade_no"));
        }
        // 1) 商户号核对：回调商户必须与配置一致，避免跨商户伪造
        if (mchId != null && !mchId.isBlank()
            && !mchId.equals(pc.getSpec().getMchId())) {
            return Mono.error(new PropertyFeeException("回调商户号与配置不一致，拒绝入账"));
        }
        // 非成功态：回调仅作通知，不改单（返回 SUCCESS 让微信停止重试）
        if (!"SUCCESS".equals(tradeState)) {
            return Mono.just(Map.of("code", "SUCCESS", "message", "OK"));
        }
        return client.fetch(FeeRecord.class, outTradeNo)
            .switchIfEmpty(Mono.error(new PropertyFeeException("订单不存在：" + outTradeNo)))
            .flatMap(rec -> {
                var spec = rec.getSpec();
                if (spec == null) {
                    return Mono.error(new PropertyFeeException("订单数据不完整"));
                }
                // 2) 金额核对：回调金额（分）必须与订单应付金额完全一致
                Long paidFen = extractFen(wx.get("amount"));
                Long expectFen = spec.getTotalAmount() == null ? null
                    : Math.round(spec.getTotalAmount() * 100);
                if (paidFen == null || expectFen == null || !paidFen.equals(expectFen)) {
                    return Mono.error(new PropertyFeeException(
                        "回调金额与订单不一致（应付 " + expectFen + " 分，实收 " + paidFen + " 分），拒绝入账"));
                }
                // 3) 幂等：已入账且交易号一致视为重复通知
                if ("PAID".equals(spec.getStatus())) {
                    if (transactionId != null && transactionId.equals(spec.getTransactionId())) {
                        return Mono.just(Map.of("code", "SUCCESS", "message", "OK"));
                    }
                    return Mono.error(new PropertyFeeException("订单已入账但交易号不一致，需人工核查"));
                }
                if (!"PENDING".equals(spec.getStatus())) {
                    return Mono.error(new PropertyFeeException(
                        "订单当前状态为 " + spec.getStatus() + "，不接受支付回调"));
                }
                spec.setStatus("PAID");
                spec.setTransactionId(transactionId);
                spec.setPaidAt(Instant.now());
                spec.setMchId(pc.getSpec().getMchId());
                spec.setPaidAmount(expectFen / 100.0);
                return client.update(rec)
                    .thenReturn(Map.of("code", "SUCCESS", "message", "OK"));
            });
    }

    private static String strOf(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    /** 从回调的 amount 对象里取分金额。 */
    private static Long extractFen(Object amountObj) {
        if (!(amountObj instanceof Map<?, ?> amount)) {
            return null;
        }
        Object total = amount.get("total");
        if (total == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(total));
        } catch (Exception e) {
            return null;
        }
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
                            && hasText(cfg.getSmsSecretId()) && hasText(cfg.getSmsSecretKey())
                            && hasText(cfg.getSmsSdkAppId())
                            && hasText(cfg.getSmsSignName()) && hasText(cfg.getSmsTemplateId());
                        if (!smsOn) {
                            // 安全要求：短信通道未配置时不允许任何形式的登录绕过
                            // （历史版本的「万能码 123456」开发通道已彻底移除）
                            return Mono.error(new PropertyFeeException(
                                "短信服务未配置，暂时无法发送验证码，请联系物业服务中心"));
                        }
                        String code = ownerAuthService.issueCode(norm);
                        String err = tencentSmsProvider.sendCode(
                            cfg.getSmsSecretId(), secretStore.decrypt(cfg.getSmsSecretKey()),
                            cfg.getSmsSdkAppId(), cfg.getSmsSignName(),
                            cfg.getSmsTemplateId(), norm, code);
                        if (err != null) {
                            ownerAuthService.invalidateCode(norm);
                            return Mono.error(new PropertyFeeException(err));
                        }
                        return ServerResponse.ok().bodyValue(Map.of(
                            "success", true, "message", "验证码已发送，请查收短信"));
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
            List<Property> mine = new ArrayList<>();
            for (Property p : all) {
                if (PropertyHelper.isOwnerPhone(p, norm)) {
                    mine.add(p);
                }
            }
            return listAll(FeeStandard.class).flatMap(standards ->
                listAll(FeeRecord.class).flatMap(records -> {
                    List<Map<String, Object>> houses = new ArrayList<>();
                    String ownerName = null;
                    for (Property p : mine) {
                        var s = p.getSpec();
                        if (s == null) {
                            continue;
                        }
                        Map<String, Object> h = new java.util.HashMap<>();
                        h.put("propertyName", p.getMetadata().getName());
                        h.put("community", s.getCommunity());
                        h.put("building", s.getBuilding());
                        h.put("unit", s.getUnit());
                        h.put("room", s.getRoom());
                        h.put("propertyType", s.getPropertyType());
                        h.put("area", s.getArea());
                        h.put("ownerName", s.getOwnerName());
                        h.put("owners", ownerSummaries(p));
                        if (ownerName == null && s.getOwnerName() != null) {
                            ownerName = s.getOwnerName();
                        }
                        // 该小区已配置收费标准的年份（倒序）
                        List<FeeStandard> stds = standards.stream()
                            .filter(fs -> fs.getSpec() != null && fs.getSpec().getYear() != null
                                && s.getCommunity() != null
                                && s.getCommunity().equals(fs.getSpec().getCommunity())
                                && !Boolean.FALSE.equals(fs.getSpec().getEnabled()))
                            .sorted((a, b) -> b.getSpec().getYear() - a.getSpec().getYear())
                            .toList();
                        List<Map<String, Object>> years = new ArrayList<>();
                        double unpaidTotal = 0;
                        java.util.Set<Integer> seenYears = new java.util.LinkedHashSet<>();
                        for (FeeStandard fs : stds) {
                            int y = fs.getSpec().getYear();
                            if (!seenYears.add(y)) {
                                continue;
                            }
                            FeeCalc calc = syncCalc(p, fs);
                            FeeRecord rec = records.stream()
                                .filter(r -> r.getSpec() != null
                                    && Integer.valueOf(y).equals(r.getSpec().getYear())
                                    && p.getMetadata().getName().equals(r.getSpec().getPropertyName())
                                    && !"REJECTED".equals(r.getSpec().getStatus()))
                                .sorted((a, b) -> {
                                    boolean pa = "PAID".equals(a.getSpec().getStatus());
                                    boolean pb = "PAID".equals(b.getSpec().getStatus());
                                    if (pa != pb) {
                                        return pa ? -1 : 1;
                                    }
                                    String ca = a.getMetadata().getCreationTimestamp() == null ? ""
                                        : a.getMetadata().getCreationTimestamp().toString();
                                    String cb = b.getMetadata().getCreationTimestamp() == null ? ""
                                        : b.getMetadata().getCreationTimestamp().toString();
                                    return cb.compareTo(ca);
                                })
                                .findFirst().orElse(null);
                            String status = rec == null || rec.getSpec().getStatus() == null
                                ? "UNPAID" : rec.getSpec().getStatus();
                            Map<String, Object> ym = new java.util.HashMap<>();
                            ym.put("year", y);
                            ym.put("amount", round2(calc.totalAmount));
                            ym.put("status", status);
                            ym.put("paidAt", rec == null || rec.getSpec().getPaidAt() == null
                                ? null : rec.getSpec().getPaidAt().toString());
                            ym.put("outTradeNo", rec == null ? null : rec.getSpec().getOutTradeNo());
                            ym.put("recordName", rec == null ? null : rec.getMetadata().getName());
                            if (!"PAID".equals(status)) {
                                unpaidTotal += calc.totalAmount;
                            }
                            years.add(ym);
                        }
                        h.put("years", years);
                        h.put("unpaidTotal", round2(unpaidTotal));
                        h.put("unitPriceTip", stds.isEmpty() ? null : stds.get(0).getSpec().getUnitPrice());
                        houses.add(h);
                    }
                    Map<String, Object> resp = new java.util.HashMap<>();
                    resp.put("phone", norm);
                    resp.put("name", ownerName);
                    resp.put("houses", houses);
                    resp.put("houseCount", houses.size());
                    return ServerResponse.ok().bodyValue(resp);
                }));
        });
    }

    /** 免验证码绑定：手机号+业主姓名与物业档案比对（姓名忽略空格）。 */
    private static boolean ownerNameMatches(Property p, String phone, String name) {
        if (p == null || p.getSpec() == null || !hasText(name) || !hasText(phone)) {
            return false;
        }
        String target = name.replaceAll("\\s", "");
        for (Property.Owner o : PropertyHelper.effectiveOwners(p.getSpec())) {
            if (o == null || !phone.equals(PropertyHelper.normalizePhone(o.getPhone()))) {
                continue;
            }
            String on = o.getName() == null ? "" : o.getName().replaceAll("\\s", "");
            if (!on.isEmpty() && on.equals(target)) {
                return true;
            }
        }
        return false;
    }

    /** 免验证码比对失败计数（防爆破）：key=手机号，连续失败≥5 次锁 10 分钟。 */
    private final Map<String, int[]> bindFailures = new java.util.concurrent.ConcurrentHashMap<>();

    private boolean isBindLocked(String phone) {
        int[] st = bindFailures.get(phone);
        if (st == null) {
            return false;
        }
        if (System.currentTimeMillis() - st[1] > 600_000L) {
            bindFailures.remove(phone);
            return false;
        }
        return st[0] >= 5;
    }

    private void recordBindFailure(String phone) {
        bindFailures.compute(phone, (k, st) -> {
            long now = System.currentTimeMillis();
            if (st == null || now - st[1] > 600_000L) {
                return new int[]{1, (int) (now / 1000)};
            }
            st[0]++;
            st[1] = (int) (now / 1000);
            return st;
        });
    }

    private void clearBindFailure(String phone) {
        bindFailures.remove(phone);
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

    /** 从请求推导当前站点基础 URL（http(s)://host），供回调/前台页面等动态拼接。
     *  优先取 X-Forwarded-Proto/X-Forwarded-Host（nginx 反代场景），其次取请求自身。 */
    private static String resolveSiteBase(ServerRequest request) {
        String proto = request.headers().firstHeader("X-Forwarded-Proto");
        String host = request.headers().firstHeader("X-Forwarded-Host");
        if (!hasText(host)) {
            host = request.headers().firstHeader("Host");
        }
        if (!hasText(host)) {
            host = "localhost";
        }
        if (!hasText(proto)) {
            proto = "https";
        }
        return proto + "://" + host;
    }

    /** 前台缴费页 URL：配置优先；为空时自动推导当前站点 + 插件伺服页面路径。 */
    private Mono<String> resolveFrontUrl(ServerRequest request) {
        return loadSystemConfig()
            .map(cfg -> {
                String front = cfg.getFrontUrl();
                if (hasText(front)) {
                    return front;
                }
                return resolveSiteBase(request)
                    + "/apis/api.propertyfee.halo.run/v1alpha1/pages/property-fee";
            });
    }

    private <E extends run.halo.app.extension.Extension> Mono<List<E>> listAll(Class<E> clazz) {
        return client.listAll(clazz, ListOptions.builder().build(),
                org.springframework.data.domain.Sort.unsorted())
            .collectList();
    }

    /**
     * 房屋信息视图。业主姓名 / 手机号属于个人资料：
     * 仅当调用方持有有效业主令牌、且该手机号确为这套房屋的登记业主时才返回；
     * 匿名调用只返回房屋本身与费用相关信息（ownerVerified=false）。
     */
    private Map<String, Object> toPropertyMap(Property p, String verifiedPhone) {
        var s = p.getSpec();
        Map<String, Object> m = new java.util.HashMap<>();
        if (s == null) {
            return m;
        }
        m.put("community", s.getCommunity());
        m.put("building", s.getBuilding());
        m.put("unit", s.getUnit());
        m.put("room", s.getRoom());
        m.put("area", s.getArea());
        m.put("propertyType", s.getPropertyType());
        m.put("houseStatus", s.getHouseStatus());
        boolean isOwner = verifiedPhone != null
            && PropertyHelper.isOwnerPhone(p, PropertyHelper.normalizePhone(verifiedPhone));
        m.put("ownerVerified", isOwner);
        if (isOwner) {
            m.put("ownerName", s.getOwnerName());
            m.put("ownerPhone", s.getOwnerPhone());
            m.put("ownerType", s.getOwnerType());
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

    // ===== 微信一键登录（安全加固：state 防重放 + 回跳白名单 + 一次性票据） =====

    /** OAuth state 暂存：单次使用、短时有效，防 CSRF / 重放。 */
    private record WxState(String redirectTarget, long expireAt) {
    }

    /** 登录票据暂存：登录结果不在 URL 中传递，改为一次性票据后台换取。 */
    private record WxTicket(String token, String phone, String openid, boolean needBind,
        long expireAt) {
    }

    private final Map<String, WxState> wxStates = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, WxTicket> wxTickets = new java.util.concurrent.ConcurrentHashMap<>();

    private static final long WX_STATE_TTL_SECONDS = 300;
    private static final long WX_TICKET_TTL_SECONDS = 120;

    /** 构造微信网页授权 URL（snsapi_base 静默拿 openid）；前端拿 url 后 location 跳转。 */
    private Mono<ServerResponse> wxAuthorize(ServerRequest request) {
        return loadSystemConfig().flatMap(cfg -> {
            if (!Boolean.TRUE.equals(cfg.getWxEnabled()) || !hasText(cfg.getWxAppId())) {
                return badRequest("微信登录未启用，请先在后台系统配置中启用（复用平台服务号）");
            }
            String fallback = hasText(cfg.getFrontUrl()) ? cfg.getFrontUrl()
                : resolveSiteBase(request)
                    + "/apis/api.propertyfee.halo.run/v1alpha1/pages/property-fee";
            String requested = request.queryParam("redirect")
                .filter(s -> hasText(s)).orElse(null);
            // 回跳地址白名单：只允许本站/已配置站点，其余一律回退默认前台页（防开放重定向）
            String redirectTarget = requested != null && isAllowedRedirect(requested, request, cfg)
                ? requested : fallback;
            String base = hasText(cfg.getWxRedirectBase())
                ? cfg.getWxRedirectBase() : resolveSiteBase(request);
            String callback = base + "/pf-wx/callback";
            String state = SecretStore.randomHex(16);
            wxStates.put(state, new WxState(redirectTarget,
                java.time.Instant.now().getEpochSecond() + WX_STATE_TTL_SECONDS));
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

    /**
     * 回跳地址白名单校验：仅允许与本站点（或已配置前台地址 / 授权回调域名）同 host 的绝对地址。
     */
    private boolean isAllowedRedirect(String target, ServerRequest request,
        SystemConfig.SystemConfigSpec cfg) {
        try {
            java.net.URI u = java.net.URI.create(target);
            String scheme = u.getScheme();
            if (scheme == null || !("http".equals(scheme) || "https".equals(scheme))) {
                return false;
            }
            String host = u.getHost();
            if (host == null || host.isBlank()) {
                return false;
            }
            java.util.Set<String> allowed = new java.util.HashSet<>();
            addHost(allowed, resolveSiteBase(request));
            addHost(allowed, cfg.getFrontUrl());
            addHost(allowed, cfg.getWxRedirectBase());
            return allowed.contains(host.toLowerCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            return false;
        }
    }

    private static void addHost(java.util.Set<String> set, String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            String h = java.net.URI.create(url).getHost();
            if (h != null && !h.isBlank()) {
                set.add(h.toLowerCase(java.util.Locale.ROOT));
            }
        } catch (Exception ignored) {
            // 忽略非法 URL
        }
    }

    /** 微信授权回调：校验 state → code 换 openid → 发一次性票据回跳（令牌不进 URL）。 */
    private Mono<ServerResponse> wxCallback(ServerRequest request) {
        String code = request.queryParam("code").orElse("");
        String stateRaw = request.queryParam("state").orElse("");
        String defaultFront = resolveSiteBase(request)
            + "/apis/api.propertyfee.halo.run/v1alpha1/pages/property-fee";
        // state 必须由本插件签发且未过期（一次性，用过即删）
        WxState st = hasText(stateRaw) ? wxStates.remove(stateRaw) : null;
        if (st == null || java.time.Instant.now().getEpochSecond() > st.expireAt()) {
            return redirectTo(withParam(defaultFront, "wx=cb&err=bad_state"));
        }
        final String redirectTarget = hasText(st.redirectTarget()) ? st.redirectTarget() : defaultFront;
        if (code.isBlank()) {
            return redirectTo(withParam(redirectTarget, "wx=cb&err=denied"));
        }
        return loadSystemConfig().flatMap(cfg -> {
            String appSecret = secretStore.decrypt(cfg.getWxAppSecret());
            if (!hasText(cfg.getWxAppId()) || !hasText(appSecret)) {
                return redirectTo(withParam(redirectTarget, "wx=cb&err=not_configured"));
            }
            String api = "https://api.weixin.qq.com/sns/oauth2/access_token?appid="
                + cfg.getWxAppId() + "&secret=" + appSecret
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
                        return redirectTo(withParam(redirectTarget, "wx=cb&err=oauth_failed"));
                    }
                    return listAll(Property.class).map(all -> {
                        long exp = java.time.Instant.now().getEpochSecond() + WX_TICKET_TTL_SECONDS;
                        for (Property p : all) {
                            String phone = PropertyHelper.findOwnerPhoneByWxOpenid(
                                p.getSpec(), openid);
                            if (phone != null) {
                                String token = ownerAuthService.issueTokenByPhone(phone);
                                String ticket = SecretStore.randomHex(24);
                                wxTickets.put(ticket, new WxTicket(token, phone, null, false, exp));
                                return withParam(redirectTarget, "wx=cb&ticket=" + ticket);
                            }
                        }
                        String ticket = SecretStore.randomHex(24);
                        wxTickets.put(ticket, new WxTicket(null, null, openid, true, exp));
                        return withParam(redirectTarget, "wx=cb&ticket=" + ticket + "&first=1");
                    }).flatMap(this::redirectTo);
                });
        });
    }

    /**
     * 用一次性票据换取登录结果。令牌与手机号只在响应体里出现，
     * 不再拼进回跳 URL（避免浏览器历史 / 日志 / Referer 泄漏）。
     */
    private Mono<ServerResponse> wxExchange(ServerRequest request) {
        return parseBody(request, Map.class)
            .flatMap(m -> {
                String ticket = m.get("ticket") == null ? null : String.valueOf(m.get("ticket"));
                if (!hasText(ticket)) {
                    return Mono.error(new PropertyFeeException("登录票据不能为空"));
                }
                WxTicket t = wxTickets.remove(ticket);
                if (t == null || java.time.Instant.now().getEpochSecond() > t.expireAt()) {
                    return Mono.error(new PropertyFeeException("登录票据已失效，请重新进入"));
                }
                if (t.needBind()) {
                    Map<String, Object> need = new java.util.HashMap<>();
                    need.put("success", true);
                    need.put("needBind", true);
                    need.put("openid", t.openid() == null ? "" : t.openid());
                    return Mono.just(need);
                }
                final String phone = t.phone();
                return listAll(Property.class).map(all -> {
                    List<Map<String, Object>> houses = new ArrayList<>();
                    String name = "";
                    for (Property p : all) {
                        if (!PropertyHelper.isOwnerPhone(p, phone)) {
                            continue;
                        }
                        var s = p.getSpec();
                        Map<String, Object> h = new java.util.HashMap<>();
                        if (s != null) {
                            h.put("community", s.getCommunity());
                            h.put("building", s.getBuilding());
                            h.put("room", s.getRoom());
                            h.put("propertyType", s.getPropertyType());
                            h.put("area", s.getArea());
                        }
                        houses.add(h);
                        if (!hasText(name)) {
                            Property.Owner o = PropertyHelper.effectiveOwners(p.getSpec()).stream()
                                .filter(x -> phone.equals(
                                    PropertyHelper.normalizePhone(x.getPhone())))
                                .findFirst().orElse(null);
                            if (o != null) {
                                name = o.getName() == null ? "" : o.getName();
                            }
                        }
                    }
                    Map<String, Object> ok = new java.util.HashMap<>();
                    ok.put("success", true);
                    ok.put("needBind", false);
                    ok.put("token", t.token());
                    ok.put("phone", phone);
                    ok.put("name", name);
                    ok.put("houses", houses);
                    ok.put("houseCount", houses.size());
                    return ok;
                });
            })
            .flatMap(v -> ServerResponse.ok().bodyValue(v))
            .onErrorResume(PropertyFeeException.class, e -> badRequest(e.getMessage()));
    }

    /** 在 URL 上追加查询串。 */
    private static String withParam(String url, String query) {
        return url + (url.contains("?") ? "&" : "?") + query;
    }

    /**
     * 把 openid 写入所有含该手机号的房屋（响应式串行写入，禁止 block）。
     * 至少成功写入一条才算成功，否则报错交由前端提示。
     */
    private Mono<Long> bindOpenidToOwners(List<Property> all, String phone, String openid) {
        List<Property> targets = new java.util.ArrayList<>();
        for (Property p : all) {
            if (PropertyHelper.isOwnerPhone(p, phone)
                && PropertyHelper.setOwnerWxOpenid(p.getSpec(), phone, openid)) {
                targets.add(p);
            }
        }
        if (targets.isEmpty()) {
            return Mono.error(new PropertyFeeException("绑定失败，请重试（未找到可绑定的业主记录）"));
        }
        return Flux.fromIterable(targets)
            .concatMap(p -> client.update(p)
                .doOnError(e -> log.warn("[property-fee] 微信绑定写入失败 {}: {}",
                    p.getMetadata().getName(), e.getMessage()))
                .onErrorResume(e -> Mono.empty()))
            .count()
            .flatMap(n -> {
                if (n == null || n == 0L) {
                    return Mono.<Long>error(new PropertyFeeException("绑定失败，请重试"));
                }
                return Mono.just(n);
            });
    }

    /**
     * 首次微信使用绑定：微信 openid + 业主手机号 +（免验证码通道：业主姓名比对 / 短信验证码通道）。
     * 校验通过后把 openid 写入该业主名下（所有含该手机号的房屋同步绑定），返回业主令牌。
     */
    private Mono<ServerResponse> wxBind(ServerRequest request) {
        return parseBody(request, Map.class)
            .flatMap(m -> {
                String openid = m.get("openid") == null ? null : String.valueOf(m.get("openid"));
                String phoneRaw = m.get("phone") == null ? null : String.valueOf(m.get("phone"));
                String code = m.get("code") == null ? null : String.valueOf(m.get("code"));
                String nameRaw = m.get("name") == null ? null : String.valueOf(m.get("name")).trim();
                if (!hasText(openid) || !hasText(phoneRaw)) {
                    return Mono.error(new PropertyFeeException("openid/手机号不能为空"));
                }
                if (!hasText(code) && !hasText(nameRaw)) {
                    return Mono.error(new PropertyFeeException("请填写业主姓名，或改用短信验证码"));
                }
                String phone = PropertyHelper.normalizePhone(phoneRaw);
                // 免验证码通道防爆破：同一手机号连续比对失败 5 次锁定 10 分钟
                if (!hasText(code) && isBindLocked(phone)) {
                    return Mono.error(new PropertyFeeException("核对失败次数过多，请 10 分钟后再试或改用短信验证码"));
                }
                // 校验业主 + 验证码（先确认手机号是登记业主）
                return listAll(Property.class).flatMap(all -> {
                    boolean isOwner = all.stream().anyMatch(p ->
                        PropertyHelper.isOwnerPhone(p, phone));
                    if (!isOwner) {
                        return Mono.error(new PropertyFeeException(
                            "该手机号未登记为业主，请联系物业核对业主档案"));
                    }
                    String token;
                    if (hasText(code)) {
                        // 通道一：短信验证码（与手机登录同一套码；须先走 auth/send-code 发码）
                        token = ownerAuthService.login(phone, code);
                    } else {
                        // 通道二（免验证码）：手机号 + 业主姓名与物业档案比对一致
                        boolean nameMatch = all.stream().anyMatch(p -> ownerNameMatches(p, phone, nameRaw));
                        if (!nameMatch) {
                            recordBindFailure(phone);
                            return Mono.error(new PropertyFeeException(
                                "业主姓名与登记信息不一致，请核对后重试，或改用短信验证码"));
                        }
                        clearBindFailure(phone);
                        token = ownerAuthService.issueTokenByPhone(phone);
                    }
                    // 绑定 openid 到所有含该手机号的房屋（响应式串行写入，禁用 block）
                    return bindOpenidToOwners(all, phone, openid)
                        .then(listAll(Property.class))
                        .flatMap(updated -> {
                            // 姓名优先用登录时填写的（免验证码通道），否则取档案里的业主姓名
                            String name = hasText(nameRaw) ? nameRaw
                                : updated.stream()
                                .filter(p -> PropertyHelper.isOwnerPhone(p, phone))
                                .map(p -> {
                                    Property.Owner o = PropertyHelper.effectiveOwners(p.getSpec())
                                        .stream().filter(x -> phone.equals(
                                            PropertyHelper.normalizePhone(x.getPhone())))
                                        .findFirst().orElse(null);
                                    return o == null || o.getName() == null ? "" : o.getName();
                                }).filter(s -> hasText(s)).findFirst().orElse("");
                            Map<String, Object> ok = new java.util.HashMap<>();
                            ok.put("success", true);
                            ok.put("message", "绑定成功，欢迎回来");
                            ok.put("token", token);
                            ok.put("phone", phone);
                            ok.put("name", name);
                            return ServerResponse.ok().bodyValue(ok);
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

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }
}
