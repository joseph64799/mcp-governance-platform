package io.github.mcpgov.identity.adapters;

import io.github.mcpgov.contracts.Model.Caller;
import io.github.mcpgov.contracts.PlatformException;
import io.github.mcpgov.identity.*;

/** An API key authenticates a stable application; rotating the key must not create a new grant subject. */
public class ApiKeyIdentityProvider implements IdentityProvider {
    @Override public Caller authenticate(AuthenticationRequest request) {
        return validateApiKey(AuthenticationRequest.credential(request.apiKey()), request);
    }
    protected Caller validateApiKey(String apiKey, AuthenticationRequest request) {
        // TODO: Verify the key digest, expiry/revocation and application status, then map its business ID.
        throw new PlatformException(501, "authentication_not_implemented");
    }
}
