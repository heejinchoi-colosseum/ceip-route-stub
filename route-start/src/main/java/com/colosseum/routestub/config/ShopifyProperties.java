package com.colosseum.routestub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Shopify 앱 설정. 값은 환경변수(application.yml 의 placeholder)에서 주입한다.
 *
 * @param apiKey          Client ID
 * @param apiSecret       Client secret
 * @param appHost         이 서비스(start)의 외부 공개 호스트(https). App URL 호스트다.
 * @param callbackHost    Shopify 가 code 를 되던지는 콜백(redirect_uri) 호스트(https). App URL 과 다른 호스트를
 *                        검증하려고 appHost 와 분리했다. 미설정 시 appHost 로 폴백해 기존 동작을 유지한다.
 * @param callbackHostCgs      service=cgs 일 때 쓰는 콜백 호스트. 서비스마다 다른 redirect_uri 를 검증하려고 둔다.
 *                             미설정 시 callbackHost 로 폴백한다. 등록한 Allowed redirection URL 중 하나여야 한다.
 * @param callbackHostColo1    service=colo1.0 일 때 쓰는 콜백 호스트. 미설정 시 callbackHost.
 * @param callbackHostColoNova service=colo-nova 일 때 쓰는 콜백 호스트. 미설정 시 callbackHost.
 * @param complianceService    개인정보 웹훅 3종(customers/data_request, customers/redact, shop/redact)의 접수 담당 service.
 *                             이 세 topic 은 앱 설정에 주소가 하나뿐이라 구독 주소 쿼리에 service 를 실을 수 없어, 릴레이가
 *                             이 값으로 전달처를 고른다(앱별 공통 접수 담당). 기본 cgs.
 * @param scopes          콤마로 구분한 요청 스코프
 * @param returnAllowlist 절대 URL returnTo 로 복귀를 허용할 호스트 목록(콤마 구분). 상대 경로 returnTo 는
 *                        같은 호스트라 목록과 무관하게 허용한다. 오픈 리다이렉트를 막는다.
 */
@ConfigurationProperties("shopify")
public record ShopifyProperties(String apiKey, String apiSecret, String appHost, String callbackHost,
        String callbackHostCgs, String callbackHostColo1, String callbackHostColoNova, String complianceService,
        String scopes, String returnAllowlist) {
}
