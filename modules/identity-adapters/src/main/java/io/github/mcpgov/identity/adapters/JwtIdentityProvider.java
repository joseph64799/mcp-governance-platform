package io.github.mcpgov.identity.adapters;

import io.github.mcpgov.contracts.PlatformException;
import io.github.mcpgov.contracts.Model.Caller;
import io.github.mcpgov.identity.IdentityMapper;
import io.github.mcpgov.identity.IdentityProvider;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;

/** JWT resource-server adapter. Login/code exchange remains with the enterprise identity system. */
public final class JwtIdentityProvider implements IdentityProvider {
    private final JwtDecoder decoder;
    private final IdentityMapper mapper;

    public JwtIdentityProvider(String issuer, String jwksUri, String audience, IdentityMapper mapper) {
        if (issuer == null || issuer.isBlank() || audience == null || audience.isBlank())
            throw new IllegalArgumentException("JWT issuer and audience must be configured");
        var nimbus = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
        OAuth2TokenValidator<Jwt> audienceValidator = token ->
            token.getAudience().contains(audience) ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer), audienceValidator));
        this.decoder = nimbus;
        this.mapper = mapper;
    }
    /** Decoder injection also makes real signature, expiry and audience validation testable. */
    public JwtIdentityProvider(JwtDecoder decoder, IdentityMapper mapper) {
        this.decoder = decoder; this.mapper = mapper;
    }
    @Override public Caller authenticate(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer "))
            throw new PlatformException(401, "authentication_required");
        try {
            Jwt jwt = decoder.decode(authorization.substring(7));
            if (jwt.getIssuer() == null || jwt.getSubject() == null)
                throw new PlatformException(401, "invalid_identity");
            return mapper.map(jwt.getIssuer().toString(), jwt.getSubject(), jwt.getClaims());
        } catch (RuntimeException e) {
            throw new PlatformException(401, "invalid_identity");
        }
    }
}
