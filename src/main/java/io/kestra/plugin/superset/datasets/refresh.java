package io.kestra.plugin.superset.datasets;

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
import org.slf4j.Logger;

import java.net.URI;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Refresh Superset dataset columns",
    description = "Refreshes the metadata and columns of a dataset in Apache Superset."
)
@Plugin(
    examples = {
        @Example(
            full = true,
            title = "Refresh a dataset after a data pipeline run",
            code = """
                id: refresh_superset_dataset
                namespace: company.team
                tasks:
                  - id: refresh
                    type: io.kestra.plugin.superset.datasets.Refresh
                    url: "{{ secret('SUPERSET_URL') }}"
                    username: "{{ secret('SUPERSET_USER') }}"
                    password: "{{ secret('SUPERSET_PASSWORD') }}"
                    datasetId: 12
                """
        )
    }
)
public class Refresh extends SupersetConnection implements RunnableTask<Refresh.Output> {

    @Schema(title = "The Superset dataset ID to refresh")
    @NotNull
    private Property<Integer> datasetId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        String baseUrl = runContext.render(this.url).as(String.class).orElseThrow();
        String user = runContext.render(this.username).as(String.class).orElseThrow();
        String pass = runContext.render(this.password).as(String.class).orElseThrow();
        String loginProvider = runContext.render(this.provider).as(String.class).orElse("db");
        Integer id = runContext.render(this.datasetId).as(Integer.class).orElseThrow();

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

            // 2. Fetch CSRF token for write access
            HttpRequest csrfReq = HttpRequest.builder()
                .uri(URI.create(baseUrl + "/security/csrf_token/"))
                .method("GET")
                .addHeader("Authorization", "Bearer " + accessToken)
                .build();

            HttpResponse<Map<String, Object>> csrfResp = client.request(csrfReq, JacksonMapper.ofJson().getTypeFactory().constructMapType(Map.class, String.class, Object.class));
            String csrfToken = (String) csrfResp.getBody().get("result");

            // 3. Trigger Superset Dataset Refresh
            logger.info("Refreshing Superset dataset with ID: {}", id);
            HttpRequest refreshReq = HttpRequest.builder()
                .uri(URI.create(baseUrl + "/dataset/" + id + "/refresh"))
                .method("PUT")
                .addHeader("Authorization", "Bearer " + accessToken)
                .addHeader("X-CSRFToken", csrfToken)
                .build();

            HttpResponse<String> refreshResp = client.request(refreshReq, String.class);

            return Output.builder()
                .datasetId(id)
                .success(refreshResp.getStatus().getCode() >= 200 && refreshResp.getStatus().getCode() < 300)
                .build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "The refreshed dataset ID")
        private Integer datasetId;

        @Schema(title = "Whether the refresh operation succeeded")
        private Boolean success;
    }
}
