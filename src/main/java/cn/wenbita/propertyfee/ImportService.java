package cn.wenbita.propertyfee;

import static org.springframework.util.StringUtils.hasText;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * 房屋/业主/收费标准 批量导入。
 *
 * <p>支持 .xlsx（无第三方依赖，直接解析 zip + XML）与 .csv（UTF-8 / GBK）。
 * 一张表即可同时维护小区、楼栋、单元、房号、面积与业主信息，并可按行携带收费标准。
 *
 * <p>表头（中文，可乱序，多余列忽略）：
 * 小区 | 楼栋 | 单元 | 房号 | 面积 | 物业类型 | 业主姓名 | 业主手机号 | 证件号 | 业主类型
 * | 单价 | 收费年份 | 计费周期
 */
@Component
@AllArgsConstructor
public class ImportService {

    private final ReactiveExtensionClient client;

    /** 导入结果摘要。 */
    public record ImportResult(int communities, int housesCreated, int housesUpdated,
                               int ownersAdded, int standardsCreated, int standardsUpdated,
                               int rows, List<String> errors) {
    }

    @SuppressWarnings("unchecked")
    public Mono<ImportResult> importData(byte[] data, String filename, boolean dryRun) {
        List<Map<String, String>> rows;
        try {
            rows = parse(data, filename);
        } catch (Exception e) {
            return Mono.error(new PropertyFeeException("文件解析失败：" + e.getMessage()));
        }
        if (rows.isEmpty()) {
            return Mono.error(new PropertyFeeException("未读取到数据行，请检查表头与内容"));
        }
        int[] counters = new int[6]; // communities, created, updated, owners, stdCreated, stdUpdated
        List<String> errors = new ArrayList<>();
        // 按 小区|楼栋|单元|房号 归并，同一套房的多行业主合并
        Map<String, List<Map<String, String>>> grouped = new LinkedHashMap<>();
        for (Map<String, String> r : rows) {
            String community = col(r, "小区", "community", "小区名称");
            String building = col(r, "楼栋", "building", "楼号");
            String unit = col(r, "单元", "unit");
            String room = col(r, "房号", "room", "房间号");
            if (!hasText(community) || !hasText(room)) {
                errors.add("跳过缺少小区或房号的行：" + r);
                continue;
            }
            grouped.computeIfAbsent(community + "|" + (building == null ? "" : building) + "|"
                + (unit == null ? "" : unit) + "|" + room, k -> new ArrayList<>()).add(r);
        }
        return upsertCommunities(grouped, counters, errors)
            .then(Mono.defer(() -> upsertHouses(grouped, counters, errors, dryRun)))
            .then(Mono.defer(() -> upsertStandards(grouped, counters, errors, dryRun)))
            .then(Mono.fromSupplier(() -> new ImportResult(counters[0], counters[1], counters[2],
                counters[3], counters[4], counters[5], rows.size(), errors)));
    }

    // ==================== 小区（含楼栋清单） ====================

    private Mono<Void> upsertCommunities(Map<String, List<Map<String, String>>> grouped,
        int[] counters, List<String> errors) {
        Set<String> names = new LinkedHashSet<>();
        Map<String, Set<String>> buildings = new LinkedHashMap<>();
        for (String key : grouped.keySet()) {
            String[] parts = key.split("\\|", -1);
            names.add(parts[0]);
            buildings.computeIfAbsent(parts[0], k -> new LinkedHashSet<>())
                .add(parts.length > 1 ? parts[1] : "");
        }
        return client.listAll(Community.class, ListOptions.builder().build(),
                org.springframework.data.domain.Sort.unsorted())
            .collectList()
            .flatMapMany(existing -> Flux.fromIterable(names).concatMap(name -> {
                Community hit = existing.stream().filter(c -> c.getSpec() != null
                    && name.equals(c.getSpec().getName())).findFirst().orElse(null);
                if (hit == null) {
                    Community c = new Community();
                    c.setMetadata(new run.halo.app.extension.Metadata());
                    c.getMetadata().setName("community-" + Integer.toHexString(name.hashCode() & 0x7fffffff));
                    Community.CommunitySpec spec = new Community.CommunitySpec();
                    spec.setName(name);
                    spec.setEnabled(true);
                    spec.setBuildings(new ArrayList<>(buildings.getOrDefault(name, Set.of())));
                    c.setSpec(spec);
                    counters[0]++;
                    return client.create(c).then();
                }
                boolean changed = false;
                var spec = hit.getSpec();
                List<String> bl = spec.getBuildings() == null ? new ArrayList<>()
                    : new ArrayList<>(spec.getBuildings());
                for (String b : buildings.getOrDefault(name, Set.of())) {
                    if (hasText(b) && !bl.contains(b)) {
                        bl.add(b);
                        changed = true;
                    }
                }
                spec.setBuildings(bl);
                if (spec.getEnabled() == null) {
                    spec.setEnabled(true);
                    changed = true;
                }
                return changed ? client.update(hit).then() : Mono.<Void>empty();
            })).then();
    }

    // ==================== 房屋 + 业主（联动） ====================

    private Mono<Void> upsertHouses(Map<String, List<Map<String, String>>> grouped,
        int[] counters, List<String> errors, boolean dryRun) {
        return client.listAll(Property.class, ListOptions.builder().build(),
                org.springframework.data.domain.Sort.unsorted())
            .collectList()
            .flatMapMany(existing -> Flux.fromIterable(grouped.entrySet()).concatMap(entry -> {
                Map<String, String> first = entry.getValue().get(0);
                String community = col(first, "小区", "community", "小区名称");
                String building = col(first, "楼栋", "building", "楼号");
                String unit = col(first, "单元", "unit");
                String room = col(first, "房号", "room", "房间号");
                Double area = num(col(first, "面积", "area", "建筑面积"));
                String ptype = def(col(first, "物业类型", "propertyType"), "住宅");
                Property hit = existing.stream().filter(p -> p.getSpec() != null
                    && community.equals(p.getSpec().getCommunity())
                    && java.util.Objects.equals(building, p.getSpec().getBuilding())
                    && java.util.Objects.equals(room, p.getSpec().getRoom())).findFirst().orElse(null);
                boolean isNew = hit == null;
                Property p = isNew ? new Property() : hit;
                if (isNew) {
                    p.setMetadata(new run.halo.app.extension.Metadata());
                    p.setMetadata(new run.halo.app.extension.Metadata());
                    p.getMetadata().setName("prop-" + Math.abs((community + building + unit + room)
                        .hashCode()) + "-" + System.nanoTime() % 100000);
                }
                Property.PropertySpec spec = p.getSpec() == null
                    ? new Property.PropertySpec() : p.getSpec();
                spec.setCommunity(community);
                if (hasText(building)) {
                    spec.setBuilding(building);
                }
                if (hasText(unit)) {
                    spec.setUnit(unit);
                }
                spec.setRoom(room);
                if (area != null) {
                    spec.setArea(area);
                }
                spec.setPropertyType(ptype);
                if (spec.getHouseStatus() == null) {
                    spec.setHouseStatus("自住");
                }
                // 业主联动：同一套房的多行 -> owners[]
                List<Property.Owner> owners = spec.getOwners() == null
                    ? new ArrayList<>() : new ArrayList<>(spec.getOwners());
                int before = owners.size();
                for (Map<String, String> r : entry.getValue()) {
                    String name = col(r, "业主姓名", "姓名", "ownerName", "业主");
                    String phone = col(r, "业主手机号", "手机号", "电话", "phone");
                    if (!hasText(name) && !hasText(phone)) {
                        continue;
                    }
                    String normPhone = PropertyHelper.normalizePhone(phone);
                    Property.Owner hitOwner = owners.stream().filter(o -> o != null
                        && ((hasText(normPhone) && normPhone.equals(o.getPhone()))
                            || (!hasText(normPhone) && hasText(name)
                                && name.equals(o.getName())))).findFirst().orElse(null);
                    if (hitOwner == null) {
                        Property.Owner o = new Property.Owner();
                        o.setName(name);
                        o.setPhone(normPhone);
                        o.setIdCard(col(r, "证件号", "身份证", "身份证号", "idCard"));
                        o.setType(def(col(r, "业主类型", "类型", "type"), "业主"));
                        o.setIsPrimary(owners.isEmpty());
                        owners.add(o);
                    } else {
                        if (hasText(name)) {
                            hitOwner.setName(name);
                        }
                        String idc = col(r, "证件号", "身份证", "身份证号", "idCard");
                        if (hasText(idc)) {
                            hitOwner.setIdCard(idc);
                        }
                        String t = col(r, "业主类型", "类型", "type");
                        if (hasText(t)) {
                            hitOwner.setType(t);
                        }
                    }
                }
                spec.setOwners(owners);
                PropertyHelper.syncPrimaryFields(spec);
                counters[3] += owners.size() - before;
                p.setSpec(spec);
                if (isNew) {
                    counters[1]++;
                } else {
                    counters[2]++;
                }
                if (dryRun) {
                    return Mono.<Void>empty();
                }
                return (isNew ? client.create(p) : client.update(p)).then();
            })).then();
    }

    // ==================== 收费标准（按行携带） ====================

    private Mono<Void> upsertStandards(Map<String, List<Map<String, String>>> grouped,
        int[] counters, List<String> errors, boolean dryRun) {
        // 小区|年份|物业类型 -> 单价/周期
        Map<String, Map<String, String>> stds = new LinkedHashMap<>();
        for (List<Map<String, String>> rs : grouped.values()) {
            for (Map<String, String> r : rs) {
                String community = col(r, "小区", "community", "小区名称");
                String year = col(r, "收费年份", "年份", "year");
                Double price = num(col(r, "单价", "unitPrice", "收费标准", "物业费单价"));
                if (!hasText(community) || !hasText(year) || price == null) {
                    continue;
                }
                String ptype = def(col(r, "物业类型", "propertyType"), "住宅");
                stds.putIfAbsent(community + "|" + year + "|" + ptype, r);
            }
        }
        if (stds.isEmpty()) {
            return Mono.empty();
        }
        return client.listAll(FeeStandard.class, ListOptions.builder().build(),
                org.springframework.data.domain.Sort.unsorted())
            .collectList()
            .flatMapMany(existing -> Flux.fromIterable(stds.entrySet()).concatMap(e -> {
                String[] parts = e.getKey().split("\\|", -1);
                Map<String, String> r = e.getValue();
                FeeStandard hit = existing.stream().filter(f -> f.getSpec() != null
                    && parts[0].equals(f.getSpec().getCommunity())
                    && String.valueOf(f.getSpec().getYear()).equals(parts[1])
                    && java.util.Objects.equals(parts[2], f.getSpec().getPropertyType()))
                    .findFirst().orElse(null);
                boolean isNew = hit == null;
                FeeStandard f = isNew ? new FeeStandard() : hit;
                if (isNew) {
                    f.setMetadata(new run.halo.app.extension.Metadata());
                    f.getMetadata().setName("fs-" + Math.abs(e.getKey().hashCode())
                        + "-" + System.nanoTime() % 100000);
                }
                FeeStandard.FeeStandardSpec spec = f.getSpec() == null
                    ? new FeeStandard.FeeStandardSpec() : f.getSpec();
                spec.setCommunity(parts[0]);
                spec.setYear(Integer.valueOf(parts[1]));
                spec.setPropertyType(parts[2]);
                spec.setUnitPrice(num(col(r, "单价", "unitPrice", "收费标准", "物业费单价")));
                spec.setBillingCycle(def(col(r, "计费周期", "billingCycle"), "year"));
                if (spec.getEnabled() == null) {
                    spec.setEnabled(true);
                }
                f.setSpec(spec);
                if (isNew) {
                    counters[4]++;
                } else {
                    counters[5]++;
                }
                if (dryRun) {
                    return Mono.<Void>empty();
                }
                return (isNew ? client.create(f) : client.update(f)).then();
            })).then();
    }

    // ==================== 解析 ====================

    /** 解析文件为「行 -> 表头->值」。 */
    public static List<Map<String, String>> parse(byte[] data, String filename) throws Exception {
        String lower = filename == null ? "" : filename.toLowerCase();
        List<List<String>> table = lower.endsWith(".xlsx") || lower.endsWith(".xlsm")
            ? parseXlsx(data) : parseCsv(data);
        if (table.isEmpty()) {
            throw new IllegalArgumentException("文件为空");
        }
        List<String> header = table.get(0);
        List<Map<String, String>> out = new ArrayList<>();
        for (int i = 1; i < table.size(); i++) {
            List<String> line = table.get(i);
            if (line == null || line.stream().allMatch(v -> v == null || v.isBlank())) {
                continue;
            }
            Map<String, String> m = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) {
                String k = header.get(c) == null ? "" : header.get(c).trim();
                if (k.isEmpty()) {
                    continue;
                }
                String v = c < line.size() ? line.get(c) : null;
                m.put(k, v == null ? "" : v.trim());
            }
            out.add(m);
        }
        return out;
    }

    /** CSV：自动识别 UTF-8 / GBK 与逗号/制表符分隔。 */
    private static List<List<String>> parseCsv(byte[] data) {
        String text = decode(data);
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '"') {
                if (inQuote && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else {
                    inQuote = !inQuote;
                }
            } else if (!inQuote && (ch == '\n' || ch == '\r')) {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                lines.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        if (cur.length() > 0) {
            lines.add(cur.toString());
        }
        char delim = lines.stream().anyMatch(l -> l.indexOf('\t') >= 0) ? '\t' : ',';
        List<List<String>> table = new ArrayList<>();
        for (String l : lines) {
            if (l.isBlank()) {
                continue;
            }
            List<String> cells = new ArrayList<>();
            StringBuilder sb = new StringBuilder();
            boolean q = false;
            for (int i = 0; i < l.length(); i++) {
                char c = l.charAt(i);
                if (c == '"') {
                    if (q && i + 1 < l.length() && l.charAt(i + 1) == '"') {
                        sb.append('"');
                        i++;
                    } else {
                        q = !q;
                    }
                } else if (!q && c == delim) {
                    cells.add(sb.toString());
                    sb.setLength(0);
                } else {
                    sb.append(c);
                }
            }
            cells.add(sb.toString());
            table.add(cells);
        }
        return table;
    }

    private static String decode(byte[] data) {
        String utf8 = new String(data, StandardCharsets.UTF_8);
        if (utf8.indexOf('\uFFFD') >= 0) {
            try {
                return new String(data, Charset.forName("GBK"));
            } catch (Exception ignore) {
                return utf8;
            }
        }
        return utf8;
    }

    /** XLSX：zip 内 sharedStrings.xml + worksheets/sheet1.xml，无第三方依赖。 */
    private static List<List<String>> parseXlsx(byte[] data) throws Exception {
        Map<Integer, String> shared = new LinkedHashMap<>();
        String sheet = null;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String n = e.getName();
                if (n == null || e.isDirectory()) {
                    continue;
                }
                if (n.equals("xl/sharedStrings.xml")) {
                    String xml = new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                    Matcher si = Pattern.compile("<si>(.*?)</si>", Pattern.DOTALL).matcher(xml);
                    int idx = 0;
                    while (si.find()) {
                        StringBuilder sb = new StringBuilder();
                        Matcher t = Pattern.compile("<t[^>]*>(.*?)</t>", Pattern.DOTALL)
                            .matcher(si.group(1));
                        while (t.find()) {
                            sb.append(unescape(t.group(1)));
                        }
                        shared.put(idx++, sb.toString());
                    }
                } else if (sheet == null && n.startsWith("xl/worksheets/sheet")
                    && n.endsWith(".xml")) {
                    sheet = new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        if (sheet == null) {
            throw new IllegalArgumentException("未找到工作表");
        }
        List<List<String>> table = new ArrayList<>();
        Matcher rowM = Pattern.compile("<row[^>]*>(.*?)</row>", Pattern.DOTALL).matcher(sheet);
        while (rowM.find()) {
            List<String> cells = new ArrayList<>();
            Matcher cM = Pattern.compile("<c([^>]*)>(.*?)</c>", Pattern.DOTALL).matcher(rowM.group(1));
            while (cM.find()) {
                String attrs = cM.group(1);
                String body = cM.group(2);
                String ref = attr(attrs, "r");
                int colIdx = cells.size();
                if (ref != null) {
                    int n = 0;
                    for (int i = 0; i < ref.length() && Character.isLetter(ref.charAt(i)); i++) {
                        n = n * 26 + (Character.toUpperCase(ref.charAt(i)) - 'A' + 1);
                    }
                    colIdx = n - 1;
                }
                while (cells.size() < colIdx) {
                    cells.add("");
                }
                String type = attr(attrs, "t");
                String val = "";
                if ("inlineStr".equals(type)) {
                    Matcher t = Pattern.compile("<t[^>]*>(.*?)</t>", Pattern.DOTALL).matcher(body);
                    if (t.find()) {
                        val = unescape(t.group(1));
                    }
                } else {
                    Matcher v = Pattern.compile("<v[^>]*>(.*?)</v>", Pattern.DOTALL).matcher(body);
                    if (v.find()) {
                        val = unescape(v.group(1));
                        if ("s".equals(type)) {
                            try {
                                val = shared.getOrDefault((int) Double.parseDouble(val), "");
                            } catch (Exception ignore) {
                                val = "";
                            }
                        }
                    }
                }
                cells.add(val);
            }
            table.add(cells);
        }
        return table;
    }

    private static String attr(String attrs, String name) {
        Matcher m = Pattern.compile(name + "=\"([^\"]*)\"").matcher(attrs);
        return m.find() ? m.group(1) : null;
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&amp;", "&");
    }

    // ==================== 取值工具 ====================

    /** 按候选表头名取值（先精确、后包含）。 */
    private static String col(Map<String, String> row, String... keys) {
        for (String k : keys) {
            String v = row.get(k);
            if (hasText(v)) {
                return v.trim();
            }
        }
        for (String k : keys) {
            for (Map.Entry<String, String> e : row.entrySet()) {
                if (e.getKey() != null && e.getKey().contains(k) && hasText(e.getValue())) {
                    return e.getValue().trim();
                }
            }
        }
        return null;
    }

    private static String def(String v, String fallback) {
        return hasText(v) ? v.trim() : fallback;
    }

    private static Double num(String v) {
        if (!hasText(v)) {
            return null;
        }
        try {
            return Double.valueOf(v.replaceAll("[^0-9.\\-]", ""));
        } catch (Exception e) {
            return null;
        }
    }
}
