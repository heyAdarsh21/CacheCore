package com.cachecore.engine;

/**
 * Validation limits shared by the cache engine and the REST layer.
 *
 * <p>These values form part of the public API contract and are documented in
 * the README.</p>
 */
public final class CacheLimits {

    /** Maximum number of characters (UTF-16 code units) in a key. */
    public static final int MAX_KEY_LENGTH = 512;

    /** Maximum number of characters (UTF-16 code units) in a value. */
    public static final int MAX_VALUE_LENGTH = 1_048_576;

    /** Maximum TTL accepted by the service: 365 days in milliseconds. */
    public static final long MAX_TTL_MILLIS = 31_536_000_000L;

    /** Maximum declared JSON request body size: 2 MiB. */
    public static final int MAX_REQUEST_BYTES = 2 * 1024 * 1024;

    private CacheLimits() {
    }
}
