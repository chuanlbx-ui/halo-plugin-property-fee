package run.halo.propertyfee.console;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.content.Builder.contentBuilder;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;
import static org.springdoc.core.fn.builders.requestbody.Builder.requestBodyBuilder;
import static org.springdoc.core.fn.builders.schema.Builder.schemaBuilder;

import io.swagger.v3.oas.annotations.enums.ParameterIn;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.propertyfee.Community;
import run.halo.propertyfee.CommunityImportRequest;
import run.halo.propertyfee.Property;
import run.halo.propertyfee.PropertyHelper;
import run.halo.propertyfee.PropertyImportRequest;
import run.halo.propertyfee.FeeRecord;
import run.halo.propertyfee.FeeStandard;
import run.halo.propertyfee.PaymentConfig;
import run.halo.propertyfee.SystemConfig;
import run.halo.propertyfee.Property;
import run.halo.propertyfee.PropertyFeeException;
import run.halo.propertyfee.PropertyImportRequest;

/**
 * 控制台 API：基础配置（Property/FeeStandard/PaymentConfig）、Excel导入、报表统计。
 *
 * @author property-fee
 */
@Component
@AllArgsConstructor
public class PropertyFeeConsoleEndpoint implements CustomEndpoint {

    private final ReactiveExtensionClient client;

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    /** Halo 2.26 对较长 JSON body 的对象/Map 解码存在偶发空值竞态（误报"空"/存默认值）。
     *  以原始字符串接收再手动反序列化规避（实测 String 通道稳定）。 */
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

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final var tag = "console.api.propertyfee.halo.run/v1alpha1/PropertyFee";
        return SpringdocRouteBuilder.route()
            // ===== 房屋 CRUD =====
            .GET("properties", this::listProperties, builder -> builder
                .operationId("ListProperties").description("List properties.").tag(tag))
            .POST("properties", this::createProperty, builder -> builder
                .operationId("CreateProperty").description("Create property.").tag(tag))
            .PUT("properties/{name}", this::updateProperty, builder -> builder
                .operationId("UpdateProperty").description("Update property.").tag(tag)
                .parameter(parameterBuilder().name("name").in(ParameterIn.PATH).required(true)
                    .implementation(String.class)))
            .DELETE("properties/{name}", this::deleteProperty, builder -> builder
                .operationId("DeleteProperty").description("Delete property.").tag(tag)
                .parameter(parameterBuilder().name("name").in(ParameterIn.PATH).required(true)
                    .implementation(String.class)))
            // ===== 小区配置 CRUD =====
            .GET("communities", this::listCommunities, builder -> builder
                .operationId("ListCommunities").description("List community configs.").tag(tag))
            .POST("communities", this::createCommunity, builder -> builder
                .operationId("CreateCommunity").description("Create community config.").tag(tag))
            .PUT("communities/{name}", this::updateCommunity, builder -> builder
                .operationId("UpdateCommunity").description("Update community config.").tag(tag)
                .parameter(parameterBuilder().name("name").in(ParameterIn.PATH).required(true)
                    .implementation(String.class)))
            .DELETE("communities/{name}", this::deleteCommunity, builder -> builder
                .operationId("DeleteCommunity").description("Delete community config.").tag(tag)
                .parameter(parameterBuilder().name("name").in(ParameterIn.PATH).required(true)
                    .implementation(String.class)))
            // 小区/楼栋批量导入（Excel 行：小区名、楼栋；同小区自动合并去重）
            .POST("communities/import", this::importCommunities, builder -> builder
                .operationId("ImportCommunities").description("Batch import communities/buildings.")
                .tag(tag))
            // ===== 房屋批量导入 =====
            .POST("properties/import", this::importProperties, builder -> builder
                .operationId("ImportProperties").description("Batch import properties.")
                .tag(tag)
                .requestBody(requestBodyBuilder().required(true)
                    .content(contentBuilder().mediaType(MediaType.APPLICATION_JSON_VALUE)
                        .schema(schemaBuilder().implementation(PropertyImportRequest.class))))
                .response(responseBuilder().implementation(Map.class)))
            // ===== 收费标准 CRUD =====
            .GET("feestandards", this::listStandards, builder -> builder
                .operationId("ListFeeStandards").description("List fee standards.").tag(tag))
            .POST("feestandards", this::createStandard, builder -> builder
                .operationId("CreateFeeStandard").description("Create fee standard.").tag(tag))
            .PUT("feestandards/{name}", this::updateStandard, builder -> builder
                .operationId("UpdateFeeStandard").description("Update fee standard.").tag(tag)
                .parameter(parameterBuilder().name("name").in(ParameterIn.PATH).required(true)
                    .implementation(String.class)))
            .DELETE("feestandards/{name}", this::deleteStandard, builder -> builder
                .operationId("DeleteFeeStandard").description("Delete fee standard.").tag(tag)
                .parameter(parameterBuilder().name("name").in(ParameterIn.PATH).required(true)
                    .implementation(String.class)))
            // ===== 商户配置 CRUD =====
            .GET("paymentconfigs", this::listConfigs, builder -> builder
                .operationId("ListPaymentConfigs").description("List payment configs.").tag(tag))
            .POST("paymentconfigs", this::createConfig, builder -> builder
                .operationId("CreatePaymentConfig").description("Create payment config.").tag(tag))
            .PUT("paymentconfigs/{name}", this::updateConfig, builder -> builder
                .operationId("UpdatePaymentConfig").description("Update payment config.").tag(tag)
                .parameter(parameterBuilder().name("name").in(ParameterIn.PATH).required(true)
                    .implementation(String.class)))
            .DELETE("paymentconfigs/{name}", this::deleteConfig, builder -> builder
                .operationId("DeletePaymentConfig").description("Delete payment config.").tag(tag)
                .parameter(parameterBuilder().name("name").in(ParameterIn.PATH).required(true)
                    .implementation(String.class)))
            // ===== 系统配置（短信/微信：复用平台既有配置，后台可维护） =====
            .GET("systemconfig", this::getSystemConfig, builder -> builder
                .operationId("GetSystemConfig").description("Get platform system config (sms/wx).").tag(tag))
            .PUT("systemconfig", this::updateSystemConfig, builder -> builder
                .operationId("UpdateSystemConfig").description("Update platform system config (sms/wx).").tag(tag))
            // ===== 缴费记录 =====
            .GET("feerecords", this::listFeeRecords, builder -> builder
                .operationId("ListFeeRecords").description("List fee records.").tag(tag))
            // ===== 报表统计 =====
            .GET("reports/summary", this::summaryReport, builder -> builder
                .operationId("SummaryReport").description("Summary report by community/building.")
                .tag(tag)
                .parameter(parameterBuilder().name("year").in(ParameterIn.QUERY)
                    .implementation(Integer.class).required(false))
                .response(responseBuilder().implementation(Map.class)))
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("console.api.propertyfee.halo.run/v1alpha1");
    }

    // ============ 房屋 ============

    private Mono<ServerResponse> listProperties(ServerRequest request) {
        return listAll(Property.class)
            .map(list -> new ListResult<>(list))
            .flatMap(r -> ServerResponse.ok().bodyValue(r))
            .onErrorResume(PropertyFeeException.class, e -> badRequest(e.getMessage()));
    }

    private Mono<ServerResponse> createProperty(ServerRequest request) {
        return parseBody(request, Property.class)
            .flatMap(p -> {
                if (p.getSpec() == null || !hasText(p.getSpec().getCommunity())) {
                    return Mono.error(new PropertyFeeException("小区名称不能为空"));
                }
                Property.PropertySpec s = p.getSpec();
                if (!hasText(s.getBuilding()) || !hasText(s.getRoom())) {
                    return Mono.error(new PropertyFeeException("楼栋/房号不能为空"));
                }
                // 多业主：主业主同步回单字段（兼容报表/Excel/老逻辑）
                PropertyHelper.syncPrimaryFields(s);
                // 小区/楼栋必须在配置内（配置化管控，避免手填不准）
                return validateCommunityBuilding(s)
                    .then(client.create(p));
            })
            .flatMap(p -> ServerResponse.ok().bodyValue(p))
            .onErrorResume(PropertyFeeException.class, e -> badRequest(e.getMessage()));
    }

    private Mono<ServerResponse> updateProperty(ServerRequest request) {
        String name = request.pathVariable("name");
        return parseBody(request, Property.class)
            .flatMap(p -> {
                Property.PropertySpec s = p.getSpec();
                if (s == null || !hasText(s.getCommunity())) {
                    return Mono.error(new PropertyFeeException("小区名称不能为空"));
                }
                if (!hasText(s.getBuilding()) || !hasText(s.getRoom())) {
                    return Mono.error(new PropertyFeeException("楼栋/房号不能为空"));
                }
                PropertyHelper.syncPrimaryFields(s);
                return validateCommunityBuilding(s).thenReturn(p);
            })
            .flatMap(p -> client.fetch(Property.class, name)
                .flatMap(existing -> {
                    p.getMetadata().setName(name);
                    // Halo 乐观锁：version 为空时自动补旧值，防止 update 退化为 create
                    if (p.getMetadata().getVersion() == null) {
                        p.getMetadata().setVersion(existing.getMetadata().getVersion());
                    }
                    return client.update(p);
                }))
            .flatMap(p -> ServerResponse.ok().bodyValue(p))
            .onErrorResume(PropertyFeeException.class, e -> badRequest(e.getMessage()));
    }

    private Mono<ServerResponse> deleteProperty(ServerRequest request) {
        String name = request.pathVariable("name");
        return client.fetch(Property.class, name)
            .flatMap(p -> client.delete(p))
            .then(ServerResponse.noContent().build())
            .onErrorResume(PropertyFeeException.class, e -> badRequest(e.getMessage()));
    }

    // ============ 批量导入 ============

    private Mono<ServerResponse> importProperties(ServerRequest request) {
        return parseBody(request, PropertyImportRequest.class)
            .switchIfEmpty(Mono.error(new PropertyFeeException("导入数据不能为空")))
            .flatMap(imp -> {
                if (imp.rows() == null || imp.rows().isEmpty()) {
                    return Mono.error(new PropertyFeeException("导入数据为空"));
                }
                // 先查已有（避免重复导入）
                return listAll(Property.class).flatMap(existing -> {
                    Map<String, Property> byKey = new HashMap<>();
                    for (Property p : existing) {
                        var s = p.getSpec();
                        if (s != null) {
                            byKey.put(keyOf(s.getCommunity(), s.getBuilding(), s.getRoom()), p);
                        }
                    }
                    int created = 0;
                    int skipped = 0;
                    List<Mono<Property>> ops = new ArrayList<>();
                    for (PropertyImportRequest.ImportRow row : imp.rows()) {
                        if (row.community() == null || row.community().isBlank()
                            || row.building() == null || row.building().isBlank()
                            || row.room() == null || row.room().isBlank()) {
                            skipped++;
                            continue;
                        }
                        String k = keyOf(row.community(), row.building(), row.room());
                        Property existingP = byKey.get(k);
                        if (existingP != null) {
                            // 更新面积/业主信息（多业主：每行业主追加进 owners，主业主同步单字段）
                            var s = existingP.getSpec();
                            boolean changed = false;
                            if (row.area() != null && !row.area().equals(s.getArea())) {
                                s.setArea(row.area());
                                changed = true;
                            }
                            if (appendImportOwner(s, row)) {
                                changed = true;
                            }
                            if (row.moveInDate() != null && !row.moveInDate().isBlank()
                                && !row.moveInDate().equals(s.getMoveInDate())) {
                                s.setMoveInDate(row.moveInDate());
                                changed = true;
                            }
                            if (row.houseStatus() != null && !row.houseStatus().isBlank()
                                && !row.houseStatus().equals(s.getHouseStatus())) {
                                s.setHouseStatus(row.houseStatus());
                                changed = true;
                            }
                            if (row.propertyType() != null && !row.propertyType().isBlank()
                                && !row.propertyType().equals(s.getPropertyType())) {
                                s.setPropertyType(row.propertyType());
                                changed = true;
                            }
                            if (changed) {
                                ops.add(client.update(existingP));
                                created++;
                            } else {
                                skipped++;
                            }
                        } else {
                            Property p = new Property();
                            Metadata meta = new Metadata();
                            meta.setGenerateName("property-");
                            p.setMetadata(meta);
                            Property.PropertySpec spec = new Property.PropertySpec();
                            spec.setCommunity(row.community());
                            spec.setBuilding(row.building());
                            spec.setUnit(row.unit());
                            spec.setRoom(row.room());
                            spec.setArea(row.area());
                            spec.setOwnerName(row.ownerName());
                            spec.setOwnerPhone(row.ownerPhone());
                            spec.setOwnerIdCard(row.ownerIdCard());
                            spec.setOwnerType(row.ownerType() == null || row.ownerType().isBlank()
                                ? "业主" : row.ownerType());
                            spec.setMoveInDate(row.moveInDate());
                            spec.setHouseStatus(row.houseStatus() == null || row.houseStatus().isBlank()
                                ? "自住" : row.houseStatus());
                            spec.setPropertyType(row.propertyType() == null || row.propertyType().isBlank()
                                ? "住宅" : row.propertyType());
                            p.setSpec(spec);
                            appendImportOwner(spec, row);
                            PropertyHelper.syncPrimaryFields(spec);
                            ops.add(client.create(p));
                            created++;
                        }
                    }
                    return Mono.when(ops)
                        .thenReturn(Map.of("success", true, "created", created,
                            "skipped", skipped, "total", imp.rows().size()));
                });
            })
            .flatMap(result -> ServerResponse.ok().bodyValue(result))
            .onErrorResume(PropertyFeeException.class, e -> badRequest(e.getMessage()));
    }

    // ============ 收费标准 ============

    private Mono<ServerResponse> listStandards(ServerRequest request) {
        return listAll(FeeStandard.class)
            .map(list -> new ListResult<>(list))
            .flatMap(r -> ServerResponse.ok().bodyValue(r))
            .onErrorResume(PropertyFeeException.class, e -> badRequest(e.getMessage()));
    }

    private Mono<ServerResponse> createStandard(ServerRequest request) {
        return parseBody(request, FeeStandard.class)
            .flatMap(fs -> {
                if (fs.getSpec() == null || fs.getSpec().getCommunity() == null
                    || fs.getSpec().getCommunity().isBlank()) {
                    return Mono.error(new PropertyFeeException("小区名称不能为空"));
                }
                if (fs.getSpec().getYear() == null) {
                    return Mono.error(new PropertyFeeException("收费年份不能为空"));
                }
                if (fs.getSpec().getUnitPrice() == null || fs.getSpec().getUnitPrice() <= 0) {
                    return Mono.error(new PropertyFeeException("物业单价必须大于0"));
                }
                return client.create(fs);
            })
            .flatMap(fs -> ServerResponse.ok().bodyValue(fs))
            .onErrorResume(PropertyFeeException.class, e -> badRequest(e.getMessage()));
    }

    private Mono<ServerResponse> updateStandard(ServerRequest request) {
        String name = request.pathVariable("name");
        return parseBody(request, FeeStandard.class)
            .flatMap(fs -> client.fetch(FeeStandard.class, name)
                .flatMap(existing -> {
                    fs.getMetadata().setName(name);
                    if (fs.getMetadata().getVersion() == null) {
                        fs.getMetadata().setVersion(existing.getMetadata().getVersion());
                    }
                    return client.update(fs);
                }))
            .flatMap(fs -> ServerResponse.ok().bodyValue(fs))
            .onErrorResume(PropertyFeeException.class, e -> badRequest(e.getMessage()));
    }

    private Mono<ServerResponse> deleteStandard(ServerRequest request) {
        String name = request.pathVariable("name");
        return client.fetch(FeeStandard.class, name)
            .flatMap(fs -> client.delete(fs))
            .then(ServerResponse.noContent().build())
            .onErrorResume(PropertyFeeException.class, e -> badRequest(e.getMessage()));
    }

    // ============ 商户配置 ============

    private Mono<ServerResponse> listConfigs(ServerRequest request) {
        return listAll(PaymentConfig.class)
            .map(list -> new ListResult<>(list))
            .flatMap(r -> ServerResponse.ok().bodyValue(r))
            .onErrorResume(PropertyFeeException.class, e -> badRequest(e.getMessage()));
    }

    private Mono<ServerResponse> createConfig(ServerRequest request) {
        return parseBody(request, PaymentConfig.class)
            .flatMap(pc -> {
                if (pc.getSpec() == null || pc.getSpec().getCommunity() == null
                    || pc.getSpec().getCommunity().isBlank()) {
                    return Mono.error(new PropertyFeeException("小区名称不能为空"));
                }
                String ct = pc.getSpec().getChannelType() == null ? "wechat_native"
                    : pc.getSpec().getChannelType();
                // 微信渠道必须配置商户号；线下/支付宝不强制
                if (("wechat_native".equals(ct) || "wechat_jsapi".equals(ct))
                    && (pc.getSpec().getMchId() == null || pc.getSpec().getMchId().isBlank())) {
                    return Mono.error(new PropertyFeeException("微信渠道商户号不能为空"));
                }
                return client.create(pc);
            })
            .flatMap(pc -> ServerResponse.ok().bodyValue(pc))
            .onErrorResume(PropertyFeeException.class, e -> badRequest(e.getMessage()));
    }

    private Mono<ServerResponse> updateConfig(ServerRequest request) {
        String name = request.pathVariable("name");
        return parseBody(request, PaymentConfig.class)
            .flatMap(pc -> client.fetch(PaymentConfig.class, name)
                .flatMap(existing -> {
                    pc.getMetadata().setName(name);
                    if (pc.getMetadata().getVersion() == null) {
                        pc.getMetadata().setVersion(existing.getMetadata().getVersion());
                    }
                    return client.update(pc);
                }))
            .flatMap(pc -> ServerResponse.ok().bodyValue(pc))
            .onErrorResume(PropertyFeeException.class, e -> badRequest(e.getMessage()));
    }

    private Mono<ServerResponse> deleteConfig(ServerRequest request) {
        String name = request.pathVariable("name");
        return client.fetch(PaymentConfig.class, name)
            .flatMap(pc -> client.delete(pc))
            .then(ServerResponse.noContent().build())
            .onErrorResume(PropertyFeeException.class, e -> badRequest(e.getMessage()));
    }

    // ============ 缴费记录 ============

    private Mono<ServerResponse> listFeeRecords(ServerRequest request) {
        return listAll(FeeRecord.class)
            .map(list -> new ListResult<>(list))
            .flatMap(r -> ServerResponse.ok().bodyValue(r))
            .onErrorResume(PropertyFeeException.class, e -> badRequest(e.getMessage()));
    }

    // ============ 报表统计 ============

    private Mono<ServerResponse> summaryReport(ServerRequest request) {
        int year = request.queryParam("year").map(Integer::parseInt)
            .orElse(java.time.Year.now().getValue());
        return listAll(Property.class)
            .flatMap(props -> listAll(FeeRecord.class)
                .map(records -> {
                    Map<String, Object> result = new HashMap<>();
                    // 按小区统计
                    Map<String, Map<String, Object>> byCommunity = new HashMap<>();
                    // 小区 -> 楼栋 -> 统计
                    Map<String, Map<String, Object>> byBuilding = new HashMap<>();
                    // 总统计
                    int totalProps = 0;
                    int paidProps = 0;
                    double totalAmount = 0;
                    double paidAmount = 0;
                    java.util.Map<String, Property> propByName = new HashMap<>();
                    for (Property p : props) {
                        propByName.put(p.getMetadata().getName(), p);
                    }
                    // 收集已缴集合
                    java.util.Set<String> paidKeys = new java.util.HashSet<>();
                    for (FeeRecord r : records) {
                        var s = r.getSpec();
                        if (s != null && "PAID".equals(s.getStatus())
                            && Integer.valueOf(year).equals(s.getYear())) {
                            paidKeys.add(s.getPropertyName());
                            paidAmount += s.getPaidAmount() == null ? 0 : s.getPaidAmount();
                        }
                    }
                    for (Property p : props) {
                        var s = p.getSpec();
                        if (s == null) {
                            continue;
                        }
                        totalProps++;
                        totalAmount += 0; // 未缴金额按标准算太复杂，报表用户数统计
                        boolean paid = paidKeys.contains(p.getMetadata().getName());
                        if (paid) {
                            paidProps++;
                        }
                        // 小区统计
                        Map<String, Object> comm = (Map<String, Object>) byCommunity
                            .computeIfAbsent(s.getCommunity(), k -> new HashMap<>());
                        comm.put("community", s.getCommunity());
                        comm.put("total", ((Number) comm.getOrDefault("total", 0)).intValue() + 1);
                        comm.put("paid", ((Number) comm.getOrDefault("paid", 0)).intValue()
                            + (paid ? 1 : 0));
                        // 楼栋统计
                        Map<String, Object> bld = (Map<String, Object>) byBuilding
                            .computeIfAbsent(s.getCommunity() + "|" + s.getBuilding(),
                                k -> new HashMap<>());
                        bld.put("community", s.getCommunity());
                        bld.put("building", s.getBuilding());
                        bld.put("total", ((Number) bld.getOrDefault("total", 0)).intValue() + 1);
                        bld.put("paid", ((Number) bld.getOrDefault("paid", 0)).intValue()
                            + (paid ? 1 : 0));
                    }
                    // 计算收缴率
                    for (Map<String, Object> m : byCommunity.values()) {
                        int total = ((Number) m.get("total")).intValue();
                        int paid = ((Number) m.get("paid")).intValue();
                        m.put("unpaid", total - paid);
                        m.put("rate", total == 0 ? 0 : Math.round(paid * 1000.0 / total) / 10.0);
                    }
                    for (Map<String, Object> m : byBuilding.values()) {
                        int total = ((Number) m.get("total")).intValue();
                        int paid = ((Number) m.get("paid")).intValue();
                        m.put("unpaid", total - paid);
                        m.put("rate", total == 0 ? 0 : Math.round(paid * 1000.0 / total) / 10.0);
                    }
                    result.put("year", year);
                    result.put("totalProperties", totalProps);
                    result.put("paidProperties", paidProps);
                    result.put("unpaidProperties", totalProps - paidProps);
                    result.put("overallRate", totalProps == 0 ? 0
                        : Math.round(paidProps * 1000.0 / totalProps) / 10.0);
                    result.put("paidAmount", Math.round(paidAmount * 100) / 100.0);
                    result.put("byCommunity", new ArrayList<>(byCommunity.values()));
                    result.put("byBuilding", new ArrayList<>(byBuilding.values()));
                    return result;
                }))
            .flatMap(result -> ServerResponse.ok().bodyValue(result))
            .onErrorResume(PropertyFeeException.class, e -> badRequest(e.getMessage()));
    }

    // ============ 工具 ============

    private <E extends run.halo.app.extension.Extension> Mono<List<E>> listAll(Class<E> clazz) {
        return client.listAll(clazz, ListOptions.builder().build(),
                org.springframework.data.domain.Sort.unsorted())
            .collectList();
    }

    private static String keyOf(String community, String building, String room) {
        return community + "|" + building + "|" + room;
    }

    // ============ 小区配置 ============

    private Mono<ServerResponse> listCommunities(ServerRequest request) {
        return listAll(Community.class)
            .map(list -> new ListResult<>(list))
            .flatMap(r -> ServerResponse.ok().bodyValue(r))
            .onErrorResume(PropertyFeeException.class, e -> badRequest(e.getMessage()));
    }

    private Mono<ServerResponse> createCommunity(ServerRequest request) {
        return parseBody(request, Community.class)
            .flatMap(c -> {
                if (c.getSpec() == null || !hasText(c.getSpec().getName())) {
                    return Mono.error(new PropertyFeeException("小区名称不能为空"));
                }
                return listAll(Community.class).flatMap(existing -> {
                    boolean dup = existing.stream().anyMatch(e ->
                        e.getSpec() != null && e.getSpec().getName().equals(c.getSpec().getName()));
                    if (dup) {
                        return Mono.error(new PropertyFeeException("小区已存在，请勿重复添加"));
                    }
                    return client.create(c);
                });
            })
            .flatMap(c -> ServerResponse.ok().bodyValue(c))
            .onErrorResume(PropertyFeeException.class, e -> badRequest(e.getMessage()));
    }

    private Mono<ServerResponse> updateCommunity(ServerRequest request) {
        String name = request.pathVariable("name");
        return parseBody(request, Community.class)
            .flatMap(c -> client.fetch(Community.class, name)
                .flatMap(existing -> {
                    c.getMetadata().setName(name);
                    if (c.getMetadata().getVersion() == null) {
                        c.getMetadata().setVersion(existing.getMetadata().getVersion());
                    }
                    return client.update(c);
                }))
            .flatMap(c -> ServerResponse.ok().bodyValue(c))
            .onErrorResume(PropertyFeeException.class, e -> badRequest(e.getMessage()));
    }

    private Mono<ServerResponse> deleteCommunity(ServerRequest request) {
        String name = request.pathVariable("name");
        return client.fetch(Community.class, name)
            .flatMap(c -> client.delete(c))
            .then(ServerResponse.noContent().build())
            .onErrorResume(PropertyFeeException.class, e -> badRequest(e.getMessage()));
    }

    /**
     * 小区/楼栋批量导入：Excel 行 = 小区名 + 楼栋。
     * 已存在小区 → 楼栋合并（去重）；新小区 → 自动创建并启用。
     */
    private Mono<ServerResponse> importCommunities(ServerRequest request) {
        return parseBody(request, CommunityImportRequest.class)
            .switchIfEmpty(Mono.error(new PropertyFeeException("导入数据不能为空")))
            .flatMap(imp -> {
                if (imp.rows() == null || imp.rows().isEmpty()) {
                    return Mono.error(new PropertyFeeException("导入数据为空"));
                }
                return listAll(Community.class).flatMap(existing -> {
                    java.util.Map<String, Community> byName = new HashMap<>();
                    for (Community c : existing) {
                        if (c.getSpec() != null && c.getSpec().getName() != null) {
                            byName.put(c.getSpec().getName(), c);
                        }
                    }
                    // 聚合：小区 -> 楼栋集合（保持顺序去重）
                    java.util.Map<String, java.util.LinkedHashSet<String>> want
                        = new java.util.LinkedHashMap<>();
                    int badRows = 0;
                    for (CommunityImportRequest.ImportRow row : imp.rows()) {
                        if (row.community() == null || row.community().isBlank()
                            || row.building() == null || row.building().isBlank()) {
                            badRows++;
                            continue;
                        }
                        want.computeIfAbsent(row.community().trim(),
                            k -> new java.util.LinkedHashSet<>()).add(row.building().trim());
                    }
                    if (want.isEmpty()) {
                        return Mono.error(new PropertyFeeException("未识别到有效的小区/楼栋数据"));
                    }
                    List<Mono<Community>> ops = new ArrayList<>();
                    int[] created = {0};
                    int[] merged = {0};
                    for (java.util.Map.Entry<String, java.util.LinkedHashSet<String>> e : want.entrySet()) {
                        String cname = e.getKey();
                        List<String> buildings = new ArrayList<>(e.getValue());
                        Community c = byName.get(cname);
                        if (c != null && c.getSpec() != null) {
                            List<String> cur = c.getSpec().getBuildings();
                            if (cur == null) {
                                cur = new ArrayList<>();
                                c.getSpec().setBuildings(cur);
                            }
                            boolean changed = false;
                            for (String b : buildings) {
                                if (!cur.contains(b)) {
                                    cur.add(b);
                                    changed = true;
                                }
                            }
                            if (changed) {
                                ops.add(client.update(c));
                                merged[0]++;
                            }
                        } else {
                            Community nc = new Community();
                            Metadata meta = new Metadata();
                            meta.setGenerateName("community-");
                            nc.setMetadata(meta);
                            Community.CommunitySpec spec = new Community.CommunitySpec();
                            spec.setName(cname);
                            spec.setBuildings(buildings);
                            spec.setEnabled(true);
                            nc.setSpec(spec);
                            ops.add(client.create(nc));
                            created[0]++;
                        }
                    }
                    if (ops.isEmpty()) {
                        return ServerResponse.ok().bodyValue(Map.of(
                            "success", true, "message", "导入完成：所有楼栋均已存在，无新增",
                            "created", 0, "merged", 0, "skipped", badRows));
                    }
                    return Mono.when(ops).then(ServerResponse.ok().bodyValue(Map.of(
                        "success", true,
                        "message", "导入完成：新增小区 " + created[0] + " 个，补充楼栋 " + merged[0] + " 个"
                            + (badRows > 0 ? "，忽略无效行 " + badRows + " 行" : ""),
                        "created", created[0], "merged", merged[0], "skipped", badRows)));
                });
            })
            .onErrorResume(PropertyFeeException.class, e -> badRequest(e.getMessage()));
    }

    /**
     * 校验房屋的小区/楼栋：小区必须在配置中；楼栋必须属于该小区配置的楼栋列表。
     * 未建任何小区配置时放行（兼容老数据，配置建成后即强校验）。
     */
    private Mono<Void> validateCommunityBuilding(Property.PropertySpec spec) {
        return listAll(Community.class).flatMap(all -> {
            if (all.isEmpty()) {
                return Mono.empty();
            }
            Community matched = all.stream()
                .filter(c -> c.getSpec() != null
                    && c.getSpec().getName().equals(spec.getCommunity()))
                .findFirst().orElse(null);
            if (matched == null) {
                return Mono.error(new PropertyFeeException(
                    "小区「" + spec.getCommunity() + "」不在配置中，请先在「小区配置」中添加"));
            }
            List<String> buildings = matched.getSpec().getBuildings();
            if (buildings != null && !buildings.isEmpty()
                && buildings.stream().noneMatch(b -> b.equals(spec.getBuilding()))) {
                return Mono.error(new PropertyFeeException(
                    "楼栋「" + spec.getBuilding() + "」不在小区「" + matched.getSpec().getName()
                        + "」的配置中，请先在小区配置里补充该楼栋"));
            }
            return Mono.empty();
        });
    }

    /**
     * 导入行业主并入房屋业主列表：同房多行 = 多业主；同手机号去重；
     * 首个业主自动成为主业主并同步单字段（在调用方 syncPrimaryFields 后落库）。
     */
    private static boolean appendImportOwner(Property.PropertySpec spec,
                                             PropertyImportRequest.ImportRow row) {
        String name = row.ownerName();
        String phone = PropertyHelper.normalizePhone(row.ownerPhone());
        if ((name == null || name.isBlank()) && phone == null) {
            return false;
        }
        if (spec.getOwners() == null) {
            spec.setOwners(new java.util.ArrayList<>());
        }
        boolean exists = spec.getOwners().stream().anyMatch(o ->
            o.getPhone() != null && o.getPhone().equals(phone));
        if (exists) {
            return false;
        }
        Property.Owner o = new Property.Owner();
        o.setName(name);
        o.setPhone(phone);
        o.setIdCard(row.ownerIdCard());
        o.setType(row.ownerType() == null || row.ownerType().isBlank() ? "业主" : row.ownerType());
        o.setIsPrimary(spec.getOwners().isEmpty());
        spec.getOwners().add(o);
        return true;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private Mono<ServerResponse> badRequest(String message) {
        return ServerResponse.status(HttpStatus.BAD_REQUEST)
            .bodyValue(Map.of("message", message));
    }

    // ===== 系统配置：短信（腾讯云，复用平台）与微信（服务号 OAuth，复用平台） =====

    private Mono<ServerResponse> getSystemConfig(ServerRequest request) {
        return client.fetch(SystemConfig.class, SystemConfig.FIXED_NAME)
            .map(cfg -> {
                Map<String, Object> m = new java.util.HashMap<>();
                m.put("metadata", cfg.getMetadata());
                m.put("spec", cfg.getSpec());
                return m;
            })
            .switchIfEmpty(Mono.just(Map.of("spec", new SystemConfig.SystemConfigSpec())))
            .flatMap(body -> ServerResponse.ok().bodyValue(body));
    }

    private Mono<ServerResponse> updateSystemConfig(ServerRequest request) {
        // Halo 2.26 大 body 竞态：配置较长走 query 通道 ?data=<base64url(JSON)>（pay 同款方案，稳定）
        String q = request.queryParam("data").orElse("");
        Mono<SystemConfig.SystemConfigSpec> specMono;
        if (hasText(q)) {
            specMono = Mono.fromCallable(() -> {
                try {
                    byte[] dec = java.util.Base64.getUrlDecoder().decode(q);
                    return MAPPER.readValue(dec, SystemConfig.SystemConfigSpec.class);
                } catch (Exception e) {
                    throw new PropertyFeeException("配置参数解析失败，请重试");
                }
            });
        } else {
            specMono = parseBody(request, SystemConfig.SystemConfigSpec.class);
        }
        return specMono
            .flatMap(spec -> client.fetch(SystemConfig.class, SystemConfig.FIXED_NAME)
                .map(cfg -> {
                    // 合并语义：前端未传（null）的凭据/秘钥字段保留旧值，
                    // 防止 UI 局部保存把平台注入的 SecretId/AppSecret 等清空。
                    SystemConfig.SystemConfigSpec old = cfg.getSpec();
                    if (old != null) {
                        if (spec.getSmsSecretId() == null) spec.setSmsSecretId(old.getSmsSecretId());
                        if (spec.getSmsSecretKey() == null) spec.setSmsSecretKey(old.getSmsSecretKey());
                        if (spec.getSmsSdkAppId() == null) spec.setSmsSdkAppId(old.getSmsSdkAppId());
                        if (spec.getSmsSignName() == null) spec.setSmsSignName(old.getSmsSignName());
                        if (spec.getSmsTemplateId() == null) spec.setSmsTemplateId(old.getSmsTemplateId());
                        if (spec.getWxAppId() == null) spec.setWxAppId(old.getWxAppId());
                        if (spec.getWxAppSecret() == null) spec.setWxAppSecret(old.getWxAppSecret());
                    }
                    cfg.setSpec(spec);
                    return cfg;
                })
                .flatMap(client::update)
                .switchIfEmpty(Mono.defer(() -> {
                    SystemConfig cfg = new SystemConfig();
                    var meta = new run.halo.app.extension.Metadata();
                    meta.setName(SystemConfig.FIXED_NAME);
                    cfg.setMetadata(meta);
                    cfg.setSpec(spec);
                    return client.create(cfg);
                }))
                .then(ServerResponse.ok().bodyValue(Map.of(
                    "success", true, "message", "系统配置已保存"))))
            .onErrorResume(PropertyFeeException.class, e -> badRequest(e.getMessage()));
    }
}
