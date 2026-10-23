package com.colosseum.routestub.callbackcgs.cafe24;

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

import com.colosseum.routestub.callbackcgs.config.Cafe24Properties;

/**
 * 소비 서비스 쪽 Cafe24 웹훅 수신. Cafe24 가 직접 보내지 않고 라우터(8080)가 X-API-Key 를 확인한 뒤 같은 본문을 넘긴다.
 * 여기서는 라우터 서명 X-Router-Hmac-SHA256(본문 HMAC, 키는 Cafe24 client secret)만 확인하고 메모리에 쌓아
 * {@code /cafe24/demo/webhooks} 로 보인다. 정식에서는 앱 삭제(event_no 90077)를 받으면 그 몰의 연동을 해제한다.
 */
@RestController
public class Cafe24WebhookController {

    private static final Logger log = LoggerFactory.getLogger(Cafe24WebhookController.class);
    private static final int KEEP = 50;

    record Received(Instant at, String plane, String topic, String eventNo, String service, String traceId, String body) {
    }

    private final Cafe24Properties properties;
    private final Deque<Received> received = new ConcurrentLinkedDeque<>();

    public Cafe24WebhookController(Cafe24Properties properties) {
        this.properties = properties;
    }

    @PostMapping("/webhooks/cafe24/{plane}/{topic}")
    public ResponseEntity<String> receive(@PathVariable String plane, @PathVariable String topic,
            @RequestHeader HttpHeaders headers, @RequestBody(required = false) byte[] body) {
        byte[] raw = body == null ? new byte[0] : body;
        if (properties.clientSecret() == null || properties.clientSecret().isBlank()
                || !Cafe24StateCodec.isValidRouterHmac(raw, headers.getFirst("X-Router-Hmac-SHA256"),
                        properties.clientSecret())) {
            log.warn("[cafe24-webhook] 라우터 서명 검증 실패 topic={}", topic);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("router hmac 검증 실패다.");
        }
        Received event = new Received(Instant.now(), plane, topic, headers.getFirst("X-Router-Event-No"),
                headers.getFirst("X-Router-Service"), headers.getFirst("X-Trace-ID"),
                new String(raw, StandardCharsets.UTF_8));
        received.addFirst(event);
        while (received.size() > KEEP) {
            received.pollLast();
        }
        log.info("[cafe24-webhook] 수신 topic={} event_no={} service={} traceId={} body={}", event.topic(),
                event.eventNo(), event.service(), event.traceId(), event.body());
        return ResponseEntity.ok("ok");
    }

    /** 받은 Cafe24 웹훅을 최근 순으로 보인다. 재기동하면 사라진다. */
    @GetMapping("/cafe24/demo/webhooks")
    public ResponseEntity<String> demoWebhooks() {
        String rows = received.isEmpty()
                ? "<tr><td colspan=\"6\">받은 웹훅이 없다. 몰에서 앱을 삭제하거나 개발자센터 WebHook 의 [test] 로 보내 본다.</td></tr>"
                : received.stream().map(e -> "<tr>"
                        + "<td>" + escape(String.valueOf(e.at())) + "</td>"
                        + "<td><code>" + escape(nullToDash(e.topic())) + "</code></td>"
                        + "<td><code>" + escape(nullToDash(e.eventNo())) + "</code></td>"
                        + "<td><code>" + escape(nullToDash(e.service())) + "</code></td>"
                        + "<td><code>" + escape(nullToDash(e.traceId())) + "</code></td>"
                        + "<td><pre style=\"margin:0;max-width:520px;white-space:pre-wrap\">" + escape(e.body()) + "</pre></td>"
                        + "</tr>").collect(Collectors.joining());
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body("""
                <!doctype html><meta charset="utf-8">
                <h2>받은 Cafe24 웹훅 (소비 서비스 데모)</h2>
                <p>라우터가 X-API-Key 를 확인하고 전달한 웹훅이다. 이 앱은 라우터 서명 X-Router-Hmac-SHA256 만 확인한다.
                   앱 삭제는 event_no 90077 이다.</p>
                <table border="1" cellpadding="6" cellspacing="0">
                  <tr><th>받은 시각</th><th>topic</th><th>event_no</th><th>service</th><th>X-Trace-ID</th><th>body</th></tr>
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
