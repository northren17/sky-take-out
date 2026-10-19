package com.sky.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.Map;

/**
 * JWT 工具类
 * <p>
 * 已从 jjwt 0.9.1 升级到 jjwt 0.12.x：
 * 0.9.1 依赖 javax.xml.bind.DatatypeConverter（JDK11+ 已移除，需要额外引入 jaxb-api），
 * 且其 API 在 0.12.x 中已废弃。
 */
public final class JwtUtil {

    /** HMAC-SHA256 要求密钥长度不少于 256 位（32 字节） */
    private static final int HMAC_SHA256_KEY_BITS = 256;

    private JwtUtil() {
    }

    /**
     * 生成jwt
     * 使用Hs256算法, 秘匙使用固定密钥
     * <p>
     * 注意：HS256 要求密钥至少 32 字节，配置的密钥过短时会自动做 SHA-256 派生，
     * 从而兼容项目原有的短密钥配置（itcast / lulumiao），旧配置无需修改即可启动。
     *
     * @param secretKey jwt密钥
     * @param ttlMillis jwt过期时间(毫秒)
     * @param claims    设置的信息
     * @return jwt 字符串
     */
    public static String createJWT(String secretKey, long ttlMillis, Map<String, Object> claims) {
        SecretKey key = toHmacShaKey(secretKey);

        // 生成JWT的时间
        long expMillis = System.currentTimeMillis() + ttlMillis;
        Date exp = new Date(expMillis);

        // 设置jwt的body
        JwtBuilder builder = Jwts.builder();
        if (claims != null) {
            // 先设置自定义声明，再设置标准声明，避免标准声明被覆盖
            claims.forEach(builder::claim);
        }
        builder
                // 设置签名使用的密钥（算法由密钥长度自动推导为 HS256）
                .signWith(key)
                // 设置过期时间
                .expiration(exp);

        return builder.compact();
    }

    /**
     * Token解密
     *
     * @param secretKey jwt密钥 此密钥一定要保留好在服务端, 不能暴露出去, 否则sign就可以被伪造, 如果对接多个客户端建议改造成多个
     * @param token     加密后的token
     * @return 载荷
     * @throws io.jsonwebtoken.JwtException token 非法或已过期时抛出
     */
    public static Claims parseJWT(String secretKey, String token) {
        return Jwts.parser()
                // 校验签名
                .verifyWith(toHmacShaKey(secretKey))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 由配置的字符串密钥生成符合 HS256 长度要求的密钥
     *
     * @param secretKey 配置的密钥
     * @return HMAC-SHA 密钥
     */
    private static SecretKey toHmacShaKey(String secretKey) {
        if (secretKey == null || secretKey.isEmpty()) {
            throw new IllegalArgumentException("jwt 密钥不能为空");
        }
        byte[] keyBytes = secretKey.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length * 8 >= HMAC_SHA256_KEY_BITS) {
            return Keys.hmacShaKeyFor(keyBytes);
        }
        // 密钥长度不足 32 字节时使用 SHA-256 派生，避免 jjwt 0.12.x 抛 WeakKeyException
        try {
            byte[] derived = MessageDigest.getInstance("SHA-256").digest(keyBytes);
            return Keys.hmacShaKeyFor(derived);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", e);
        }
    }
}
