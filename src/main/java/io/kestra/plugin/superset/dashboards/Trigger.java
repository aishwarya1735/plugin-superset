package io.kestra.plugin.superset.dashboards;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.models.triggers.PollingTriggerInterface;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerOutput;
import io.kestra.core.models.triggers.TriggerService;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.storages.kv.KVValueAndMetadata;
import io.kestra.plugin.superset.SupersetConnection;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger flow on Superset dashboard change",
    description = """
        Polls Apache Superset dashboards and triggers an execution when one or more dashboards
        are created or modified since the persisted namespace KV Store watermark.
        """
)
@Plugin(
    examples = {
        @Example(
            full = true,
            title = "Watch for dashboard modifications",
            code = """
                id: on_superset_change
                namespace: company.team
                triggers:
                  - id: watch
                    type: io.kestra.plugin.superset.dashboards.Trigger
                    url: "{{ secret('SUPERSET_URL') }}"
                    username: "{{ secret('SUPERSET_USER') }}"
                    password: "{{ secret('SUPERSET_PASSWORD') }}"
                    interval: PT5M
                tasks:
                  - id: handle_event
                    type: io.kestra.plugin.core.log.Log
                    message: "Event received: {{ trigger.dashboards[0].dashboard_title }}"
                """
        )
    }
)
public class Trigger extends AbstractTrigger implements PollingTriggerInterface, TriggerOutput<Trigger.Output> {

    @Schema(
        title = "Interval between polling evaluations",
        description = """
            Duration between two consecutive polls of the Superset dashboard endpoint (defaults to 5 minutes).
            """
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private final Duration interval = Duration.ofMinutes(5);

    @Schema(
        title = "The Superset API base URL",
        description = """
            Base URL of the Apache Superset API, including the `/api/v1` path suffix
            (for example `https://superset.example.com/api/v1`).
            """
    )
    @NotNull
    @PluginProperty(group = "connection")
    protected Property<String> url;

    @Schema(
        title = "The Superset username",
        description = """
            Username used to authenticate against Apache Superset via `POST /security/login`.
            """
    )
    @NotNull
    @PluginProperty(group = "connection")
    protected Property<String> username;

    @Schema(
        title = "The Superset password",
        description = """
            Password associated with the Superset username. Always store this value in a Kestra secret.
            """
    )
    @NotNull
    @PluginProperty(group = "connection", secret = true)
    @ToString.Exclude
    protected Property<String> password;

    @Schema(
        title = "Authentication provider",
        description = """
            Superset authentication provider passed to `/security/login`.
            Supported values include `db` (default), `ldap`, `oauth`, and `oid`.
            """
    )
    @Builder.Default
    @PluginProperty(group = "connection")
    protected Property<String> provider = Property.ofValue("db");

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        var runContext = conditionContext.getRunContext();
        var rBaseUrl = SupersetConnection.resolveBaseUrl(runContext, this.url);

        try (var client = HttpClient.builder().runContext(runContext).build()) {
            var rAccessToken = SupersetConnection.authenticate(
                client, runContext, rBaseUrl, this.username, this.password, this.provider
            );

            var watermarkKey = "superset_wm_" + context.getFlowId().length() + "_" + context.getFlowId()
                + "_" + this.getId().length() + "_" + this.getId();
            var kvStore = runContext.namespaceKv(context.getNamespace());
            Optional<String> lastSeen = kvStore.getValue(watermarkKey).map(v -> v.value().toString());

            if (lastSeen.isEmpty()) {
                var firstPage = fetchDashboardPage(client, rBaseUrl, rAccessToken, 0, 1);
                if (firstPage == null || firstPage.getResult() == null || firstPage.getResult().isEmpty()) {
                    return Optional.empty();
                }
                var initialWatermark = firstPage.getResult().getFirst().getChangedOn();
                if (initialWatermark != null) {
                    kvStore.put(watermarkKey, new KVValueAndMetadata(null, initialWatermark));
                }
                return Optional.empty();
            }

            var previousWatermark = lastSeen.get();
            var maxChangedOn = previousWatermark;
            var changedDashboards = new ArrayList<DashboardItem>();
            var page = 0;
            var pageSize = 100;
            var hasMore = true;

            while (hasMore) {
                var response = fetchDashboardPage(client, rBaseUrl, rAccessToken, page, pageSize);
                if (response == null || response.getResult() == null || response.getResult().isEmpty()) {
                    break;
                }

                for (var item : response.getResult()) {
                    if (item.getChangedOn() != null && item.getChangedOn().compareTo(previousWatermark) > 0) {
                        changedDashboards.add(item);
                        if (item.getChangedOn().compareTo(maxChangedOn) > 0) {
                            maxChangedOn = item.getChangedOn();
                        }
                    } else {
                        hasMore = false;
                        break;
                    }
                }

                if (hasMore) {
                    if (response.getCount() != null && (page + 1) * pageSize >= response.getCount()) {
                        hasMore = false;
                    } else {
                        page++;
                    }
                }
            }

            if (changedDashboards.isEmpty()) {
                return Optional.empty();
            }

            kvStore.put(watermarkKey, new KVValueAndMetadata(null, maxChangedOn));

            var output = Output.builder()
                .dashboards(changedDashboards)
                .count(changedDashboards.size())
                .build();

            var execution = TriggerService.generateExecution(this, conditionContext, context, output);
            return Optional.of(execution);
        }
    }

    private DashboardListResponse fetchDashboardPage(
        HttpClient client,
        String baseUrl,
        String accessToken,
        int page,
        int pageSize
    ) throws Exception {
        var query = String.format("?q=(order_column:changed_on,order_direction:desc,page:%d,page_size:%d)", page, pageSize);
        var listReq = HttpRequest.builder()
            .uri(URI.create(baseUrl + "/dashboard/" + query))
            .method("GET")
            .addHeader("Authorization", "Bearer " + accessToken)
            .build();

        var listResp = client.request(listReq, String.class);
        if (listResp.getBody() == null || listResp.getBody().isBlank()) {
            return null;
        }
        return JacksonMapper.ofJson().readValue(listResp.getBody(), DashboardListResponse.class);
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "All dashboards modified since the last watermark")
        private List<DashboardItem> dashboards;

        @Schema(title = "Number of modified dashboards detected")
        private Integer count;
    }

    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DashboardListResponse {
        private Integer count;
        private List<DashboardItem> result;
    }

    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DashboardItem {
        @Schema(title = "Dashboard ID")
        private Integer id;

        @JsonProperty("dashboard_title")
        private String dashboardTitle;

        @JsonProperty("changed_on_utc")
        @JsonAlias("changed_on")
        private String changedOn;
    }
}
