package io.github.mcpgov.audit;
import java.util.Map;

@FunctionalInterface
public interface AuditSink {
    /** Supply identifiers and outcome only; never pass credentials, raw arguments or business response bodies. */
    void record(String action, String outcome, Map<String,Object> attributes);
}
