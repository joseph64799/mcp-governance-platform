package io.github.mcpgov.identity.adapters;

import io.github.mcpgov.contracts.Model.*;
import io.github.mcpgov.contracts.PlatformException;
import io.github.mcpgov.identity.*;
import java.time.Clock;
import java.util.Set;

/** Mirrors aomi-base-gateway's token/channel flow without accepting an unverified user ID. */
public class ConsumerIdentityProvider implements IdentityProvider {
    private final Clock clock;
    public ConsumerIdentityProvider() { this(Clock.systemUTC()); }
    public ConsumerIdentityProvider(Clock clock) { this.clock = clock; }

    @Override public Caller authenticate(AuthenticationRequest request) {
        String token = AuthenticationRequest.credential(request.token());
        String channel = AuthenticationRequest.credential(request.channel());
        String[] parts = token.split(":", -1);
        if ((parts.length != 3 && parts.length != 4) || !keyPart(parts[0]) ||
            !keyPart(channel) || parts[2].isBlank() || (parts.length == 4 && !keyPart(parts[3])))
            throw AuthenticationRequest.invalid();
        long expiresAt;
        try { expiresAt = Long.parseLong(parts[1]); }
        catch (NumberFormatException e) { throw AuthenticationRequest.invalid(); }
        if (expiresAt <= clock.millis()) throw AuthenticationRequest.invalid();
        String redisKey = "AUTH:USERCENTER:" + parts[0] + ":" + channel +
            (parts.length == 4 ? ":" + parts[3] : "");
        if (!validateLoginState(redisKey, token)) throw AuthenticationRequest.invalid();
        return new Caller(parts[0], request.tenant(), "aomi-usercenter", Set.of(), ClientType.CONSUMER);
    }

    protected boolean validateLoginState(String redisKey, String presentedToken) {
        // TODO: Read TokenData from the existing Redis and compare accessToken. Missing/revoked tokens fail;
        // Redis failure must not fall back to the user ID parsed from the untrusted token.
        throw new PlatformException(501, "authentication_not_implemented");
    }
    private boolean keyPart(String value) { return value.matches("[A-Za-z0-9_.-]{1,128}"); }
}
