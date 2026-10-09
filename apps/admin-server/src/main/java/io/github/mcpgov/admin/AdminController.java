package io.github.mcpgov.admin;

import io.github.mcpgov.audit.AuditSink;
import io.github.mcpgov.catalog.CatalogService;
import io.github.mcpgov.contracts.*;
import io.github.mcpgov.contracts.Model.*;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.Map;
import java.util.function.Supplier;

@RestController
public class AdminController {
    private final CatalogService catalog;
    private final AuditSink audit;
    public AdminController(CatalogService catalog, AuditSink audit) { this.catalog = catalog; this.audit = audit; }
    @GetMapping(value={"/api/snapshot", "/internal/snapshot"}, produces=MediaType.APPLICATION_JSON_VALUE)
    public String snapshot() { return Json.write(catalog.snapshot()); }

    @PostMapping(value="/api/services", produces=MediaType.APPLICATION_JSON_VALUE)
    public Mono<String> register(@RequestBody String body) {
        return mutate("service.configure", () -> catalog.register(Json.read(body, ServiceRegistration.class)));
    }
    @PostMapping(value="/internal/discovery", produces=MediaType.APPLICATION_JSON_VALUE)
    public Mono<String> discover(@RequestBody String body) {
        return mutate("tools.observe", () -> catalog.observe(Json.read(body, DiscoveryReport.class)));
    }
    @PostMapping(value="/api/groups", produces=MediaType.APPLICATION_JSON_VALUE)
    public Mono<String> group(@RequestBody String body) {
        return mutate("group.configure", () -> catalog.group(Json.read(body, CapabilityGroup.class)));
    }
    @PostMapping(value="/api/grants", produces=MediaType.APPLICATION_JSON_VALUE)
    public Mono<String> grant(@RequestBody String body) {
        return mutate("permission.grant", () -> catalog.grant(Json.read(body, Grant.class)));
    }
    @DeleteMapping(value="/api/grants/{id}", produces=MediaType.APPLICATION_JSON_VALUE)
    public Mono<String> revoke(@PathVariable String id) { return mutate("permission.revoke", () -> catalog.revoke(id)); }

    @PostMapping(value="/api/tools/{id}/publish", produces=MediaType.APPLICATION_JSON_VALUE)
    public Mono<String> publish(@PathVariable String id, @RequestBody String body) {
        Map<String,Object> options = Json.object(body);
        return mutate("tool.publish", () -> catalog.publish(id, Boolean.TRUE.equals(options.get("resourceProtected"))));
    }
    @PostMapping(value="/api/tools/{id}/disable", produces=MediaType.APPLICATION_JSON_VALUE)
    public Mono<String> disable(@PathVariable String id) { return mutate("tool.disable", () -> catalog.disable(id)); }

    private Mono<String> mutate(String action, Supplier<Snapshot> operation) {
        return Mono.fromCallable(() -> {
            try {
                Snapshot next = operation.get();
                audit.record(action, "success", Map.of("actor", action.equals("tools.observe") ? "discovery-service" : "bootstrap-admin",
                    "snapshotVersion", next.version()));
                return Json.write(next);
            } catch (RuntimeException e) {
                audit.record(action, "rejected", Map.of()); throw e;
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
