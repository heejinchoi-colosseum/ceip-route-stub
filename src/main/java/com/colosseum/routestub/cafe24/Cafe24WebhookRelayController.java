package com.colosseum.routestub.cafe24;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.colosseum.routestub.config.Cafe24Properties;
import com.colosseum.routestub.config.ShopifyProperties;
import com.colosseum.routestub.shopify.ServiceRouting;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Cafe24 웹훅을 라우터(8080)가 받아 검증하고 소비 서비스로 전달한다. Shopify {@code WebhookRelayController} 와 같은
 * 역할이지만 Cafe24 규격이 달라 세 가지가 다르다.
 *
 * <ul>
 *   <li>검증: HMAC 서명이 없다. 개발자센터 WebHook "인증정보"가 X-API-Key 헤더로 그대로 오고, 이를 설정값과 비교한다.</li>
 *   <li>주소: 개발자센터에 이벤트별로 주소 하나를 앱 단위로 등록한다(구독 API 가 아니다). 쿼리에 service 를 실을 수 없다.</li>
 *   <li>전달처: 본문 {@code resource.mall_id} 로 {@link Cafe24MallRegistry} 에서 그 몰을 연결한 서비스를 찾아 모두에 보낸다.
 *       기록이 없으면(라우터 재기동 등) 콜백 앱이 있는 모든 서비스에 보낸다.</li>
 * </ul>
 *
 * <p>소비 서비스로는 같은 본문을 {@code /webhooks/cafe24/{plane}/{topic}} 에 POST 하고 X-Router-Hmac-SHA256(본문 HMAC,
 * 키는 Cafe24 client secret)을 붙인다. 하나라도 전달에 실패하면 502 를 돌린다. Cafe24 는 1주일간 실패 100건 초과이고
 * 성공률 10% 미만이면 그 웹훅을 자동으로 미수신 처리하므로, 테스트 중 실패가 쌓이지 않게 본다.
 */
@RestController
public class Cafe24WebhookRelayController {

    private static final Logger log = LoggerFactory.getLogger(Cafe24WebhookRelayController.class);

    private final Cafe24Properties properties;
    private final ShopifyProperties hosts;
    private final Cafe24MallRegistry registry;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestClient restClient;

    public Cafe24WebhookRelayController(Cafe24Properties properties, ShopifyProperties hosts,
            Cafe24MallRegistry registry) {
        this.properties = properties;
        this.hosts = hosts;
        this.registry = registry;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000);
        factory.setReadTimeout(3000);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @PostMapping("/webhooks/cafe24/{plane}/{topic}")
    public ResponseEntity<String> relay(@PathVariable String plane, @PathVariable String topic,
            @RequestHeader HttpHeaders headers, @RequestBody(required = false) byte[] body) {
        byte[] raw = body == null ? new byte[0] : body;
        String traceId = headers.getFirst("X-Trace-ID");
        if (properties.webhookKey() == null || properties.webhookKey().isBlank()) {
            log.warn("[cafe24-webhook] CAFE24_WEBHOOK_KEY 가 비어 있어 검증할 수 없다. 개발자센터 WebHook 인증정보를 넣고 재기동하라.");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("webhook key 미설정");
        }
        if (!sameKey(properties.webhookKey(), headers.getFirst("X-API-Key"))) {
            log.warn("[cafe24-webhook] X-API-Key 검증 실패 topic={} traceId={}", topic, traceId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("X-API-Key 검증 실패다.");
        }

        String eventNo = null;
        String mallId = null;
        try {
            JsonNode json = objectMapper.readTree(raw);
            eventNo = json.path("event_no").asText(null);
            mallId = json.path("resource").path("mall_id").asText(null);
        } catch (Exception e) {
            log.warn("[cafe24-webhook] 본문이 JSON 이 아니다 topic={} traceId={} error={}", topic, traceId, e.getMessage());
        }

        Collection<String> services = registry.servicesOf(mallId);
        boolean fallback = services.isEmpty();
        if (fallback) {
            services = Cafe24MallRegistry.ALL_SERVICES;
        }
        log.info("[cafe24-webhook] 검증 성공 topic={} event_no={} mallId={} traceId={} 전달처={}{}", topic, eventNo, mallId,
                traceId, services, fallback ? " (연결 기록 없음 → 전체)" : "");

        List<String> failures = new ArrayList<>();
        for (String service : services) {
            String target = ServiceRouting.callbackHost(hosts, service) + "/webhooks/cafe24/" + plane + "/" + topic;
            String eventNoHeader = eventNo;
            try {
                ResponseEntity<Void> forwarded = restClient.post()
                        .uri(target)
                        .contentType(MediaType.APPLICATION_JSON)
                        .headers(h -> {
                            if (traceId != null) {
                                h.set("X-Trace-ID", traceId);
                            }
                            h.set("X-Router-Hmac-SHA256", Cafe24StateCodec.base64HmacSha256(raw, properties.clientSecret()));
                            h.set("X-Router-Service", service);
                            h.set("X-Router-Event-No", eventNoHeader == null ? "" : eventNoHeader);
                        })
                        .body(raw)
                        .retrieve()
                        .toBodilessEntity();
                log.info("[cafe24-webhook] 전달 완료 service={} status={} target={}", service, forwarded.getStatusCode(), target);
            } catch (RestClientException e) {
                log.warn("[cafe24-webhook] 전달 실패 service={} target={} error={}", service, target, e.getMessage());
                failures.add(service);
            }
        }
        if (!failures.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body("소비 서비스 전달 실패: " + failures);
        }
        return ResponseEntity.ok("relayed to " + services);
    }

    private static boolean sameKey(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.trim().getBytes(StandardCharsets.UTF_8));
    }
}
