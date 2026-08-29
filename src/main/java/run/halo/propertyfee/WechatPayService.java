package run.halo.propertyfee;

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

    /**
     * 获取小区的支付配置。
     * 优先匹配小区专属商户；若未配置则回退到「默认商户」（协会统一微信支付通道）。
     */
    public Mono<PaymentConfig> getConfig(String community) {
        return client.listAll(PaymentConfig.class,
                run.halo.app.extension.ListOptions.builder().build(),
                org.springframework.data.domain.Sort.unsorted())
            .filter(pc -> pc.getSpec() != null
                && community.equals(pc.getSpec().getCommunity()))
            .next()
            .switchIfEmpty(Mono.defer(() ->
                client.listAll(PaymentConfig.class,
                        run.halo.app.extension.ListOptions.builder().build(),
                        org.springframework.data.domain.Sort.unsorted())
                    .filter(pc -> pc.getSpec() != null
                        && DEFAULT_COMMUNITY.equals(pc.getSpec().getCommunity()))
                    .next()))
            .switchIfEmpty(Mono.error(new PropertyFeeException("该小区未配置微信支付商户，请联系物业")));
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
        String signature = sign(method, urlPath, body, nonceStr, timestamp,
            pc.getSpec().getMchPrivateKey());
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
     */
    public static String decryptNotifyResource(String apiV3Key, String ciphertext,
        String nonce, String associatedData) throws Exception {
        byte[] full = Base64.getDecoder().decode(ciphertext);
        // 注意：微信回调密文是「密文+16字节tag」整体，Java GCM 解密需传入完整数据（含tag）
        javax.crypto.spec.SecretKeySpec keySpec =
            new javax.crypto.spec.SecretKeySpec(apiV3Key.getBytes(StandardCharsets.UTF_8), "AES");
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        javax.crypto.spec.GCMParameterSpec gcmSpec =
            new javax.crypto.spec.GCMParameterSpec(128, Base64.getDecoder().decode(nonce));
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, gcmSpec);
        cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
        return new String(cipher.doFinal(full), StandardCharsets.UTF_8);
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
