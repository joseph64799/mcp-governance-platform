package io.github.mcpgov.admin;

import io.github.mcpgov.audit.*;
import io.github.mcpgov.catalog.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@SpringBootApplication
public class AdminApplication {
    public static void main(String[] args) { SpringApplication.run(AdminApplication.class, args); }
    @Bean CatalogService catalogService(Environment env) {
        Set<String> hosts = Arrays.stream(env.getRequiredProperty("mcp.admin.allowed-upstream-hosts").split(","))
            .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
        return new CatalogService(new FileCatalogStore(Path.of(env.getRequiredProperty("mcp.admin.catalog-file"))), hosts);
    }
    @Bean AuditSink auditSink() { return new LoggingAuditSink(); }
}
