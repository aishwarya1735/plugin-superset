package io.kestra.plugin.superset.dashboards;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.superset.SupersetConnection;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;

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
    title = "List Superset dashboards",
    description = "Retrieves a paginated list of dashboards configured in Apache Superset."
)
@Plugin(
    examples = {
        @Example(
            full = true,
            title = "List all dashboards",
            code = """
                id: list_dashboards
                namespace: company.team
                tasks:
                  - id: list
                    type: io.kestra.plugin.superset.dashboards.List
                    url: "{{ secret('SUPERSET_URL') }}"
                    username: "{{ secret('SUPERSET_USER') }}"
                    password: "{{ secret('SUPERSET_PASSWORD') }}"
                """
        )
    }
)
public class List extends SupersetConnection implements RunnableTask<List.Output> {

    @Schema(title = "How to return the output data (defaults to FETCH)")
    @Builder.Default
    @PluginProperty(group = "main")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Override
    public Output run(RunContext runContext) throws Exception {
        String baseUrl = getBaseUrl(runContext);
        FetchType fetchMode = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.FETCH);

        java.util.List<DashboardItem> allDashboards = new ArrayList<>();
        int page = 0;
        int pageSize = 100;
        boolean hasMore = true;

        try (HttpClient client = createHttpClient(runContext)) {
            String token = login(client, runContext);

            // Loop to handle full pagination (Reviewer Finding #4)
            while (hasMore) {
                String queryParam = String.format("?q=(page:%d,page_size:%d)", page, pageSize);
                HttpRequest listReq = HttpRequest.builder()
                    .uri(URI.create(baseUrl + "/dashboard/" + queryParam))
                    .method("GET")
                    .addHeader("Authorization", "Bearer " + token)
                    .build();

                HttpResponse<String> listResp = client.request(listReq, String.class);
                DashboardListResponse response = JacksonMapper.ofJson().readValue(listResp.getBody(), DashboardListResponse.class);

                if (response.getResult() == null || response.getResult().isEmpty()) {
                    hasMore = false;
                } else {
                    allDashboards.addAll(response.getResult());
                    if (response.getResult().size() < pageSize) {
                        hasMore = false;
                    } else {
                        page++;
                    }
                }
            }
        }

        return Output.builder()
            .dashboards(allDashboards)
            .size(allDashboards.size())
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "The retrieved dashboards")
        private java.util.List<DashboardItem> dashboards;

        @Schema(title = "The total count of dashboards retrieved")
        private Integer size;
    }

    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DashboardListResponse {
        private java.util.List<DashboardItem> result;
    }

    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DashboardItem {
        private Object id;
        @JsonProperty("dashboard_title")
        private String dashboardTitle;
        @JsonProperty("changed_on")
        private String changedOn;
    }
}
