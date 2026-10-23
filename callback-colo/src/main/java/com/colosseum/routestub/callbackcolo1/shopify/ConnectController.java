package com.colosseum.routestub.callbackcolo1.shopify;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.colosseum.routestub.callbackcolo1.config.ShopifyProperties;

/**
 * colo1.0 소비 서비스의 연결 화면을 흉내 내는 {@code /connect}. callback-cgs 의 복제본이고 템플릿 기본값(service=colo1.0,
 * region=kr)만 다르다. 소비 서비스 호스트(8082, 두 번째 cloudflared)에서 sellerId, warehouseId 를
 * 입력받고, 서비스가 이미 보유한 channel, region, service, shop, returnTo 와 함께 start 호스트(8080, ngrok)의
 * {@code /oauth/shopify/default/start} 로 GET 폼을 보낸다. start 호스트가 설정값이라 정적 파일 대신 여기서 치환해 내려준다.
 */
@RestController
public class ConnectController {

    private static final String TEMPLATE = "templates/connect.html";
    private static final String START_PATH = "/oauth/shopify/default/start";

    private final String page;

    public ConnectController(ShopifyProperties properties) {
        this.page = load().replace("{{startUrl}}", escape(startUrl(properties.appHost())));
    }

    @GetMapping(value = "/connect", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> connect() {
        return ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .contentType(MediaType.TEXT_HTML)
                .body(page);
    }

    /** start 폼이 향할 절대 URL. appHost 가 비어 있으면 같은 호스트의 상대 경로로 둔다(단일 호스트 실행용). */
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
