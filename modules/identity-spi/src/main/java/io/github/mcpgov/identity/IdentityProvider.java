package io.github.mcpgov.identity;

import io.github.mcpgov.contracts.Model.Caller;

/** Authenticate a credential. Implementations must verify it before creating a Caller. */
@FunctionalInterface
public interface IdentityProvider {
    Caller authenticate(AuthenticationRequest request);
}
