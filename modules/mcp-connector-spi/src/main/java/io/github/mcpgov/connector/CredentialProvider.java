package io.github.mcpgov.connector;
import java.util.Map;

/** Separate outbound service credentials from caller identity and inbound authentication. */
@FunctionalInterface
public interface CredentialProvider {
    Map<String,String> headers(String credentialRef);
}
