package com.colosseum.routestub.shopify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.colosseum.routestub.config.ShopifyProperties;

class StartRedirectTest {

    private static final String APP_HOST = "https://app.example.com";
    private static final String CALLBACK_HOST = "https://callback.example.com";
    private static final String CGS_HOST = "https://cgs-callback.example.com";

    /** 서비스별 콜백 호스트 없이 공통 콜백 호스트만 둔 설정. */
    private static ShopifyProperties properties() {
        return new ShopifyProperties("key", "shpss_secret", APP_HOST, CALLBACK_HOST, null, null, null, "cgs", "read_orders", "");
    }

    @Test
    void start의_redirect_uri는_appHost가_아니라_callbackHost를_쓴다() {
        ShopifyOAuthController sut = new ShopifyOAuthController(properties());

        ResponseEntity<Void> response = sut.start("default", "test-ceip-store.myshopify.com", "shopify", "kr", "cgs", null, null, null, false, false);

        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        URI location = response.getHeaders().getLocation();
        assertNotNull(location);
        String authorizeUrl = URLDecoder.decode(location.toString(), StandardCharsets.UTF_8);
        assertTrue(authorizeUrl.contains("redirect_uri=" + CALLBACK_HOST + "/oauth/shopify/default/callback"),
                "redirect_uri 는 callbackHost 를 써야 한다: " + authorizeUrl);
        assertFalse(authorizeUrl.contains("grant_options"), "offline 요청에는 grant_options 가 없어야 한다: " + authorizeUrl);
    }

    @Test
    void online이면_grant_options_per_user를_붙인다() {
        ShopifyOAuthController sut = new ShopifyOAuthController(properties());

        ResponseEntity<Void> response = sut.start("default", "test-ceip-store.myshopify.com", "shopify", "kr", "cgs", null, null, null, true, false);

        URI location = response.getHeaders().getLocation();
        assertNotNull(location);
        assertTrue(location.toString().endsWith("&grant_options[]=per-user"),
                "online 요청은 grant_options[]=per-user 로 끝나야 한다: " + location);
    }

    @Test
    void expiring이면_state에_expiring이_실린다() {
        ShopifyOAuthController sut = new ShopifyOAuthController(properties());

        ResponseEntity<Void> response = sut.start("default", "test-ceip-store.myshopify.com", "shopify", "kr", "cgs", null, null, null, false, true);

        StateCodec.State state = StateCodec.decode(stateParam(response.getHeaders().getLocation()), "shpss_secret");
        assertNotNull(state);
        assertTrue(state.expiring(), "expiring 요청은 state 에 expiring=1 로 실려야 한다");
    }

    @Test
    void online과_expiring을_함께_주면_expiring은_무시한다() {
        ShopifyOAuthController sut = new ShopifyOAuthController(properties());

        ResponseEntity<Void> response = sut.start("default", "test-ceip-store.myshopify.com", "shopify", "kr", "cgs", null, null, null, true, true);

        StateCodec.State state = StateCodec.decode(stateParam(response.getHeaders().getLocation()), "shpss_secret");
        assertNotNull(state);
        assertFalse(state.expiring(), "online 토큰에는 refresh_token 이 없어 expiring 을 싣지 않는다");
    }

    @Test
    void sellerId와_warehouseId는_redirect_uri가_아니라_state에_실린다() {
        ShopifyOAuthController sut = new ShopifyOAuthController(properties());

        ResponseEntity<Void> response = sut.start("default", "test-ceip-store.myshopify.com", "shopify", "us", "cgs",
                "/demo/return", "seller-001", "wh-001", false, false);

        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        URI location = response.getHeaders().getLocation();
        assertNotNull(location);
        String authorizeUrl = URLDecoder.decode(location.toString(), StandardCharsets.UTF_8);
        assertTrue(authorizeUrl.contains("redirect_uri=" + CALLBACK_HOST + "/oauth/shopify/default/callback&"),
                "redirect_uri 에는 서비스별 값을 붙이지 않는다(등록값과 글자까지 같아야 한다): " + authorizeUrl);

        StateCodec.State state = StateCodec.decode(stateParam(location), "shpss_secret");
        assertNotNull(state);
        assertEquals("seller-001", state.extras().get("sellerId"));
        assertEquals("wh-001", state.extras().get("warehouseId"));
        assertEquals("/demo/return", state.returnTo());
    }

    @Test
    void 알_수_없는_service는_400() {
        ShopifyOAuthController sut = new ShopifyOAuthController(properties());

        ResponseEntity<Void> response = sut.start("default", "test-ceip-store.myshopify.com", "shopify", "us", "unknown",
                null, "seller-001", "wh-001", false, false);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void 서비스별_payload는_빈_값을_싣지_않는다() {
        ShopifyOAuthController sut = new ShopifyOAuthController(properties());

        assertEquals(java.util.Map.of("sellerId", "s1"), sut.routeFor("colo1.0", "s1", " ").payload());
        assertTrue(sut.routeFor("colo-nova", null, null).payload().isEmpty());
    }

    @Test
    void 서비스별_콜백_호스트가_있으면_그_service의_redirect_uri만_바뀐다() {
        ShopifyProperties properties = new ShopifyProperties("key", "shpss_secret",
                APP_HOST, CALLBACK_HOST, CGS_HOST + "/", null, "", "cgs", "read_orders", "");
        ShopifyOAuthController sut = new ShopifyOAuthController(properties);

        String cgs = redirectUri(sut.start("default", "test-ceip-store.myshopify.com", "shopify", "us", "cgs",
                null, "s1", "w1", false, false));
        String colo1 = redirectUri(sut.start("default", "test-ceip-store.myshopify.com", "shopify", "kr", "colo1.0",
                null, "s1", "w1", false, false));
        String nova = redirectUri(sut.start("default", "test-ceip-store.myshopify.com", "shopify", "kr", "colo-nova",
                null, "s1", "w1", false, false));

        assertEquals(CGS_HOST + "/oauth/shopify/default/callback", cgs, "cgs 는 서비스별 호스트(끝 슬래시 제거)");
        assertEquals(CALLBACK_HOST + "/oauth/shopify/default/callback", colo1, "미설정(null)이면 공통 호스트");
        assertEquals(CALLBACK_HOST + "/oauth/shopify/default/callback", nova, "빈 문자열도 공통 호스트");
    }

    private static String redirectUri(ResponseEntity<Void> response) {
        String authorizeUrl = URLDecoder.decode(response.getHeaders().getLocation().toString(), StandardCharsets.UTF_8);
        for (String pair : authorizeUrl.substring(authorizeUrl.indexOf('?') + 1).split("&")) {
            if (pair.startsWith("redirect_uri=")) {
                return pair.substring("redirect_uri=".length());
            }
        }
        return null;
    }

    private static String stateParam(URI location) {
        for (String pair : location.getRawQuery().split("&")) {
            if (pair.startsWith("state=")) {
                return URLDecoder.decode(pair.substring("state=".length()), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
