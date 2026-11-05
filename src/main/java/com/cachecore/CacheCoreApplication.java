package com.cachecore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CacheCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(CacheCoreApplication.class, args);
    }
}
