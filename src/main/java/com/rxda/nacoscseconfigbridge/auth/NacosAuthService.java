package com.rxda.nacoscseconfigbridge.auth;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.rxda.nacoscseconfigbridge.config.NacosAuthProperties;

/**
 * 校验 Nacos 登录凭据，并签发、验证无状态登录令牌。
 *
 * <p>令牌由共享的签名密钥通过 HMAC-SHA256 签名，不保存在进程内存中。
 * 所有副本使用相同配置时，任意副本签发的令牌都能由其他副本直接验证，
 * 因此不需要负载均衡会话保持。</p>
 */
@Component
public class NacosAuthService implements InitializingBean {

    private static final String TOKEN_VERSION = "v1";
    private static final String DISABLED_AUTH_TOKEN = "authentication-disabled";

    private final NacosAuthProperties properties;
    private final Clock clock;
    private volatile byte[] tokenSecret = new byte[0];

    /**
     * 创建使用系统时钟的鉴权服务。
     *
     * @param properties 鉴权配置
     */
    public NacosAuthService(NacosAuthProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /**
     * 创建可注入时钟的鉴权服务，便于测试令牌过期。
     *
     * @param properties 鉴权配置
     * @param clock 读取当前时间的时钟
     */
    NacosAuthService(NacosAuthProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 在启动时校验凭据、签名密钥和令牌有效期配置。
     *
     * <p>严格模式可以由相同用户名密码派生签名密钥；任意凭据模式无法从
     * 客户端输入派生副本间一致的密钥，因此必须显式配置共享密钥。</p>
     */
    @Override
    public void afterPropertiesSet() {
        if (properties.isEnabled()
                && !properties.isAcceptAnyCredentials()
                && (!StringUtils.hasText(properties.getUsername())
                || !StringUtils.hasText(properties.getPassword()))) {
            throw new IllegalStateException(
                    "启用 Nacos 严格鉴权时必须同时配置非空的用户名和密码");
        }
        if (properties.getTokenTtlSeconds() <= 0) {
            throw new IllegalStateException("Nacos 鉴权令牌有效期必须大于 0 秒");
        }
        tokenSecret = resolveTokenSecret();
    }

    /**
     * 判断当前是否要求请求携带令牌。
     *
     * @return 启用鉴权时返回 {@code true}
     */
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /**
     * 校验登录凭据并签发无状态令牌。
     *
     * <p>鉴权关闭时，只要用户名和密码均非空也返回兼容令牌，以兼容已经配置了
     * Nacos 用户名的客户端；此时令牌不会被强制校验。</p>
     *
     * @param username 客户端提交的用户名
     * @param password 客户端提交的密码
     * @return 校验通过后的令牌
     */
    public Optional<String> login(String username, String password) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            return Optional.empty();
        }
        if (properties.isEnabled() && !properties.isAcceptAnyCredentials()
                && !(constantTimeEquals(properties.getUsername(), username)
                && constantTimeEquals(properties.getPassword(), password))) {
            return Optional.empty();
        }
        if (!properties.isEnabled()) {
            return Optional.of(DISABLED_AUTH_TOKEN);
        }
        long expiresAt = clock.millis()
                + properties.getTokenTtlSeconds() * 1000L;
        return Optional.of(createSignedToken(username, expiresAt));
    }

    /**
     * 验证请求携带的无状态令牌。
     *
     * @param token 请求中的令牌，可为 {@code null}
     * @return 鉴权关闭或令牌签名、有效期均正确时返回 {@code true}
     */
    public boolean isTokenValid(String token) {
        if (!properties.isEnabled()) {
            return true;
        }
        if (!StringUtils.hasText(token)) {
            return false;
        }
        return verifySignedToken(token);
    }

    /**
     * 解析所有副本共享的令牌签名密钥。
     *
     * @return HMAC-SHA256 使用的原始密钥
     * @throws IllegalStateException 任意凭据模式未配置共享密钥
     */
    private byte[] resolveTokenSecret() {
        if (StringUtils.hasText(properties.getTokenSecret())) {
            return properties.getTokenSecret().getBytes(StandardCharsets.UTF_8);
        }
        if (!properties.isEnabled()) {
            return new byte[0];
        }
        if (properties.isAcceptAnyCredentials()) {
            throw new IllegalStateException(
                    "任意凭据模式必须配置所有副本一致的 NACOS_AUTH_TOKEN_SECRET");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] username = properties.getUsername().getBytes(StandardCharsets.UTF_8);
            byte[] password = properties.getPassword().getBytes(StandardCharsets.UTF_8);
            digest.update(intToBytes(username.length));
            digest.update(username);
            digest.update(intToBytes(password.length));
            digest.update(password);
            return digest.digest();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("无法计算 Nacos 鉴权令牌签名密钥", exception);
        }
    }

    /**
     * 生成包含用户名和过期时间的签名令牌。
     *
     * @param username 已通过凭据校验的用户名
     * @param expiresAtEpochMilli 令牌过期时间
     * @return 无状态令牌
     */
    private String createSignedToken(String username, long expiresAtEpochMilli) {
        try {
            byte[] payload = encodePayload(username, expiresAtEpochMilli);
            String payloadPart = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(payload);
            String signedPart = TOKEN_VERSION + "." + payloadPart;
            String signaturePart = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(hmacSha256(signedPart.getBytes(StandardCharsets.UTF_8)));
            return signedPart + "." + signaturePart;
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("无法签发 Nacos 鉴权令牌", exception);
        }
    }

    /**
     * 验证令牌的版本、签名和过期时间。
     *
     * @param token 待验证令牌
     * @return 令牌有效时返回 {@code true}
     */
    private boolean verifySignedToken(String token) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || !TOKEN_VERSION.equals(parts[0])) {
            return false;
        }
        try {
            String signedPart = parts[0] + "." + parts[1];
            byte[] expectedSignature = hmacSha256(
                    signedPart.getBytes(StandardCharsets.UTF_8));
            byte[] actualSignature = Base64.getUrlDecoder().decode(parts[2]);
            if (!MessageDigest.isEqual(expectedSignature, actualSignature)) {
                return false;
            }
            TokenPayload payload = decodePayload(Base64.getUrlDecoder().decode(parts[1]));
            return payload.expiresAtEpochMilli() > clock.millis();
        } catch (IllegalArgumentException | GeneralSecurityException exception) {
            return false;
        }
    }

    private byte[] encodePayload(String username, long expiresAtEpochMilli) {
        byte[] usernameBytes = username.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(
                Integer.BYTES + usernameBytes.length + Long.BYTES);
        buffer.putInt(usernameBytes.length);
        buffer.put(usernameBytes);
        buffer.putLong(expiresAtEpochMilli);
        return buffer.array();
    }

    private TokenPayload decodePayload(byte[] payload) {
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        if (buffer.remaining() < Integer.BYTES + Long.BYTES) {
            throw new IllegalArgumentException("令牌内容过短");
        }
        int usernameLength = buffer.getInt();
        if (usernameLength < 0 || usernameLength > buffer.remaining() - Long.BYTES) {
            throw new IllegalArgumentException("令牌用户名长度无效");
        }
        buffer.position(buffer.position() + usernameLength);
        return new TokenPayload(buffer.getLong());
    }

    private byte[] hmacSha256(byte[] value) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(tokenSecret, "HmacSHA256"));
        return mac.doFinal(value);
    }

    private static byte[] intToBytes(int value) {
        return new byte[] {
                (byte) (value >>> 24),
                (byte) (value >>> 16),
                (byte) (value >>> 8),
                (byte) value
        };
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private record TokenPayload(long expiresAtEpochMilli) {
    }
}
