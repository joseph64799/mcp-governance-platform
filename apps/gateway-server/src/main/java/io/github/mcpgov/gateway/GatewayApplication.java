package io.github.mcpgov.gateway;

import io.github.mcpgov.audit.*;
import io.github.mcpgov.authorization.*;
import io.github.mcpgov.contracts.Model.Caller;
import io.github.mcpgov.control.*;
import io.github.mcpgov.identity.*;
import io.github.mcpgov.identity.adapters.*;
import io.github.mcpgov.security.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@SpringBootApplication
public class GatewayApplication {
    public static void main(String[] args) { SpringApplication.run(GatewayApplication.class, args); }
    @Bean RouteLocator mcpRoutes(RouteLocatorBuilder builder, Environment env) {
        return builder.routes().route("mcp-runtime", route -> route.path("/mcp/**")
            .uri(env.getRequiredProperty("mcp.runtime.url"))).build();
    }
    @Bean ControlPlaneClient control(Environment env) {
        return new ControlPlaneClient(env.getRequiredProperty("mcp.control.url"),
            ServiceTokens.configured(env.getProperty("mcp.control.read-token")), "");
    }
    @Bean(destroyMethod="close") SnapshotCache snapshots(ControlPlaneClient control, Environment env) {
        return new SnapshotCache(control::snapshot,
            Duration.ofMillis(env.getProperty("mcp.snapshot.refresh-ms", Long.class, 2000L)),
            Duration.ofMillis(env.getProperty("mcp.snapshot.max-age-ms", Long.class, 15000L)));
    }
    @Bean PermitSigner permits(Environment env) { return new PermitSigner(env.getProperty("mcp.runtime.signing-secret")); }
    @Bean @ConditionalOnMissingBean AuditSink audit() { return new LoggingAuditSink(); }
    @Bean @ConditionalOnMissingBean ResourceAccessChecker resourceAccess() { return ResourceAccessChecker.denyProtectedResources(); }
    @Bean @ConditionalOnMissingBean AuthorizationProvider authorization(ResourceAccessChecker resources) {
        return new DefaultAuthorizationProvider(resources);
    }
    @Bean @ConditionalOnMissingBean IdentityMapper identityMapper(Environment env) {
        return (issuer, subject, claims) -> {
            String tenant = env.getRequiredProperty("mcp.identity.tenant");
            Object claim = claims.get(env.getProperty("mcp.identity.roles-claim", "roles"));
            Set<String> roles = claim instanceof Collection<?> values
                ? values.stream().filter(String.class::isInstance).map(String.class::cast).collect(Collectors.toUnmodifiableSet()) : Set.of();
            return new Caller(subject, tenant, issuer, roles);
        };
    }
    @Bean @ConditionalOnMissingBean IdentityProvider identity(Environment env, IdentityMapper mapper) {
        String mode = env.getProperty("mcp.identity.mode", "jwt");
        if ("demo".equals(mode)) {
            if (!Arrays.asList(env.getActiveProfiles()).contains("demo") ||
                !"127.0.0.1".equals(env.getProperty("server.address")))
                throw new IllegalArgumentException("Demo identity requires the demo profile and loopback binding");
            return new DemoIdentityProvider(Map.of(
                env.getRequiredProperty("mcp.identity.demo-alice-token"), new Caller("alice", "demo", "demo", Set.of("operator")),
                env.getRequiredProperty("mcp.identity.demo-bob-token"), new Caller("bob", "demo", "demo", Set.of("reviewer"))));
        }
        if (!"jwt".equals(mode)) throw new IllegalArgumentException("Unknown identity mode");
        String tenant = env.getProperty("mcp.identity.tenant");
        if (tenant == null || tenant.isBlank()) throw new IllegalArgumentException("JWT tenant mapping must be configured");
        return new JwtIdentityProvider(env.getRequiredProperty("mcp.identity.issuer"),
            env.getRequiredProperty("mcp.identity.jwks-uri"), env.getRequiredProperty("mcp.identity.audience"), mapper);
    }
}
