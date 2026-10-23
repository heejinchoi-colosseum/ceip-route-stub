package com.colosseum.routestub.callbackcgs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.colosseum.routestub.callbackcgs.config.Cafe24Properties;
import com.colosseum.routestub.callbackcgs.config.ShopifyProperties;

/**
 * Shopify 콜백(redirect_uri)만 받는 스텁 앱. 8081 에서 뜨고 cloudflared 로 외부 노출한다.
 * start(8080, ngrok)와 다른 호스트에서 콜백을 받아도 인증이 성립하는지 검증하기 위한 별도 모듈이다.
 */
@SpringBootApplication
@EnableConfigurationProperties({ShopifyProperties.class, Cafe24Properties.class})
public class CallbackStubApplication {

    public static void main(String[] args) {
        SpringApplication.run(CallbackStubApplication.class, args);
    }
}
