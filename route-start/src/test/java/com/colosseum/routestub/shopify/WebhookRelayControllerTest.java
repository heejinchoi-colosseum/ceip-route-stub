package com.colosseum.routestub.shopify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.colosseum.routestub.config.ShopifyProperties;

class WebhookRelayControllerTest {

    private static final String SECRET = "shpss_secret";
    private static final byte[] BODY = "{\"id\":1,\"myshopify_domain\":\"test-ceip-store.myshopify.com\"}"
            .getBytes(StandardCharsets.UTF_8);

    private final WebhookRelayController sut = new WebhookRelayController(new ShopifyProperties("key", SECRET,
            "https://app.example.com", "https://callback.example.com", null, null, null, "cgs", "read_orders", ""));

    @Test
    void 웹훅_hmac은_본문_raw_bytes의_base64다() {
        String hmac = HmacVerifier.base64HmacSha256(BODY, SECRET);

        assertTrue(HmacVerifier.isValidWebhook(BODY, hmac, SECRET));
        assertTrue(HmacVerifier.isValidWebhook(BODY, hmac + "\n", SECRET), "헤더 끝 공백은 무시한다");
        assertFalse(HmacVerifier.isValidWebhook(BODY, hmac, "other"));
        assertFalse(HmacVerifier.isValidWebhook("{}".getBytes(StandardCharsets.UTF_8), hmac, SECRET));
        assertFalse(HmacVerifier.isValidWebhook(BODY, null, SECRET));
    }

    @Test
    void hmac이_틀리면_401이고_전달하지_않는다() {
        HttpHeaders headers = shopifyHeaders("bad");

        ResponseEntity<String> response = sut.relay("default", "app-uninstalled", "cgs", "us", headers, BODY);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void hmac은_맞지만_service를_모르면_400() {
        HttpHeaders headers = shopifyHeaders(HmacVerifier.base64HmacSha256(BODY, SECRET));

        ResponseEntity<String> response = sut.relay("default", "app-uninstalled", "unknown", "us", headers, BODY);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void 소비_서비스에_닿지_못하면_502로_Shopify_재시도를_유도한다() {
        // callback.example.com 은 없는 호스트라 연결이 실패한다(연결 1초 타임아웃).
        HttpHeaders headers = shopifyHeaders(HmacVerifier.base64HmacSha256(BODY, SECRET));

        ResponseEntity<String> response = sut.relay("default", "app-uninstalled", "cgs", "us", headers, BODY);

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
    }

    @Test
    void service_쿼리가_없어도_compliance_topic이면_접수_담당으로_폴백한다() {
        assertEquals("cgs", WebhookRelayController.resolveService(null, "customers-data_request", "customers/data_request", "cgs"));
        assertEquals("cgs", WebhookRelayController.resolveService("", "customers-redact", null, "cgs"), "헤더가 없으면 경로로 판단");
        assertEquals("cgs", WebhookRelayController.resolveService(null, "shop-redact", "shop/redact", "cgs"));
        assertEquals("colo1.0", WebhookRelayController.resolveService("colo1.0", "shop-redact", "shop/redact", "cgs"),
                "쿼리에 service 가 있으면 그 값이 우선");
        assertEquals(null, WebhookRelayController.resolveService(null, "app-uninstalled", "app/uninstalled", "cgs"),
                "compliance 가 아닌 topic 은 service 없이는 전달처가 없다");
        assertEquals(null, WebhookRelayController.resolveService(null, "shop-redact", "shop/redact", ""),
                "접수 담당이 설정돼 있지 않으면 폴백하지 않는다");
    }

    @Test
    void service_쿼리_없는_app_uninstalled는_400() {
        HttpHeaders headers = shopifyHeaders(HmacVerifier.base64HmacSha256(BODY, SECRET));

        ResponseEntity<String> response = sut.relay("default", "app-uninstalled", null, null, headers, BODY);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    private static HttpHeaders shopifyHeaders(String hmac) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Shopify-Hmac-SHA256", hmac);
        headers.set("X-Shopify-Topic", "app/uninstalled");
        headers.set("X-Shopify-Shop-Domain", "test-ceip-store.myshopify.com");
        headers.set("X-Shopify-Webhook-Id", "wh-1");
        return headers;
    }
}
