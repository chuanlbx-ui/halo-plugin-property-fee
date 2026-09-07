package run.halo.propertyfee;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 业主档案与小区配置的公共工具：
 * 1) 统一读取房屋业主列表（owners 优先，老单业主字段兜底）
 * 2) 保存时双写 owners 与主业主单字段，保证 Excel/报表/老逻辑零改动
 * 3) 校验房屋的小区/楼栋是否在小区配置内
 */
public final class PropertyHelper {

    private PropertyHelper() {
    }

    /** 房屋是否属于给定手机号业主（多业主任一命中，去除空号）。 */
    public static boolean isOwnerPhone(Property property, String phone) {
        if (property == null || property.getSpec() == null || phone == null || phone.isBlank()) {
            return false;
        }
        return effectiveOwners(property.getSpec()).stream()
            .anyMatch(o -> o.getPhone() != null && o.getPhone().replaceAll("\\s", "").equals(phone));
    }

    /** 取房屋业主列表：owners 非空用之；否则老单业主字段兜底（历史数据）。 */
    public static List<Property.Owner> effectiveOwners(Property.PropertySpec spec) {
        List<Property.Owner> owners = spec.getOwners();
        if (owners != null && !owners.isEmpty()) {
            return owners;
        }
        List<Property.Owner> list = new ArrayList<>();
        if (hasText(spec.getOwnerName()) || hasText(spec.getOwnerPhone())) {
            Property.Owner o = new Property.Owner();
            o.setName(spec.getOwnerName());
            o.setPhone(spec.getOwnerPhone());
            o.setIdCard(spec.getOwnerIdCard());
            o.setType(spec.getOwnerType() == null ? "业主" : spec.getOwnerType());
            o.setIsPrimary(true);
            list.add(o);
        }
        return list;
    }

    /** 保存前把 owners 双写回单业主字段（主业主 = isPrimary 或第一个）。 */
    public static void syncPrimaryFields(Property.PropertySpec spec) {
        List<Property.Owner> owners = spec.getOwners();
        if (owners == null || owners.isEmpty()) {
            return;
        }
        Property.Owner primary = owners.stream()
            .filter(o -> Boolean.TRUE.equals(o.getIsPrimary()))
            .findFirst()
            .orElse(owners.get(0));
        spec.setOwnerName(primary.getName());
        spec.setOwnerPhone(primary.getPhone());
        spec.setOwnerIdCard(primary.getIdCard());
        spec.setOwnerType(primary.getType() == null ? "业主" : primary.getType());
    }

    /** 手机号格式规整：去空格、补全 1 开头的 11 位（若漏写）。 */
    public static String normalizePhone(String phone) {
        if (phone == null) {
            return null;
        }
        String p = phone.replaceAll("[^0-9]", "");
        return p.isEmpty() ? null : p;
    }

    /**
     * 把微信 openid 绑定到指定手机号的业主记录上（owners 优先，单业主字段兜底）。
     *
     * @return true=绑定成功；false=该手机号不是此房屋业主
     */
    public static boolean setOwnerWxOpenid(Property.PropertySpec spec, String phone, String openid) {
        if (spec == null || phone == null || openid == null) {
            return false;
        }
        String norm = normalizePhone(phone);
        List<Property.Owner> owners = spec.getOwners();
        if (owners != null && !owners.isEmpty()) {
            for (Property.Owner o : owners) {
                if (norm.equals(normalizePhone(o.getPhone()))) {
                    o.setWechatOpenid(openid);
                    return true;
                }
            }
            return false;
        }
        // 老单业主字段：视为主业主
        if (norm.equals(normalizePhone(spec.getOwnerPhone()))) {
            Property.Owner o = new Property.Owner();
            o.setName(spec.getOwnerName());
            o.setPhone(spec.getOwnerPhone());
            o.setType("业主");
            o.setIsPrimary(true);
            o.setWechatOpenid(openid);
            List<Property.Owner> list = new java.util.ArrayList<>();
            list.add(o);
            spec.setOwners(list);
            return true;
        }
        return false;
    }

    /**
     * 按微信 openid 反查业主手机号（遍历房屋 owners）。未绑定返回 null。
     */
    public static String findOwnerPhoneByWxOpenid(Property.PropertySpec spec, String openid) {
        if (spec == null || openid == null) {
            return null;
        }
        List<Property.Owner> owners = spec.getOwners();
        if (owners != null) {
            for (Property.Owner o : owners) {
                if (openid.equals(o.getWechatOpenid())) {
                    return normalizePhone(o.getPhone());
                }
            }
        }
        return null;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
