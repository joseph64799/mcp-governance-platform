package io.github.mcpgov.contracts;

import java.util.*;

/** Shared, deliberately small JSON-RPC envelope validation; business policy lives elsewhere. */
public final class Rpc {
    private Rpc() {}
    public static Map<String,Object> request(String body) {
        Map<String,Object> request = Json.object(body);
        if (request == null || !"2.0".equals(request.get("jsonrpc")) || !(request.get("method") instanceof String method))
            throw new PlatformException(400, "invalid_rpc_request");
        Object id = request.get("id");
        if (id == null && !"notifications/initialized".equals(method))
            throw new PlatformException(400, "request_id_required");
        if (id != null && !(id instanceof String) && !(id instanceof Number))
            throw new PlatformException(400, "invalid_request_id");
        if (request.get("params") != null && !(request.get("params") instanceof Map))
            throw new PlatformException(400, "invalid_rpc_params");
        if (!Set.of("initialize", "ping", "tools/list", "tools/call", "notifications/initialized").contains(method))
            throw new PlatformException(400, "unsupported_mcp_method");
        if ("notifications/initialized".equals(method) && id != null)
            throw new PlatformException(400, "invalid_notification");
        if ("tools/call".equals(method)) {
            Map<String,Object> params = params(request);
            if (!(params.get("name") instanceof String name) || name.isBlank() ||
                (params.get("arguments") != null && !(params.get("arguments") instanceof Map)))
                throw new PlatformException(400, "invalid_tool_call");
        }
        return request;
    }
    @SuppressWarnings("unchecked")
    public static Map<String,Object> params(Map<String,Object> request) {
        return request.get("params") instanceof Map<?,?> value ? (Map<String,Object>) value : Map.of();
    }
    @SuppressWarnings("unchecked")
    public static Map<String,Object> arguments(Map<String,Object> request) {
        Object value = params(request).get("arguments");
        return value instanceof Map<?,?> map ? (Map<String,Object>) map : Map.of();
    }
    public static Map<String,Object> result(Object id, Object result) {
        return Map.of("jsonrpc", "2.0", "id", id, "result", result);
    }
}
