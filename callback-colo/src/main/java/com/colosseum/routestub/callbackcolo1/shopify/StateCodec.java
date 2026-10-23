package com.colosseum.routestub.callbackcolo1.shopify;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.util.UriUtils;

/**
 * OAuth state 를 서명해 담고 되읽는다. route-start 모듈 {@code com.colosseum.routestub.shopify.StateCodec} 의 복제본이다.
 * 콜백 모듈을 격리하려고 복제했고, 형식과 서명 방식은 start 와 정확히 같아야 왕복이 성립한다.
 *
 * <p>형식은 {@code base64url(payload)."hmacHex} 이고 payload 는 {@code key=value&...} 쿼리 문자열이다.
 */
final class StateCodec {

    private static final long TTL_MILLIS = 10 * 60 * 1000L;

    private StateCodec() {
    }

    /** 서비스별 부가 값(sellerId, warehouseId 등)을 payload 에 실을 때 키 앞에 붙이는 접두어. */
    private static final String EXTRA_PREFIX = "extra.";

    /**
     * 복원된 state. expiring 은 콜백이 토큰 교환에 expiring=1 을 붙여 만료형 offline 토큰을 받을지다.
     * extras 는 start 가 서비스별로 조합해 실은 부가 값(sellerId, warehouseId 등)이다. Shopify 는 redirect_uri 를
     * 등록값과 글자까지 같게 요구해 쿼리를 붙일 수 없으므로, 콜백까지 나를 값은 전부 여기에 싣는다.
     */
    record State(String shop, String region, String service, String returnTo, boolean expiring,
            Map<String, String> extras) {
        State {
            extras = extras == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(extras));
        }
    }

    static String encode(String shop, String region, String service, String returnTo, boolean expiring,
            String secret) {
        return encode(shop, region, service, returnTo, expiring, Map.of(), secret);
    }

    static String encode(String shop, String region, String service, String returnTo, boolean expiring,
            Map<String, String> extras, String secret) {
        StringBuilder payload = new StringBuilder("shop=" + enc(shop)
                + "&region=" + enc(region)
                + "&service=" + enc(service)
                + "&returnTo=" + enc(returnTo)
                + "&expiring=" + (expiring ? "1" : "0"));
        if (extras != null) {
            extras.forEach((key, value) -> payload.append('&').append(EXTRA_PREFIX).append(enc(key))
                    .append('=').append(enc(value)));
        }
        payload.append("&nonce=").append(UUID.randomUUID().toString().replace("-", ""))
                .append("&ts=").append(System.currentTimeMillis());
        String signature = HmacVerifier.hexHmacSha256(payload.toString(), secret);
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
        String expected = HmacVerifier.hexHmacSha256(payload, secret);
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
        return new State(dec(fields.get("shop")), dec(fields.get("region")),
                dec(fields.get("service")), dec(fields.get("returnTo")), "1".equals(fields.get("expiring")), extras);
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
