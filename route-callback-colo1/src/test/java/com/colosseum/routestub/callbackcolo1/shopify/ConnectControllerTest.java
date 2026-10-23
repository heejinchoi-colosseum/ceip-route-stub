package com.colosseum.routestub.callbackcolo1.shopify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.colosseum.routestub.callbackcolo1.config.ShopifyProperties;

class ConnectControllerTest {

    @Test
    void connect_폼은_appHost의_start로_간다() {
        ConnectController sut = new ConnectController(
                new ShopifyProperties("key", "shpss_secret", "https://app.example.com/", "2026-07", ""));

        ResponseEntity<String> response = sut.connect();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String body = response.getBody();
        assertTrue(body.contains("action=\"https://app.example.com/oauth/shopify/default/start\""), body);
        assertTrue(body.contains("name=\"sellerId\""));
        assertTrue(body.contains("name=\"warehouseId\""));
        assertTrue(body.contains("name=\"expiring\""), "만료형 offline 토큰 체크박스가 있어야 한다");
        for (String held : new String[] {"channel", "region", "service", "shop", "returnTo"}) {
            assertTrue(body.contains("name=\"" + held + "\""), held + " 는 사용자 입력 없이 함께 보내야 한다");
        }
    }

    @Test
    void appHost가_없으면_같은_호스트의_상대_경로를_쓴다() {
        assertEquals("/oauth/shopify/default/start", ConnectController.startUrl(null));
        assertEquals("/oauth/shopify/default/start", ConnectController.startUrl(" "));
    }
}
