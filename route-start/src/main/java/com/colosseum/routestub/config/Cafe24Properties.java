package com.colosseum.routestub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cafe24 앱 설정. 값은 환경변수(application.yml 의 placeholder)에서 주입한다. 콜백 호스트는 채널과 무관하게
 * {@link ShopifyProperties} 의 callback-host* 를 함께 쓴다(같은 소비 서비스 앱, 같은 터널).
 *
 * @param clientId     Cafe24 개발자센터 앱의 Client ID
 * @param clientSecret Client Secret. state 서명에도 쓰므로 콜백 모듈과 같은 값이어야 한다.
 * @param scopes       요청 권한(콤마 구분). 앱 권한 설정에 등록한 scope 안에서만 요청할 수 있다. authorize 에는 공백으로 이어 보낸다.
 * @param webhookKey   개발자센터 WebHook "인증정보". Cafe24 는 웹훅에 서명 대신 이 값을 X-API-Key 헤더로 그대로 싣는다.
 */
@ConfigurationProperties("cafe24")
public record Cafe24Properties(String clientId, String clientSecret, String scopes, String webhookKey) {
}
