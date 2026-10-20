package io.kestra.plugin.superset.dashboards;

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
import jakarta.validation.constraints.NotEmpty;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Export Superset dashboards",
    description = "Exports dashboards as a ZIP backup file saved to internal storage."
)
@Plugin(
    examples = {
        @Example(
            full = true,
            title = "Export dashboards as ZIP",
            code = """
                id: export_dashboards
                namespace: company.team
                tasks:
                  - id: export
                    type: io.kestra.plugin.superset.dashboards.Export
                    url: "{{ secret('SUPERSET_URL') }}"
                    username: "{{ secret('SUPERSET_USER') }}"
                    password: "{{ secret('SUPERSET_PASSWORD') }}"
                    dashboardIds: [1, 2]
                """
        )
    }
)
public class Export extends SupersetConnection implements RunnableTask<Export.Output> {

    @Schema(title = "List of Dashboard IDs to export")
    @NotEmpty
    private Property<List<Integer>> dashboardIds;

    @Override
    public Output run(RunContext runContext) throws Exception {
        String baseUrl = runContext.render(this.url).as(String.class).orElseThrow();
        String user = runContext.render(this.username).as(String.class).orElseThrow();
        String pass = runContext.render(this.password).as(String.class).orElseThrow();
        String loginProvider = runContext.render(this.provider).as(String.class).orElse("db");
        List<Integer> ids = runContext.render(this.dashboardIds).asList(Integer.class);

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

            // Build query parameter containing the IDs: ?q=(1,2)
            StringBuilder queryIds = new StringBuilder("?q=(");
            for (int i = 0; i < ids.size(); i++) {
                queryIds.append(ids.get(i));
                if (i < ids.size() - 1) {
                    queryIds.append(",");
                }
            }
            queryIds.append(")");

            // 2. Export Dashboards (GET /dashboard/export/)
            HttpRequest exportReq = HttpRequest.builder()
                .uri(URI.create(baseUrl + "/dashboard/export/" + queryIds))
                .method("GET")
                .addHeader("Authorization", "Bearer " + accessToken)
                .build();

            HttpResponse<InputStream> exportResp = client.request(exportReq, InputStream.class);

            // Put the ZIP stream into Kestra's internal storage
            URI storageUri = runContext.storage().put(URI.create("superset-dashboards-export.zip"), exportResp.getBody());

            return Output.builder()
                .uri(storageUri)
                .build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "URI of the exported ZIP file in Kestra internal storage")
        private URI uri;
    }
}
