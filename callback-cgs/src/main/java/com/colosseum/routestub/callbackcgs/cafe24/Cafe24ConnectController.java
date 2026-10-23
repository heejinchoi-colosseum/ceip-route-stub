package com.colosseum.routestub.callbackcgs.cafe24;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.colosseum.routestub.callbackcgs.config.Cafe24Properties;

/**
 * 소비 서비스의 Cafe24 연결 화면을 흉내 내는 {@code /cafe24/connect}. Shopify {@code /connect} 와 같이 sellerId,
 * warehouseId 를 입력받고 서비스 보유 값(channel, region, service, mallId, returnTo)과 함께 start 호스트(8080)의
 * {@code /oauth/cafe24/default/start} 로 GET 폼을 보낸다.
 */
@RestController
public class Cafe24ConnectController {

    private static final String TEMPLATE = "templates/cafe24-connect.html";
    private static final String START_PATH = "/oauth/cafe24/default/start";

    private final String page;

    public Cafe24ConnectController(Cafe24Properties properties) {
        this.page = load()
                .replace("{{startUrl}}", escape(startUrl(properties.appHost())))
                .replace("{{mallId}}", escape(properties.mallId() == null ? "" : properties.mallId()));
    }

    @GetMapping(value = "/cafe24/connect", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> connect() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.TEXT_HTML)
                .body(page);
    }

    /** start 폼이 향할 절대 URL. appHost 가 비어 있으면 같은 호스트의 상대 경로로 둔다. */
    static String startUrl(String appHost) {
        if (appHost == null || appHost.isBlank()) {
            return START_PATH;
        }
        String host = appHost.endsWith("/") ? appHost.substring(0, appHost.length() - 1) : appHost;
        return host + START_PATH;
    }

    private static String load() {
        try {
            return new ClassPathResource(TEMPLATE).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(TEMPLATE + " 을 읽지 못했다", e);
        }
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
