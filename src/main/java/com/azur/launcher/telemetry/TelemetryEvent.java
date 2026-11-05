package com.azur.launcher.telemetry;

/**
 * One anonymous measurement. {@code step} and {@code code} come from fixed lists (see {@link ErrorCode}); there is no field
 * that could carry a nickname, a path, an address or log text.
 */
public record TelemetryEvent(String step, boolean ok, String code, long ms) {

    public TelemetryEvent {
        ms = Math.max(0, Math.min(3_600_000L, ms));
        code = ok ? "" : (code == null || code.isEmpty() ? ErrorCode.OTHER : code);
    }
}
