package com.colosseum.routestub.callback.shopify;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.colosseum.routestub.callback.config.ShopifyProperties;

/**
 * 토큰 교환 직후 shop 별 웹훅 구독을 만든다. 수신 엔드포인트만 있어서는 아무것도 오지 않는다. Shopify 는 구독된 주소로만
 * 보낸다. 앱 단위 구독(shopify.app.toml + shopify app deploy)은 앱 설정 전체를 덮어쓰므로 Dev Dashboard 를 손으로
 * 관리하는 지금 방식과 섞지 않고, GraphQL Admin API {@code webhookSubscriptionCreate} 로 shop 별 구독을 만든다.
 *
 * <p>구독 주소는 라우터(APP_HOST)의 릴레이 {@code /webhooks/shopify/{plane}/{topic}} 이고, 라우터가 무상태로 전달처를
 * 알 수 있게 service, region 을 쿼리에 싣는다. app/uninstalled 는 별도 scope 가 필요 없고, uninstall 로 토큰이
 * 폐기돼도 이 웹훅은 전달된다. 구독은 shop 에 묶이므로 uninstall 뒤 Shopify 가 지운다.
 */
final class WebhookSubscriber {

    private static final Logger log = LoggerFactory.getLogger(WebhookSubscriber.class);

    private static final String MUTATION = """
            mutation($topic: WebhookSubscriptionTopic!, $sub: WebhookSubscriptionInput!) {
              webhookSubscriptionCreate(topic: $topic, webhookSubscription: $sub) {
                webhookSubscription { id }
                userErrors { field message }
              }
            }""";

    private final ShopifyProperties properties;
    private final RestClient restClient;

    WebhookSubscriber(ShopifyProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    /** 릴레이 주소. APP_HOST 가 없으면 null(구독 생략). */
    String callbackUrl(String plane, String service, String region) {
        String appHost = properties.appHost();
        if (appHost == null || appHost.isBlank()) {
            return null;
        }
        String host = appHost.endsWith("/") ? appHost.substring(0, appHost.length() - 1) : appHost;
        return host + "/webhooks/shopify/" + plane + "/app-uninstalled"
                + "?service=" + encode(service) + "&region=" + encode(region);
    }

    /** app/uninstalled 구독을 만들고, 화면과 로그에 남길 한 줄 결과를 돌려준다. 실패해도 인증 흐름은 막지 않는다. */
    String subscribeAppUninstalled(String shop, String plane, String service, String region, Object accessToken) {
        String callbackUrl = callbackUrl(plane, service, region);
        if (callbackUrl == null) {
            return "구독 생략: APP_HOST 가 없어 릴레이 주소를 만들 수 없다.";
        }
        if (accessToken == null) {
            return "구독 생략: access_token 이 없다.";
        }
        Map<String, Object> request = Map.of(
                "query", MUTATION,
                "variables", Map.of(
                        "topic", "APP_UNINSTALLED",
                        "sub", Map.of("callbackUrl", callbackUrl, "format", "JSON")));
        Map<?, ?> response;
        try {
            response = restClient.post()
                    .uri("https://{shop}/admin/api/{version}/graphql.json", shop, properties.apiVersion())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Shopify-Access-Token", String.valueOf(accessToken))
                    .body(request)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientException e) {
            log.warn("[webhook-subscribe] shop={} 실패 {}", shop, e.getMessage());
            return "구독 실패: " + e.getMessage();
        }

        String note = summarize(response, callbackUrl);
        log.info("[webhook-subscribe] shop={} topic=app/uninstalled callbackUrl={} → {}", shop, callbackUrl, note);
        return note;
    }

    private static String summarize(Map<?, ?> response, String callbackUrl) {
        if (response == null) {
            return "구독 실패: 빈 응답";
        }
        if (response.get("errors") instanceof List<?> errors && !errors.isEmpty()) {
            return "구독 실패: " + errors;
        }
        Object data = response.get("data");
        Object create = data instanceof Map<?, ?> d ? d.get("webhookSubscriptionCreate") : null;
        if (!(create instanceof Map<?, ?> result)) {
            return "구독 실패: 응답 형식이 다르다 " + response;
        }
        if (result.get("userErrors") instanceof List<?> userErrors && !userErrors.isEmpty()) {
            return "구독 실패(userErrors): " + userErrors + " callbackUrl=" + callbackUrl;
        }
        Object subscription = result.get("webhookSubscription");
        Object id = subscription instanceof Map<?, ?> s ? s.get("id") : null;
        return "구독 성공 id=" + id + " callbackUrl=" + callbackUrl;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
