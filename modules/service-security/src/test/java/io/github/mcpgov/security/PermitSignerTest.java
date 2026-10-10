package io.github.mcpgov.security;

import io.github.mcpgov.contracts.Model.*;
import io.github.mcpgov.contracts.PlatformException;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class PermitSignerTest {
    static final String SECRET = "unit-test-secret-only-never-a-deployment-credential";
    final Clock clock = Clock.fixed(Instant.ofEpochSecond(1000), ZoneOffset.UTC);
    InvocationPermit permit(long expiry) {
        return new InvocationPermit("nonce", new Caller("alice", "tenant", "issuer", Set.of(), ClientType.CONSUMER), "ops", 4, Set.of("tool"),
            1000, expiry, PermitSigner.hashRequest("POST", "/mcp/ops", "{}"));
    }
    @Test void authenticRequestIsAcceptedOnceAndCannotChangeBodyOrPath() {
        var signer = new PermitSigner(SECRET, clock);
        String token = signer.sign(permit(1015));
        assertThrows(PlatformException.class, () -> signer.verify(token, "POST", "/mcp/ops", "{ }"));
        assertThrows(PlatformException.class, () -> signer.verify(token, "POST", "/mcp/other", "{}"));
        assertEquals("alice", signer.verify(token, "POST", "/mcp/ops", "{}").caller().subject());
        assertThrows(PlatformException.class, () -> signer.verify(token, "POST", "/mcp/ops", "{}"));
    }
    @Test void tamperedExpiredAndOverlongPermitsAreRejected() {
        var signer = new PermitSigner(SECRET, clock);
        String token = signer.sign(permit(1015));
        assertThrows(PlatformException.class, () -> signer.verify("x" + token.substring(1), "POST", "/mcp/ops", "{}"));
        assertThrows(PlatformException.class, () -> signer.verify(signer.sign(permit(999)), "POST", "/mcp/ops", "{}"));
        assertThrows(PlatformException.class, () -> signer.verify(signer.sign(permit(1040)), "POST", "/mcp/ops", "{}"));
        assertThrows(IllegalArgumentException.class, () -> new PermitSigner("short"));
    }
}
