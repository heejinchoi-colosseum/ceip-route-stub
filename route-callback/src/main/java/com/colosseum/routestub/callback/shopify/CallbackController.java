package com.colosseum.routestub.callback.shopify;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.colosseum.routestub.callback.config.ShopifyProperties;

/**
 * Shopify 콜백만 처리하는 스텁 컨트롤러. 루트 모듈 {@code ShopifyOAuthController} 의 callback, demo/return 을
 * 복제했다. start, refresh 는 여기 없다(8080 이 담당). 콜백은 start 와 다른 호스트(cloudflared)에서 뜬다.
 *
 * <p>hmac 과 서명 state 를 검증하고 code 를 토큰으로 교환한 뒤, returnTo 가 있으면 그리로 302 한다.
 * 무상태라 state 에 실려 온 region, service, returnTo 로 복귀를 결정한다.
 */
@RestController
public class CallbackController {

    private static final Logger log = LoggerFactory.getLogger(CallbackController.class);

    private static final Pattern SHOP_DOMAIN = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9-]*\\.myshopify\\.com$");

    private final ShopifyProperties properties;
    private final RestClient restClient = RestClient.create();
    /**
     * 콜백이 받은 토큰 응답을 demo/return 화면이 보여주기 위한 임시 보관소(메모리, 재기동 시 사라짐).
     * 토큰을 URL 에 싣지 않으려고 일회용 id 만 returnTo 에 붙이고, 같은 호스트의 demo 화면이 id 로 꺼내 본다.
     * 정식에서는 소비 서비스가 서버 간 채널로 토큰을 받으므로 이 보관소는 스텁에만 있다.
     */
    private final Map<String, StoredResult> results = new ConcurrentHashMap<>();

    /**
     * 콜백 또는 갱신이 받은 토큰 응답과, 갱신 요청에 필요한 shop, plane 과 복귀에 쓰는 region, service, extras.
     * extras 는 start 가 서비스별로 state 에 실은 값(sellerId, warehouseId 등)이다. webhookNote 는 토큰 교환 직후
     * app/uninstalled 구독을 만든 결과 한 줄이다.
     */
    record StoredResult(String shop, String plane, String region, String service, Map<String, String> extras,
            Map<?, ?> token, String webhookNote) {
    }

    private final WebhookSubscriber webhookSubscriber;

    public CallbackController(ShopifyProperties properties) {
        this.properties = properties;
        this.webhookSubscriber = new WebhookSubscriber(properties, restClient);
    }

    /** 콜백. hmac 과 서명 state 를 검증하고 code 를 토큰으로 교환한 뒤, returnTo 가 있으면 그리로 302 한다. */
    @GetMapping("/oauth/shopify/{plane}/callback")
    public ResponseEntity<String> callback(@PathVariable String plane, @RequestParam Map<String, String> params) {
        String shop = params.get("shop");
        String code = params.get("code");
        String state = params.get("state");
        String hmac = params.get("hmac");

        if (shop == null || !SHOP_DOMAIN.matcher(shop).matches()) {
            return html(HttpStatus.BAD_REQUEST, "잘못된 shop 값이다: " + shop);
        }
        if (hmac == null || !HmacVerifier.isValid(params, hmac, properties.apiSecret())) {
            return html(HttpStatus.UNAUTHORIZED, "hmac 검증 실패다.");
        }
        StateCodec.State decoded = StateCodec.decode(state, properties.apiSecret());
        if (decoded == null || !shop.equals(decoded.shop())) {
            return html(HttpStatus.BAD_REQUEST, "state 검증 실패다. 다시 시작하라.");
        }

        // 토큰 교환. expiring=1 이면 만료형 offline 토큰(expires_in 3600, refresh_token 동봉)이 온다.
        // 이 값이 없으면 기본 0 으로 비만료 offline 토큰이라 refresh_token 이 없다.
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.apiKey());
        form.add("client_secret", properties.apiSecret());
        form.add("code", code);
        if (decoded.expiring()) {
            form.add("expiring", "1");
        }
        Map<?, ?> token = restClient.post()
                .uri("https://{shop}/admin/oauth/access_token", shop)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);

        log.info("[callback] shop={} plane={} region={} service={} returnTo={} extras={} token={}",
                shop, plane, decoded.region(), decoded.service(), decoded.returnTo(), decoded.extras(), token);
        // 만료형 offline 토큰이면 refresh_token 이 함께 온다. 발급 여부를 한 줄로 따로 남긴다.
        log.info("[callback] expiring 요청={} refresh_token 발급={} expires_in={} refresh_token_expires_in={}",
                decoded.expiring(), field(token, "refresh_token") != null, field(token, "expires_in"),
                field(token, "refresh_token_expires_in"));

        // 설치 직후 app/uninstalled 웹훅을 shop 별로 구독한다. 주소는 라우터 릴레이(APP_HOST)다. 실패해도 인증은 성공으로 둔다.
        String webhookNote = webhookSubscriber.subscribeAppUninstalled(shop, plane, decoded.service(), decoded.region(),
                field(token, "access_token"));

        // 토큰은 절대 URL 로 넘기지 않는다. returnTo 에는 결과 표시용 식별자만 붙인다.
        String resultId = store(shop, plane, decoded.region(), decoded.service(), decoded.extras(), token, webhookNote);
        String location = resolveReturn(decoded.returnTo(), shop, decoded.region(), decoded.service(), decoded.extras(),
                resultId);
        if (location != null) {
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
        }
        boolean returnBlocked = decoded.returnTo() != null && !decoded.returnTo().isBlank();
        return html(HttpStatus.OK, renderToken(shop, plane, decoded, token, returnBlocked, resultId, webhookNote));
    }

    /**
     * refresh_token 으로 액세스 토큰을 갱신한다(만료형 offline 토큰 전용). refresh_token 은 URL 에 싣지 않고
     * 콜백이 보관한 결과 id 로 찾는다. 응답을 새 id 로 보관하고 demo/return 으로 302 해 같은 화면에서 비교한다.
     */
    @PostMapping("/oauth/shopify/{plane}/token/refresh")
    public ResponseEntity<String> refreshToken(@PathVariable String plane, @RequestParam String result) {
        StoredResult stored = results.get(result);
        if (stored == null) {
            return html(HttpStatus.BAD_REQUEST, "보관된 토큰 응답이 없다. 콜백 앱이 재기동됐거나 id 가 틀렸다. 인증을 다시 시작하라.");
        }
        Object refreshToken = field(stored.token(), "refresh_token");
        if (refreshToken == null) {
            return html(HttpStatus.BAD_REQUEST, "이 토큰에는 refresh_token 이 없다. 만료형 offline(expiring) 으로 다시 인증하라.");
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.apiKey());
        form.add("client_secret", properties.apiSecret());
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", String.valueOf(refreshToken));
        Map<?, ?> token;
        try {
            token = restClient.post()
                    .uri("https://{shop}/admin/oauth/access_token", stored.shop())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientResponseException e) {
            log.warn("[refresh] shop={} 실패 status={} body={}", stored.shop(), e.getStatusCode(), e.getResponseBodyAsString());
            return html(HttpStatus.BAD_GATEWAY, "갱신 실패다. Shopify 응답 " + e.getStatusCode() + ": " + escape(e.getResponseBodyAsString()));
        }

        boolean rotated = field(token, "refresh_token") != null && !refreshToken.equals(field(token, "refresh_token"));
        log.info("[refresh] shop={} plane={} token={}", stored.shop(), plane, token);
        log.info("[refresh] 새 access_token 발급={} expires_in={} refresh_token 회전={} refresh_token_expires_in={}",
                field(token, "access_token") != null, field(token, "expires_in"), rotated,
                field(token, "refresh_token_expires_in"));

        String newId = store(stored.shop(), plane, stored.region(), stored.service(), stored.extras(), token,
                stored.webhookNote());
        String location = "/demo/return?shop=" + encode(stored.shop())
                + "&region=" + encode(stored.region())
                + "&service=" + encode(stored.service())
                + extrasQuery(stored.extras())
                + "&ok=true&refreshed=true&result=" + encode(newId);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
    }

    /**
     * 원 서비스 복귀 데모 화면. 정식에서는 각 소비 서비스의 완료 화면이 이 자리를 대신한다.
     * {@code result} id 가 있으면 콜백이 보관한 토큰 응답을 표로 함께 보인다(로그를 뒤지지 않게).
     */
    @GetMapping("/demo/return")
    public ResponseEntity<String> demoReturn(@RequestParam Map<String, String> params) {
        String rows = params.entrySet().stream()
                .map(e -> "<tr><td>" + escape(e.getKey()) + "</td><td><code>" + escape(e.getValue()) + "</code></td></tr>")
                .collect(Collectors.joining());

        String resultId = params.get("result");
        StoredResult stored = resultId == null ? null : results.get(resultId);
        String tokenBlock;
        if (stored == null) {
            tokenBlock = "<p>이 화면에 보여줄 토큰 응답이 없다(result id 없음, 또는 콜백 앱 재기동으로 사라짐). 콜백 로그를 본다.</p>";
        } else {
            String title = params.containsKey("refreshed") ? "갱신(grant_type=refresh_token) 응답" : "토큰 응답";
            tokenBlock = "<h3>" + title + "</h3>"
                    + "<table border=\"1\" cellpadding=\"6\" cellspacing=\"0\">" + tokenRows(stored.token()) + "</table>"
                    + expiryBlock(stored.token(), stored.plane())
                    + refreshForm(stored, resultId)
                    + webhookBlock(stored.webhookNote())
                    + "<p style=\"color:#a00\">주의: 액세스 토큰과 refresh_token 은 민감 정보다. 테스트 후 폐기하라.</p>";
        }
        return html(HttpStatus.OK, """
                <!doctype html><meta charset="utf-8">
                <h2>원 서비스 복귀 (데모)</h2>
                <p>라우터가 인증을 마치고 이 화면으로 302 했다. 토큰은 URL 이 아니라 콜백 앱 메모리에서 result id 로 꺼내 보인다.</p>
                <table border="1" cellpadding="6" cellspacing="0">%s</table>
                %s
                """.formatted(rows, tokenBlock));
    }

    /**
     * returnTo 로 302 할 최종 URL 을 만든다. 없으면 null(결과 화면 표시).
     * 상대 경로는 같은 호스트라 허용하고, 절대 URL 은 허용 호스트 목록에 있을 때만 허용한다(오픈 리다이렉트 차단).
     */
    private String resolveReturn(String returnTo, String shop, String region, String service, Map<String, String> extras,
            String resultId) {
        if (returnTo == null || returnTo.isBlank()) {
            return null;
        }
        boolean allowed;
        if (returnTo.startsWith("/")) {
            allowed = true;
        } else {
            URI uri;
            try {
                uri = URI.create(returnTo);
            } catch (IllegalArgumentException e) {
                return null;
            }
            allowed = uri.getScheme() != null && uri.getHost() != null && allowlist().contains(uri.getHost());
        }
        if (!allowed) {
            log.warn("[callback] returnTo 호스트가 허용목록에 없어 복귀 생략: {}", returnTo);
            return null;
        }
        String separator = returnTo.contains("?") ? "&" : "?";
        return returnTo + separator
                + "shop=" + encode(shop)
                + "&region=" + encode(region)
                + "&service=" + encode(service)
                + extrasQuery(extras)
                + "&ok=true"
                + "&result=" + encode(resultId);
    }

    /** state 에 실려 온 서비스별 값(sellerId, warehouseId 등)을 복귀 URL 쿼리로 잇는다. 없으면 빈 문자열. */
    private static String extrasQuery(Map<String, String> extras) {
        if (extras == null || extras.isEmpty()) {
            return "";
        }
        return extras.entrySet().stream()
                .map(e -> "&" + encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining());
    }

    private Set<String> allowlist() {
        String raw = properties.returnAllowlist();
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    private String renderToken(String shop, String plane, StateCodec.State state, Map<?, ?> token, boolean returnBlocked,
            String resultId, String webhookNote) {
        String tokenRows = tokenRows(token);
        String expiryBlock = expiryBlock(token, plane)
                + (token == null ? "" : refreshForm(
                        new StoredResult(shop, plane, state.region(), state.service(), state.extras(), token, webhookNote),
                        resultId))
                + webhookBlock(webhookNote);

        String returnNote = returnBlocked
                ? "<p style=\"color:#a00\">returnTo 호스트가 허용목록에 없어 복귀를 생략했다.</p>"
                : "";

        return """
                <!doctype html><meta charset="utf-8">
                <h2>인증 성공 (콜백 호스트)</h2>
                <table border="1" cellpadding="6" cellspacing="0">
                  <tr><td>shop</td><td><code>%s</code></td></tr>
                  <tr><td>channel</td><td><code>shopify</code></td></tr>
                  <tr><td>plane</td><td><code>%s</code></td></tr>
                  <tr><td>region</td><td><code>%s</code></td></tr>
                  <tr><td>service</td><td><code>%s</code></td></tr>
                  %s
                </table>
                <h3>토큰 응답</h3>
                <table border="1" cellpadding="6" cellspacing="0">%s</table>
                %s
                %s
                <p style="color:#a00">주의: 액세스 토큰은 민감 정보다. 테스트 후 폐기하라.</p>
                """.formatted(
                escape(shop),
                escape(plane),
                escape(nullToDash(state.region())),
                escape(nullToDash(state.service())),
                extrasRows(state.extras()),
                tokenRows,
                expiryBlock,
                returnNote);
    }

    private String store(String shop, String plane, String region, String service, Map<String, String> extras,
            Map<?, ?> token, String webhookNote) {
        String id = UUID.randomUUID().toString().replace("-", "");
        if (token != null) {
            results.put(id, new StoredResult(shop, plane, region, service, extras, token, webhookNote));
        }
        return id;
    }

    /** app/uninstalled 구독 결과와, 받은 웹훅을 볼 수 있는 화면 링크. */
    private static String webhookBlock(String webhookNote) {
        return "<p>app/uninstalled 웹훅: <code>" + escape(nullToDash(webhookNote)) + "</code> "
                + "<a href=\"/demo/webhooks\">받은 웹훅 보기</a></p>";
    }

    private static String extrasRows(Map<String, String> extras) {
        if (extras == null || extras.isEmpty()) {
            return "";
        }
        return extras.entrySet().stream()
                .map(e -> "<tr><td>" + escape(e.getKey()) + "</td><td><code>" + escape(e.getValue()) + "</code></td></tr>")
                .collect(Collectors.joining());
    }

    /** refresh_token 이 있을 때만 갱신 버튼을 만든다. 토큰은 폼에 싣지 않고 결과 id 만 넘긴다. */
    private static String refreshForm(StoredResult stored, String resultId) {
        if (field(stored.token(), "refresh_token") == null) {
            return "";
        }
        return "<form method=\"post\" action=\"/oauth/shopify/" + escape(stored.plane()) + "/token/refresh\">"
                + "<input type=\"hidden\" name=\"result\" value=\"" + escape(resultId) + "\">"
                + "<button type=\"submit\">refresh_token 으로 갱신 요청</button>"
                + " <span>grant_type=refresh_token 으로 새 access_token 을 받아 이 화면에 다시 보인다.</span></form>";
    }

    private static String tokenRows(Map<?, ?> token) {
        if (token == null) {
            return "<tr><td colspan=\"2\">(빈 응답)</td></tr>";
        }
        return token.entrySet().stream()
                .map(e -> "<tr><td>" + escape(String.valueOf(e.getKey()))
                        + "</td><td><code>" + escape(String.valueOf(e.getValue())) + "</code></td></tr>")
                .collect(Collectors.joining());
    }

    /** 토큰 종류(비만료 offline, 만료형 offline+refresh_token, online)를 한 문단으로 설명한다. */
    private static String expiryBlock(Map<?, ?> token, String plane) {
        Object expiresIn = field(token, "expires_in");
        Object refreshToken = field(token, "refresh_token");
        // 갱신(/refresh)은 start 호스트(8080)의 기능이라, 콜백 호스트에는 링크 대신 안내만 둔다.
        if (expiresIn == null) {
            return "<p>비만료 offline 토큰이다. expires_in, refresh_token 이 없다.</p>";
        }
        if (refreshToken != null) {
            return "<p>만료형 offline 토큰이다. <b>" + escape(String.valueOf(expiresIn)) + "초</b> 후 만료. "
                    + "<b>refresh_token 이 발급됐다</b>(refresh_token_expires_in="
                    + escape(String.valueOf(field(token, "refresh_token_expires_in"))) + "초). "
                    + "갱신은 <code>grant_type=refresh_token</code> 교환으로 한다.</p>";
        }
        return "<p>online(사용자 세션형) 토큰이다. <b>" + escape(String.valueOf(expiresIn)) + "초</b> 후 만료. "
                + "refresh_token 은 없다. 갱신은 start 호스트의 <code>/oauth/shopify/" + escape(plane)
                + "/refresh</code> 로 재인증한다.</p>";
    }

    private static Object field(Map<?, ?> token, String key) {
        return token == null ? null : token.get(key);
    }

    private static ResponseEntity<String> html(HttpStatus status, String body) {
        return ResponseEntity.status(status).contentType(MediaType.TEXT_HTML).body(body);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
