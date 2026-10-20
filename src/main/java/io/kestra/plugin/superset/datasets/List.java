package io.kestra.plugin.superset.datasets;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.superset.SupersetConnection;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "List Superset datasets",
    description = "Retrieves a paginated list of datasets configured in Apache Superset."
)
@Plugin(
    examples = {
        @Example(
            full = true,
            title = "List all datasets",
            code = """
                id: list_datasets
                namespace: company.team
                tasks:
                  - id: list
                    type: io.kestra.plugin.superset.datasets.List
                    url: "{{ secret('SUPERSET_URL') }}"
                    username: "{{ secret('SUPERSET_USER') }}"
                    password: "{{ secret('SUPERSET_PASSWORD') }}"
                """
        )
    }
)
public class List extends SupersetConnection implements RunnableTask<List.Output> {

    @Override
    public Output run(RunContext runContext) throws Exception {
        String baseUrl = runContext.render(this.url).as(String.class).orElseThrow();
        String user = runContext.render(this.username).as(String.class).orElseThrow();
        String pass = runContext.render(this.password).as(String.class).orElseThrow();
        String loginProvider = runContext.render(this.provider).as(String.class).orElse("db");

        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        try (HttpClient client = new HttpClient(runContext)) {
            // 1. Authenticate
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

            // 2. Fetch Datasets (GET /dataset/)
            HttpRequest listReq = HttpRequest.builder()
                .uri(URI.create(baseUrl + "/dataset/"))
                .method("GET")
                .addHeader("Authorization", "Bearer " + accessToken)
                .build();

            HttpResponse<Map<String, Object>> listResp = client.request(listReq, JacksonMapper.ofJson().getTypeFactory().constructMapType(Map.class, String.class, Object.class));

            return Output.builder()
                .datasets(listResp.getBody())
                .build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "The list of datasets returned by Superset")
        private Map<String, Object> datasets;
    }
}
