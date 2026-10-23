package com.colosseum.routestub.callbackcolo1.shopify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.colosseum.routestub.callbackcolo1.config.ShopifyProperties;

class CallbackControllerTest {

    private final CallbackController sut =
            new CallbackController(new ShopifyProperties("key", "shpss_secret", "https://app.example.com", "2026-07", ""));

    @Test
    void shop이_없으면_400() {
        ResponseEntity<String> response = sut.callback("default", Map.of());

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void hmac이_없으면_401() {
        ResponseEntity<String> response = sut.callback("default", Map.of("shop", "test-ceip-store.myshopify.com"));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void 보관되지_않은_result로_갱신하면_400() {
        ResponseEntity<String> response = sut.refreshToken("default", "unknown");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void result가_없는_demo_return은_토큰_없이_200() {
        ResponseEntity<String> response = sut.demoReturn(Map.of("shop", "test-ceip-store.myshopify.com"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("토큰 응답이 없다"));
        assertFalse(response.getBody().contains("갱신 요청"));
    }
}
