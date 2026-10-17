package com.sky.utils;

import com.sky.properties.WeChatProperties;
import com.wechat.pay.contrib.apache.httpclient.util.PemUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class WeChatNotificationVerifier {
    private static final long ALLOWED_CLOCK_SKEW_SECONDS = 300;
    private final WeChatProperties weChatProperties;

    public void verify(HttpServletRequest request, String body) throws Exception {
        String timestamp = request.getHeader("Wechatpay-Timestamp");
        String nonce = request.getHeader("Wechatpay-Nonce");
        String signatureValue = request.getHeader("Wechatpay-Signature");
        String serial = request.getHeader("Wechatpay-Serial");
        if (!StringUtils.hasText(timestamp) || !StringUtils.hasText(nonce) ||
                !StringUtils.hasText(signatureValue) || !StringUtils.hasText(serial)) {
            throw new IllegalArgumentException("微信退款通知缺少验签请求头");
        }
        long epochSeconds = Long.parseLong(timestamp);
        if (Math.abs(Instant.now().getEpochSecond() - epochSeconds) > ALLOWED_CLOCK_SKEW_SECONDS) {
            throw new IllegalArgumentException("微信退款通知时间戳已过期");
        }
        String certificatePath = weChatProperties.getWeChatPayCertFilePath();
        if (!StringUtils.hasText(certificatePath)) {
            throw new IllegalStateException("未配置微信支付平台证书，无法验签退款通知");
        }
        X509Certificate certificate;
        try (FileInputStream input = new FileInputStream(new File(certificatePath))) {
            certificate = PemUtil.loadCertificate(input);
        }
        String expectedSerial = certificate.getSerialNumber().toString(16).toUpperCase(Locale.ROOT);
        if (!expectedSerial.equals(serial.toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("微信退款通知证书序列号不匹配");
        }
        String message = timestamp + "\n" + nonce + "\n" + body + "\n";
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(certificate.getPublicKey());
        verifier.update(message.getBytes(StandardCharsets.UTF_8));
        if (!verifier.verify(Base64.getDecoder().decode(signatureValue))) {
            throw new IllegalArgumentException("微信退款通知签名无效");
        }
    }
}
