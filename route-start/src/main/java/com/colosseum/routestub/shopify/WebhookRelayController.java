package com.colosseum.routestub.shopify;

import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.colosseum.routestub.config.ShopifyProperties;

/**
 * Shopify 웹훅을 라우터(8080)가 한 번에 받아 검증하고 소비 서비스로 전달한다.
 *
 * <p>구독 주소는 콜백이 토큰 교환 직후 {@code APP_HOST/webhooks/shopify/{plane}/{topic}?service=..&region=..} 로 등록한다.
 * 웹훅 본문에는 어느 서비스의 shop 인지 없으므로, 라우터가 무상태로 전달처를 알 수 있게 service, region 을 구독
 * 주소 쿼리에 실어 둔다(웹훅 주소는 redirect_uri 와 달리 등록 목록 검사가 없어 쿼리를 붙일 수 있다).
 *
 * <p>검증은 X-Shopify-Hmac-SHA256(본문 raw bytes 의 HMAC, base64)이다. 통과하면 같은 본문을 소비 서비스의
 * {@code /webhooks/shopify/{plane}/{topic}} 으로 POST 하고, Shopify 헤더를 그대로 넘기며 라우터 서명
 * X-Router-Hmac-SHA256(같은 secret, 같은 방식)을 붙여 소비 서비스가 출처를 확인하게 한다.
 *
 * <p>개인정보 웹훅 3종(customers/data_request, customers/redact, shop/redact)은 Admin API 로 구독할 수 없고 Dev Dashboard
 * 의 Compliance webhooks 에 앱당 주소 하나만 둔다. 그래서 쿼리에 service 가 없고, 릴레이는 설정한 접수 담당
 * service({@code compliance-service})로 폴백한다. 그 밖의 topic 에 service 가 없으면 400 이다.
 *
 * <p>Shopify 는 연결 1초, 전체 5초 안에 2xx 를 받지 못하면 실패로 보고 4시간 동안 8번 재시도한다. 그래서 전달은
 * 연결 1초, 읽기 3초로 끊고, 소비 서비스가 2xx 가 아니면 502 를 돌려 Shopify 가 재시도하게 둔다.
 */
@RestController
public class WebhookRelayController {

    private static final Logger log = LoggerFactory.getLogger(WebhookRelayController.class);

    /** 앱 설정(Compliance webhooks)으로만 구독되는 개인정보 3종. 구독 주소에 service 쿼리가 없다. */
    static final Set<String> COMPLIANCE_TOPICS = Set.of("customers/data_request", "customers/redact", "shop/redact");

    /** Shopify 가 보내는 헤더 중 소비 서비스에 그대로 넘길 것. */
    private static final List<String> PASS_THROUGH_HEADERS = List.of(
            "X-Shopify-Topic", "X-Shopify-Shop-Domain", "X-Shopify-Webhook-Id", "X-Shopify-Event-Id",
            "X-Shopify-API-Version", "X-Shopify-Triggered-At");

    private final ShopifyProperties properties;
    private final RestClient restClient;

    public WebhookRelayController(ShopifyProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000);
        factory.setReadTimeout(3000);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @PostMapping("/webhooks/shopify/{plane}/{topic}")
    public ResponseEntity<String> relay(@PathVariable String plane, @PathVariable String topic,
            @RequestParam(required = false) String service,
            @RequestParam(required = false) String region,
            @RequestHeader HttpHeaders headers,
            @RequestBody(required = false) byte[] body) {
        byte[] raw = body == null ? new byte[0] : body;
        String shop = headers.getFirst("X-Shopify-Shop-Domain");
        String shopifyTopic = headers.getFirst("X-Shopify-Topic");

        String hmac = headers.getFirst("X-Shopify-Hmac-SHA256");
        if (!HmacVerifier.isValidWebhook(raw, hmac, properties.apiSecret())) {
            log.warn("[webhook] hmac 검증 실패 topic={} shop={} service={}", shopifyTopic, shop, service);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("webhook hmac 검증 실패다.");
        }

        String resolvedService = resolveService(service, topic, shopifyTopic, properties.complianceService());
        if (resolvedService == null) {
            log.warn("[webhook] 전달처 없음: service 쿼리가 없고 compliance topic 도 아니다 (topic={} path={} shop={})",
                    shopifyTopic, topic, shop);
            return ResponseEntity.badRequest().body("service 쿼리가 없고 compliance topic 도 아니다: " + topic);
        }
        if (!resolvedService.equals(service)) {
            log.info("[webhook] compliance topic 이라 접수 담당 service={} 로 폴백 (topic={})", resolvedService, shopifyTopic);
        }
        String consumerHost;
        try {
            consumerHost = ServiceRouting.callbackHost(properties, resolvedService);
        } catch (IllegalArgumentException e) {
            log.warn("[webhook] 전달처 없음: {} (topic={} shop={})", e.getMessage(), shopifyTopic, shop);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
        String target = consumerHost + "/webhooks/shopify/" + plane + "/" + topic;
        log.info("[webhook] 검증 성공 topic={} shop={} service={} region={} webhookId={} → {}",
                shopifyTopic, shop, resolvedService, region, headers.getFirst("X-Shopify-Webhook-Id"), target);

        try {
            ResponseEntity<Void> forwarded = restClient.post()
                    .uri(target)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> {
                        for (String name : PASS_THROUGH_HEADERS) {
                            String value = headers.getFirst(name);
                            if (value != null) {
                                h.set(name, value);
                            }
                        }
                        h.set("X-Router-Hmac-SHA256", HmacVerifier.base64HmacSha256(raw, properties.apiSecret()));
                        h.set("X-Router-Service", resolvedService);
                        h.set("X-Router-Region", nullToEmpty(region));
                    })
                    .body(raw)
                    .retrieve()
                    .toBodilessEntity();
            log.info("[webhook] 전달 완료 status={} target={}", forwarded.getStatusCode(), target);
            return ResponseEntity.ok("relayed");
        } catch (RestClientException e) {
            // 소비 서비스가 못 받았다. 2xx 를 주면 Shopify 는 성공으로 보고 다시 보내지 않으므로 502 로 재시도를 유도한다.
            log.warn("[webhook] 전달 실패 target={} error={}", target, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body("소비 서비스 전달 실패: " + e.getMessage());
        }
    }

    /**
     * 전달처 service 를 정한다. 쿼리에 service 가 있으면 그 값이다. 없으면 topic 이 개인정보 3종일 때만 접수 담당
     * service 로 폴백하고, 그 밖에는 null(400). topic 은 헤더 X-Shopify-Topic 을 우선 보고, 없으면 경로의 topic
     * ({@code customers-data_request} 처럼 첫 {@code -} 가 {@code /})으로 판단한다.
     */
    static String resolveService(String service, String topicPath, String headerTopic, String complianceService) {
        if (service != null && !service.isBlank()) {
            return service;
        }
        String topic = headerTopic != null && !headerTopic.isBlank() ? headerTopic
                : topicPath == null ? "" : topicPath.replaceFirst("-", "/");
        if (COMPLIANCE_TOPICS.contains(topic) && complianceService != null && !complianceService.isBlank()) {
            return complianceService;
        }
        return null;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
