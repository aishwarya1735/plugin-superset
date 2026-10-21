
package io.kestra.plugin.superset.charts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
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
    description = "Queries chart data via POST /chart/data and outputs rows using FetchType."
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
    @PluginProperty(group = "main")
    private Property<Map<String, Object>> chartPayload;

    @Schema(title = "How to return output data: FETCH, FETCH_ONE, or STORE (defaults to STORE)")
    @Builder.Default
    @PluginProperty(group = "main")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.STORE);

    @Override
    public Output run(RunContext runContext) throws Exception {
        String baseUrl = getBaseUrl(runContext);
        Map<String, Object> payload = runContext.render(this.chartPayload).asMap(String.class, Object.class);
        FetchType fetchMode = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.STORE);

        List<Map<String, Object>> allRows = new ArrayList<>();

        try (HttpClient client = createHttpClient(runContext)) {
            String token = login(client, runContext);

            HttpRequest dataReq = HttpRequest.builder()
                .uri(URI.create(baseUrl + "/chart/data"))
                .method("POST")
                .addHeader("Authorization", "Bearer " + token)
                .body(HttpRequest.JsonRequestBody.builder().content(payload).build())
                .build();

            HttpResponse<String> dataResp = client.request(dataReq, String.class);
            ChartDataResponse response = JacksonMapper.ofJson().readValue(dataResp.getBody(), ChartDataResponse.class);

            if (response.getResult() != null) {
                for (ChartResultItem item : response.getResult()) {
                    if (item.getData() != null) {
                        allRows.addAll(item.getData());
                    }
                }
            }
        }

        Output.OutputBuilder outputBuilder = Output.builder()
            .size(allRows.size());

        switch (fetchMode) {
            case FETCH_ONE -> {
                outputBuilder.row(!allRows.isEmpty() ? allRows.getFirst() : null);
            }
            case FETCH -> {
                outputBuilder.rows(allRows);
            }
            case STORE -> {
                File tempFile = runContext.workingDir().createTempFile(".ion").toFile();
                try (OutputStream outputStream = new FileOutputStream(tempFile)) {
                    FileSerde.writeAll(outputStream, allRows);
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
