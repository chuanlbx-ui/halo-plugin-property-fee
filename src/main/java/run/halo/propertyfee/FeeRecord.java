package run.halo.propertyfee;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * 物业费缴费记录。一次缴费一条记录（年份维度）。
 *
 * @author property-fee
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "propertyfee.halo.run", version = "v1alpha1", kind = "FeeRecord",
    plural = "feerecords", singular = "feerecord")
public class FeeRecord extends AbstractExtension {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private FeeRecordSpec spec;

    @Data
    public static class FeeRecordSpec {

        /** 小区名称。 */
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private String community;

        /** 楼栋号。 */
        private String building;

        /** 房号。 */
        private String room;

        /** 房屋 metadata.name（关联 Property）。 */
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private String propertyName;

        /** 缴费年份，如 2026。 */
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private Integer year;

        /** 房屋面积（㎡），缴费时快照。 */
        private Double area;

        /** 物业费金额（面积×单价×12，元）。 */
        private Double propertyFee;

        /** 额外费用合计（元）。 */
        private Double extraFee;

        /** 应缴总额（propertyFee + extraFee，元）。 */
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private Double totalAmount;

        /** 实缴金额（元，即微信支付金额，单位元）。 */
        private Double paidAmount;

        /** 支付状态：PENDING / PAID / FAILED / CLOSED。 */
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private String status;

        /** 商户号（本次支付使用的微信商户号）。 */
        private String mchId;

        /** 商户自定义订单号（out_trade_no）。 */
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private String outTradeNo;

        /** 微信支付交易号（transaction_id）。 */
        private String transactionId;

        /** 支付方式：native（扫码）/ jsapi（微信内）。 */
        private String payType;

        /** 缴费时间（微信回调成功时间）。 */
        private Instant paidAt;

        /** 创建时间。 */
        private Instant createdAt;

        /** 业主姓名（快照）。 */
        private String ownerName;

        /** 业主手机号（快照）。 */
        private String ownerPhone;
    }
}
