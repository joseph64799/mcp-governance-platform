package io.github.mcpgov.identity;

import io.github.mcpgov.contracts.Model.ClientType;
import io.github.mcpgov.contracts.PlatformException;

/** Credentials stay at the gateway. Group, tenant and client type come from its catalog, never caller headers. */
public record AuthenticationRequest(String groupId, String tenant, ClientType clientType,
                                    String authorization, String token, String channel, String apiKey) {
    public String bearerToken() {
        if (authorization == null || !authorization.startsWith("Bearer ")) throw invalid();
        return credential(authorization.substring(7));
    }
    public static String credential(String value) {
        if (value == null || value.isBlank() || value.length() > 8192 ||
            value.chars().anyMatch(c -> c <= 32 || c >= 127)) throw invalid();
        return value;
    }
    public static PlatformException invalid() { return new PlatformException(401, "invalid_identity"); }
    @Override public String toString() { return "AuthenticationRequest[credentials=REDACTED]"; }
}
