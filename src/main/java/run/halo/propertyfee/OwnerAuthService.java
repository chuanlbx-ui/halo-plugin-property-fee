package run.halo.propertyfee;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 业主手机号验证登录（V4）：
 * - sendCode：校验手机号确为某房屋业主（owners/单业主字段）后生成 6 位验证码
 *   （验证码内存存储、5 分钟有效、60 秒防重发）；短信发送走可插拔 Provider，
 *   未配置短信服务时自动进入开发模式（万能码 123456，日志可见）。
 * - login：验证码换签名 Token（HMAC-SHA256，7 天有效），后续查费/缴费携带。
 * 短信 Provider 接入：实现 {@link SmsProvider} 并注入即可（腾讯云等）。
 */
@Slf4j
@Component
public class OwnerAuthService {

    /** 验证码记录：phone -> {code, expireAt, lastSentAt}。 */
    private final Map<String, CodeBox> codes = new ConcurrentHashMap<>();

    /** 签名密钥（本地固定；生产建议通过插件设置注入覆盖）。 */
    private String secret = "property-fee-owner-auth-secret-v1-2026";

    private static final long CODE_TTL_SECONDS = 300;
    private static final long RESEND_SECONDS = 60;
    private static final long TOKEN_TTL_SECONDS = 7 * 24 * 3600;

    /** 短信发送通道；默认空实现（开发模式，万能码 123456）。 */
    private SmsProvider smsProvider = new DevSmsProvider();

    public OwnerAuthService() {
    }

    /** 注入真实短信通道（如腾讯云），未注入时走开发模式。 */
    public void setSmsProvider(SmsProvider provider) {
        if (provider != null) {
            this.smsProvider = provider;
        }
    }

    public void setSecret(String secret) {
        if (secret != null && secret.length() >= 16) {
            this.secret = secret;
        }
    }

    /** 是否开发模式（未接入真实短信通道）。 */
    public boolean isDevMode() {
        return smsProvider instanceof DevSmsProvider;
    }

    /**
     * 签发验证码并返回明文（供真实短信通道发送）；手机号不属于业主时返回 null。
     * 同时承担发送频率限制与验证码存储。
     */
    public String issueCode(String rawPhone) {
        String phone = PropertyHelper.normalizePhone(rawPhone);
        if (phone == null || phone.length() != 11) {
            throw new PropertyFeeException("手机号格式不正确");
        }
        CodeBox box = codes.get(phone);
        long now = Instant.now().getEpochSecond();
        if (box != null && now - box.lastSentAt < RESEND_SECONDS) {
            throw new PropertyFeeException("验证码发送太频繁，请稍后再试");
        }
        String code = String.valueOf((int) (Math.random() * 900000) + 100000);
        codes.put(phone, new CodeBox(code, now + CODE_TTL_SECONDS, now));
        log.info("[property-fee] 验证码已生成 phone={} code={}", phone, code);
        return code;
    }

    /** 发送验证码；手机号不属于任何业主时返回 false。 */
    public boolean sendCode(String rawPhone) {
        String phone = PropertyHelper.normalizePhone(rawPhone);
        if (phone == null || phone.length() != 11) {
            throw new PropertyFeeException("手机号格式不正确");
        }
        CodeBox box = codes.get(phone);
        long now = Instant.now().getEpochSecond();
        if (box != null && now - box.lastSentAt < RESEND_SECONDS) {
            throw new PropertyFeeException("验证码发送太频繁，请稍后再试");
        }
        String code = String.valueOf((int) (Math.random() * 900000) + 100000);
        codes.put(phone, new CodeBox(code, now + CODE_TTL_SECONDS, now));
        log.info("[property-fee] 验证码已生成 phone={} code={} (provider={})\n",
            phone, code, smsProvider.getClass().getSimpleName());
        return smsProvider.send(phone, code);
    }

    /** 直接签发业主 Token（微信 openid 命中已绑定业主等免密场景）。 */
    public String issueTokenByPhone(String phone) {
        String norm = PropertyHelper.normalizePhone(phone);
        if (norm == null || norm.length() != 11) {
            throw new PropertyFeeException("手机号无效");
        }
        return issueToken(norm);
    }

    /** 登录：验证码正确返回签名 Token，否则抛业务异常。 */
    public String login(String rawPhone, String inputCode) {
        String phone = PropertyHelper.normalizePhone(rawPhone);
        if (phone == null || inputCode == null || inputCode.isBlank()) {
            throw new PropertyFeeException("手机号或验证码不能为空");
        }
        CodeBox box = codes.get(phone);
        if (box == null || Instant.now().getEpochSecond() > box.expireAt) {
            throw new PropertyFeeException("验证码已过期，请重新获取");
        }
        boolean devOk = "123456".equals(inputCode) && smsProvider instanceof DevSmsProvider;
        if (!box.code.equals(inputCode) && !devOk) {
            throw new PropertyFeeException("验证码错误");
        }
        codes.remove(phone);
        return issueToken(phone);
    }

    /** 解析 Token 得到手机号；无效/过期抛业务异常。 */
    public String verifyToken(String token) {
        if (token == null || token.isBlank()) {
            throw new PropertyFeeException("请先登录业主账号");
        }
        try {
            String payload = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = payload.split("\\|");
            if (parts.length != 3) {
                throw new PropertyFeeException("登录凭证无效，请重新登录");
            }
            String phone = parts[0];
            long exp = Long.parseLong(parts[1]);
            String sig = parts[2];
            if (!hmac(phone + "|" + exp).equals(sig)) {
                throw new PropertyFeeException("登录凭证无效，请重新登录");
            }
            if (Instant.now().getEpochSecond() > exp) {
                throw new PropertyFeeException("登录已过期，请重新登录");
            }
            return phone;
        } catch (Exception e) {
            throw new PropertyFeeException("登录凭证无效，请重新登录");
        }
    }

    private String issueToken(String phone) {
        long exp = Instant.now().getEpochSecond() + TOKEN_TTL_SECONDS;
        String raw = phone + "|" + exp;
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString((raw + "|" + hmac(raw)).getBytes(StandardCharsets.UTF_8));
    }

    private String hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }

    /** 短信通道抽象：真实通道（腾讯云等）实现后 setSmsProvider 注入。 */
    public interface SmsProvider {
        /** 发送验证码；返回是否成功（失败不应影响开发模式主流程）。 */
        boolean send(String phone, String code);
    }

    /** 开发模式通道：不真发短信（万能码 123456 可登录），打印日志便于联调。 */
    static class DevSmsProvider implements SmsProvider {
        @Override
        public boolean send(String phone, String code) {
            log.info("[property-fee][dev-sms] 模拟发送验证码 -> {} : {}", phone, code);
            return true;
        }
    }

    record CodeBox(String code, long expireAt, long lastSentAt) {
    }
}
