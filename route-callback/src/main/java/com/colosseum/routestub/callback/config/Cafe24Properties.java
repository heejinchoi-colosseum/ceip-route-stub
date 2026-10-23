package com.colosseum.routestub.callback.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 콜백 모듈용 Cafe24 설정. 루트 모듈 설정에서 콜백이 쓰는 값만 남기고, /cafe24/connect 화면에 쓰는 값을 더했다.
 *
 * @param clientId        Client ID (토큰 교환 Basic 인증에 쓴다)
 * @param clientSecret    Client Secret (state 복원, 토큰 교환에 쓴다). start 와 같은 값이어야 한다.
 * @param appHost         start(8080, ngrok)의 외부 공개 호스트. /cafe24/connect 폼이 이 호스트의 start 로 간다.
 * @param mallId          /cafe24/connect 화면의 서비스 보유 값 mallId 기본값(테스트 쇼핑몰 ID).
 * @param returnAllowlist 절대 URL returnTo 로 복귀를 허용할 호스트 목록(콤마 구분). 상대 경로는 항상 허용한다.
 */
@ConfigurationProperties("cafe24")
public record Cafe24Properties(String clientId, String clientSecret, String appHost, String mallId,
        String returnAllowlist) {
}
