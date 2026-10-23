package com.colosseum.routestub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.colosseum.routestub.config.Cafe24Properties;
import com.colosseum.routestub.config.ShopifyProperties;

@SpringBootApplication
@EnableConfigurationProperties({ShopifyProperties.class, Cafe24Properties.class})
public class RouteStubApplication {

    public static void main(String[] args) {
        SpringApplication.run(RouteStubApplication.class, args);
    }
}
