package io.github.mcpgov.identity.adapters;

import io.github.mcpgov.contracts.Model.Caller;
import io.github.mcpgov.contracts.PlatformException;
import io.github.mcpgov.identity.IdentityProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/** Only assembled by the explicit demo profile; never fall back to this provider on JWT failure. */
public final class DemoIdentityProvider implements IdentityProvider {
    private final Map<String,Caller> credentials;
    public DemoIdentityProvider(Map<String,Caller> credentials) {
        credentials.keySet().forEach(token -> { if (token.length() < 32) throw new IllegalArgumentException("Demo tokens must be random"); });
        this.credentials = Map.copyOf(credentials);
    }
    @Override public Caller authenticate(String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            byte[] presented = authorization.substring(7).getBytes(StandardCharsets.UTF_8);
            for (var entry : credentials.entrySet()) {
                if (MessageDigest.isEqual(presented, entry.getKey().getBytes(StandardCharsets.UTF_8))) return entry.getValue();
            }
        }
        throw new PlatformException(401, "invalid_identity");
    }
}
