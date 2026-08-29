package run.halo.propertyfee;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * 微信支付商户配置。按小区绑定独立商户号，实现不同小区支付通道独立。
 * 敏感字段（APIv3密钥）仅管理端可读写。
 *
 * @author property-fee
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "propertyfee.halo.run", version = "v1alpha1", kind = "PaymentConfig",
    plural = "paymentconfigs", singular = "paymentconfig")
public class PaymentConfig extends AbstractExtension {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private PaymentConfigSpec spec;

    @Data
    public static class PaymentConfigSpec {

        /** 小区名称（一个小区一个商户配置）。 */
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private String community;

        /** 微信公众号 AppID。 */
        private String appId;

        /** 微信商户号 mch_id。 */
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private String mchId;

        /** APIv3 密钥（32位）。 */
        private String apiV3Key;

        /** 商户证书序列号（apiclient_cert.pem 的序列号）。 */
        private String mchSerialNo;

        /** 商户私钥（apiclient_key.pem 内容，PEM 格式）。 */
        private String mchPrivateKey;

        /** 支付回调通知地址（公网可访问）。 */
        private String notifyUrl;

        /** 备注。 */
        private String remark;
    }
}
