package com.colosseum.routestub.shopify;

import com.colosseum.routestub.config.ShopifyProperties;

/**
 * service 별 소비 서비스 호스트를 정한다. start 는 이 호스트로 redirect_uri 를 만들고, 웹훅 릴레이는 이 호스트로
 * 검증한 웹훅을 전달한다. 서비스별 설정(callback-host-cgs 등)이 비어 있으면 공통 callback-host 로 폴백한다.
 * 모르는 service 는 거부한다.
 *
 * <p>콜백 호스트는 채널과 무관하다(같은 소비 서비스 앱, 같은 터널). Cafe24 start 도 이 클래스로 redirect_uri 호스트를 고른다.
 */
public final class ServiceRouting {

    private ServiceRouting() {
    }

    public static String callbackHost(ShopifyProperties properties, String service) {
        String host = switch (service == null ? "" : service) {
            case "cgs" -> properties.callbackHostCgs();
            case "colo1.0" -> properties.callbackHostColo1();
            case "colo-nova" -> properties.callbackHostColoNova();
            default -> throw new IllegalArgumentException("알 수 없는 service 다: " + service);
        };
        if (host == null || host.isBlank()) {
            host = properties.callbackHost();
        }
        return host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
    }
}
