package com.cachecore.web;

/**
 * Response body for a successful cache read.
 */
public record CacheEntryResponse(String key, String value) {
}
