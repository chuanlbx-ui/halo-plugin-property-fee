package run.halo.propertyfee;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * 收费标准（计费规则引擎）。按小区+年份+物业类型配置：
 * 物业单价(元/㎡·月) + 额外费用项 + 缴费周期 + 优惠减免 + 滞纳金规则。
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

        /** 物业类型：住宅 / 商铺 / 车位 / 其他（用于差异化定价）。 */
        private String propertyType = "住宅";

        /** 物业费单价：元/㎡·月。 */
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private Double unitPrice;

        /** 缴费周期：year(年) / half(半年) / quarter(季) / month(月)。默认年缴。 */
        private String billingCycle = "year";

        /** 额外费用项（如电梯费/垃圾费/公摊等），每年固定金额，可多个。 */
        private List<ExtraFee> extraFees;

        /** 优惠减免规则（选填）。 */
        private Discount discount;

        /** 滞纳金规则（选填）。 */
        private LateFee lateFee;

        /** 是否启用该标准（默认 true）。 */
        private Boolean enabled = true;

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

            /** 计费方式：fixed(固定金额) / perArea(按面积) / perMonth(按月计费)。默认 fixed。 */
            private String chargeMode = "fixed";

            /** 备注（选填）。 */
            private String remark;
        }

        @Data
        public static class Discount {
            /** 减免类型：amount(固定减免) / percent(按比例减免) / firstYear(首年优惠)。 */
            private String type;

            /** 固定减免金额（元，type=amount 时生效）。 */
            private Double amount;

            /** 减免比例（0-1，type=percent 时生效，如 0.2 = 减免20%）。 */
            private Double percent;

            /** 备注。 */
            private String remark;
        }

        @Data
        public static class LateFee {
            /** 宽限期天数（逾期 N 天后开始计滞纳金，默认 0）。 */
            private Integer graceDays = 0;

            /** 滞纳金日利率（如 0.0005 = 日万分之五）。 */
            private Double dailyRate = 0.0005;

            /** 滞纳金封顶比例（0-1，如 0.3 = 最高不超过应缴的30%，null 不封顶）。 */
            private Double maxRate;
        }
    }
}
