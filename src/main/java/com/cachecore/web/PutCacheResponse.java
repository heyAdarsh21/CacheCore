package com.cachecore.web;

/**
 * Response body for a successful put.
 *
 * @param key       stored key
 * @param value     stored value
 * @param ttlMillis requested TTL, or null for no expiration
 * @param created   true when the key was absent (HTTP 201), false when it was replaced (HTTP 200)
 */
public record PutCacheResponse(String key, String value, Long ttlMillis, boolean created) {
}
