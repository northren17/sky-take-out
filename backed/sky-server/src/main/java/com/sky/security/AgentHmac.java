package com.sky.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class AgentHmac {
    public static final String TIMESTAMP_HEADER = "X-Agent-Timestamp";
    public static final String NONCE_HEADER = "X-Agent-Nonce";
    public static final String SIGNATURE_HEADER = "X-Agent-Signature";

    private AgentHmac() {
    }

    public static String sign(String secret, String method, String target, String timestamp,
                              String nonce, byte[] body) {
        String canonical = method + "\n" + target + "\n" + timestamp + "\n" + nonce + "\n"
                + sha256Hex(body);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to sign internal Agent request", ex);
        }
    }

    public static boolean matches(String expected, String actual) {
        if (expected == null || actual == null) return false;
        try {
            return MessageDigest.isEqual(
                    HexFormat.of().parseHex(expected),
                    HexFormat.of().parseHex(actual));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static String sha256Hex(byte[] body) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(body));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to hash internal Agent request", ex);
        }
    }
}
