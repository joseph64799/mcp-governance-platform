package io.github.mcpgov.testing;

import io.github.mcpgov.contracts.Model.*;
import io.github.mcpgov.gateway.GatewayApplication;
import io.github.mcpgov.identity.*;
import org.springframework.boot.SpringApplication;
import java.util.*;

/** Smoke-test launcher only: target/test-classes is never packaged in the deployable gateway JAR. */
public final class GatewayFixture {
    public static void main(String[] args) {
        Map<String,Caller> identities = Map.of(
            required("TEST_ALICE_TOKEN"), new Caller("alice", "fixture", "fixture", Set.of("operator"), ClientType.CONSUMER),
            required("TEST_BOB_TOKEN"), new Caller("bob", "fixture", "fixture", Set.of("reviewer"), ClientType.CONSUMER),
            required("TEST_EMPLOYEE_TOKEN"), new Caller("alice", "fixture", "fixture", Set.of("operator"), ClientType.EMPLOYEE),
            required("TEST_APP_TOKEN"), new Caller("alice", "fixture", "fixture", Set.of(), ClientType.APPLICATION));
        IdentityProvider provider = request -> {
            Caller caller = identities.get(request.bearerToken());
            if (caller == null || caller.clientType() != request.clientType()) throw AuthenticationRequest.invalid();
            return caller;
        };
        SpringApplication app = new SpringApplication(GatewayApplication.class);
        app.addInitializers(context -> context.getBeanFactory().registerSingleton("fixtureIdentity", provider));
        app.run(args);
    }
    private static String required(String name) { return Objects.requireNonNull(System.getenv(name), name); }
}
