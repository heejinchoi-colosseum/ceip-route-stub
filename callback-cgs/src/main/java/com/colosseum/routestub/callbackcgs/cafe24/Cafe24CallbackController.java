package com.colosseum.routestub.callbackcgs.cafe24;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.colosseum.routestub.callbackcgs.config.Cafe24Properties;

/**
 * Cafe24 콜백 스텁. Shopify {@code CallbackController} 와 같은 역할을 Cafe24 규격으로 한다.
 *
 * <p>Cafe24 는 이 호스트로 직접 오지 않는다. Cafe24 에는 라우터 콜백(8080) 하나만 등록하고, 라우터가 state 의 service 로
 * 골라 code, state 를 이 경로로 넘긴다(302). 그래서 토큰 교환의 redirect_uri 는 이 호스트가 아니라 state 에 실린 라우터 콜백이다.
 *
 * <p>Shopify 와 다른 점. 콜백 쿼리는 {@code code, state} 뿐이고 hmac 이 없어 서명 state 로만 검증한다. 동의 거부 등
 * 실패는 {@code error, error_description} 으로 온다. 토큰 교환은 {@code https://{mallId}.cafe24api.com/api/v2/oauth/token}
 * 에 client_id:client_secret Basic 인증으로 보내고, 본문에 authorize 때와 같은 redirect_uri 를 넣는다(state 에 실려 온다).
 * code 는 1분 유효다. 응답은 access_token(2시간), refresh_token(2주)이 함께 오고 만료는 초가 아니라 시각(expires_at)이다.
 */
@RestController
public class Cafe24CallbackController {

    private static final Logger log = LoggerFactory.getLogger(Cafe24CallbackController.class);

    private final Cafe24Properties properties;
    private final RestClient restClient = RestClient.create();
    /** demo 화면용 토큰 응답 임시 보관소(메모리). 토큰을 URL 에 싣지 않으려고 일회용 id 만 복귀 URL 에 붙인다. */
    private final Map<String, StoredResult> results = new ConcurrentHashMap<>();

    record StoredResult(String mallId, String plane, String region, String service, Map<String, String> extras,
            Map<?, ?> token) {
    }

    public Cafe24CallbackController(Cafe24Properties properties) {
        this.properties = properties;
    }

    @GetMapping("/oauth/cafe24/{plane}/callback")
    public ResponseEntity<String> callback(@PathVariable String plane, @RequestParam Map<String, String> params) {
        String error = params.get("error");
        if (error != null) {
            log.warn("[cafe24-callback] 인증 실패 error={} description={}", error, params.get("error_description"));
            return html(HttpStatus.BAD_REQUEST, "Cafe24 인증 실패다: " + escape(error) + " "
                    + escape(nullToDash(params.get("error_description"))));
        }
        if (properties.clientSecret() == null || properties.clientSecret().isBlank()) {
            return html(HttpStatus.INTERNAL_SERVER_ERROR, "CAFE24_CLIENT_SECRET 이 비어 있다. env.sh 또는 .env 에 넣고 콜백 앱을 재기동하라.");
        }
        Cafe24StateCodec.State decoded = Cafe24StateCodec.decode(params.get("state"), properties.clientSecret());
        if (decoded == null) {
            return html(HttpStatus.BAD_REQUEST, "state 검증 실패다. 다시 시작하라.");
        }
        String code = params.get("code");
        if (code == null || code.isBlank()) {
            return html(HttpStatus.BAD_REQUEST, "code 가 없다.");
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", decoded.redirectUri());
        Map<?, ?> token;
        try {
            token = requestToken(decoded.mallId(), form);
        } catch (RestClientResponseException e) {
            log.warn("[cafe24-callback] mallId={} 토큰 교환 실패 status={} body={}", decoded.mallId(), e.getStatusCode(),
                    e.getResponseBodyAsString());
            return html(HttpStatus.BAD_GATEWAY, "토큰 교환 실패다. Cafe24 응답 " + e.getStatusCode() + ": "
                    + escape(e.getResponseBodyAsString()));
        }

        log.info("[cafe24-callback] mallId={} plane={} region={} service={} returnTo={} redirectUri={} extras={} token={}",
                decoded.mallId(), plane, decoded.region(), decoded.service(), decoded.returnTo(), decoded.redirectUri(),
                decoded.extras(), token);
        log.info("[cafe24-callback] expires_at={} refresh_token 발급={} refresh_token_expires_at={} scopes={}",
                field(token, "expires_at"), field(token, "refresh_token") != null,
                field(token, "refresh_token_expires_at"), field(token, "scopes"));

        String resultId = store(decoded.mallId(), plane, decoded.region(), decoded.service(), decoded.extras(), token);
        String location = resolveReturn(decoded.returnTo(), decoded.mallId(), decoded.region(), decoded.service(),
                decoded.extras(), resultId);
        if (location != null) {
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(demoReturnUrl(decoded.mallId(), decoded.region(), decoded.service(),
                        decoded.extras(), resultId)))
                .build();
    }

    /** refresh_token 으로 갱신한다. Cafe24 는 갱신마다 refresh_token 을 새로 주고 이전 것은 폐기한다. */
    @PostMapping("/oauth/cafe24/{plane}/token/refresh")
    public ResponseEntity<String> refreshToken(@PathVariable String plane, @RequestParam String result) {
        StoredResult stored = results.get(result);
        if (stored == null) {
            return html(HttpStatus.BAD_REQUEST, "보관된 토큰 응답이 없다. 콜백 앱이 재기동됐거나 id 가 틀렸다. 인증을 다시 시작하라.");
        }
        Object refreshToken = field(stored.token(), "refresh_token");
        if (refreshToken == null) {
            return html(HttpStatus.BAD_REQUEST, "이 토큰 응답에는 refresh_token 이 없다.");
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", String.valueOf(refreshToken));
        Map<?, ?> token;
        try {
            token = requestToken(stored.mallId(), form);
        } catch (RestClientResponseException e) {
            log.warn("[cafe24-refresh] mallId={} 실패 status={} body={}", stored.mallId(), e.getStatusCode(),
                    e.getResponseBodyAsString());
            return html(HttpStatus.BAD_GATEWAY, "갱신 실패다. Cafe24 응답 " + e.getStatusCode() + ": "
                    + escape(e.getResponseBodyAsString()));
        }
        log.info("[cafe24-refresh] mallId={} plane={} token={}", stored.mallId(), plane, token);

        String newId = store(stored.mallId(), plane, stored.region(), stored.service(), stored.extras(), token);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(demoReturnUrl(stored.mallId(), stored.region(), stored.service(), stored.extras(),
                        newId) + "&refreshed=true"))
                .build();
    }

    /**
     * 실험용. 보관된 access_token 으로 읽기 전용 API {@code GET /api/v2/admin/store} 를 호출해 토큰이 살아 있는지 본다.
     * 한쪽 서비스의 갱신, 폐기가 다른 쪽 토큰에 영향을 주는지 확인할 때 양쪽에서 누른다.
     */
    @PostMapping("/oauth/cafe24/{plane}/token/check")
    public ResponseEntity<String> checkToken(@PathVariable String plane, @RequestParam String result) {
        StoredResult stored = results.get(result);
        if (stored == null) {
            return html(HttpStatus.BAD_REQUEST, "보관된 토큰 응답이 없다. 콜백 앱이 재기동됐거나 id 가 틀렸다.");
        }
        String outcome;
        try {
            ResponseEntity<String> response = restClient.get()
                    .uri("https://{mallId}.cafe24api.com/api/v2/admin/store", stored.mallId())
                    .header("Authorization", "Bearer " + field(stored.token(), "access_token"))
                    .header("Content-Type", "application/json")
                    .retrieve()
                    .toEntity(String.class);
            outcome = "살아 있음 " + response.getStatusCode() + " " + abbreviate(response.getBody());
        } catch (RestClientResponseException e) {
            outcome = "실패 " + e.getStatusCode() + " " + abbreviate(e.getResponseBodyAsString());
        }
        log.info("[cafe24-check] mallId={} service={} result={} → {}", stored.mallId(), stored.service(), result, outcome);
        return html(HttpStatus.OK, resultPage("API 호출 확인 (GET /api/v2/admin/store)", stored, result, outcome));
    }

    /**
     * 실험용. 보관된 토큰을 {@code POST /api/v2/oauth/revoke} 로 폐기한다. hint 는 access_token 또는 refresh_token 이다.
     * 문서상 access_token 을 폐기하면 짝인 refresh_token 도 폐기된다.
     */
    @PostMapping("/oauth/cafe24/{plane}/token/revoke")
    public ResponseEntity<String> revokeToken(@PathVariable String plane, @RequestParam String result,
            @RequestParam(defaultValue = "access_token") String hint) {
        StoredResult stored = results.get(result);
        if (stored == null) {
            return html(HttpStatus.BAD_REQUEST, "보관된 토큰 응답이 없다. 콜백 앱이 재기동됐거나 id 가 틀렸다.");
        }
        String tokenHint = "refresh_token".equals(hint) ? "refresh_token" : "access_token";
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("token", String.valueOf(field(stored.token(), tokenHint)));
        form.add("token_hint", tokenHint);
        String outcome;
        try {
            ResponseEntity<String> response = restClient.post()
                    .uri("https://{mallId}.cafe24api.com/api/v2/oauth/revoke", stored.mallId())
                    .header("Authorization", "Basic " + basicAuth())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toEntity(String.class);
            outcome = "폐기 요청 성공 " + response.getStatusCode() + " " + abbreviate(response.getBody());
        } catch (RestClientResponseException e) {
            outcome = "폐기 실패 " + e.getStatusCode() + " " + abbreviate(e.getResponseBodyAsString());
        }
        log.info("[cafe24-revoke] mallId={} service={} result={} hint={} → {}", stored.mallId(), stored.service(), result,
                tokenHint, outcome);
        return html(HttpStatus.OK, resultPage("토큰 폐기 (" + tokenHint + ")", stored, result, outcome));
    }

    private static String resultPage(String title, StoredResult stored, String resultId, String outcome) {
        return "<!doctype html><meta charset=\"utf-8\"><h2>" + escape(title) + "</h2>"
                + "<table border=\"1\" cellpadding=\"6\" cellspacing=\"0\">"
                + "<tr><td>mallId</td><td><code>" + escape(nullToDash(stored.mallId())) + "</code></td></tr>"
                + "<tr><td>service</td><td><code>" + escape(nullToDash(stored.service())) + "</code></td></tr>"
                + "<tr><td>result id</td><td><code>" + escape(resultId) + "</code></td></tr>"
                + "<tr><td>issued_at</td><td><code>" + escape(String.valueOf(field(stored.token(), "issued_at"))) + "</code></td></tr>"
                + "<tr><td>결과</td><td><code>" + escape(outcome) + "</code></td></tr></table>"
                + "<p><a href=\"/cafe24/demo/return?result=" + encode(resultId) + "\">← 토큰 화면으로</a></p>";
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        String oneLine = body.replaceAll("\\s+", " ");
        return oneLine.length() > 300 ? oneLine.substring(0, 300) + "…" : oneLine;
    }

    private String basicAuth() {
        return Base64.getEncoder().encodeToString(
                (properties.clientId() + ":" + properties.clientSecret()).getBytes(StandardCharsets.UTF_8));
    }

    /** 원 서비스 복귀 데모 화면. result id 가 있으면 콜백이 보관한 토큰 응답을 표로 보인다. */
    @GetMapping("/cafe24/demo/return")
    public ResponseEntity<String> demoReturn(@RequestParam Map<String, String> params) {
        String rows = rows(params);
        String resultId = params.get("result");
        StoredResult stored = resultId == null ? null : results.get(resultId);
        String tokenBlock;
        if (stored == null) {
            tokenBlock = "<p>이 화면에 보여줄 토큰 응답이 없다(result id 없음, 또는 콜백 앱 재기동으로 사라짐). 콜백 로그를 본다.</p>";
        } else {
            String title = params.containsKey("refreshed") ? "갱신(grant_type=refresh_token) 응답" : "토큰 응답";
            tokenBlock = "<h3>" + title + "</h3>"
                    + "<table border=\"1\" cellpadding=\"6\" cellspacing=\"0\">" + tokenRows(stored.token()) + "</table>"
                    + refreshForm(stored, resultId)
                    + experimentForms(stored, resultId)
                    + "<p><a href=\"/cafe24/demo/webhooks\">받은 Cafe24 웹훅 보기</a></p>"
                    + "<p style=\"color:#a00\">주의: 액세스 토큰과 refresh_token 은 민감 정보다. 테스트 후 폐기하라.</p>";
        }
        return html(HttpStatus.OK, """
                <!doctype html><meta charset="utf-8">
                <h2>원 서비스 복귀 (Cafe24 데모)</h2>
                <p>라우터가 인증을 마치고 이 화면으로 302 했다. 토큰은 URL 이 아니라 콜백 앱 메모리에서 result id 로 꺼내 보인다.</p>
                <table border="1" cellpadding="6" cellspacing="0">%s</table>
                %s
                """.formatted(rows, tokenBlock));
    }

    private Map<?, ?> requestToken(String mallId, MultiValueMap<String, String> form) {
        return restClient.post()
                .uri("https://{mallId}.cafe24api.com/api/v2/oauth/token", mallId)
                .header("Authorization", "Basic " + basicAuth())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);
    }

    /**
     * returnTo 로 302 할 최종 URL. 없으면 null(데모 화면으로 보낸다). 상대 경로는 허용하고, 절대 URL 은 허용 호스트
     * 목록에 있을 때만 허용한다(오픈 리다이렉트 차단).
     */
    private String resolveReturn(String returnTo, String mallId, String region, String service,
            Map<String, String> extras, String resultId) {
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
            log.warn("[cafe24-callback] returnTo 호스트가 허용목록에 없어 데모 화면으로 보낸다: {}", returnTo);
            return null;
        }
        return returnTo + (returnTo.contains("?") ? "&" : "?") + returnQuery(mallId, region, service, extras, resultId);
    }

    private static String demoReturnUrl(String mallId, String region, String service, Map<String, String> extras,
            String resultId) {
        return "/cafe24/demo/return?" + returnQuery(mallId, region, service, extras, resultId);
    }

    private static String returnQuery(String mallId, String region, String service, Map<String, String> extras,
            String resultId) {
        String extrasQuery = extras == null ? "" : extras.entrySet().stream()
                .map(e -> "&" + encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining());
        return "channel=cafe24&mallId=" + encode(mallId)
                + "&region=" + encode(region)
                + "&service=" + encode(service)
                + extrasQuery
                + "&ok=true&result=" + encode(resultId);
    }

    private Set<String> allowlist() {
        String raw = properties.returnAllowlist();
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    private String store(String mallId, String plane, String region, String service, Map<String, String> extras,
            Map<?, ?> token) {
        String id = UUID.randomUUID().toString().replace("-", "");
        if (token != null) {
            results.put(id, new StoredResult(mallId, plane, region, service, extras, token));
        }
        return id;
    }

    private static String refreshForm(StoredResult stored, String resultId) {
        if (field(stored.token(), "refresh_token") == null) {
            return "";
        }
        return "<form method=\"post\" action=\"/oauth/cafe24/" + escape(stored.plane()) + "/token/refresh\">"
                + "<input type=\"hidden\" name=\"result\" value=\"" + escape(resultId) + "\">"
                + "<button type=\"submit\">refresh_token 으로 갱신 요청</button>"
                + " <span>grant_type=refresh_token 으로 새 access_token, refresh_token 을 받아 이 화면에 다시 보인다.</span></form>";
    }

    /** 토큰 실험 버튼. API 호출로 살아 있는지 보고, access_token 또는 refresh_token 을 폐기한다. */
    private static String experimentForms(StoredResult stored, String resultId) {
        String base = "/oauth/cafe24/" + escape(stored.plane()) + "/token/";
        String hidden = "<input type=\"hidden\" name=\"result\" value=\"" + escape(resultId) + "\">";
        return "<h3>토큰 실험</h3><div style=\"display:flex;gap:8px\">"
                + "<form method=\"post\" action=\"" + base + "check\">" + hidden
                + "<button type=\"submit\">API 호출 확인 (GET /admin/store)</button></form>"
                + "<form method=\"post\" action=\"" + base + "revoke\">" + hidden
                + "<input type=\"hidden\" name=\"hint\" value=\"access_token\">"
                + "<button type=\"submit\">access_token 폐기</button></form>"
                + "<form method=\"post\" action=\"" + base + "revoke\">" + hidden
                + "<input type=\"hidden\" name=\"hint\" value=\"refresh_token\">"
                + "<button type=\"submit\">refresh_token 폐기</button></form></div>";
    }

    private static String rows(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> "<tr><td>" + escape(e.getKey()) + "</td><td><code>" + escape(e.getValue()) + "</code></td></tr>")
                .collect(Collectors.joining());
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
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
