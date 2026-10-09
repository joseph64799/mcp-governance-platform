package io.github.mcpgov.discoveryapp;

import io.github.mcpgov.audit.*;
import io.github.mcpgov.connector.*;
import io.github.mcpgov.connector.sdk.*;
import io.github.mcpgov.control.*;
import io.github.mcpgov.discovery.DiscoveryCoordinator;
import io.github.mcpgov.security.ServiceTokens;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DiscoveryApplication {
    public static void main(String[] args) { SpringApplication.run(DiscoveryApplication.class, args); }
    @Bean ControlPlaneClient control(Environment env) {
        return new ControlPlaneClient(env.getRequiredProperty("mcp.control.url"),
            ServiceTokens.configured(env.getProperty("mcp.control.read-token")),
            ServiceTokens.configured(env.getProperty("mcp.control.discovery-token")));
    }
    @Bean McpConnector connector() { return new SdkMcpConnector(new EnvironmentCredentialProvider()); }
    @Bean DiscoveryCoordinator coordinator(ControlPlaneClient control, McpConnector connector) {
        return new DiscoveryCoordinator(control, connector);
    }
    @Bean AuditSink audit() { return new LoggingAuditSink(); }
}
