package io.github.mcpgov.connector.sdk;
import io.github.mcpgov.connector.CredentialProvider;
import java.util.Map;

/** Only explicit MCP_UPSTREAM_* references can access environment secrets. */
public final class EnvironmentCredentialProvider implements CredentialProvider {
    @Override public Map<String,String> headers(String reference) {
        if (reference == null || reference.isBlank()) return Map.of();
        if (!reference.matches("MCP_UPSTREAM_[A-Z0-9_]+")) throw new IllegalArgumentException("Invalid credential reference");
        String value = System.getenv(reference);
        if (value == null || value.isBlank()) throw new IllegalStateException("Upstream credential is unavailable");
        return Map.of("Authorization", value);
    }
}
