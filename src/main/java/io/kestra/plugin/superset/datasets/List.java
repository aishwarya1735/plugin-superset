package io.kestra.plugin.superset.datasets;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
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
import java.io.File;
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

    @Schema(title = "How to return the output data: FETCH, FETCH_ONE, or STORE (defaults to FETCH)")
    @Builder.Default
    @PluginProperty(group = "main")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Override
    public Output run(RunContext runContext) throws Exception {
        String baseUrl = getBaseUrl(runContext);
        FetchType fetchMode = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.FETCH);

        java.util.List<DatasetItem> allDatasets = new ArrayList<>();
        int page = 0;
        int pageSize = 100;
        boolean hasMore = true;

        try (HttpClient client = createHttpClient(runContext)) {
            String token = login(client, runContext);

            while (hasMore) {
                String queryParam = String.format("?q=(page:%d,page_size:%d)", page, pageSize);
                HttpRequest listReq = HttpRequest.builder()
                    .uri(URI.create(baseUrl + "/dataset/" + queryParam))
                    .method("GET")
                    .addHeader("Authorization", "Bearer " + token)
                    .build();

                HttpResponse<String> listResp = client.request(listReq, String.class);
                DatasetListResponse response = JacksonMapper.ofJson().readValue(listResp.getBody(), DatasetListResponse.class);

                if (response.getResult() == null || response.getResult().isEmpty()) {
                    hasMore = false;
                } else {
                    allDatasets.addAll(response.getResult());
                    if (response.getCount() != null && allDatasets.size() >= response.getCount()) {
                        hasMore = false;
                    } else if (response.getResult().size() < pageSize) {
                        hasMore = false;
                    } else {
                        page++;
                    }
                }
            }
        }

        Output.OutputBuilder outputBuilder = Output.builder()
            .size(allDatasets.size());

        switch (fetchMode) {
            case FETCH_ONE -> outputBuilder.dataset(!allDatasets.isEmpty() ? allDatasets.getFirst() : null);
            case FETCH -> outputBuilder.datasets(allDatasets);
            case STORE -> {
                File tempFile = runContext.workingDir().createTempFile(".ion").toFile();
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(tempFile))) {
                    FileSerde.writeAll(writer, Flux.fromIterable(allDatasets)).block();
                }
                URI storageUri = runContext.storage().put(tempFile.toURI());
                outputBuilder.uri(storageUri);
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
