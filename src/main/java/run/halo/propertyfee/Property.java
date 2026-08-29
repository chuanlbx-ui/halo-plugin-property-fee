package run.halo.propertyfee;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * 房屋（物业单元）。Excel 导入后自动生成，或后台手工添加。
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

        /** 业主姓名。 */
        private String ownerName;

        /** 业主手机号（用于查费/缴费身份识别）。 */
        private String ownerPhone;

        /** 备注。 */
        private String remark;
    }
}
