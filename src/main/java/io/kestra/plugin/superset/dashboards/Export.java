package io.kestra.plugin.superset.dashboards;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.superset.SupersetConnection;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Export Superset dashboards",
    description = """
        Exports one or more Apache Superset dashboards as a ZIP archive via `GET /dashboard/export/`
        and stores the archive in Kestra internal storage.
        """
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
                    dashboardIds:
                      - 1
                      - 2
                """
        )
    }
)
public class Export extends SupersetConnection implements RunnableTask<Export.Output> {

    @Schema(
        title = "List of Dashboard IDs to export",
        description = """
            Non-empty list of numeric Superset dashboard IDs to include in the exported ZIP bundle.
            """
    )
    @NotEmpty
    @PluginProperty(group = "main")
    private Property<List<Integer>> dashboardIds;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rBaseUrl = getBaseUrl(runContext);
        var rIds = runContext.render(this.dashboardIds).asList(Integer.class);
        if (rIds.isEmpty()) {
            throw new IllegalArgumentException("dashboardIds must not be empty");
        }

        var queryIds = "?q=" + rIds.stream().map(String::valueOf).collect(Collectors.joining(",", "!(", ")"));

        try (HttpClient client = createHttpClient(runContext)) {
            var token = login(client, runContext);

            HttpRequest exportReq = HttpRequest.builder()
                .uri(URI.create(rBaseUrl + "/dashboard/export/" + queryIds))
                .method("GET")
                .addHeader("Authorization", "Bearer " + token)
                .build();

            HttpResponse<InputStream> exportResp = client.request(exportReq, InputStream.class);
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
