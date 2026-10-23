package com.colosseum.routestub.shopify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class StateCodecTest {

    private static final String SECRET = "shpss_test_secret";

    @Test
    void shopRegionServiceReturnTo를_왕복한다() {
        String state = StateCodec.encode("test-ceip-store.myshopify.com", "kr", "colo1.0", "/demo/return", false, SECRET);

        StateCodec.State decoded = StateCodec.decode(state, SECRET);

        assertEquals("test-ceip-store.myshopify.com", decoded.shop());
        assertEquals("kr", decoded.region());
        assertEquals("colo1.0", decoded.service());
        assertEquals("/demo/return", decoded.returnTo());
        assertFalse(decoded.expiring());
    }

    @Test
    void expiring을_왕복한다() {
        String state = StateCodec.encode("test-ceip-store.myshopify.com", "kr", "colo1.0", "/demo/return", true, SECRET);

        assertTrue(StateCodec.decode(state, SECRET).expiring());
    }

    @Test
    void extras를_순서대로_왕복한다() {
        Map<String, String> extras = new LinkedHashMap<>();
        extras.put("sellerId", "seller 001&x");
        extras.put("warehouseId", "wh=001");
        String state = StateCodec.encode("test-ceip-store.myshopify.com", "us", "cgs", "/demo/return", false, extras, SECRET);

        StateCodec.State decoded = StateCodec.decode(state, SECRET);

        assertEquals(extras, decoded.extras());
        assertEquals("us", decoded.region());
        assertEquals("cgs", decoded.service());
    }

    @Test
    void extras가_없으면_빈_맵이다() {
        String state = StateCodec.encode("test-ceip-store.myshopify.com", "kr", "colo1.0", "/demo/return", false, SECRET);

        assertTrue(StateCodec.decode(state, SECRET).extras().isEmpty());
    }

    @Test
    void 변조된_state는_거부한다() {
        String state = StateCodec.encode("test-ceip-store.myshopify.com", "kr", "colo1.0", "/demo/return", false, SECRET);
        String tampered = state.substring(0, state.length() - 4) + "AAAA";

        assertNull(StateCodec.decode(tampered, SECRET));
    }

    @Test
    void 다른_secret으로_서명된_state는_거부한다() {
        String state = StateCodec.encode("test-ceip-store.myshopify.com", "kr", "colo1.0", "/demo/return", false, SECRET);

        assertNull(StateCodec.decode(state, "other_secret"));
    }
}
