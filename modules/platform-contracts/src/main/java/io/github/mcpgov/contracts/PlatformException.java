package io.github.mcpgov.contracts;

/** Sanitized public error code; the cause must not be returned to callers. */
public final class PlatformException extends RuntimeException {
    private final int status;
    private final String code;
    public PlatformException(int status, String code) { super(code); this.status = status; this.code = code; }
    public int status() { return status; }
    public String code() { return code; }
}
