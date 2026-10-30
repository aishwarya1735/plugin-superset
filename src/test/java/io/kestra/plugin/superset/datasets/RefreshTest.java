package io.kestra.plugin.superset.datasets;

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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@KestraTest
class RefreshTest {

    @Inject
    private RunContextFactory runContextFactory;

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> loginBody = new AtomicReference<>("{\"access_token\":\"test-jwt-token\"}");

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/security/login", exchange -> {
            var body = loginBody.get().getBytes(StandardCharsets.UTF_8);
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
    void testRefreshRunSuccessfully() throws Exception {
        var runContext = runContextFactory.of();

        var refresh = Refresh.builder()
            .url(Property.ofValue(baseUrl))
            .username(Property.ofValue("admin"))
            .password(Property.ofValue("admin"))
            .datasetId(Property.ofValue(12))
            .build();
        assertNull(refresh.run(runContext));
    }

    @Test
    void testLoginFailureThrowsActionableException() {
        loginBody.set("{}");
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
