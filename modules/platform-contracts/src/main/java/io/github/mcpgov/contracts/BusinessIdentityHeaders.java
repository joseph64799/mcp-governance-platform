package io.github.mcpgov.contracts;

import io.github.mcpgov.contracts.Model.*;
import java.util.*;

/** Bridges can reuse their user/third-party annotations after verifying the platform's service credential. */
public final class BusinessIdentityHeaders {
    public static final String USER_ID = "X-AOMI-AUTH-UID";
    public static final String APP_ID = "x-aomi-third-appid";
    public static final String CLIENT_TYPE = "X-Mcp-Client-Type";
    private BusinessIdentityHeaders() {}
    public static boolean isIdentityHeader(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        return key.startsWith("x-aomi-") || key.equals("current-user-aomi-merchant") ||
            key.equals("auth-user-id") || key.equals("x-mcp-client-type");
    }
    public static Map<String,String> from(Caller caller) {
        Objects.requireNonNull(caller, "Authenticated caller is required");
        // IDs become HTTP headers. Reject control/non-ASCII characters rather than normalizing an identity.
        if (caller.subject().length() > 512 || caller.subject().chars().anyMatch(c -> c <= 32 || c >= 127))
            throw new PlatformException(400, "invalid_business_identity");
        return Map.of(CLIENT_TYPE, caller.clientType().name(),
            caller.clientType() == ClientType.APPLICATION ? APP_ID : USER_ID, caller.subject());
    }
}
