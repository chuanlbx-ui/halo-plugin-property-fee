package run.halo.propertyfee;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * 房屋（物业单元）。Excel 导入后自动生成，或后台手工添加。
 * 支持完整业主档案：身份证/业主类型/入住日期/房屋状态/物业类型。
 *
 * @author property-fee
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "propertyfee.halo.run", version = "v1alpha1", kind = "Property",
    plural = "properties", singular = "property")
public class Property extends AbstractExtension {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private PropertySpec spec;

    @Data
    public static class PropertySpec {

        /** 小区名称（用于关联收费标准与商户号）。 */
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private String community;

        /** 楼栋号，如 1 / A2。 */
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private String building;

        /** 单元号，如 1 / 2（可空）。 */
        private String unit;

        /** 房号，如 101 / 1202。 */
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private String room;

        /** 建筑面积（㎡），用于计算物业费。 */
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private Double area;

        /** 物业类型：住宅 / 商铺 / 车位 / 其他（默认住宅）。 */
        private String propertyType = "住宅";

        /** 业主姓名。 */
        private String ownerName;

        /** 业主手机号（用于查费/缴费身份识别）。 */
        private String ownerPhone;

        /** 业主身份证号（选填）。 */
        private String ownerIdCard;

        /** 业主类型：业主 / 租户 / 亲属（默认业主）。 */
        private String ownerType = "业主";

        /** 入住日期（yyyy-MM-dd，选填）。 */
        private String moveInDate;

        /** 房屋状态：自住 / 出租 / 空置 / 装修（默认自住）。 */
        private String houseStatus = "自住";

        /** 备注。 */
        private String remark;
    }
}
