package com.cachecore.engine;

/**
 * Raised when a cache operation receives an argument that violates the
 * documented key, value, or TTL rules.
 */
public final class CacheValidationException extends RuntimeException {

    private final String code;

    public CacheValidationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
