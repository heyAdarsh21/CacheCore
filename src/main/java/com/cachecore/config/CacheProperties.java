package com.cachecore.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cachecore")
public class CacheProperties {

    /**
     * Maximum number of live entries held by the cache. Must be positive.
     */
    private int maxEntries = 10_000;

    public int getMaxEntries() {
        return maxEntries;
    }

    public void setMaxEntries(int maxEntries) {
        this.maxEntries = maxEntries;
    }
}
