package com.colosseum.routestub.cafe24;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.colosseum.routestub.config.Cafe24Properties;
import com.colosseum.routestub.config.ShopifyProperties;
import com.colosseum.routestub.shopify.ServiceRouting;

/**
 * Cafe24 OAuth 의 start 와 라우터 콜백. Shopify 와 같은 규약({@code /oauth/cafe24/{plane}/...}, plane=default)이다.
 *
 * <p>Shopify 와 구조가 다르다. Shopify 는 서비스별 콜백 호스트를 redirect_uri 로 바로 보내고, Shopify 가 그 호스트로
 * code 를 던진다. Cafe24 는 Redirect URI(s) 를 여러 줄 등록하면 동의 뒤 그 줄들을 이어 붙인 주소로 보내는 것이
 * 관찰됐다(우리가 보낸 redirect_uri 와 무관). 그래서 Cafe24 에는 라우터 콜백 하나만 등록하고, 라우터가 서명 state 의
 * service 로 소비 서비스 콜백 호스트를 골라 code, state 를 그대로 넘긴다(302).
 *
 * <pre>
 * start(8080) → Cafe24 동의 → 라우터 callback(8080) ─302→ 서비스 callback(8081 cgs | 8082 colo1.0) → 토큰 교환
 * </pre>
 *
 * <p>토큰 교환에는 authorize 때와 같은 redirect_uri(라우터 콜백)가 필요해 state 에 함께 싣는다. 서비스 콜백은 이 값을
 * 그대로 쓴다. 동의 화면은 쇼핑몰마다 {@code https://{mallId}.cafe24api.com} 에 있어 mallId 가 필수다.
 */
@RestController
public class Cafe24OAuthController {

    private static final Logger log = LoggerFactory.getLogger(Cafe24OAuthController.class);

    /** Cafe24 쇼핑몰 ID. 영문 소문자, 숫자로 시작하고 하이픈이 섞일 수 있다. */
    private static final Pattern MALL_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{1,29}$");

    private final Cafe24Properties properties;
    private final ShopifyProperties hosts;
    private final Cafe24MallRegistry registry;

    public Cafe24OAuthController(Cafe24Properties properties, ShopifyProperties hosts, Cafe24MallRegistry registry) {
        this.properties = properties;
        this.hosts = hosts;
        this.registry = registry;
    }

    @GetMapping("/oauth/cafe24/{plane}/start")
    public ResponseEntity<Void> start(@PathVariable String plane,
            @RequestParam String mallId,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String service,
            @RequestParam(required = false) String returnTo,
            @RequestParam(required = false) String sellerId,
            @RequestParam(required = false) String warehouseId) {
        if (properties.clientSecret() == null || properties.clientSecret().isBlank()) {
            // state 서명 키가 없으면 SecretKeySpec 이 Empty key 로 터진다. 원인을 바로 알 수 있게 먼저 막는다.
            log.warn("[cafe24-start] CAFE24_CLIENT_SECRET 이 비어 있다. env.sh 또는 .env 에 넣고 재기동하라.");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        if (!MALL_ID.matcher(mallId).matches()) {
            log.warn("[cafe24-start] 잘못된 mallId 다: {}", mallId);
            return ResponseEntity.badRequest().build();
        }
        String serviceHost;
        Map<String, String> payload;
        try {
            serviceHost = ServiceRouting.callbackHost(hosts, service);
            payload = payloadFor(service, sellerId, warehouseId);
        } catch (IllegalArgumentException e) {
            log.warn("[cafe24-start] {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }

        // Cafe24 에 등록하는 Redirect URI 는 라우터 콜백 하나다. 서비스 분기는 라우터 콜백이 state 의 service 로 한다.
        String redirectUri = routerCallback(plane);
        String state = Cafe24StateCodec.encode(mallId, region, service, returnTo, redirectUri, payload,
                properties.clientSecret());

        String authorizeUrl = "https://" + mallId + ".cafe24api.com/api/v2/oauth/authorize"
                + "?response_type=code"
                + "&client_id=" + encode(properties.clientId())
                + "&state=" + encode(state)
                + "&redirect_uri=" + encode(redirectUri)
                + "&scope=" + encode(scopeParam(properties.scopes()));

        log.info("[cafe24-start] mallId={} plane={} channel={} region={} service={} returnTo={} payload={} redirectUri={} 서비스콜백={}",
                mallId, plane, channel, region, service, returnTo, payload, redirectUri,
                serviceHost + "/oauth/cafe24/" + plane + "/callback");
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(authorizeUrl)).build();
    }

    /**
     * 라우터 콜백. Cafe24 가 code, state(실패면 error, error_description, state)를 던지는 유일한 등록 주소다. 서명 state 를
     * 검증해 service 를 꺼내고, 그 소비 서비스 콜백 호스트로 받은 쿼리를 그대로 붙여 302 한다. 토큰 교환은 서비스 콜백이 한다.
     * state 가 위조되었거나 만료되면 어느 서비스로도 넘기지 않는다.
     */
    @GetMapping("/oauth/cafe24/{plane}/callback")
    public ResponseEntity<String> callback(@PathVariable String plane, HttpServletRequest request) {
        Cafe24StateCodec.State decoded = properties.clientSecret() == null || properties.clientSecret().isBlank()
                ? null
                : Cafe24StateCodec.decode(request.getParameter("state"), properties.clientSecret());
        if (decoded == null) {
            log.warn("[cafe24-router-callback] state 검증 실패 error={}", request.getParameter("error"));
            return ResponseEntity.badRequest().contentType(MediaType.TEXT_HTML)
                    .body("<!doctype html><meta charset=\"utf-8\">state 검증 실패다. 어느 서비스로도 넘기지 않는다. 다시 시작하라.");
        }
        String serviceHost;
        try {
            serviceHost = ServiceRouting.callbackHost(hosts, decoded.service());
        } catch (IllegalArgumentException e) {
            log.warn("[cafe24-router-callback] {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
        if (request.getParameter("error") == null) {
            // 웹훅에는 mall_id 만 오므로 어느 서비스가 이 몰을 연결했는지 기억해 둔다.
            registry.record(decoded.mallId(), decoded.service());
        }
        String location = serviceHost + "/oauth/cafe24/" + plane + "/callback?" + request.getQueryString();
        log.info("[cafe24-router-callback] mallId={} service={} error={} → {}", decoded.mallId(), decoded.service(),
                request.getParameter("error"), serviceHost + "/oauth/cafe24/" + plane + "/callback");
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
    }

    private String routerCallback(String plane) {
        String host = hosts.appHost();
        host = host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
        return host + "/oauth/cafe24/" + plane + "/callback";
    }

    /**
     * 서비스별로 콜백까지 실어 나를 값을 조합한다. Shopify start 와 같이 지금은 세 서비스 모두 sellerId, warehouseId 를
     * 싣는다. 서비스별로 달라지면 해당 case 만 고친다. 모르는 service 는 거부한다.
     */
    static Map<String, String> payloadFor(String service, String sellerId, String warehouseId) {
        Map<String, String> payload = new LinkedHashMap<>();
        switch (service == null ? "" : service) {
            case "cgs", "colo1.0", "colo-nova" -> {
                putIfPresent(payload, "sellerId", sellerId);
                putIfPresent(payload, "warehouseId", warehouseId);
            }
            default -> throw new IllegalArgumentException("알 수 없는 service 다: " + service);
        }
        return payload;
    }

    /** 설정의 콤마 구분 scope 를 Cafe24 authorize 가 받는 공백 구분으로 바꾼다. */
    static String scopeParam(String scopes) {
        if (scopes == null) {
            return "";
        }
        return Arrays.stream(scopes.split("[,\\s]+")).filter(s -> !s.isEmpty()).collect(Collectors.joining(" "));
    }

    private static void putIfPresent(Map<String, String> payload, String key, String value) {
        if (value != null && !value.isBlank()) {
            payload.put(key, value);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
