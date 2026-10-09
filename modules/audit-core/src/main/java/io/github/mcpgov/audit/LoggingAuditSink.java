package io.github.mcpgov.audit;
import io.github.mcpgov.contracts.Json;
import org.slf4j.LoggerFactory;
import java.time.Instant;
import java.util.Map;

public final class LoggingAuditSink implements AuditSink {
    @Override public void record(String action, String outcome, Map<String,Object> attributes) {
        LoggerFactory.getLogger("MCP_AUDIT").info("{}", Json.write(Map.of(
            "at", Instant.now().toString(), "action", action, "outcome", outcome, "attributes", attributes)));
    }
}
