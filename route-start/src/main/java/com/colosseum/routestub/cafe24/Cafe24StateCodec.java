package com.colosseum.routestub.cafe24;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.web.util.UriUtils;

/**
 * Cafe24 OAuth state 를 서명해 담고 되읽는다. Shopify {@code StateCodec} 과 같은 형식
 * ({@code base64url(payload)."hmacHex}, 키는 Cafe24 client secret)이고 필드만 다르다.
 *
 * <p>Cafe24 는 Shopify 와 달리 콜백에 hmac 을 붙이지 않는다. 그래서 위변조 확인은 이 서명 state 가 전부다.
 * 또 토큰 교환 요청에 authorize 때와 같은 redirect_uri 를 다시 보내야 하므로, start 가 고른 redirect_uri 를
 * state 에 함께 실어 콜백이 자기 공개 호스트를 몰라도 그대로 쓰게 한다.
 */
final class Cafe24StateCodec {

    private static final long TTL_MILLIS = 10 * 60 * 1000L;

    /** 서비스별 부가 값(sellerId, warehouseId 등)을 payload 에 실을 때 키 앞에 붙이는 접두어. */
    private static final String EXTRA_PREFIX = "extra.";

    private Cafe24StateCodec() {
    }

    /** 복원된 state. extras 는 start 가 서비스별로 조합해 실은 부가 값이다. */
    record State(String mallId, String region, String service, String returnTo, String redirectUri,
            Map<String, String> extras) {
        State {
            extras = extras == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(extras));
        }
    }

    static String encode(String mallId, String region, String service, String returnTo, String redirectUri,
            Map<String, String> extras, String secret) {
        StringBuilder payload = new StringBuilder("mallId=" + enc(mallId)
                + "&region=" + enc(region)
                + "&service=" + enc(service)
                + "&returnTo=" + enc(returnTo)
                + "&redirectUri=" + enc(redirectUri));
        if (extras != null) {
            extras.forEach((key, value) -> payload.append('&').append(EXTRA_PREFIX).append(enc(key))
                    .append('=').append(enc(value)));
        }
        payload.append("&nonce=").append(UUID.randomUUID().toString().replace("-", ""))
                .append("&ts=").append(System.currentTimeMillis());
        String signature = hexHmacSha256(payload.toString(), secret);
        String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8));
        return encodedPayload + "." + signature;
    }

    /** 서명, TTL 을 검증하고 복원한다. 어긋나면 null. */
    static State decode(String state, String secret) {
        if (state == null) {
            return null;
        }
        int dot = state.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(state.substring(0, dot)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String signature = state.substring(dot + 1);
        String expected = hexHmacSha256(payload, secret);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8))) {
            return null;
        }

        Map<String, String> fields = parse(payload);
        long ts = parseLong(fields.get("ts"));
        if (System.currentTimeMillis() - ts > TTL_MILLIS) {
            return null;
        }
        Map<String, String> extras = new LinkedHashMap<>();
        fields.forEach((key, value) -> {
            if (key.startsWith(EXTRA_PREFIX)) {
                extras.put(dec(key.substring(EXTRA_PREFIX.length())), dec(value));
            }
        });
        return new State(dec(fields.get("mallId")), dec(fields.get("region")), dec(fields.get("service")),
                dec(fields.get("returnTo")), dec(fields.get("redirectUri")), extras);
    }

    /** 라우터가 소비 서비스로 웹훅을 넘길 때 붙이는 X-Router-Hmac-SHA256(본문 raw bytes 의 HMAC, base64). */
    static String base64HmacSha256(byte[] body, String secret) {
        return Base64.getEncoder().encodeToString(hmacSha256(body, secret));
    }

    static String hexHmacSha256(String message, String secret) {
        byte[] digest = hmacSha256(message.getBytes(StandardCharsets.UTF_8), secret);
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    private static byte[] hmacSha256(byte[] message, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC 계산 실패", e);
        }
    }

    private static Map<String, String> parse(String payload) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String pair : payload.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                fields.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return fields;
    }

    private static long parseLong(String value) {
        try {
            return value == null ? 0L : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String enc(String value) {
        return UriUtils.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String dec(String value) {
        return value == null ? null : UriUtils.decode(value, StandardCharsets.UTF_8);
    }
}
