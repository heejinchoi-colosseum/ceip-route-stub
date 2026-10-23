package com.colosseum.routestub.callback.cafe24;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import com.colosseum.routestub.callback.config.Cafe24Properties;

class Cafe24WebhookControllerTest {

    private static final byte[] BODY = "{\"event_no\":90077,\"resource\":{\"mall_id\":\"coloceip\"}}"
            .getBytes(StandardCharsets.UTF_8);

    private final Cafe24WebhookController sut = new Cafe24WebhookController(
            new Cafe24Properties("cafe24-client", "cafe24-secret", "https://app.example.com", "coloceip", ""));

    @Test
    void 라우터_서명이_맞으면_받아서_보인다() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Router-Hmac-SHA256", sign(BODY, "cafe24-secret"));
        headers.set("X-Router-Event-No", "90077");

        assertEquals(HttpStatus.OK, sut.receive("default", "app-uninstalled", headers, BODY).getStatusCode());
        assertTrue(sut.demoWebhooks().getBody().contains("90077"));
    }

    @Test
    void 라우터_서명이_틀리면_401() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Router-Hmac-SHA256", sign(BODY, "other-secret"));

        assertEquals(HttpStatus.UNAUTHORIZED, sut.receive("default", "app-uninstalled", headers, BODY).getStatusCode());
    }

    private static String sign(byte[] body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(body));
    }
}
