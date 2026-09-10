package cn.wenbita.propertyfee;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 业主手机号验证登录（安全加固版）：
 * <ul>
 *   <li><b>不再存在任何固定验证码 / 开发绕过通道</b>：短信通道未配置时直接拒绝发码，
 *       而不是放行一个通用码。</li>
 *   <li>验证码使用 {@link java.security.SecureRandom} 生成，5 分钟有效、60 秒防重发、
 *       连续输错 5 次即作废；<b>验证码不写入任何日志</b>。</li>
 *   <li>令牌签名密钥由 {@link SecretStore} 按安装随机生成（HKDF 派生），
 *       代码中不再存在硬编码密钥；令牌带 {@code v2.} 版本前缀，
 *       旧版本令牌（无前缀）一律判为无效，升级即完成旧令牌失效。</li>
 * </ul>
 */
@Slf4j
@Component
public class OwnerAuthService {

    /** 令牌格式版本前缀：升级密钥体系时递增，旧前缀令牌全部失效。 */
    private static final String TOKEN_VERSION = "v2.";

    private static final int MAX_VERIFY_ATTEMPTS = 5;
    private static final long CODE_TTL_SECONDS = 300;
    private static final long RESEND_SECONDS = 60;
    private static final long TOKEN_TTL_SECONDS = 7 * 24 * 3600;

    /** 验证码记录：phone -> {code, expireAt, lastSentAt, attempts}。 */
    private final Map<String, CodeBox> codes = new ConcurrentHashMap<>();

    private final byte[] tokenKey;

    public OwnerAuthService(SecretStore secretStore) {
        this.tokenKey = secretStore.deriveKey("owner-auth-token");
    }

    /**
     * 签发验证码并返回明文（仅供调用方通过真实短信通道发送）。
     * 手机号格式错误或发送过于频繁时抛业务异常。
     */
    public String issueCode(String rawPhone) {
        String phone = requirePhone(rawPhone);
        long now = Instant.now().getEpochSecond();
        CodeBox box = codes.get(phone);
        if (box != null && now - box.lastSentAt() < RESEND_SECONDS) {
            throw new PropertyFeeException("验证码发送太频繁，请稍后再试");
        }
        String code = SecretStore.randomNumericCode(6);
        codes.put(phone, new CodeBox(code, now + CODE_TTL_SECONDS, now, 0));
        // 注意：验证码属于敏感凭据，任何情况下都不写入日志
        log.info("[property-fee] 业主验证码已生成（不记录明文）phone={}", SecretStore.maskPhone(phone));
        return code;
    }

    /** 直接签发业主令牌（微信 openid 命中已绑定业主等已完成身份校验的场景）。 */
    public String issueTokenByPhone(String phone) {
        return issueToken(requirePhone(phone));
    }

    /** 登录：验证码正确返回签名令牌，否则抛业务异常。 */
    public String login(String rawPhone, String inputCode) {
        String phone = requirePhone(rawPhone);
        if (inputCode == null || inputCode.isBlank()) {
            throw new PropertyFeeException("验证码不能为空");
        }
        CodeBox box = codes.get(phone);
        long now = Instant.now().getEpochSecond();
        if (box == null || now > box.expireAt()) {
            throw new PropertyFeeException("验证码已过期，请重新获取");
        }
        if (box.attempts() >= MAX_VERIFY_ATTEMPTS) {
            codes.remove(phone);
            throw new PropertyFeeException("验证码错误次数过多，请重新获取");
        }
        if (!SecretStore.constantTimeEquals(box.code(), inputCode.trim())) {
            codes.put(phone, box.withAttempt());
            throw new PropertyFeeException("验证码错误");
        }
        codes.remove(phone);
        return issueToken(phone);
    }

    /** 解析令牌得到手机号；无效 / 过期 / 旧版本一律抛业务异常。 */
    public String verifyToken(String token) {
        if (token == null || token.isBlank()) {
            throw new PropertyFeeException("请先登录业主账号");
        }
        if (!token.startsWith(TOKEN_VERSION)) {
            // 旧版本令牌（含升级前生成的）一律失效，强制重新登录
            throw new PropertyFeeException("登录凭证已失效，请重新登录");
        }
        try {
            String payload = new String(
                Base64.getUrlDecoder().decode(token.substring(TOKEN_VERSION.length())),
                StandardCharsets.UTF_8);
            String[] parts = payload.split("\\|");
            if (parts.length != 3) {
                throw new PropertyFeeException("登录凭证无效，请重新登录");
            }
            String phone = parts[0];
            long exp = Long.parseLong(parts[1]);
            String sig = parts[2];
            String expected = hmac(phone + "|" + exp);
            if (!SecretStore.constantTimeEquals(expected, sig)) {
                throw new PropertyFeeException("登录凭证无效，请重新登录");
            }
            if (Instant.now().getEpochSecond() > exp) {
                throw new PropertyFeeException("登录已过期，请重新登录");
            }
            return phone;
        } catch (PropertyFeeException e) {
            throw e;
        } catch (Exception e) {
            throw new PropertyFeeException("登录凭证无效，请重新登录");
        }
    }

    /** 主动作废某手机号待用的验证码（如发送失败时回滚）。 */
    public void invalidateCode(String rawPhone) {
        String phone = PropertyHelper.normalizePhone(rawPhone);
        if (phone != null) {
            codes.remove(phone);
        }
    }

    private String issueToken(String phone) {
        long exp = Instant.now().getEpochSecond() + TOKEN_TTL_SECONDS;
        String raw = phone + "|" + exp;
        return TOKEN_VERSION + Base64.getUrlEncoder().withoutPadding()
            .encodeToString((raw + "|" + hmac(raw)).getBytes(StandardCharsets.UTF_8));
    }

    private String hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(tokenKey, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("令牌签名失败", e);
        }
    }

    private static String requirePhone(String rawPhone) {
        String phone = PropertyHelper.normalizePhone(rawPhone);
        if (phone == null || phone.length() != 11) {
            throw new PropertyFeeException("手机号格式不正确");
        }
        return phone;
    }

    /** 验证码记录（不可变，便于并发安全地更新失败次数）。 */
    record CodeBox(String code, long expireAt, long lastSentAt, int attempts) {
        CodeBox withAttempt() {
            return new CodeBox(code, expireAt, lastSentAt, attempts + 1);
        }
    }
}
