package io.kestra.plugin.superset.datasets;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.superset.SupersetConnection;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;
import reactor.core.publisher.Flux;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.net.URI;
import java.util.ArrayList;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "List Superset datasets",
    description = """
        Retrieves datasets configured in Apache Superset.
        Pages through all results and returns them according to the selected `fetchType`.
        """
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

    @Schema(
        title = "How to return the output data",
        description = """
            Controls how datasets are returned: `FETCH` (all items in output, default),
            `FETCH_ONE` (first item only), or `STORE` (written as an Amazon Ion file in internal storage).
            """
    )
    @Builder.Default
    @PluginProperty(group = "main")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rBaseUrl = getBaseUrl(runContext);
        var rFetchMode = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.FETCH);

        java.util.List<DatasetItem> allDatasets = new ArrayList<>();
        var page = 0;
        var pageSize = 100;
        var hasMore = true;

        try (var client = createHttpClient(runContext)) {
            var rToken = login(client, runContext);

            while (hasMore) {
                var queryParam = String.format("?q=(page:%d,page_size:%d)", page, pageSize);
                var listReq = HttpRequest.builder()
                    .uri(URI.create(rBaseUrl + "/dataset/" + queryParam))
                    .method("GET")
                    .addHeader("Authorization", "Bearer " + rToken)
                    .build();

                var listResp = client.request(listReq, String.class);
                if (listResp.getBody() == null || listResp.getBody().isBlank()) {
                    throw new IllegalStateException("Superset /dataset/ returned an empty response body");
                }

                var response = JacksonMapper.ofJson().readValue(listResp.getBody(), DatasetListResponse.class);
                if (response == null || response.getResult() == null || response.getResult().isEmpty()) {
                    hasMore = false;
                } else {
                    allDatasets.addAll(response.getResult());
                    if (response.getCount() != null && allDatasets.size() >= response.getCount()) {
                        hasMore = false;
                    } else {
                        page++;
                    }
                }
            }
        }

        var outputBuilder = Output.builder()
            .size(allDatasets.size());

        switch (rFetchMode) {
            case FETCH_ONE -> outputBuilder.dataset(!allDatasets.isEmpty() ? allDatasets.getFirst() : null);
            case FETCH -> outputBuilder.datasets(allDatasets);
            case STORE -> {
                var tempFile = runContext.workingDir().createTempFile(".ion").toFile();
                try (var writer = new BufferedWriter(new FileWriter(tempFile))) {
                    FileSerde.writeAll(writer, Flux.fromIterable(allDatasets)).block();
                }
                var storageUri = runContext.storage().putFile(tempFile);
                outputBuilder.uri(storageUri);
            }
            default -> {
            }
        }

        return outputBuilder.build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "URI of stored datasets in Kestra internal storage (when STORE)")
        private URI uri;

        @Schema(title = "The retrieved datasets (when FETCH)")
        private java.util.List<DatasetItem> datasets;

        @Schema(title = "First retrieved dataset (when FETCH_ONE)")
        private DatasetItem dataset;

        @Schema(title = "The total count of datasets retrieved")
        private Integer size;
    }

    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DatasetListResponse {
        private Integer count;
        private java.util.List<DatasetItem> result;
    }

    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DatasetItem {
        @Schema(title = "Dataset ID")
        private Integer id;

        @JsonProperty("table_name")
        @Schema(title = "Table name")
        private String tableName;

        @JsonProperty("schema")
        @Schema(title = "Schema name")
        private String schema;
    }
}
