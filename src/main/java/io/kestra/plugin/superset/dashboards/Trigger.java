package io.kestra.plugin.superset.dashboards;

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
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger flow on Superset dashboard change",
    description = "Polls Superset dashboards and triggers execution when dashboards are modified or created."
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
                    message: "Event received: {{ trigger.dashboardTitle }}"
                """
        )
    }
)
public class Trigger extends AbstractTrigger implements PollingTriggerInterface, TriggerOutput<Trigger.Output> {

    @Builder.Default
    private final Duration interval = Duration.ofMinutes(5);

    @Schema(title = "The Superset API base URL")
    @NotNull
    @PluginProperty(group = "connection")
    protected Property<String> url;

    @Schema(title = "The Superset username")
    @NotNull
    @PluginProperty(group = "connection")
    protected Property<String> username;

    @Schema(title = "The Superset password")
    @NotNull
    @PluginProperty(group = "connection", secret = true)
    @ToString.Exclude
    protected Property<String> password;

    @Schema(title = "Authentication provider")
    @Builder.Default
    @PluginProperty(group = "connection")
    protected Property<String> provider = Property.ofValue("db");

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        RunContext runContext = conditionContext.getRunContext();

        String baseUrl = runContext.render(this.url).as(String.class).orElseThrow();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String user = runContext.render(this.username).as(String.class).orElseThrow();
        String pass = runContext.render(this.password).as(String.class).orElseThrow();
        String loginProvider = runContext.render(this.provider).as(String.class).orElse("db");

        try (HttpClient client = HttpClient.builder().runContext(runContext).build()) {
            HttpRequest loginReq = HttpRequest.builder()
                .uri(URI.create(baseUrl + "/security/login"))
                .method("POST")
                .body(HttpRequest.JsonRequestBody.builder().content(Map.of("username", user, "password", pass, "provider", loginProvider)).build())
                .build();

            HttpResponse<String> loginResp = client.request(loginReq, String.class);
            Map<String, Object> tokenMap = JacksonMapper.ofJson().readValue(loginResp.getBody(), Map.class);
            String accessToken = (String) tokenMap.get("access_token");

            HttpRequest listReq = HttpRequest.builder()
                .uri(URI.create(baseUrl + "/dashboard/?q=(order_column:changed_on_delta_humanized,order_direction:desc,page_size:1)"))
                .method("GET")
                .addHeader("Authorization", "Bearer " + accessToken)
                .build();

            HttpResponse<String> listResp = client.request(listReq, String.class);
            DashboardListResponse response = JacksonMapper.ofJson().readValue(listResp.getBody(), DashboardListResponse.class);

            if (response.getResult() == null || response.getResult().isEmpty()) {
                return Optional.empty();
            }

            DashboardItem latest = response.getResult().getFirst();
            String dashboardId = String.valueOf(latest.getId());
            String dashboardTitle = latest.getDashboardTitle();
            String changedOn = latest.getChangedOn();

            String watermarkKey = "superset_wm_" + context.getFlowId().length() + "_" + context.getFlowId() + "_" + this.getId().length() + "_" + this.getId();
            var kvStore = runContext.namespaceKv(context.getNamespace());
            Optional<Object> lastSeen = kvStore.getValue(watermarkKey);

            if (lastSeen.isPresent() && changedOn.equals(lastSeen.get().toString())) {
                return Optional.empty();
            }

            kvStore.put(watermarkKey, changedOn);

            Output output = Output.builder()
                .dashboardId(dashboardId)
                .dashboardTitle(dashboardTitle)
                .changedOn(changedOn)
                .build();

            Execution execution = TriggerService.generateExecution(this, conditionContext, context, output);
            return Optional.of(execution);
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "The modified dashboard ID")
        private String dashboardId;

        @Schema(title = "The modified dashboard title")
        private String dashboardTitle;

        @Schema(title = "Timestamp of modification")
        private String changedOn;
    }

    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DashboardListResponse {
        private List<DashboardItem> result;
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
