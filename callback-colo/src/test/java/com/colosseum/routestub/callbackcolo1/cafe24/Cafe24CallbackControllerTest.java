package com.colosseum.routestub.callbackcolo1.cafe24;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.colosseum.routestub.callbackcolo1.config.Cafe24Properties;

class Cafe24CallbackControllerTest {

    private final Cafe24CallbackController sut = new Cafe24CallbackController(
            new Cafe24Properties("cafe24-client", "cafe24-secret", "https://app.example.com", "ceipmall", ""));

    @Test
    void 동의_거부_error는_400() {
        ResponseEntity<String> response = sut.callback("default",
                Map.of("error", "access_denied", "error_description", "denied", "state", "x"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().contains("access_denied"));
    }

    @Test
    void 위조된_state는_400() {
        String forged = Cafe24StateCodec.encode("ceipmall", "kr", "cgs", "/cafe24/demo/return",
                "https://cb.example.com/oauth/cafe24/default/callback", Map.of(), "other-secret");

        ResponseEntity<String> response = sut.callback("default", Map.of("code", "abc", "state", forged));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().contains("state 검증 실패"));
    }

    @Test
    void 보관되지_않은_result로_갱신하면_400() {
        assertEquals(HttpStatus.BAD_REQUEST, sut.refreshToken("default", "unknown").getStatusCode());
    }

    @Test
    void state는_redirect_uri와_서비스별_값을_왕복시킨다() {
        String state = Cafe24StateCodec.encode("ceipmall", "kr", "colo1.0", "/cafe24/demo/return",
                "https://cb.example.com/oauth/cafe24/default/callback", Map.of("sellerId", "s1"), "cafe24-secret");

        Cafe24StateCodec.State decoded = Cafe24StateCodec.decode(state, "cafe24-secret");

        assertEquals("https://cb.example.com/oauth/cafe24/default/callback", decoded.redirectUri());
        assertEquals(Map.of("sellerId", "s1"), decoded.extras());
    }
}
