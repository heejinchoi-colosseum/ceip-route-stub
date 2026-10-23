package com.colosseum.routestub.callback.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 콜백 모듈용 Shopify 설정. route-start 모듈 설정의 복제본이되, 콜백이 실제로 쓰는 값만 남겼다.
 * start 를 담당하지 않으므로 appHost, callbackHost, scopes 는 두지 않는다.
 *
 * @param apiKey          Client ID (토큰 교환에 쓴다)
 * @param apiSecret       Client secret (hmac 검증, state 복원, 토큰 교환에 쓴다). start 와 같은 값이어야 한다.
 * @param appHost         start(8080, ngrok)의 외부 공개 호스트(https). /connect 화면이 AUTHORIZE 폼을 이 호스트의
 *                        start 로 보내고, 콜백이 만드는 app/uninstalled 웹훅 구독 주소(라우터 릴레이)도 이 호스트다.
 * @param apiVersion      웹훅 구독을 만들 GraphQL Admin API 버전(예: 2026-07). 분기마다 새 버전이 나오고 12개월 지원된다.
 * @param returnAllowlist 절대 URL returnTo 로 복귀를 허용할 호스트 목록(콤마 구분). 상대 경로 returnTo 는
 *                        같은 호스트라 목록과 무관하게 허용한다. 오픈 리다이렉트를 막는다.
 */
@ConfigurationProperties("shopify")
public record ShopifyProperties(String apiKey, String apiSecret, String appHost, String apiVersion, String returnAllowlist) {
}
