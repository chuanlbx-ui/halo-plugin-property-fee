package run.halo.propertyfee;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * 收费标准。按小区+年份配置：物业单价(元/㎡·月) + 额外费用项。
 *
 * @author property-fee
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "propertyfee.halo.run", version = "v1alpha1", kind = "FeeStandard",
    plural = "feestandards", singular = "feestandard")
public class FeeStandard extends AbstractExtension {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private FeeStandardSpec spec;

    @Data
    public static class FeeStandardSpec {

        /** 小区名称。 */
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private String community;

        /** 收费年份，如 2026。 */
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private Integer year;

        /** 物业费单价：元/㎡·月。 */
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private Double unitPrice;

        /** 额外费用项（如电梯费/垃圾费/公摊等），每年固定金额，可多个。 */
        private List<ExtraFee> extraFees;

        /** 备注。 */
        private String remark;

        @Data
        public static class ExtraFee {
            /** 费用名称，如 电梯费。 */
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            private String name;

            /** 每年固定金额（元）。 */
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            private Double amount;

            /** 备注（选填）。 */
            private String remark;
        }
    }
}
