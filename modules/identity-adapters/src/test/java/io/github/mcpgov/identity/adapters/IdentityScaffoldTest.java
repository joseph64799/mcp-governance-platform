package io.github.mcpgov.identity.adapters;

import io.github.mcpgov.contracts.Model.*;
import io.github.mcpgov.contracts.PlatformException;
import io.github.mcpgov.identity.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** The production skeleton must refuse credentials until its external verification is implemented. */
class IdentityScaffoldTest {
    final Clock clock = Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC);
    AuthenticationRequest request(ClientType type, String token, String channel) {
        return new AuthenticationRequest("orders", "tenant", type, "Bearer employee-secret", token, channel, "application-secret");
    }
    @Test void consumerBuildsExistingRedisKeysButNeverTrustsParsedUserWithoutVerification() {
        List<String> keys = new ArrayList<>();
        ConsumerIdentityProvider provider = new ConsumerIdentityProvider(clock) {
            @Override protected boolean validateLoginState(String key, String token) { keys.add(key); return false; }
        };
        for (String token : List.of("42:2000:random", "42:2000:random:app")) {
            assertEquals(401, assertThrows(PlatformException.class,
                () -> provider.authenticate(request(ClientType.CONSUMER, token, "ios"))).status());
        }
        assertEquals(List.of("AUTH:USERCENTER:42:ios", "AUTH:USERCENTER:42:ios:app"), keys);
        keys.clear();
        for (String token : List.of("42", "42:invalid:random", "42:999:random", "42:2000:", "42:2000:random:")) {
            assertEquals(401, assertThrows(PlatformException.class,
                () -> provider.authenticate(request(ClientType.CONSUMER, token, "ios"))).status());
        }
        assertTrue(keys.isEmpty(), "Malformed/expired tokens must never access the login store");
    }
    @Test void allUnimplementedAdaptersFailClosedAndDoNotEchoSecrets() {
        var providers = Map.<ClientType,IdentityProvider>of(ClientType.CONSUMER, new ConsumerIdentityProvider(clock),
            ClientType.EMPLOYEE, new EmployeeIdentityProvider(), ClientType.APPLICATION, new ApiKeyIdentityProvider());
        var router = new RoutingIdentityProvider(providers);
        providers.keySet().forEach(type -> {
            var input = request(type, "42:2000:consumer-secret", "ios");
            var error = assertThrows(PlatformException.class, () -> router.authenticate(input));
            assertEquals(501, error.status()); assertEquals("authentication_not_implemented", error.code());
            assertFalse(input.toString().contains("secret"));
            var missing = new AuthenticationRequest("orders", "tenant", type, null, null, null, null);
            assertEquals(401, assertThrows(PlatformException.class, () -> router.authenticate(missing)).status());
        });
    }
    @Test void routingUsesCatalogTypeAndRejectsAnAdapterReturningAnotherDomain() {
        var router = new RoutingIdentityProvider(Map.of(ClientType.EMPLOYEE,
            request -> new Caller("42", "tenant", "company", Set.of(), ClientType.CONSUMER)));
        assertEquals(401, assertThrows(PlatformException.class,
            () -> router.authenticate(request(ClientType.EMPLOYEE, null, null))).status());
        assertEquals(503, assertThrows(PlatformException.class,
            () -> router.authenticate(request(null, null, null))).status());
    }
}
