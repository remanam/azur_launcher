package com.azur.launcher.telemetry;

import java.io.IOException;

/** Posts one JSON body to a path of the site; throws on any failure (the client ignores it). */
public interface TelemetrySender {
    void post(String path, String json) throws IOException;
}
