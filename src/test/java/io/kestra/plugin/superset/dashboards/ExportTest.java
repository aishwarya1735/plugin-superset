package io.kestra.plugin.superset.dashboards;

import com.sun.net.httpserver.HttpServer;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@KestraTest
class ExportTest {

    @Inject
    private RunContextFactory runContextFactory;

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/security/login", exchange -> {
            var body = "{\"access_token\":\"test-jwt-token\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/api/v1/dashboard/export/", exchange -> {
            var body = new byte[]{0x50, 0x4B, 0x03, 0x04};
            exchange.getResponseHeaders().add("Content-Type", "application/zip");
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1";
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void testExportRunSuccessfully() throws Exception {
        var runContext = runContextFactory.of();

        var export = Export.builder()
            .url(Property.ofValue(baseUrl))
            .username(Property.ofValue("admin"))
            .password(Property.ofValue("admin"))
            .dashboardIds(Property.ofValue(java.util.List.of(1, 2)))
            .build();
        var exportOut = export.run(runContext);
        assertNotNull(exportOut.getUri());
    }
}
