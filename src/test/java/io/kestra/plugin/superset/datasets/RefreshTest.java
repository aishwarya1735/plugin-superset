package io.kestra.plugin.superset.datasets;

import com.sun.net.httpserver.HttpServer;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.superset.charts.Data;
import io.kestra.plugin.superset.dashboards.Export;
import io.kestra.plugin.superset.dashboards.Trigger;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@KestraTest
class RefreshTest {

    @Inject
    private RunContextFactory runContextFactory;

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> changedOnRef = new AtomicReference<>("2026-01-01T00:00:00Z");
    private final AtomicReference<String> loginBodyRef = new AtomicReference<>("{\"access_token\":\"test-jwt-token\"}");

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/security/login", exchange -> {
            var body = loginBodyRef.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/api/v1/security/csrf_token/", exchange -> {
            var body = "{\"result\":\"test-csrf-token\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/api/v1/dataset/12/refresh", exchange -> {
            var body = "{\"message\":\"OK\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/api/v1/dataset/", exchange -> {
            var body = "{\"count\":1,\"result\":[{\"id\":12,\"table_name\":\"sales\",\"schema\":\"public\"}]}".getBytes(StandardCharsets.UTF_8);
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
        server.createContext("/api/v1/dashboard/", exchange -> {
            var json = "{\"count\":1,\"result\":[{\"id\":1,\"dashboard_title\":\"Exec\",\"changed_on_utc\":\"" + changedOnRef.get() + "\"}]}";
            var body = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/api/v1/chart/data", exchange -> {
            var body = "{\"result\":[{\"data\":[{\"metric\":42}]}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
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
    void testTasksAndTriggerRunSuccessfully() throws Exception {
        var runContext = runContextFactory.of();

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

        var trigger = Trigger.builder()
            .id("watch")
            .type(Trigger.class.getName())
            .url(Property.ofValue(baseUrl))
            .username(Property.ofValue("admin"))
            .password(Property.ofValue("admin"))
            .build();
        var mocked = TestsUtils.mockTrigger(runContextFactory, trigger);

        var firstEval = trigger.evaluate(mocked.getKey(), mocked.getValue());
        assertTrue(firstEval.isEmpty());

        changedOnRef.set("2026-01-02T00:00:00Z");
        var secondEval = trigger.evaluate(mocked.getKey(), mocked.getValue());
        assertTrue(secondEval.isPresent());
    }

    @Test
    void testLoginFailureThrowsActionableException() {
        loginBodyRef.set("{}");
        var runContext = runContextFactory.of();

        var refresh = Refresh.builder()
            .url(Property.ofValue(baseUrl))
            .username(Property.ofValue("admin"))
            .password(Property.ofValue("wrong"))
            .datasetId(Property.ofValue(12))
            .build();

        assertThrows(IllegalStateException.class, () -> refresh.run(runContext));
    }
}
