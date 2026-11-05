package com.cachecore.config;

import com.cachecore.engine.CacheEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CacheConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CacheConfiguration.class);

    @Bean
    public CacheEngine cacheEngine(CacheProperties properties) {
        return new CacheEngine(properties.getMaxEntries());
    }

    @Bean
    ApplicationRunner cacheConfigurationLogger(CacheEngine cacheEngine, CacheProperties properties) {
        return args -> log.info("CacheCore cache initialized: maxEntries={}", properties.getMaxEntries());
    }
}
