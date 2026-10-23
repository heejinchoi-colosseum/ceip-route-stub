package com.colosseum.routestub.shopify;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.colosseum.routestub.config.ShopifyProperties;

/**
 * Shopify OAuth authorization code 흐름을 테스트하기 위한 스텁 컨트롤러.
 * 정식 라우트 서비스가 나오기 전까지 인증 흐름만 검증한다.
 *
 * <p>경로는 라우트 서비스 규약을 따른다. Shopify 는 plane 이 없어 {plane} 에 default 를 쓴다.
 * region, service, returnTo 는 어느 리전, 소비 서비스가 시작했고 인증 뒤 어디로 돌려보낼지
 * 나타내는 값이다. 라우터는 저장소를 두지 않고, 이 값들을 서명한 {@code state} 에 실어 콜백까지
 * 왕복시킨다(무상태). 콜백 성공 시 returnTo 가 있으면 그 URL 로 302, 없으면 결과 화면을 보인다.
 *
 * <p>소비 서비스가 넘긴 sellerId, warehouseId 같은 서비스별 값도 같은 state 에 싣는다. Shopify 는 redirect_uri 를
 * 앱에 등록한 Redirect URL 과 글자까지 같게 요구하므로 redirect_uri 쿼리에는 아무것도 붙일 수 없다.
 * 대신 redirect_uri 의 호스트는 서비스마다 다르게 둘 수 있다(각각 앱에 등록). 서비스별로 어느 콜백 호스트를 쓰고
 * 무엇을 실을지는 {@link #routeFor} 의 service switch 가 정한다.
 */
@RestController
public class ShopifyOAuthController {

    private static final Logger log = LoggerFactory.getLogger(ShopifyOAuthController.class);

    private static final Pattern SHOP_DOMAIN = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9-]*\\.myshopify\\.com$");

    private final ShopifyProperties properties;

    public ShopifyOAuthController(ShopifyProperties properties) {
        this.properties = properties;
    }

    /**
     * 인증 시작. shop 을 받아 Shopify 동의 화면으로 302 보낸다. region, service, returnTo 와 서비스별 값(sellerId,
     * warehouseId)은 서명 state 에 실어 나른다. channel 은 경로가 이미 shopify 로 고정이라 받기만 하고 로그에 남긴다.
     *
     * <p>토큰 종류는 셋이다. 기본은 비만료 offline. {@code online=true} 면 사용자 세션형 online 토큰(24시간,
     * refresh_token 없음). {@code expiring=true} 면 콜백이 토큰 교환에 expiring=1 을 붙여 만료형 offline 토큰
     * (expires_in 3600, refresh_token 동봉)을 받는다. online 과 함께 주면 expiring 은 무시한다.
     */
    @GetMapping("/oauth/shopify/{plane}/start")
    public ResponseEntity<Void> start(@PathVariable String plane,
            @RequestParam String shop,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String service,
            @RequestParam(required = false) String returnTo,
            @RequestParam(required = false) String sellerId,
            @RequestParam(required = false) String warehouseId,
            @RequestParam(defaultValue = "false") boolean online,
            @RequestParam(defaultValue = "false") boolean expiring) {
        if (!SHOP_DOMAIN.matcher(shop).matches()) {
            return ResponseEntity.badRequest().build();
        }
        ServiceRoute route;
        try {
            route = routeFor(service, sellerId, warehouseId);
        } catch (IllegalArgumentException e) {
            log.warn("[start] {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }

        boolean expiringOffline = expiring && !online;
        String state = StateCodec.encode(shop, region, service, returnTo, expiringOffline, route.payload(),
                properties.apiSecret());
        // redirect_uri 는 앱에 등록한 Redirect URL 중 하나와 글자까지 같아야 한다. 호스트는 서비스별로 고르고,
        // 쿼리는 붙이지 않는다(서비스별 값은 state 에 싣는다).
        String redirectUri = route.callbackHost() + "/oauth/shopify/" + plane + "/callback";

        String authorizeUrl = "https://" + shop + "/admin/oauth/authorize"
                + "?client_id=" + encode(properties.apiKey())
                + "&scope=" + encode(properties.scopes())
                + "&redirect_uri=" + encode(redirectUri)
                + "&state=" + encode(state);
        if (online) {
            // online(만료형) 토큰 요청. Shopify 는 이 파라미터를 리터럴로 받는다.
            authorizeUrl += "&grant_options[]=per-user";
        }

        log.info("[start] shop={} plane={} channel={} region={} service={} returnTo={} payload={} online={} expiring={} redirectUri={}",
                shop, plane, channel, region, service, returnTo, route.payload(), online, expiringOffline, redirectUri);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(authorizeUrl)).build();
    }

    /** service switch 의 결과. 어느 콜백 호스트로 redirect_uri 를 만들지와, state 에 실어 나를 서비스별 값. */
    record ServiceRoute(String callbackHost, Map<String, String> payload) {
    }

    /**
     * 서비스별로 콜백 호스트와 콜백까지 실어 나를 값을 조합한다. 소비 서비스마다 다르므로 service 로 분기한다.
     * 콜백 호스트는 {@link ServiceRouting} 이 정한다(서비스별 설정, 비어 있으면 공통 callback-host). 웹훅 릴레이도 같은
     * 호스트로 전달한다. 지금은 세 서비스 모두 sellerId, warehouseId 를 싣는다. 서비스별로 달라지면 해당 case 만 고친다.
     * 값이 없는 키는 싣지 않는다. 모르는 service 는 거부한다.
     */
    ServiceRoute routeFor(String service, String sellerId, String warehouseId) {
        String callbackHost = ServiceRouting.callbackHost(properties, service);
        Map<String, String> payload = new LinkedHashMap<>();
        switch (service) {
            case "cgs" -> {
                putIfPresent(payload, "sellerId", sellerId);
                putIfPresent(payload, "warehouseId", warehouseId);
            }
            case "colo1.0" -> {
                putIfPresent(payload, "sellerId", sellerId);
                putIfPresent(payload, "warehouseId", warehouseId);
            }
            case "colo-nova" -> {
                putIfPresent(payload, "sellerId", sellerId);
                putIfPresent(payload, "warehouseId", warehouseId);
            }
            default -> throw new IllegalArgumentException("알 수 없는 service 다: " + service);
        }
        return new ServiceRoute(callbackHost, payload);
    }

    private static void putIfPresent(Map<String, String> payload, String key, String value) {
        if (value != null && !value.isBlank()) {
            payload.put(key, value);
        }
    }

    /**
     * 토큰 갱신. Shopify 클래식 OAuth 는 refresh_token 이 없으므로 online 그랜트를 재실행한다.
     * 동의가 이미 되어 있으면 동의 화면 없이 새 online 토큰을 받는다.
     */
    @GetMapping("/oauth/shopify/{plane}/refresh")
    public ResponseEntity<Void> refresh(@PathVariable String plane,
            @RequestParam String shop,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String service,
            @RequestParam(required = false) String returnTo,
            @RequestParam(required = false) String sellerId,
            @RequestParam(required = false) String warehouseId) {
        return start(plane, shop, "shopify", region, service, returnTo, sellerId, warehouseId, true, false);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
