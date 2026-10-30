package io.kestra.plugin.superset.datasets;

import com.sun.net.httpserver.HttpServer;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.superset.charts.Data;
import io.kestra.plugin.superset.dashboards.Export;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@KestraTest
class RefreshTest {

    @Inject
    private RunContextFactory runContextFactory;

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/security/login", exchange -> {
            byte[] body = "{\"access_token\":\"test-jwt-token\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/api/v1/security/csrf_token/", exchange -> {
            byte[] body = "{\"result\":\"test-csrf-token\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/api/v1/dataset/12/refresh", exchange -> {
            byte[] body = "{\"message\":\"OK\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/api/v1/dataset/", exchange -> {
            byte[] body = "{\"count\":1,\"result\":[{\"id\":12,\"table_name\":\"sales\",\"schema\":\"public\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/api/v1/dashboard/export/", exchange -> {
            byte[] body = new byte[]{0x50, 0x4B, 0x03, 0x04};
            exchange.getResponseHeaders().add("Content-Type", "application/zip");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/api/v1/dashboard/", exchange -> {
            byte[] body = "{\"count\":1,\"result\":[{\"id\":1,\"dashboard_title\":\"Exec\",\"changed_on_utc\":\"2026-01-01T00:00:00Z\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/api/v1/chart/data", exchange -> {
            byte[] body = "{\"result\":[{\"data\":[{\"metric\":42}]}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
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
    void testTasksRunSuccessfully() throws Exception {
        RunContext runContext = runContextFactory.of();

        var refresh = Refresh.builder()
            .url(Property.ofValue(baseUrl))
            .username(Property.ofValue("admin"))
            .password(Property.ofValue("admin"))
            .datasetId(Property.ofValue(12))
            .build();
        assertNull(refresh.run(runContext));

        var datasetList = List.builder()
            .url(Property.ofValue(baseUrl))
            .username(Property.ofValue("admin"))
            .password(Property.ofValue("admin"))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();
        var datasetOut = datasetList.run(runContext);
        assertEquals(1, datasetOut.getSize());
        assertEquals("sales", datasetOut.getDatasets().getFirst().getTableName());

        var dashList = io.kestra.plugin.superset.dashboards.List.builder()
            .url(Property.ofValue(baseUrl))
            .username(Property.ofValue("admin"))
            .password(Property.ofValue("admin"))
            .fetchType(Property.ofValue(FetchType.FETCH_ONE))
            .build();
        var dashOut = dashList.run(runContext);
        assertEquals(1, dashOut.getSize());
        assertEquals("Exec", dashOut.getDashboard().getDashboardTitle());

        var export = Export.builder()
            .url(Property.ofValue(baseUrl))
            .username(Property.ofValue("admin"))
            .password(Property.ofValue("admin"))
            .dashboardIds(Property.ofValue(java.util.List.of(1, 2)))
            .build();
        var exportOut = export.run(runContext);
        assertNotNull(exportOut.getUri());

        var chartData = Data.builder()
            .url(Property.ofValue(baseUrl))
            .username(Property.ofValue("admin"))
            .password(Property.ofValue("admin"))
            .chartPayload(Property.ofValue(Map.of("queries", java.util.List.of(Map.of("row_limit", 10)))))
            .fetchType(Property.ofValue(FetchType.STORE))
            .build();
        var chartOut = chartData.run(runContext);
        assertEquals(1, chartOut.getSize());
        assertNotNull(chartOut.getUri());
    }
}
