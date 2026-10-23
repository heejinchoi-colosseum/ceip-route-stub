package com.colosseum.routestub.callbackcolo.shopify;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.colosseum.routestub.callbackcolo.config.ShopifyProperties;

/**
 * 소비 서비스 쪽 웹훅 수신. Shopify 가 직접 보내는 게 아니라 라우터(8080)가 X-Shopify-Hmac-SHA256 을 검증한 뒤
 * 같은 본문을 전달한다. 여기서는 라우터 서명 X-Router-Hmac-SHA256(같은 secret, 본문 raw bytes 의 HMAC base64)만
 * 확인하고 메모리에 쌓아 {@code /demo/webhooks} 로 보여준다. 정식에서는 소비 서비스가 이 자리에서 연동 해제 처리를 한다.
 */
@RestController
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);
    private static final int KEEP = 50;

    record Received(Instant at, String plane, String topic, String shop, String service, String region,
            String webhookId, String body) {
    }

    private final ShopifyProperties properties;
    private final Deque<Received> received = new ConcurrentLinkedDeque<>();

    public WebhookController(ShopifyProperties properties) {
        this.properties = properties;
    }

    @PostMapping("/webhooks/shopify/{plane}/{topic}")
    public ResponseEntity<String> receive(@PathVariable String plane, @PathVariable String topic,
            @RequestHeader HttpHeaders headers, @RequestBody(required = false) byte[] body) {
        byte[] raw = body == null ? new byte[0] : body;
        String shop = headers.getFirst("X-Shopify-Shop-Domain");
        String shopifyTopic = headers.getFirst("X-Shopify-Topic");

        if (!HmacVerifier.isValidWebhook(raw, headers.getFirst("X-Router-Hmac-SHA256"), properties.apiSecret())) {
            log.warn("[webhook] 라우터 서명 검증 실패 topic={} shop={}", shopifyTopic, shop);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("router hmac 검증 실패다.");
        }

        Received event = new Received(Instant.now(), plane, shopifyTopic == null ? topic : shopifyTopic, shop,
                headers.getFirst("X-Router-Service"), headers.getFirst("X-Router-Region"),
                headers.getFirst("X-Shopify-Webhook-Id"), new String(raw, StandardCharsets.UTF_8));
        received.addFirst(event);
        while (received.size() > KEEP) {
            received.pollLast();
        }
        log.info("[webhook] 수신 topic={} shop={} service={} region={} webhookId={} body={}",
                event.topic(), event.shop(), event.service(), event.region(), event.webhookId(), event.body());
        return ResponseEntity.ok("ok");
    }

    /** 받은 웹훅을 최근 순으로 보인다. 재기동하면 사라진다. */
    @GetMapping("/demo/webhooks")
    public ResponseEntity<String> demoWebhooks() {
        String rows = received.isEmpty()
                ? "<tr><td colspan=\"7\">받은 웹훅이 없다. 앱을 uninstall 하거나 CLI 로 trigger 해 본다.</td></tr>"
                : received.stream().map(e -> "<tr>"
                        + "<td>" + escape(String.valueOf(e.at())) + "</td>"
                        + "<td><code>" + escape(nullToDash(e.topic())) + "</code></td>"
                        + "<td><code>" + escape(nullToDash(e.shop())) + "</code></td>"
                        + "<td><code>" + escape(nullToDash(e.service())) + "</code></td>"
                        + "<td><code>" + escape(nullToDash(e.region())) + "</code></td>"
                        + "<td><code>" + escape(nullToDash(e.webhookId())) + "</code></td>"
                        + "<td><pre style=\"margin:0;max-width:520px;white-space:pre-wrap\">" + escape(e.body()) + "</pre></td>"
                        + "</tr>").collect(Collectors.joining());
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body("""
                <!doctype html><meta charset="utf-8">
                <h2>받은 웹훅 (소비 서비스 데모)</h2>
                <p>라우터가 X-Shopify-Hmac-SHA256 을 검증하고 전달한 웹훅이다. 이 앱은 라우터 서명 X-Router-Hmac-SHA256 만 확인한다.</p>
                <table border="1" cellpadding="6" cellspacing="0">
                  <tr><th>받은 시각</th><th>topic</th><th>shop</th><th>service</th><th>region</th><th>webhookId</th><th>body</th></tr>
                  %s
                </table>
                """.formatted(rows));
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
