package io.github.mcpgov.identity.adapters;

import io.github.mcpgov.contracts.Model.*;
import io.github.mcpgov.contracts.PlatformException;
import io.github.mcpgov.identity.*;
import java.util.Map;

/** Selects authentication from the catalog's client type; never guesses it from an unverified credential. */
public final class RoutingIdentityProvider implements IdentityProvider {
    private final Map<ClientType,IdentityProvider> providers;
    public RoutingIdentityProvider(Map<ClientType,IdentityProvider> providers) { this.providers = Map.copyOf(providers); }
    @Override public Caller authenticate(AuthenticationRequest request) {
        if (request.clientType() == null) throw new PlatformException(503, "group_identity_not_configured");
        IdentityProvider provider = providers.get(request.clientType());
        if (provider == null) throw new PlatformException(501, "authentication_not_implemented");
        Caller caller = provider.authenticate(request);
        if (caller == null || caller.clientType() != request.clientType() || !caller.tenant().equals(request.tenant()))
            throw AuthenticationRequest.invalid();
        return caller;
    }
}
