package io.github.mcpgov.runtime;

import io.github.mcpgov.audit.*;
import io.github.mcpgov.connector.*;
import io.github.mcpgov.connector.sdk.*;
import io.github.mcpgov.control.*;
import io.github.mcpgov.security.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import java.time.Duration;

@SpringBootApplication
public class RuntimeApplication {
    public static void main(String[] args) { SpringApplication.run(RuntimeApplication.class, args); }
    @Bean @ConditionalOnMissingBean CredentialProvider credentials() { return new EnvironmentCredentialProvider(); }
    @Bean @ConditionalOnMissingBean McpConnector connector(CredentialProvider credentials) { return new SdkMcpConnector(credentials); }
    @Bean McpFacade facade(McpConnector connector) { return new McpFacade(connector); }
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
}
