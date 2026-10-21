package io.kestra.plugin.superset.datasets;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.VoidOutput;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.superset.SupersetConnection;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.net.URI;

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
public class Refresh extends SupersetConnection implements RunnableTask<VoidOutput> {

    @Schema(title = "The Superset dataset ID to refresh")
    @NotNull
    @PluginProperty(group = "main")
    private Property<Integer> datasetId;

    @Override
    public VoidOutput run(RunContext runContext) throws Exception {
        Integer id = runContext.render(this.datasetId).as(Integer.class).orElseThrow();
        String baseUrl = getBaseUrl(runContext);

        try (HttpClient client = createHttpClient(runContext)) {
            String token = login(client, runContext);
            String csrf = fetchCsrfToken(client, runContext, token);

            HttpRequest refreshReq = HttpRequest.builder()
                .uri(URI.create(baseUrl + "/dataset/" + id + "/refresh"))
                .method("PUT")
                .addHeader("Authorization", "Bearer " + token)
                .addHeader("X-CSRFToken", csrf)
                .build();

            client.request(refreshReq, String.class);
        }

        return null;
    }
}
