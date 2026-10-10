package io.github.mcpgov.identity.adapters;

import io.github.mcpgov.contracts.Model.Caller;
import io.github.mcpgov.contracts.PlatformException;
import io.github.mcpgov.identity.*;
import java.util.Map;

/** Enterprise login is external; this adapter verifies its credential and maps it to a business employee. */
public class EmployeeIdentityProvider implements IdentityProvider {
    @Override public Caller authenticate(AuthenticationRequest request) {
        return mapEmployee(verifyCredential(request.bearerToken()), request);
    }
    protected Map<String,Object> verifyCredential(String credential) {
        // TODO: Validate with DingTalk or the configured enterprise identity service. No login UI here.
        throw new PlatformException(501, "authentication_not_implemented");
    }
    protected Caller mapEmployee(Map<String,Object> verifiedIdentity, AuthenticationRequest request) {
        // TODO: Resolve the business employee ID, tenant and roles; external account IDs are not employee IDs.
        throw new PlatformException(501, "authentication_not_implemented");
    }
}
