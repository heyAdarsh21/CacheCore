package com.cachecore.web;

import com.cachecore.engine.CacheEngine;
import com.cachecore.engine.CacheStats;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cache")
public class CacheController {

    /**
     * Reserved for the statistics route. Rejecting it on entry routes keeps the
     * route namespace unambiguous: {@code GET .../cache/stats} is always a
     * statistics request, never a lookup of a key named "stats".
     */
    static final String RESERVED_KEY = "stats";

    private final CacheEngine cacheEngine;

    public CacheController(CacheEngine cacheEngine) {
        this.cacheEngine = cacheEngine;
    }

    @PutMapping("/{key}")
    public ResponseEntity<PutCacheResponse> put(@PathVariable String key,
                                                @RequestBody PutCacheRequest request) {
        rejectReservedKey(key);
        boolean created = cacheEngine.put(key, request.value(), request.ttlMillis());
        PutCacheResponse body = new PutCacheResponse(key, request.value(), request.ttlMillis(), created);
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK).body(body);
    }

    @GetMapping("/{key}")
    public CacheEntryResponse get(@PathVariable String key) {
        rejectReservedKey(key);
        String value = cacheEngine.get(key);
        if (value == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CACHE_MISS",
                    "No cache entry exists for key '" + key + "'");
        }
        return new CacheEntryResponse(key, value);
    }

    @DeleteMapping("/{key}")
    public ResponseEntity<Void> delete(@PathVariable String key) {
        rejectReservedKey(key);
        if (!cacheEngine.delete(key)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CACHE_MISS",
                    "No cache entry exists for key '" + key + "'");
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * A literal mapping so "stats" cannot be captured by {@code /{key}}.
     */
    @GetMapping("/stats")
    public CacheStats stats() {
        return cacheEngine.stats();
    }

    private static void rejectReservedKey(String key) {
        if (RESERVED_KEY.equals(key)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RESERVED_KEY",
                    "The key 'stats' is reserved for the statistics endpoint");
        }
    }
}
