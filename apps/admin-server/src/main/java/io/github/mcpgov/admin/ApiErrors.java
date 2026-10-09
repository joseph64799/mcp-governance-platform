package io.github.mcpgov.admin;

import io.github.mcpgov.contracts.Json;
import io.github.mcpgov.contracts.PlatformException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** Return stable error codes, never exception traces, tokens or upstream response bodies. */
@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(PlatformException.class)
    public ResponseEntity<String> platform(PlatformException e) { return response(e.status(), e.code()); }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> invalid(IllegalArgumentException e) { return response(400, "invalid_request"); }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> unexpected(Exception e) { return response(500, "internal_error"); }
    private ResponseEntity<String> response(int status, String code) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(Json.write(Map.of("error", code)));
    }
}
