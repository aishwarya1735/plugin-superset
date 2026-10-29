package io.kestra.plugin.superset.charts;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
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
import java.util.concurrent.atomic.AtomicInteger;

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

        List<Map<String, Object>> fetchedRows = new ArrayList<>();
        AtomicInteger rowCount = new AtomicInteger(0);
        File tempFile = rFetchMode == FetchType.STORE ? runContext.workingDir().createTempFile(".ion").toFile() : null;

        try (HttpClient client = createHttpClient(runContext)) {
            var rToken = login(client, runContext);

            HttpRequest dataReq = HttpRequest.builder()
                .uri(URI.create(rBaseUrl + "/chart/data"))
                .method("POST")
                .addHeader("Authorization", "Bearer " + rToken)
                .body(HttpRequest.JsonRequestBody.builder().content(rPayload).build())
                .build();

            client.request(dataReq, response -> {
                try (
                    InputStream bodyStream = response.getBody();
                    BufferedWriter writer = tempFile != null ? new BufferedWriter(new FileWriter(tempFile)) : null
                ) {
                    if (bodyStream == null) {
                        throw new IllegalStateException("Superset /chart/data returned an empty response body");
                    }
                    streamChartRows(bodyStream, rFetchMode, writer, fetchedRows, rowCount);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }

        Output.OutputBuilder outputBuilder = Output.builder()
            .size(rowCount.get());

        switch (rFetchMode) {
            case FETCH_ONE -> outputBuilder.row(!fetchedRows.isEmpty() ? fetchedRows.getFirst() : null);
            case FETCH -> outputBuilder.rows(fetchedRows);
            case STORE -> {
                URI storageUri = runContext.storage().putFile(tempFile);
                outputBuilder.uri(storageUri);
            }
            default -> {
            }
        }

        return outputBuilder.build();
    }

    @SuppressWarnings("unchecked")
    private void streamChartRows(
        InputStream bodyStream,
        FetchType fetchMode,
        BufferedWriter writer,
        List<Map<String, Object>> fetchedRows,
        AtomicInteger rowCount
    ) throws IOException {
        var mapper = JacksonMapper.ofJson();
        boolean foundResult = false;
        try (JsonParser parser = mapper.getFactory().createParser(bodyStream)) {
            while (parser.nextToken() != null) {
                if (parser.currentToken() == JsonToken.FIELD_NAME && "result".equals(parser.currentName())) {
                    if (parser.nextToken() == JsonToken.START_ARRAY) {
                        foundResult = true;
                        while (parser.nextToken() != JsonToken.END_ARRAY && parser.currentToken() != null) {
                            while (parser.nextToken() != JsonToken.END_OBJECT && parser.currentToken() != null) {
                                if (parser.currentToken() == JsonToken.FIELD_NAME && "data".equals(parser.currentName())) {
                                    if (parser.nextToken() == JsonToken.START_ARRAY) {
                                        while (parser.nextToken() != JsonToken.END_ARRAY && parser.currentToken() != null) {
                                            Map<String, Object> row = mapper.readValue(parser, Map.class);
                                            rowCount.incrementAndGet();
                                            if (fetchMode == FetchType.STORE && writer != null) {
                                                FileSerde.write(writer, row);
                                            } else if (fetchMode == FetchType.FETCH) {
                                                fetchedRows.add(row);
                                            } else if (fetchMode == FetchType.FETCH_ONE && fetchedRows.isEmpty()) {
                                                fetchedRows.add(row);
                                            }
                                        }
                                    }
                                } else if (parser.currentToken().isStructStart()) {
                                    parser.skipChildren();
                                }
                            }
                        }
                    }
                }
            }
        }
        if (!foundResult) {
            throw new IllegalStateException("Superset /chart/data returned no result array");
        }
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
}
