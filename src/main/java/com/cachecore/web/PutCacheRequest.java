package com.cachecore.web;

/**
 * Request body for {@code PUT /api/v1/cache/{key}}.
 *
 * @param value     value to store; required
 * @param ttlMillis optional time-to-live in milliseconds; omit or send null for no expiration
 */
public record PutCacheRequest(String value, Long ttlMillis) {
}
