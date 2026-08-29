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
import run.halo.propertyfee.FeeRecord;
import run.halo.propertyfee.FeeStandard;
import run.halo.propertyfee.PaymentConfig;
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
        return request.bodyToMono(Property.class)
            .flatMap(p -> {
                if (p.getSpec() == null || p.getSpec().getCommunity() == null
                    || p.getSpec().getCommunity().isBlank()) {
                    return Mono.error(new PropertyFeeException("小区名称不能为空"));
                }
                return client.create(p);
            })
            .flatMap(p -> ServerResponse.ok().bodyValue(p))
            .onErrorResume(PropertyFeeException.class, e -> badRequest(e.getMessage()));
    }

    private Mono<ServerResponse> updateProperty(ServerRequest request) {
        String name = request.pathVariable("name");
        return request.bodyToMono(Property.class)
            .flatMap(p -> {
                p.getMetadata().setName(name);
                return client.update(p);
            })
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
        return request.bodyToMono(PropertyImportRequest.class)
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
                            // 更新面积/业主信息
                            var s = existingP.getSpec();
                            boolean changed = false;
                            if (row.area() != null && !row.area().equals(s.getArea())) {
                                s.setArea(row.area());
                                changed = true;
                            }
                            if (row.ownerName() != null && !row.ownerName().isBlank()
                                && !row.ownerName().equals(s.getOwnerName())) {
                                s.setOwnerName(row.ownerName());
                                changed = true;
                            }
                            if (row.ownerPhone() != null && !row.ownerPhone().isBlank()
                                && !row.ownerPhone().equals(s.getOwnerPhone())) {
                                s.setOwnerPhone(row.ownerPhone());
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
                            p.setSpec(spec);
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
        return request.bodyToMono(FeeStandard.class)
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
        return request.bodyToMono(FeeStandard.class)
            .flatMap(fs -> {
                fs.getMetadata().setName(name);
                return client.update(fs);
            })
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
        return request.bodyToMono(PaymentConfig.class)
            .flatMap(pc -> {
                if (pc.getSpec() == null || pc.getSpec().getCommunity() == null
                    || pc.getSpec().getCommunity().isBlank()) {
                    return Mono.error(new PropertyFeeException("小区名称不能为空"));
                }
                if (pc.getSpec().getMchId() == null || pc.getSpec().getMchId().isBlank()) {
                    return Mono.error(new PropertyFeeException("商户号不能为空"));
                }
                return client.create(pc);
            })
            .flatMap(pc -> ServerResponse.ok().bodyValue(pc))
            .onErrorResume(PropertyFeeException.class, e -> badRequest(e.getMessage()));
    }

    private Mono<ServerResponse> updateConfig(ServerRequest request) {
        String name = request.pathVariable("name");
        return request.bodyToMono(PaymentConfig.class)
            .flatMap(pc -> {
                pc.getMetadata().setName(name);
                return client.update(pc);
            })
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

    private Mono<ServerResponse> badRequest(String message) {
        return ServerResponse.status(HttpStatus.BAD_REQUEST)
            .bodyValue(Map.of("message", message));
    }
}
