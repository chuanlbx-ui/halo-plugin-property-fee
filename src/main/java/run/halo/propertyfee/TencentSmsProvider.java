package run.halo.propertyfee;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * 腾讯云短信发送（TC3-HMAC-SHA256 签名，SMS API v2021-01-11）。
 * 凭据复用平台既有配置（LMS lib/sms.ts 同一套），不重复申请。
 */
@Component
public class TencentSmsProvider {

    public static final String ENDPOINT = "sms.tencentcloudapi.com";

    private static String sha256Hex(String data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return hex(md.digest(data.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] hmacSha256(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 发送验证码短信。
     *
     * @param secretId    腾讯云 SecretId
     * @param secretKey   腾讯云 SecretKey
     * @param sdkAppId    SMS SDKAppID
     * @param signName    短信签名
     * @param templateId  验证码模板 ID（模板参数需为 [code] 或 [code,minute]）
     * @param phone       手机号（11 位）
     * @param code        验证码
     * @return null=成功；非 null=失败原因
     */
    public String sendCode(String secretId, String secretKey, String sdkAppId,
        String signName, String templateId, String phone, String code) {
        try {
            String region = "ap-guangzhou";
            String service = "sms";
            String version = "2021-01-11";
            String host = ENDPOINT;
            String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
            String date = DateTimeFormatter.ofPattern("yyyy-MM-dd")
                .withZone(ZoneOffset.UTC).format(java.time.Instant.now());

            String payload = java.lang.String.format(
                "{\"PhoneNumberSet\":[\"+86%s\"],\"SmsSdkAppId\":\"%s\",\"SignName\":\"%s\","
                    + "\"TemplateId\":\"%s\",\"TemplateParamSet\":[\"%s\"]}",
                phone, sdkAppId, signName, templateId, code);

            String contentType = "application/json; charset=utf-8";
            String canonicalHeaders = "content-type:" + contentType + "\nhost:" + host
                + "\nx-tc-action:sendsms\n";
            String signedHeaders = "content-type;host;x-tc-action";
            String hashedPayload = sha256Hex(payload);
            String canonicalRequest = "POST\n/\n\n" + canonicalHeaders + "\n" + signedHeaders
                + "\n" + hashedPayload;

            String algorithm = "TC3-HMAC-SHA256";
            String credentialScope = date + "/" + service + "/tc3_request";
            String hashedCanonical = sha256Hex(canonicalRequest);
            String stringToSign = algorithm + "\n" + timestamp + "\n" + credentialScope + "\n"
                + hashedCanonical;

            byte[] secretDate = hmacSha256(("TC3" + secretKey).getBytes(StandardCharsets.UTF_8), date);
            byte[] secretService = hmacSha256(secretDate, service);
            byte[] secretSigning = hmacSha256(secretService, "tc3_request");
            String signature = hex(hmacSha256(secretSigning, stringToSign));

            String authorization = algorithm + " Credential=" + secretId + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://" + host))
                .header("Content-Type", contentType)
                .header("Host", host)
                .header("X-TC-Action", "SendSms")
                .header("X-TC-Version", version)
                .header("X-TC-Timestamp", timestamp)
                .header("X-TC-Region", region)
                .header("Authorization", authorization)
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

            HttpResponse<String> resp = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
            String body = resp.body();
            if (body != null && body.contains("\"Error\"")) {
                // 抽取错误信息
                int idx = body.indexOf("\"Message\"");
                String msg = idx > 0 ? body.substring(idx, Math.min(body.length(), idx + 120)) : body;
                return "腾讯云短信发送失败: " + msg;
            }
            return null;
        } catch (Exception e) {
            return "腾讯云短信调用异常: " + e.getMessage();
        }
    }
}
