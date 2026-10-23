package com.colosseum.routestub.cafe24;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.util.UriComponentsBuilder;

import com.colosseum.routestub.config.Cafe24Properties;
import com.colosseum.routestub.config.ShopifyProperties;

class Cafe24StartTest {

    private static final String CGS_HOST = "https://cgs-callback.example.com";
    private static final String COLO1_HOST = "https://colo1-callback.example.com";

    private final Cafe24OAuthController sut = new Cafe24OAuthController(
            new Cafe24Properties("cafe24-client", "cafe24-secret", "mall.read_order,mall.read_product", "hook-key"),
            new ShopifyProperties("key", "shpss_secret", "https://app.example.com", "https://callback.example.com",
                    CGS_HOST, COLO1_HOST, null, "cgs", "read_orders"), new Cafe24MallRegistry());

    private static final String ROUTER_CALLBACK = "https://app.example.com/oauth/cafe24/default/callback";

    @Test
    void redirect_uri는_service와_무관하게_라우터_콜백_하나다() {
        assertEquals(ROUTER_CALLBACK, param(start("cgs"), "redirect_uri"));
        assertEquals(ROUTER_CALLBACK, param(start("colo1.0"), "redirect_uri"));
    }

    @Test
    void 라우터_콜백은_state의_service로_서비스_콜백에_쿼리를_그대로_넘긴다() {
        String state = param(start("colo1.0"), "state");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setQueryString("code=abc&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8));
        request.setParameter("code", "abc");
        request.setParameter("state", state);

        ResponseEntity<String> response = sut.callback("default", request);

        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        String location = response.getHeaders().getLocation().toString();
        assertTrue(location.startsWith(COLO1_HOST + "/oauth/cafe24/default/callback?code=abc&state="), location);

        String cgsState = param(start("cgs"), "state");
        request.setParameter("state", cgsState);
        assertTrue(sut.callback("default", request).getHeaders().getLocation().toString()
                .startsWith(CGS_HOST + "/oauth/cafe24/default/callback?"));
    }

    @Test
    void 라우터_콜백은_위조된_state면_어디로도_넘기지_않는다() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("code", "abc");
        request.setParameter("state", "forged.state");

        assertEquals(HttpStatus.BAD_REQUEST, sut.callback("default", request).getStatusCode());
    }

    @Test
    void authorize는_mallId_호스트로_가고_scope는_공백으로_잇는다() {
        URI location = start("cgs");

        assertEquals("ceipmall.cafe24api.com", location.getHost());
        assertEquals("/api/v2/oauth/authorize", location.getPath());
        assertEquals("code", param(location, "response_type"));
        assertEquals("cafe24-client", param(location, "client_id"));
        assertEquals("mall.read_order mall.read_product", param(location, "scope"));
    }

    @Test
    void state에_redirect_uri와_서비스별_값이_실린다() {
        URI location = start("colo1.0");

        Cafe24StateCodec.State state = Cafe24StateCodec.decode(param(location, "state"), "cafe24-secret");
        assertNotNull(state);
        assertEquals("ceipmall", state.mallId());
        assertEquals("colo1.0", state.service());
        assertEquals(ROUTER_CALLBACK, state.redirectUri());
        assertEquals(Map.of("sellerId", "seller-001", "warehouseId", "wh-001"), state.extras());
        assertTrue(param(location, "redirect_uri").indexOf('?') < 0, "redirect_uri 에는 쿼리를 붙이지 않는다");
    }

    @Test
    void 모르는_service와_잘못된_mallId는_400() {
        assertEquals(HttpStatus.BAD_REQUEST,
                sut.start("default", "ceipmall", "cafe24", "kr", "unknown", null, null, null).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
                sut.start("default", "evil.com/x", "cafe24", "kr", "cgs", null, null, null).getStatusCode());
    }

    private URI start(String service) {
        ResponseEntity<Void> response = sut.start("default", "ceipmall", "cafe24", "kr", service, "/cafe24/demo/return",
                "seller-001", "wh-001");
        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        return response.getHeaders().getLocation();
    }

    private static String param(URI location, String name) {
        String raw = UriComponentsBuilder.fromUri(location).build().getQueryParams().getFirst(name);
        return raw == null ? null : URLDecoder.decode(raw, StandardCharsets.UTF_8);
    }
}
