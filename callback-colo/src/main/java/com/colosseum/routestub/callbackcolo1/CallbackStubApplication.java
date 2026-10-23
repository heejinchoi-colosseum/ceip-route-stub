package com.colosseum.routestub.callbackcolo1;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.colosseum.routestub.callbackcolo1.config.Cafe24Properties;
import com.colosseum.routestub.callbackcolo1.config.ShopifyProperties;

/**
 * colo1.0 용 Shopify 콜백 스텁 앱. callback-cgs(8081, cgs)의 복제본이고 8082 에서 뜬다. 두 번째 cloudflared 로
 * 외부 노출해, start 가 service=colo1.0 일 때 다른 콜백 호스트(CALLBACK_HOST_COLO1)로 보낸 redirect_uri 를 받는다.
 */
@SpringBootApplication
@EnableConfigurationProperties({ShopifyProperties.class, Cafe24Properties.class})
public class CallbackStubApplication {

    public static void main(String[] args) {
        SpringApplication.run(CallbackStubApplication.class, args);
    }
}
