package io.github.mcpgov.security;

import io.github.mcpgov.contracts.PlatformException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Bootstrap/internal API credentials; distinct scopes use distinct configured secrets. */
public final class ServiceTokens {
    private ServiceTokens() {}
    public static String configured(String value) {
        if (value == null || value.length() < 32) throw new IllegalArgumentException("Service secret must be at least 32 characters");
        return value;
    }
    public static void require(String header, String expected) {
        configured(expected);
        if (header == null || !header.startsWith("Bearer ") || !MessageDigest.isEqual(
            header.substring(7).getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8)))
            throw new PlatformException(401, "authentication_required");
    }
}
