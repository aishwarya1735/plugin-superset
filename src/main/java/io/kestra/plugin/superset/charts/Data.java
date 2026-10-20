package io.kestra.plugin.superset.charts;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.superset.SupersetConnection;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.net.URI;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Fetch chart data",
    description = "Queries chart data via POST /chart/data and stores output rows to internal storage."
)
@Plugin(
    examples = {
        @Example(
            full = true,
            title = "Query chart data and store rows",
            code = """
                id: get_chart_data
                namespace: company.team
                tasks:
                  - id: fetch_data
                    type: io.kestra.plugin.superset.charts.Data
                    url: "{{ secret('SUPERSET_URL') }}"
                    username: "{{ secret('SUPERSET_USER') }}"
                    password: "{{ secret('SUPERSET_PASSWORD') }}"
                    chartPayload:
                      queries:
                        - extras: {}
                """
        )
    }
)
public class Data extends SupersetConnection implements RunnableTask<Data.Output> {

    @Schema(title = "The JSON query payload to send to /chart/data")
    @NotNull
    private Property<Map<String, Object>> chartPayload;

    @Override
    public Output run(RunContext runContext) throws Exception {
        String baseUrl = runContext.render(this.url).as(String.class).orElseThrow();
        String user = runContext.render(this.username).as(String.class).orElseThrow();
        String pass = runContext.render(this.password).as(String.class).orElseThrow();
        String loginProvider = runContext.render(this.provider).as(String.class).orElse("db");
        Map<String, Object> payload = runContext.render(this.chartPayload).asMap(String.class, Object.class);

        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        try (HttpClient client = new HttpClient(runContext)) {
            // 1. JWT Authentication
            String loginUrl = baseUrl + "/security/login";
            Map<String, Object> loginPayload = Map.of(
                "username", user,
                "password", pass,
                "provider", loginProvider,
                "refresh", true
            );

            HttpRequest loginReq = HttpRequest.builder()
                .uri(URI.create(loginUrl))
                .method("POST")
                .body(HttpRequest.JsonRequestBody.builder().content(loginPayload).build())
                .build();

            HttpResponse<Map<String, Object>> loginResp = client.request(loginReq, JacksonMapper.ofJson().getTypeFactory().constructMapType(Map.class, String.class, Object.class));
            String accessToken = (String) loginResp.getBody().get("access_token");

            // 2. Query Chart Data (POST /chart/data)
            HttpRequest dataReq = HttpRequest.builder()
                .uri(URI.create(baseUrl + "/chart/data"))
                .method("POST")
                .addHeader("Authorization", "Bearer " + accessToken)
                .body(HttpRequest.JsonRequestBody.builder().content(payload).build())
                .build();

            HttpResponse<String> dataResp = client.request(dataReq, String.class);

            // Store JSON data to internal storage
            File tempFile = runContext.workingDir().createTempFile(".json").toFile();
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(tempFile))) {
                writer.write(dataResp.getBody());
            }
            URI storageUri = runContext.storage().put(tempFile.toURI());

            return Output.builder()
                .uri(storageUri)
                .build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "URI of stored chart data in Kestra internal storage")
        private URI uri;
    }
}
