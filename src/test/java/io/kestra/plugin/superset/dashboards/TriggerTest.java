package io.kestra.plugin.superset.dashboards;

import com.sun.net.httpserver.HttpServer;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.TestsUtils;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@KestraTest
class TriggerTest {

    @Inject
    private RunContextFactory runContextFactory;

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> changedOnRef = new AtomicReference<>("2026-01-01T00:00:00Z");

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
        server.createContext("/api/v1/dashboard/", exchange -> {
            var json = "{\"count\":1,\"result\":[{\"id\":1,\"dashboard_title\":\"Exec\",\"changed_on_utc\":\"" + changedOnRef.get() + "\"}]}";
            var body = json.getBytes(StandardCharsets.UTF_8);
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
    void testTriggerEvaluateSuccessfully() throws Exception {
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

        var variables = secondEval.get().getTrigger().getVariables();
        assertEquals(1, variables.get("count"));
        var dashboards = (List<?>) variables.get("dashboards");
        assertEquals(1, dashboards.size());
        var dashboard = (Map<?, ?>) dashboards.get(0);
        assertEquals(1, dashboard.get("id"));
        assertEquals("Exec", dashboard.get("dashboard_title"));
    }
}
