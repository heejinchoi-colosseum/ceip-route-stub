package com.colosseum.routestub.cafe24;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import com.colosseum.routestub.config.Cafe24Properties;
import com.colosseum.routestub.config.ShopifyProperties;

class Cafe24WebhookRelayControllerTest {

    private static final byte[] BODY = """
            {"event_no":90077,"resource":{"mall_id":"coloceip","client_id":"c","app_name":"dev"}}
            """.getBytes(StandardCharsets.UTF_8);

    private static Cafe24WebhookRelayController relay(String webhookKey) {
        return new Cafe24WebhookRelayController(
                new Cafe24Properties("cafe24-client", "cafe24-secret", "mall.read_store", webhookKey),
                new ShopifyProperties("key", "shpss_secret", "https://app.example.com", "https://cb.example.com",
                        null, null, null, "cgs", "read_orders"),
                new Cafe24MallRegistry());
    }

    @Test
    void X_API_Key가_다르면_401() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", "wrong");

        assertEquals(HttpStatus.UNAUTHORIZED,
                relay("hook-key").relay("default", "app-uninstalled", headers, BODY).getStatusCode());
    }

    @Test
    void X_API_Key가_없으면_401() {
        assertEquals(HttpStatus.UNAUTHORIZED,
                relay("hook-key").relay("default", "app-uninstalled", new HttpHeaders(), BODY).getStatusCode());
    }

    @Test
    void 웹훅_키를_설정하지_않으면_500() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", "anything");

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR,
                relay("").relay("default", "app-uninstalled", headers, BODY).getStatusCode());
    }

    @Test
    void 연결_기록이_있는_몰은_그_서비스만_전달처다() {
        Cafe24MallRegistry registry = new Cafe24MallRegistry();
        registry.record("coloceip", "colo1.0");

        assertEquals(java.util.Set.of("colo1.0"), registry.servicesOf("coloceip"));
        assertEquals(java.util.Set.of(), registry.servicesOf("unknown"));
    }
}
