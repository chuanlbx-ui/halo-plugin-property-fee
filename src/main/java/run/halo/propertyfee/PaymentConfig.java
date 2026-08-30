package run.halo.propertyfee;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * 支付渠道配置。按小区绑定多个支付渠道（微信Native/微信JSAPI/支付宝/线下），
 * 支持多商户号、渠道启用/默认设置。敏感字段（APIv3密钥/私钥）仅管理端可读写。
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

        /** 小区名称（一个小区可配置多个支付渠道）。 */
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private String community;

        /** 支付渠道类型：wechat_native / wechat_jsapi / alipay / offline。 */
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private String channelType = "wechat_native";

        /** 渠道展示名称，如：微信扫码支付 / 公众号支付 / 支付宝 / 线下转账。 */
        private String channelName;

        /** 是否启用该渠道（默认 true）。 */
        private Boolean enabled = true;

        /** 是否默认渠道（前台优先展示，一个小区仅一个默认）。 */
        private Boolean isDefault = false;

        /** 微信公众号 AppID（微信渠道必填）。 */
        private String appId;

        /** 微信商户号 mch_id（微信渠道必填）。 */
        private String mchId;

        /** APIv3 密钥（32位，微信渠道）。 */
        private String apiV3Key;

        /** 商户证书序列号（apiclient_cert.pem 的序列号，微信渠道）。 */
        private String mchSerialNo;

        /** 商户私钥（apiclient_key.pem 内容，PEM 格式，微信渠道）。 */
        private String mchPrivateKey;

        /** 支付回调通知地址（公网可访问）。 */
        private String notifyUrl;

        /** 支付宝开放平台 AppID（支付宝渠道）。 */
        private String alipayAppId;

        /** 支付宝应用私钥（支付宝渠道，PKCS8 PEM）。 */
        private String alipayPrivateKey;

        /** 支付宝公钥（支付宝渠道）。 */
        private String alipayPublicKey;

        /** 线下收款方式说明（如：现金/银行转账 户名/账号，线下渠道展示）。 */
        private String offlineInstruction;

        /** 备注。 */
        private String remark;
    }
}
