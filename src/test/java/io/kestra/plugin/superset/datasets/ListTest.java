package io.kestra.plugin.superset.datasets;

import com.sun.net.httpserver.HttpServer;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@KestraTest
class ListTest {

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
        server.createContext("/api/v1/dataset/", exchange -> {
            var body = "{\"count\":1,\"result\":[{\"id\":12,\"table_name\":\"sales\",\"schema\":\"public\"}]}".getBytes(StandardCharsets.UTF_8);
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
    void testDatasetListRunSuccessfully() throws Exception {
        var runContext = runContextFactory.of();

        var datasetList = List.builder()
            .url(Property.ofValue(baseUrl))
            .username(Property.ofValue("admin"))
            .password(Property.ofValue("admin"))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();
        var datasetOut = datasetList.run(runContext);
        assertEquals(1, datasetOut.getSize());
        assertEquals("sales", datasetOut.getDatasets().getFirst().getTableName());
    }
}
