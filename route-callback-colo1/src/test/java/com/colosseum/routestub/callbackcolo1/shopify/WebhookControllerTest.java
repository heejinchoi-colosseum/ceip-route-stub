package com.colosseum.routestub.callbackcolo1.shopify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.colosseum.routestub.callbackcolo1.config.ShopifyProperties;

class WebhookControllerTest {

    private static final String SECRET = "shpss_secret";
    private static final byte[] BODY = "{\"myshopify_domain\":\"test-ceip-store.myshopify.com\"}"
            .getBytes(StandardCharsets.UTF_8);

    private final WebhookController sut = new WebhookController(
            new ShopifyProperties("key", SECRET, "https://app.example.com", "2026-07", ""));

    @Test
    void 라우터_서명이_틀리면_401() {
        HttpHeaders headers = routerHeaders("bad");

        ResponseEntity<String> response = sut.receive("default", "app-uninstalled", headers, BODY);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertTrue(sut.demoWebhooks().getBody().contains("받은 웹훅이 없다"));
    }

    @Test
    void 라우터_서명이_맞으면_200이고_demo_webhooks에_보인다() {
        HttpHeaders headers = routerHeaders(HmacVerifier.base64HmacSha256(BODY, SECRET));

        ResponseEntity<String> response = sut.receive("default", "app-uninstalled", headers, BODY);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String page = sut.demoWebhooks().getBody();
        assertTrue(page.contains("app/uninstalled"), page);
        assertTrue(page.contains("test-ceip-store.myshopify.com"), page);
        assertTrue(page.contains("<code>cgs</code>"), page);
    }

    @Test
    void 구독_주소는_라우터_릴레이에_service와_region을_쿼리로_싣는다() {
        WebhookSubscriber subscriber = new WebhookSubscriber(
                new ShopifyProperties("key", SECRET, "https://app.example.com/", "2026-07", ""), null);

        assertEquals("https://app.example.com/webhooks/shopify/default/app-uninstalled?service=colo1.0&region=kr",
                subscriber.callbackUrl("default", "colo1.0", "kr"));
    }

    private static HttpHeaders routerHeaders(String routerHmac) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Router-Hmac-SHA256", routerHmac);
        headers.set("X-Router-Service", "cgs");
        headers.set("X-Router-Region", "us");
        headers.set("X-Shopify-Topic", "app/uninstalled");
        headers.set("X-Shopify-Shop-Domain", "test-ceip-store.myshopify.com");
        headers.set("X-Shopify-Webhook-Id", "wh-1");
        return headers;
    }
}
