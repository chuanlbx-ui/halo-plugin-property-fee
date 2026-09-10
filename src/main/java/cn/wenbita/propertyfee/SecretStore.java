package cn.wenbita.propertyfee;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 服务端秘密存储：为插件提供「每套安装一份」的主密钥，用于两件事：
 * <ol>
 *   <li>加密落库的敏感配置（腾讯云短信 SecretKey、服务号 AppSecret、微信支付商户私钥 / APIv3 密钥）；</li>
 *   <li>派生业主登录令牌的 HMAC 签名密钥（不再使用代码内置的固定密钥）。</li>
 * </ol>
 *
 * <p>主密钥为 32 字节安全随机数，首次使用时生成并落盘到 Halo 工作目录下（权限 0600），
 * <b>不写入代码库、不写入扩展数据、不通过任何接口返回</b>。主密钥变更后，
 * 所有用旧密钥加密的配置将无法解密（需管理员重新填写），同时全部旧登录令牌自动失效。
 */
@Slf4j
@Component
public class SecretStore {

    /** 加密串前缀（带版本号，便于将来换算法）。 */
    public static final String ENC_PREFIX = "enc:v1:";

    private static final int KEY_BYTES = 32;
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final String AES = "AES";
    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] masterKey;

    public SecretStore(@Value("${halo.work-dir:}") String workDir) {
        this.masterKey = loadOrCreateMasterKey(workDir);
    }

    /** 候选存放目录，按优先级尝试第一个可写的。 */
    private static Set<Path> candidateDirs(String workDir) {
        Set<Path> dirs = new LinkedHashSet<>();
        if (workDir != null && !workDir.isBlank()) {
            dirs.add(Paths.get(workDir.trim()).resolve("property-fee"));
        }
        String home = System.getProperty("user.home", "");
        if (!home.isBlank()) {
            dirs.add(Paths.get(home).resolve(".halo2").resolve("property-fee"));
            dirs.add(Paths.get(home).resolve(".property-fee"));
        }
        dirs.add(Paths.get(System.getProperty("java.io.tmpdir", "/tmp")).resolve("property-fee-secrets"));
        return dirs;
    }

    private static byte[] loadOrCreateMasterKey(String workDir) {
        for (Path dir : candidateDirs(workDir)) {
            try {
                Files.createDirectories(dir);
                Path keyFile = dir.resolve("master.key");
                if (Files.exists(keyFile)) {
                    byte[] existing = Files.readAllBytes(keyFile);
                    if (existing.length >= KEY_BYTES) {
                        return Arrays.copyOf(existing, KEY_BYTES);
                    }
                    log.warn("[property-fee] 主密钥文件长度异常，将重新生成: {}", keyFile);
                }
                byte[] key = new byte[KEY_BYTES];
                RANDOM.nextBytes(key);
                Files.write(keyFile, key);
                restrictToOwner(keyFile);
                log.info("[property-fee] 已生成服务端主密钥（仅本机可读）: {}", keyFile);
                return key;
            } catch (Exception e) {
                log.warn("[property-fee] 主密钥目录不可用 {}: {}", dir, e.getMessage());
            }
        }
        // 极端兜底：不阻塞插件启动，但明确告警（进程重启后需重新填写敏感配置、业主重新登录）
        log.error("[property-fee] 无法落盘服务端主密钥，改用进程内临时密钥；"
            + "敏感配置在重启后需重新填写、业主令牌会失效，请检查工作目录写权限");
        byte[] fallback = new byte[KEY_BYTES];
        RANDOM.nextBytes(fallback);
        return fallback;
    }

    private static void restrictToOwner(Path file) {
        try {
            Files.setPosixFilePermissions(file,
                java.util.EnumSet.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (Exception ignored) {
            // 非 POSIX 文件系统（如 Windows）忽略
        }
    }

    /** 是否已是密文。 */
    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(ENC_PREFIX);
    }

    /**
     * 加密敏感值。空值原样返回；已是密文则不再二次加密（幂等）。
     */
    public String encrypt(String plain) {
        if (plain == null || plain.isBlank()) {
            return plain;
        }
        if (isEncrypted(plain)) {
            return plain;
        }
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(masterKey, AES),
                new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(cipherText, 0, packed, iv.length, cipherText.length);
            return ENC_PREFIX + Base64.getEncoder().encodeToString(packed);
        } catch (Exception e) {
            throw new IllegalStateException("敏感配置加密失败", e);
        }
    }

    /**
     * 解密；历史明文（无前缀）原样返回以兼容旧数据。解密失败返回空串（视为未配置）。
     */
    public String decrypt(String stored) {
        if (stored == null || stored.isBlank()) {
            return stored;
        }
        if (!isEncrypted(stored)) {
            return stored;
        }
        try {
            byte[] packed = Base64.getDecoder().decode(stored.substring(ENC_PREFIX.length()));
            byte[] iv = Arrays.copyOfRange(packed, 0, GCM_IV_BYTES);
            byte[] cipherText = Arrays.copyOfRange(packed, GCM_IV_BYTES, packed.length);
            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(masterKey, AES),
                new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[property-fee] 敏感配置解密失败（主密钥已变更或数据损坏），该配置按未配置处理");
            return "";
        }
    }

    /**
     * 按用途派生独立的签名子密钥（HKDF 简化版），避免不同用途共用同一把密钥。
     */
    public byte[] deriveKey(String purpose) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(masterKey, "HmacSHA256"));
            return mac.doFinal(("property-fee/" + purpose).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("派生签名密钥失败", e);
        }
    }

    /** 生成安全随机十六进制串（用于 state / 一次性票据）。 */
    public static String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        StringBuilder sb = new StringBuilder(buf.length * 2);
        for (byte b : buf) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** 安全随机数字验证码（固定位长）。 */
    public static String randomNumericCode(int digits) {
        StringBuilder sb = new StringBuilder(digits);
        for (int i = 0; i < digits; i++) {
            sb.append(RANDOM.nextInt(10));
        }
        return sb.toString();
    }

    /** 定长比较，避免时序侧信道。 */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
            a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** 手机号脱敏：138****8000。 */
    public static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return "";
        }
        String p = phone.trim();
        if (p.length() < 7) {
            return "****";
        }
        return p.substring(0, 3) + "****" + p.substring(p.length() - 4);
    }

    /** 姓名脱敏：仅保留姓氏（张*）。 */
    public static String maskName(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String n = name.trim();
        if (n.length() == 1) {
            return n;
        }
        return n.charAt(0) + "*".repeat(Math.min(n.length() - 1, 3));
    }

    /** 敏感值是否已配置（用于接口只回传布尔标记，不回传明文）。 */
    public static boolean isConfigured(String value) {
        return value != null && !value.isBlank();
    }
}
