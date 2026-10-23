package com.colosseum.routestub.callbackcolo1.shopify;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 콜백 쿼리 파라미터의 hmac 검증. 루트 모듈 {@code com.colosseum.routestub.shopify.HmacVerifier} 의 복제본이다. */
final class HmacVerifier {

    private HmacVerifier() {
    }

    static boolean isValid(Map<String, String> params, String hmac, String secret) {
        // hmac, signature 를 뺀 나머지를 키 사전순으로 정렬해 key=value&... 로 잇는다.
        TreeMap<String, String> sorted = new TreeMap<>(params);
        sorted.remove("hmac");
        sorted.remove("signature");
        String message = sorted.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&"));

        String computed = hexHmacSha256(message, secret);
        return MessageDigest.isEqual(
                computed.getBytes(StandardCharsets.UTF_8),
                hmac.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 웹훅 검증. OAuth 콜백과 달리 서명이 헤더 X-Shopify-Hmac-SHA256 에 base64 로 오고, 입력은 정렬한 쿼리가 아니라
     * 요청 본문 raw bytes 다. 키는 같은 client secret 이다. 라우터가 소비 서비스로 전달할 때 붙이는 X-Router-Hmac-SHA256 도
     * 같은 방식으로 만든다.
     */
    static boolean isValidWebhook(byte[] body, String base64Hmac, String secret) {
        if (body == null || base64Hmac == null) {
            return false;
        }
        String computed = base64HmacSha256(body, secret);
        return MessageDigest.isEqual(
                computed.getBytes(StandardCharsets.UTF_8),
                base64Hmac.trim().getBytes(StandardCharsets.UTF_8));
    }

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
}
