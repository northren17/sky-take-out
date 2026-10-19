import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.DefaultJedisClientConfig;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;

/**
 * 本地验证用：按后端 JwtUtil 的算法签一个 user 令牌，并写进 Redis（后端会比对 Redis 里的值）。
 * 用法: MintToken <userId> <redisDb>
 */
public class MintToken {
    private static final String SECRET = "lulumiao"; // sky.jwt.user-secret-key
    private static final long TTL_MILLIS = 7_200_000L; // sky.jwt.user-ttl

    public static void main(String[] args) throws Exception {
        long userId = args.length > 0 ? Long.parseLong(args[0]) : 1L;
        int db = args.length > 1 ? Integer.parseInt(args[1]) : 10;

        // 与后端一致：密钥不足 32 字节时用 SHA-256 派生
        byte[] raw = SECRET.getBytes(StandardCharsets.UTF_8);
        byte[] keyBytes = raw.length * 8 >= 256 ? raw : MessageDigest.getInstance("SHA-256").digest(raw);
        SecretKey key = Keys.hmacShaKeyFor(keyBytes);

        long exp = System.currentTimeMillis() + TTL_MILLIS;
        String token = Jwts.builder()
                .claim("userId", userId)
                .claim("username", "smoke_user")
                .signWith(key)
                .expiration(new Date(exp))
                .compact();

        try (Jedis jedis = new Jedis("127.0.0.1", 6379, DefaultJedisClientConfig.builder().database(db).build())) {
            jedis.setex("user:token:" + userId, TTL_MILLIS / 1000, token);
        }
        System.out.println(token);
    }
}
