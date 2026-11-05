package com.cachecore.web;

import com.cachecore.engine.CacheLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CacheControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void putCreatesThenReplacesAndDeleteRemoves() throws Exception {
        String key = uniqueKey();

        mockMvc.perform(put(path(key))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"first\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value(key))
                .andExpect(jsonPath("$.value").value("first"))
                .andExpect(jsonPath("$.created").value(true));

        mockMvc.perform(get(path(key)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value(key))
                .andExpect(jsonPath("$.value").value("first"));

        mockMvc.perform(put(path(key))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"second\",\"ttlMillis\":60000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("second"))
                .andExpect(jsonPath("$.created").value(false))
                .andExpect(jsonPath("$.ttlMillis").value(60000));

        mockMvc.perform(delete(path(key)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(path(key)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CACHE_MISS"));
    }

    @Test
    void missingKeyAndMissingDeleteBothReturnCacheMiss() throws Exception {
        String key = uniqueKey();

        mockMvc.perform(get(path(key)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("CACHE_MISS"));

        mockMvc.perform(delete(path(key)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CACHE_MISS"));
    }

    @Test
    void ttlExpiresAnEntryAtTheHttpLayer() throws Exception {
        String key = uniqueKey();

        mockMvc.perform(put(path(key))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"short-lived\",\"ttlMillis\":60}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get(path(key)))
                .andExpect(status().isOk());

        Thread.sleep(150);

        mockMvc.perform(get(path(key)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CACHE_MISS"));
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, 31_536_000_001L})
    void invalidTtlValuesReturnBadRequest(long ttlMillis) throws Exception {
        mockMvc.perform(put(path(uniqueKey()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"v\",\"ttlMillis\":" + ttlMillis + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TTL"));
    }

    @Test
    void nonNumericTtlIsRejectedAsMalformedJson() throws Exception {
        mockMvc.perform(put(path(uniqueKey()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"v\",\"ttlMillis\":\"soon\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void missingOrNullValueIsRejected() throws Exception {
        mockMvc.perform(put(path(uniqueKey()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VALUE"));

        mockMvc.perform(put(path(uniqueKey()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VALUE"));
    }

    @Test
    void malformedJsonReturnsBadRequest() throws Exception {
        mockMvc.perform(put(path(uniqueKey()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void oversizedValueIsRejected() throws Exception {
        String body = "{\"value\":\"" + "x".repeat(CacheLimits.MAX_VALUE_LENGTH + 1) + "\"}";

        mockMvc.perform(put(path(uniqueKey()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VALUE"));
    }

    @Test
    void oversizedRequestIsRejectedBeforeParsing() throws Exception {
        String body = "x".repeat(CacheLimits.MAX_REQUEST_BYTES + 1);

        mockMvc.perform(put(path(uniqueKey()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("REQUEST_TOO_LARGE"));
    }

    @Test
    void invalidKeysAreRejected() throws Exception {
        mockMvc.perform(put(path("k".repeat(CacheLimits.MAX_KEY_LENGTH + 1)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"v\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_KEY"));

        mockMvc.perform(get("/api/v1/cache/ "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_KEY"));
    }

    @Test
    void statsRouteReturnsStatisticsAndKeyIsReserved() throws Exception {
        mockMvc.perform(get("/api/v1/cache/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hits").isNumber())
                .andExpect(jsonPath("$.misses").isNumber())
                .andExpect(jsonPath("$.evictions").isNumber())
                .andExpect(jsonPath("$.expirations").isNumber())
                .andExpect(jsonPath("$.size").isNumber())
                .andExpect(jsonPath("$.maxEntries").isNumber())
                .andExpect(jsonPath("$.hitRatio").isNumber())
                .andExpect(jsonPath("$.size").value(greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.size").value(lessThanOrEqualTo(10_000)))
                .andExpect(jsonPath("$.value").doesNotExist());

        mockMvc.perform(put("/api/v1/cache/stats")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"not-a-cache-entry\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESERVED_KEY"));

        mockMvc.perform(delete("/api/v1/cache/stats"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESERVED_KEY"));
    }

    @Test
    void healthEndpointIsAvailable() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void unsupportedContentTypeReturnsUnsupportedMediaType() throws Exception {
        mockMvc.perform(put(path(uniqueKey()))
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("value"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    private static String uniqueKey() {
        return "test-" + UUID.randomUUID();
    }

    private static String path(String key) {
        return "/api/v1/cache/" + UriUtils.encodePathSegment(key, StandardCharsets.UTF_8);
    }
}
