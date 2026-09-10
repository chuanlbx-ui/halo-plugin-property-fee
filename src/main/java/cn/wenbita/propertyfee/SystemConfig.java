package cn.wenbita.propertyfee;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * 平台级配置（全局唯一：metadata.name = pf-platform-config）。
 * 直接复用文山州互联网协会既有平台配置，不重复申请：
 *  - 短信：腾讯云 SMS（与 LMS 平台 lib/sms.ts 同一套 SecretId/SDKAppID/签名/模板）
 *  - 微信：服务号网页授权（与 LMS 微信登录同一公众号 wxe338bd3016cb27f4，网页授权域名 aiedu.yn.cn 已配）
 *
 * @author property-fee
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "propertyfee.halo.run", version = "v1alpha1", kind = "SystemConfig",
    plural = "systemconfigs", singular = "systemconfig")
public class SystemConfig extends AbstractExtension {

    public static final String FIXED_NAME = "pf-platform-config";

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private SystemConfigSpec spec;

    @Data
    public static class SystemConfigSpec {

        // ===== 短信（腾讯云）=====
        /** 是否启用真实短信通道（false=开发万能码）。 */
        private Boolean smsEnabled = false;

        /** 腾讯云 SecretId。 */
        private String smsSecretId;

        /** 腾讯云 SecretKey。 */
        private String smsSecretKey;

        /** 腾讯云 SMS SDKAppID。 */
        private String smsSdkAppId;

        /** 短信签名（如：文山市文笔塔商贸）。 */
        private String smsSignName;

        /** 验证码模板 ID。 */
        private String smsTemplateId;

        // ===== 微信网页授权（服务号）=====
        /** 是否启用微信一键登录。 */
        private Boolean wxEnabled = false;

        /** 服务号 AppID（平台统一：wxe338bd3016cb27f4）。 */
        private String wxAppId;

        /** 服务号 AppSecret（与 LMS 微信登录同一 Secret，仅存插件配置，不入代码库）。 */
        private String wxAppSecret;

        /**
         * 微信回调域名基础（必须是本公众号后台「网页授权域名」已备案的域名）。
         * 为空表示未配置，微信登录功能不可用——不提供任何默认站点，避免把开发者站点带到用户环境。
         */
        private String wxRedirectBase;

        /** 前台缴费页完整 URL（可配置；为空时自动推导当前站点 + /apis/api.propertyfee.halo.run/v1alpha1/property-fee-page）。 */
        private String frontUrl;
    }
}
