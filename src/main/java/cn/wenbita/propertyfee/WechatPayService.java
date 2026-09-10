package cn.wenbita.propertyfee;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPrivateKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * 微信支付 APIv3 工具：按小区动态取商户配置签名下单。
 * 支持多商户号：不同小区绑定不同商户（PaymentConfig）。
 *
 * @author property-fee
 */
@Component
@AllArgsConstructor
public class WechatPayService {

    private static final String API_BASE = "https://api.mch.weixin.qq.com";

    private final run.halo.app.extension.ReactiveExtensionClient client;

    /** 服务端密钥库：商户私钥 / APIv3 密钥均以密文落库，使用时才解密。 */
    private final SecretStore secretStore;

    /** 读取商户私钥明文（落库为密文）。 */
    private String privateKeyOf(PaymentConfig pc) {
        return secretStore.decrypt(pc.getSpec() == null ? null : pc.getSpec().getMchPrivateKey());
    }

    /** 读取 APIv3 密钥明文（落库为密文）。 */
    public String apiV3KeyOf(PaymentConfig pc) {
        return secretStore.decrypt(pc.getSpec() == null ? null : pc.getSpec().getApiV3Key());
    }

    /**
     * 获取小区的支付配置。
     * 优先匹配小区专属商户；若未配置则回退到「默认商户」（协会统一微信支付通道）。
     */
    public Mono<PaymentConfig> getConfig(String community) {
        return getConfig(community, null, null);
    }

    /**
     * 按小区 + 渠道类型 + 渠道名称获取支付配置。
     * 匹配优先级：小区+渠道名称 > 小区+渠道类型(启用且默认) > 小区任意启用渠道
     *          > 默认商户+渠道类型 > 默认商户任意启用渠道。
     */
    public Mono<PaymentConfig> getConfig(String community, String channelType, String payChannel) {
        return client.listAll(PaymentConfig.class,
                run.halo.app.extension.ListOptions.builder().build(),
                org.springframework.data.domain.Sort.unsorted())
            .filter(pc -> pc.getSpec() != null && community.equals(pc.getSpec().getCommunity()))
            .collectList()
            .flatMap(list -> {
                // 1. 渠道名称精确匹配（前端选中的渠道）
                if (payChannel != null && !payChannel.isBlank()) {
                    var hit = list.stream().filter(pc ->
                            payChannel.equals(pc.getSpec().getChannelName())
                                && isEnabled(pc)).findFirst().orElse(null);
                    if (hit != null) {
                        return Mono.just(hit);
                    }
                }
                // 2. 小区 + 渠道类型 + 默认
                if (channelType != null && !channelType.isBlank()) {
                    var hit = list.stream().filter(pc -> channelType.equals(pc.getSpec().getChannelType())
                            && isEnabled(pc) && Boolean.TRUE.equals(pc.getSpec().getIsDefault()))
                        .findFirst().orElse(null);
                    if (hit != null) {
                        return Mono.just(hit);
                    }
                    // 3. 小区 + 渠道类型（任意启用）
                    hit = list.stream().filter(pc -> channelType.equals(pc.getSpec().getChannelType())
                            && isEnabled(pc)).findFirst().orElse(null);
                    if (hit != null) {
                        return Mono.just(hit);
                    }
                }
                // 4. 小区默认渠道或任意启用渠道
                var hit = list.stream().filter(pc -> Boolean.TRUE.equals(pc.getSpec().getIsDefault())
                        && isEnabled(pc)).findFirst().orElse(null);
                if (hit == null) {
                    hit = list.stream().filter(this::isEnabled).findFirst().orElse(null);
                }
                if (hit != null) {
                    return Mono.just(hit);
                }
                return Mono.empty();
            })
            .switchIfEmpty(Mono.defer(() -> getDefaultConfig(channelType)))
            .switchIfEmpty(Mono.error(new PropertyFeeException("该小区未配置可用支付渠道，请联系物业")));
    }

    private boolean isEnabled(PaymentConfig pc) {
        return pc.getSpec() != null && !Boolean.FALSE.equals(pc.getSpec().getEnabled());
    }

    private Mono<PaymentConfig> getDefaultConfig(String channelType) {
        return client.listAll(PaymentConfig.class,
                run.halo.app.extension.ListOptions.builder().build(),
                org.springframework.data.domain.Sort.unsorted())
            .filter(pc -> pc.getSpec() != null
                && DEFAULT_COMMUNITY.equals(pc.getSpec().getCommunity()))
            .collectList()
            .flatMap(list -> {
                if (channelType != null && !channelType.isBlank()) {
                    var hit = list.stream().filter(pc -> channelType.equals(pc.getSpec().getChannelType())
                            && isEnabled(pc)).findFirst().orElse(null);
                    if (hit != null) {
                        return Mono.just(hit);
                    }
                }
                var hit = list.stream().filter(this::isEnabled).findFirst().orElse(null);
                return hit == null ? Mono.empty() : Mono.just(hit);
            });
    }

    /** 默认商户配置的小区标记（协会统一微信支付通道）。 */
    public static final String DEFAULT_COMMUNITY = "默认商户";

    /**
     * 获取全部支付配置（回调时逐个尝试解密）。
     */
    public Mono<List<PaymentConfig>> listAllConfigs() {
        return client.listAll(PaymentConfig.class,
                run.halo.app.extension.ListOptions.builder().build(),
                org.springframework.data.domain.Sort.unsorted())
            .collectList();
    }

    /** 列出小区所有启用渠道（前台展示支付方式用）。 */
    public Mono<List<Map<String, Object>>> listChannels(String community) {
        return client.listAll(PaymentConfig.class,
                run.halo.app.extension.ListOptions.builder().build(),
                org.springframework.data.domain.Sort.unsorted())
            .filter(pc -> pc.getSpec() != null && isEnabled(pc)
                && (community.equals(pc.getSpec().getCommunity())
                    || DEFAULT_COMMUNITY.equals(pc.getSpec().getCommunity())))
            .collectList()
            .map(list -> list.stream().map(pc -> {
                var s = pc.getSpec();
                java.util.Map<String, Object> m = new java.util.HashMap<>();
                m.put("channelName", s.getChannelName() == null || s.getChannelName().isBlank()
                    ? s.getChannelType() : s.getChannelName());
                m.put("channelType", s.getChannelType());
                m.put("isDefault", Boolean.TRUE.equals(s.getIsDefault()));
                m.put("offlineInstruction", s.getOfflineInstruction());
                return m;
            }).toList());
    }

    /**
     * 微信 APIv3 请求签名。
     */
    private String sign(String method, String urlPath, String body,
        String nonceStr, long timestamp, String privateKeyPem) throws Exception {
        String message = method + "\n" + urlPath + "\n" + timestamp + "\n" + nonceStr + "\n"
            + body + "\n";
        // 兼容私钥中字面 \\n（双重转义存储）与真实换行：先还原真实换行
        String pemText = privateKeyPem.replace("\\n", "\n").replace("\\r", "");
        // 只提取 BEGIN 行之后、END 行之前的 base64 内容（PEM 头尾字母不能混入）
        int beginIdx = pemText.indexOf("BEGIN");
        int endIdx = pemText.lastIndexOf("END");
        if (beginIdx >= 0 && endIdx > beginIdx) {
            // 从 BEGIN 行末尾换行之后开始
            int lineEnd = pemText.indexOf('\n', beginIdx);
            int endLineStart = pemText.lastIndexOf('\n', endIdx);
            if (lineEnd >= 0 && endLineStart > lineEnd) {
                pemText = pemText.substring(lineEnd + 1, endLineStart);
            } else {
                pemText = pemText.substring(beginIdx + 5, endIdx);
            }
        }
        String pem = pemText.replaceAll("[^A-Za-z0-9+/=]", "");
        byte[] der = Base64.getDecoder().decode(pem);
        // 私钥可能是 PKCS#8（外层包裹）或 PKCS#1；统一解析出 (n, e, d) 用 RSAPrivateKeySpec 构造，
        // 避免部分微信证书 CRT 参数(qp)异常导致 Java 签名失败（openssl/node 宽容，Java 严格）。
        java.math.BigInteger[] neds = extractNED(der);
        if (neds != null) {
            RSAPrivateKeySpec spec = new RSAPrivateKeySpec(neds[0], neds[1]);
            PrivateKey privateKey = KeyFactory.getInstance("RSA").generatePrivate(spec);
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        }
        // 回退：标准 PKCS#8 解析（兼容参数正常的证书）
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        PrivateKey privateKey = KeyFactory.getInstance("RSA").generatePrivate(spec);
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(privateKey);
        signature.update(message.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signature.sign());
    }

    /**
     * 从 DER 中提取 RSA (n, d)：兼容 PKCS#8 包裹和裸 PKCS#1 两种格式。
     * 返回 null 表示不是可识别的 RSA 私钥。
     */
    private static java.math.BigInteger[] extractNED(byte[] der) {
        try {
            // 如果是 PKCS#8：SEQUENCE { INTEGER version, SEQUENCE alg, OCTET STRING pkcs1 }
            int off = 0;
            if (der[off] == 0x30) {
                int[] pos = readTlv(der, off);
                int innerStart = pos[1]; // body start
                int innerEnd = pos[2];   // body end
                // 第一个子元素必须是 INTEGER(version)
                int[] ver = readTlv(der, innerStart);
                if (der[innerStart] == 0x02) {
                    // 再跳过 AlgorithmIdentifier
                    int[] alg = readTlv(der, ver[2]);
                    // 找到 OCTET STRING
                    int[] oct = readTlv(der, alg[2]);
                    if (der[alg[2]] == 0x04) {
                        byte[] pkcs1 = java.util.Arrays.copyOfRange(der, oct[1], oct[2]);
                        return parsePKCS1NED(pkcs1);
                    }
                }
                // 否则尝试直接按 PKCS#1 解析
                return parsePKCS1NED(der);
            }
            return parsePKCS1NED(der);
        } catch (Exception e) {
            return null;
        }
    }

    private static java.math.BigInteger[] parsePKCS1NED(byte[] der) {
        try {
            int[] seq = readTlv(der, 0);
            if (der[0] != 0x30) return null;
            int off = seq[1];
            // version
            int[] ver = readTlv(der, off);
            off = ver[2];
            // n
            int[] nTlv = readTlv(der, off);
            java.math.BigInteger n = new java.math.BigInteger(1,
                java.util.Arrays.copyOfRange(der, nTlv[1], nTlv[2]));
            off = nTlv[2];
            // e
            int[] eTlv = readTlv(der, off);
            java.math.BigInteger e = new java.math.BigInteger(1,
                java.util.Arrays.copyOfRange(der, eTlv[1], eTlv[2]));
            off = eTlv[2];
            // d
            int[] dTlv = readTlv(der, off);
            java.math.BigInteger d = new java.math.BigInteger(1,
                java.util.Arrays.copyOfRange(der, dTlv[1], dTlv[2]));
            return new java.math.BigInteger[] {n, d, e};
        } catch (Exception e) {
            return null;
        }
    }

    /** 返回 [tag, bodyStart, bodyEnd] */
    private static int[] readTlv(byte[] der, int off) {
        int tag = der[off] & 0xff;
        int lenByte = der[off + 1] & 0xff;
        int len = 0;
        int hdr = 2;
        if ((lenByte & 0x80) != 0) {
            int numLen = lenByte & 0x7f;
            for (int i = 0; i < numLen; i++) {
                len = (len << 8) | (der[off + 2 + i] & 0xff);
            }
            hdr = 2 + numLen;
        } else {
            len = lenByte;
        }
        return new int[] {tag, off + hdr, off + hdr + len};
    }

    private String buildAuthHeader(PaymentConfig pc, String method, String urlPath,
        String body, String nonceStr, long timestamp) throws Exception {
        String signature = sign(method, urlPath, body, nonceStr, timestamp, privateKeyOf(pc));
        return "WECHATPAY2-SHA256-RSA2048 mchid=\"" + pc.getSpec().getMchId()
            + "\",nonce_str=\"" + nonceStr + "\",signature=\"" + signature
            + "\",timestamp=\"" + timestamp + "\",serial_no=\""
            + pc.getSpec().getMchSerialNo() + "\"";
    }

    /**
     * Native 扫码支付下单（电脑端/非微信环境）。
     * 返回 code_url（二维码内容）。
     */
    public Mono<Map<String, Object>> createNativeOrder(PaymentConfig pc,
        String description, String outTradeNo, long totalFen, String notifyUrl) {
        String urlPath = "/v3/pay/transactions/native";
        Map<String, Object> body = Map.of(
            "appid", pc.getSpec().getAppId(),
            "mchid", pc.getSpec().getMchId(),
            "description", description,
            "out_trade_no", outTradeNo,
            "notify_url", notifyUrl,
            "amount", Map.of("total", totalFen, "currency", "CNY")
        );
        return doRequest(pc, "POST", urlPath, body);
    }

    /**
     * JSAPI 支付下单（微信内，需 openid）。
     * 返回 prepay_id。
     */
    public Mono<Map<String, Object>> createJsapiOrder(PaymentConfig pc,
        String description, String outTradeNo, long totalFen, String notifyUrl,
        String openid) {
        String urlPath = "/v3/pay/transactions/jsapi";
        Map<String, Object> body = Map.of(
            "appid", pc.getSpec().getAppId(),
            "mchid", pc.getSpec().getMchId(),
            "description", description,
            "out_trade_no", outTradeNo,
            "notify_url", notifyUrl,
            "amount", Map.of("total", totalFen, "currency", "CNY"),
            "payer", Map.of("openid", openid)
        );
        return doRequest(pc, "POST", urlPath, body);
    }

    /**
     * 查询订单（用于前端轮询确认支付结果）。
     */
    public Mono<Map<String, Object>> queryOrder(PaymentConfig pc, String outTradeNo) {
        String urlPath = "/v3/pay/transactions/out-trade-no/" + outTradeNo
            + "?mchid=" + pc.getSpec().getMchId();
        return doRequest(pc, "GET", urlPath, null);
    }

    private Mono<Map<String, Object>> doRequest(PaymentConfig pc,
        String method, String urlPath, Map<String, Object> body) {
        return Mono.create(sink -> {
            try {
                String bodyStr = body == null ? "" : toJson(body);
                String nonceStr = randomString(32);
                long timestamp = System.currentTimeMillis() / 1000;
                String auth = buildAuthHeader(pc, method, urlPath, bodyStr, nonceStr, timestamp);

                WebClient webClient = WebClient.builder()
                    .baseUrl(API_BASE)
                    .defaultHeader("Content-Type", "application/json")
                    .defaultHeader("Accept", "application/json")
                    .defaultHeader("Authorization", auth)
                    .build();

                WebClient.RequestBodySpec spec = webClient.method(
                        org.springframework.http.HttpMethod.valueOf(method))
                    .uri(urlPath);

                Mono<String> responseMono = body == null
                    ? spec.retrieve().bodyToMono(String.class)
                    : spec.bodyValue(body).retrieve().bodyToMono(String.class);

                responseMono.subscribe(
                    resp -> {
                        try {
                            sink.success(parseJson(resp));
                        } catch (Exception e) {
                            sink.error(new PropertyFeeException("微信返回解析失败: " + e.getMessage()));
                        }
                    },
                    err -> sink.error(new PropertyFeeException("微信支付接口调用失败: " + extractErr(err)))
                );
            } catch (Exception e) {
                sink.error(new PropertyFeeException("微信支付签名失败: " + e.getMessage()));
            }
        });
    }

    /**
     * 解密微信回调 resource（AEAD_AES_256_GCM）。
     *
     * <p>规范要点（历史实现曾踩坑）：APIv3 报文里的 {@code nonce} 是 12 个字符的
     * <b>ASCII 字符串</b>，直接作为 GCM 的 IV 使用，<b>不能做 base64 解码</b>；
     * {@code ciphertext} 整体 base64 解码后「密文 + 16 字节 auth tag」，
     * Java 侧直接传入完整数据即可；{@code associated_data} 原样 UTF-8。
     */
    public static String decryptNotifyResource(String apiV3Key, String ciphertext,
        String nonce, String associatedData) throws Exception {
        if (apiV3Key == null || apiV3Key.getBytes(StandardCharsets.UTF_8).length != 32) {
            throw new PropertyFeeException("APIv3 密钥必须为 32 字节");
        }
        byte[] full = Base64.getDecoder().decode(ciphertext);
        javax.crypto.spec.SecretKeySpec keySpec =
            new javax.crypto.spec.SecretKeySpec(apiV3Key.getBytes(StandardCharsets.UTF_8), "AES");
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        javax.crypto.spec.GCMParameterSpec gcmSpec =
            new javax.crypto.spec.GCMParameterSpec(128, ivOf(nonce));
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, gcmSpec);
        cipher.updateAAD((associatedData == null ? "" : associatedData).getBytes(StandardCharsets.UTF_8));
        return new String(cipher.doFinal(full), StandardCharsets.UTF_8);
    }

    /** GCM IV：nonce 原文（12 字符 ASCII）优先；个别场景兼容 base64(12B)。 */
    private static byte[] ivOf(String nonce) {
        if (nonce == null || nonce.isBlank()) {
            throw new PropertyFeeException("回调 nonce 缺失");
        }
        byte[] raw = nonce.getBytes(StandardCharsets.UTF_8);
        if (raw.length == 12) {
            return raw;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(nonce);
            if (decoded.length == 12) {
                return decoded;
            }
        } catch (Exception ignored) {
            // 落到下方统一报错
        }
        throw new PropertyFeeException("回调 nonce 长度非法：" + raw.length);
    }

    // ============ 支付回调验签（微信支付平台证书） ============

    /** 平台证书缓存：mchId -> serialNo(大写) -> 证书。 */
    private final java.util.Map<String, java.util.Map<String, java.security.cert.X509Certificate>>
        platformCertCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 校验微信支付回调签名（Wechatpay-Signature / -Timestamp / -Nonce / -Serial），
     * 并返回验签通过的商户配置；找不到可验签商户时抛异常（调用方应返回失败让微信重试）。
     *
     * <p>验签串规范：{@code timestamp + "\n" + nonce + "\n" + body + "\n"}，
     * 用对应平台证书公钥做 SHA256withRSA 校验；同时限制时间戳在 ±300 秒内防重放。
     */
    public Mono<PaymentConfig> verifyNotifySignature(List<PaymentConfig> configs, String timestamp,
        String nonce, String signature, String serial, String body) {
        if (timestamp == null || nonce == null || signature == null
            || serial == null || body == null || serial.isBlank()) {
            return Mono.error(new PropertyFeeException("回调缺少验签头，拒绝处理"));
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp.trim());
        } catch (Exception e) {
            return Mono.error(new PropertyFeeException("回调时间戳非法"));
        }
        if (Math.abs(System.currentTimeMillis() / 1000 - ts) > 300) {
            return Mono.error(new PropertyFeeException("回调时间戳超出允许范围（防重放）"));
        }
        final String message = timestamp + "\n" + nonce + "\n" + body + "\n";
        final String targetSerial = serial.trim().toUpperCase(java.util.Locale.ROOT);
        return reactor.core.publisher.Flux.fromIterable(configs)
            .filter(pc -> pc.getSpec() != null && pc.getSpec().getMchId() != null)
            .concatMap(pc -> platformCert(pc, targetSerial)
                .filter(cert -> verifySignature(cert, message, signature))
                .map(cert -> pc))
            .next()
            .switchIfEmpty(Mono.error(new PropertyFeeException(
                "回调验签失败：未匹配到可验签的商户配置")));
    }

    private static boolean verifySignature(java.security.cert.X509Certificate cert,
        String message, String signature) {
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(cert.getPublicKey());
            verifier.update(message.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signature));
        } catch (Exception e) {
            return false;
        }
    }

    /** 取平台证书：本地缓存优先，未命中则调 /v3/certificates 拉取并用 APIv3 密钥解密。 */
    private Mono<java.security.cert.X509Certificate> platformCert(PaymentConfig pc, String serial) {
        String mchId = pc.getSpec().getMchId();
        var cache = platformCertCache.computeIfAbsent(mchId,
            k -> new java.util.concurrent.ConcurrentHashMap<>());
        var cached = cache.get(serial);
        if (cached != null) {
            return Mono.just(cached);
        }
        return doRequest(pc, "GET", "/v3/certificates", null)
            .flatMap(resp -> {
                try {
                    String apiV3Key = apiV3KeyOf(pc);
                    if (apiV3Key == null || apiV3Key.isBlank()) {
                        return Mono.error(new PropertyFeeException("商户未配置 APIv3 密钥"));
                    }
                    Object dataObj = resp.get("data");
                    if (!(dataObj instanceof List<?> data)) {
                        return Mono.empty();
                    }
                    for (Object itemObj : data) {
                        if (!(itemObj instanceof Map<?, ?> item)) {
                            continue;
                        }
                        Object encObj = item.get("encrypt_certificate");
                        if (!(encObj instanceof Map<?, ?> enc)) {
                            continue;
                        }
                        String pem = decryptNotifyResource(apiV3Key,
                            String.valueOf(enc.get("ciphertext")),
                            String.valueOf(enc.get("nonce")),
                            enc.get("associated_data") == null
                                ? "" : String.valueOf(enc.get("associated_data")));
                        var cert = parseCertificate(pem);
                        if (cert != null) {
                            String sn = item.get("serial_no") == null
                                ? cert.getSerialNumber().toString(16)
                                : String.valueOf(item.get("serial_no"));
                            cache.put(sn.toUpperCase(java.util.Locale.ROOT), cert);
                            cache.put(cert.getSerialNumber().toString(16)
                                .toUpperCase(java.util.Locale.ROOT), cert);
                        }
                    }
                    var found = cache.get(serial);
                    return found == null ? Mono.empty() : Mono.just(found);
                } catch (Exception e) {
                    return Mono.error(new PropertyFeeException(
                        "平台证书获取失败: " + e.getMessage()));
                }
            });
    }

    private static java.security.cert.X509Certificate parseCertificate(String pem) {
        try {
            String base64 = pem.replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(base64);
            var factory = java.security.cert.CertificateFactory.getInstance("X.509");
            return (java.security.cert.X509Certificate) factory.generateCertificate(
                new java.io.ByteArrayInputStream(der));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 主动查单补偿：回调丢失时按商户订单号查询真实支付状态。
     */
    public Mono<Map<String, Object>> queryOrderByOutTradeNo(PaymentConfig pc, String outTradeNo) {
        return queryOrder(pc, outTradeNo);
    }

    private static String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v instanceof Map || v instanceof java.util.List) {
                sb.append(toJson((Map<String, Object>) v));
            } else if (v instanceof String) {
                sb.append("\"").append(((String) v).replace("\"", "\\\"")).append("\"");
            } else {
                sb.append(v);
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private static Map<String, Object> parseJson(String json) {
        // 简单 JSON 解析（用 Jackson 或手写均可用；这里用最小实现）
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new PropertyFeeException("JSON解析失败: " + e.getMessage());
        }
    }

    private static String extractErr(Throwable err) {
        String msg = err.getMessage() == null ? "unknown" : err.getMessage();
        // WebClient 4xx/5xx 的错误体在异常里
        return msg.length() > 200 ? msg.substring(0, 200) : msg;
    }

    private static String randomString(int len) {
        String chars = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678";
        StringBuilder sb = new StringBuilder();
        java.security.SecureRandom random = new java.security.SecureRandom();
        for (int i = 0; i < len; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
