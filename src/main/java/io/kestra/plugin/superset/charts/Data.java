package io.kestra.plugin.superset.charts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.kestra.core.http.HttpRequest;
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
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import reactor.core.publisher.Flux;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Fetch chart data",
    description = """
        Queries chart data via `POST /chart/data` and outputs rows using `fetchType`.
        Rows can be fetched in memory or streamed as Amazon Ion into internal storage.
        """
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
                      datasource:
                        id: 1
                        type: table
                      queries:
                        - columns: []
                          metrics: ["count"]
                          row_limit: 1000
                      result_format: json
                      result_type: full
                """
        )
    }
)
public class Data extends SupersetConnection implements RunnableTask<Data.Output> {

    @Schema(
        title = "The JSON query payload to send to /chart/data",
        description = """
            Map representing the Superset chart query context (including `datasource` and `queries`)
            submitted as JSON to `POST /api/v1/chart/data`.
            """
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<Map<String, Object>> chartPayload;

    @Schema(
        title = "How to return output data",
        description = """
            Controls how chart rows are returned: `STORE` (written as Amazon Ion in internal storage, default),
            `FETCH` (all rows in output), or `FETCH_ONE` (first row only).
            """
    )
    @Builder.Default
    @PluginProperty(group = "main")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.STORE);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var rBaseUrl = getBaseUrl(runContext);
        var rPayload = runContext.render(this.chartPayload).asMap(String.class, Object.class);
        if (rPayload.isEmpty()) {
            throw new IllegalArgumentException("chartPayload is required");
        }
        var rFetchMode = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.STORE);

        List<Map<String, Object>> allRows = new ArrayList<>();

        try (HttpClient client = createHttpClient(runContext)) {
            var token = login(client, runContext);

            HttpRequest dataReq = HttpRequest.builder()
                .uri(URI.create(rBaseUrl + "/chart/data"))
                .method("POST")
                .addHeader("Authorization", "Bearer " + token)
                .body(HttpRequest.JsonRequestBody.builder().content(rPayload).build())
                .build();

            client.request(dataReq, response -> {
                try (InputStream bodyStream = response.getBody()) {
                    if (bodyStream == null) {
                        throw new IllegalStateException("Superset /chart/data returned an empty response body");
                    }
                    ChartDataResponse parsed = JacksonMapper.ofJson().readValue(bodyStream, ChartDataResponse.class);
                    if (parsed == null || parsed.getResult() == null) {
                        throw new IllegalStateException("Superset /chart/data returned no result array");
                    }
                    for (ChartResultItem item : parsed.getResult()) {
                        if (item.getData() != null) {
                            allRows.addAll(item.getData());
                        }
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }

        Output.OutputBuilder outputBuilder = Output.builder()
            .size(allRows.size());

        switch (rFetchMode) {
            case FETCH_ONE -> outputBuilder.row(!allRows.isEmpty() ? allRows.getFirst() : null);
            case FETCH -> outputBuilder.rows(allRows);
            case STORE -> {
                File tempFile = runContext.workingDir().createTempFile(".ion").toFile();
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(tempFile))) {
                    FileSerde.writeAll(writer, Flux.fromIterable(allRows)).block();
                }
                URI storageUri = runContext.storage().put(tempFile.toURI());
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
        @Schema(title = "URI of stored chart data in Kestra internal storage (when STORE)")
        private URI uri;

        @Schema(title = "All fetched rows (when FETCH)")
        private List<Map<String, Object>> rows;

        @Schema(title = "First fetched row (when FETCH_ONE)")
        private Map<String, Object> row;

        @Schema(title = "Total number of rows returned")
        private Integer size;
    }

    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ChartDataResponse {
        private List<ChartResultItem> result;
    }

    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ChartResultItem {
        private List<Map<String, Object>> data;
    }
}
