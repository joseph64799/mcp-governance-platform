package io.github.mcpgov.gateway;

import io.github.mcpgov.audit.*;
import io.github.mcpgov.authorization.*;
import io.github.mcpgov.contracts.Model.ClientType;
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
    @Bean @ConditionalOnMissingBean IdentityProvider identity() {
        return new RoutingIdentityProvider(Map.of(
            ClientType.CONSUMER, new ConsumerIdentityProvider(),
            ClientType.EMPLOYEE, new EmployeeIdentityProvider(),
            ClientType.APPLICATION, new ApiKeyIdentityProvider()));
    }
}
