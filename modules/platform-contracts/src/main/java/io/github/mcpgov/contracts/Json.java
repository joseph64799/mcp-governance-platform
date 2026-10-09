package io.github.mcpgov.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Wire serialization is isolated from Spring's HTTP codec and from the MCP SDK codec. */
public final class Json {
    public static final ObjectMapper MAPPER = JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .serializationInclusion(JsonInclude.Include.NON_NULL).build();
    private Json() {}
    public static String write(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalArgumentException("Cannot encode payload", e); }
    }
    public static <T> T read(String text, Class<T> type) {
        try { return MAPPER.readValue(text, type); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid JSON payload", e); }
    }
    public static Map<String,Object> object(String text) {
        try { return MAPPER.readValue(text, new TypeReference<Map<String,Object>>() {}); }
        catch (Exception e) { throw new IllegalArgumentException("Expected a JSON object", e); }
    }
    public static Map<String,Object> map(Object value) {
        return MAPPER.convertValue(value, new TypeReference<Map<String,Object>>() {});
    }
    public static String sha256(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    /** Deep freeze prevents a consumer from changing a published snapshot through nested maps. */
    @SuppressWarnings("unchecked")
    public static <T> T freeze(T value) {
        if (value instanceof Map<?,?> source) {
            Map<String,Object> copy = new TreeMap<>();
            source.forEach((k,v) -> copy.put(String.valueOf(k), freeze(v)));
            return (T) Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> source) return (T) source.stream().map(Json::freeze).toList();
        return value;
    }
}
