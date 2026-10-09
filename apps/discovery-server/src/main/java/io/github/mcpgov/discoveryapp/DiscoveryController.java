package io.github.mcpgov.discoveryapp;

import io.github.mcpgov.audit.AuditSink;
import io.github.mcpgov.contracts.*;
import io.github.mcpgov.control.ControlPlaneClient;
import io.github.mcpgov.discovery.DiscoveryCoordinator;
import io.github.mcpgov.security.ServiceTokens;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@RestController
public class DiscoveryController {
    private final ControlPlaneClient control;
    private final DiscoveryCoordinator coordinator;
    private final AuditSink audit;
    private final String token;
    private final Map<String,Object> status = new ConcurrentHashMap<>();
    public DiscoveryController(ControlPlaneClient control, DiscoveryCoordinator coordinator, AuditSink audit, Environment env) {
        this.control = control; this.coordinator = coordinator; this.audit = audit;
        this.token = ServiceTokens.configured(env.getProperty("mcp.control.discovery-token"));
    }
    @Scheduled(fixedDelayString="${mcp.discovery.interval-ms:10000}")
    public void scheduled() {
        try { synchronize(null); } catch (RuntimeException e) { audit.record("discovery.refresh", "failed", Map.of()); }
    }
    @PostMapping(value="/internal/sync", produces=MediaType.APPLICATION_JSON_VALUE)
    public Mono<String> sync(@RequestHeader(value=HttpHeaders.AUTHORIZATION, required=false) String authorization,
                             @RequestBody String body) {
        ServiceTokens.require(authorization, token);
        Object id = Json.object(body).get("serviceId");
        return Mono.fromCallable(() -> { synchronize(id == null ? null : String.valueOf(id)); return Json.write(status); })
            .subscribeOn(Schedulers.boundedElastic());
    }
    @GetMapping(value="/internal/status", produces=MediaType.APPLICATION_JSON_VALUE)
    public String status(@RequestHeader(value=HttpHeaders.AUTHORIZATION, required=false) String authorization) {
        ServiceTokens.require(authorization, token); return Json.write(status);
    }
    private synchronized void synchronize(String serviceId) {
        var services = control.snapshot().services();
        if (serviceId != null && !services.containsKey(serviceId)) throw new IllegalArgumentException("Unknown service");
        services.values().stream().filter(s -> s.enabled() && (serviceId == null || s.id().equals(serviceId))).forEach(service -> {
            String outcome = "success";
            try { coordinator.synchronize(service); }
            catch (RuntimeException e) { outcome = "failed"; }
            status.put(service.id(), Map.of("outcome", outcome, "at", Instant.now().toString()));
            audit.record("service.discover", outcome, Map.of("serviceId", service.id()));
        });
    }
}
