package io.github.mcpgov.runtime;

import io.github.mcpgov.audit.AuditSink;
import io.github.mcpgov.contracts.*;
import io.github.mcpgov.control.SnapshotCache;
import io.github.mcpgov.security.PermitSigner;
import org.springframework.http.*;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.Map;

/** Only signed, request-bound gateway decisions are accepted. No user credentials are forwarded upstream. */
@RestController
public class McpController {
    private final McpFacade facade;
    private final SnapshotCache snapshots;
    private final PermitSigner signer;
    private final AuditSink audit;
    public McpController(McpFacade facade, SnapshotCache snapshots, PermitSigner signer, AuditSink audit) {
        this.facade = facade; this.snapshots = snapshots; this.signer = signer; this.audit = audit;
    }
    @PostMapping(value="/mcp/{group}", consumes=MediaType.APPLICATION_JSON_VALUE, produces=MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<String>> execute(@PathVariable String group, @RequestBody String body, ServerHttpRequest http) {
        return Mono.fromCallable(() -> {
            var permit = signer.verify(http.getHeaders().getFirst(PermitSigner.HEADER), "POST", http.getURI().getRawPath(), body);
            var request = Rpc.request(body);
            var snapshot = snapshots.current();
            if (!group.equals(permit.groupId()) || snapshot.version() != permit.snapshotVersion())
                throw new PlatformException(409, "configuration_version_mismatch");
            var capability = snapshot.groups().get(group);
            if (capability == null || !capability.tenant().equals(permit.caller().tenant()))
                throw new PlatformException(403, "group_access_denied");
            if ("notifications/initialized".equals(request.get("method"))) return ResponseEntity.accepted().<String>build();
            long start = System.nanoTime();
            try {
                Map<String,Object> result = facade.execute(group, request, permit, snapshot);
                audit.record("mcp.execute", Boolean.TRUE.equals(result.get("isError")) ? "tool_error" : "success",
                    Map.of("requestId", permit.requestId(), "subject", permit.caller().subject(),
                        "tenant", permit.caller().tenant(), "group", group, "method", request.get("method"),
                        "durationMs", (System.nanoTime() - start) / 1000000));
                return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(Json.write(Rpc.result(request.get("id"), result)));
            } catch (PlatformException e) {
                audit.record("mcp.execute", "failed", Map.of("requestId", permit.requestId(), "reason", e.code()));
                throw e;
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
