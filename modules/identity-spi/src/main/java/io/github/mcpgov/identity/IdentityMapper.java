package io.github.mcpgov.identity;

import io.github.mcpgov.contracts.Model.Caller;
import java.util.Map;

/** Enterprise-specific account mapping; do not merge users just because their emails match. */
@FunctionalInterface
public interface IdentityMapper {
    Caller map(String verifiedIssuer, String verifiedSubject, Map<String,Object> verifiedClaims);
}
