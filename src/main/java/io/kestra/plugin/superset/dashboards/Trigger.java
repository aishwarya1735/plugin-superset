package io.kestra.plugin.superset.dashboards;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.executions.ExecutionTrigger;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.models.triggers.PollingTriggerInterface;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerOutput;
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
    protected Property<String> url;

    @Schema(title = "The Superset username")
    @NotNull
    protected Property<String> username;

    @Schema(title = "The Superset password")
    @NotNull
    @PluginProperty(secret = true)
    @ToString.Exclude
    protected Property<String> password;

    @Schema(title = "Authentication provider", description = "Defaults to db")
    @Builder.Default
    protected Property<String> provider = Property.of("db");

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        RunContext runContext = conditionContext.getRunContext();

        String baseUrl = runContext.render(this.url).as(String.class).orElseThrow();
        String user = runContext.render(this.username).as(String.class).orElseThrow();
        String pass = runContext.render(this.password).as(String.class).orElseThrow();
        String loginProvider = runContext.render(this.provider).as(String.class).orElse("db");

        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        try (HttpClient client = new HttpClient(runContext)) {
            // 1. Authenticate
            HttpRequest loginReq = HttpRequest.builder()
                .uri(URI.create(baseUrl + "/security/login"))
                .method("POST")
                .body(HttpRequest.JsonRequestBody.builder().content(Map.of("username", user, "password", pass, "provider", loginProvider)).build())
                .build();

            HttpResponse<Map<String, Object>> loginResp = client.request(loginReq, JacksonMapper.ofJson().getTypeFactory().constructMapType(Map.class, String.class, Object.class));
            String accessToken = (String) loginResp.getBody().get("access_token");

            // 2. Fetch latest dashboard ordered by changed_on desc
            HttpRequest listReq = HttpRequest.builder()
                .uri(URI.create(baseUrl + "/dashboard/?q=(order_column:changed_on_delta_humanized,order_direction:desc,page_size:1)"))
                .method("GET")
                .addHeader("Authorization", "Bearer " + accessToken)
                .build();

            HttpResponse<Map<String, Object>> listResp = client.request(listReq, JacksonMapper.ofJson().getTypeFactory().constructMapType(Map.class, String.class, Object.class));
            List<Map<String, Object>> result = (List<Map<String, Object>>) listResp.getBody().get("result");

            if (result == null || result.isEmpty()) {
                return Optional.empty();
            }

            Map<String, Object> latestDashboard = result.get(0);
            String dashboardId = String.valueOf(latestDashboard.get("id"));
            String dashboardTitle = (String) latestDashboard.get("dashboard_title");
            String changedOn = (String) latestDashboard.get("changed_on");

            // Key-Value Watermark check
            String watermarkKey = "superset_wm_" + context.getFlowId() + "_" + this.getId();
            var kvStore = runContext.namespaceKv(context.getNamespace());
            Optional<String> lastSeen = kvStore.getValue(watermarkKey).map(Object::toString);

            if (lastSeen.isPresent() && lastSeen.get().equals(changedOn)) {
                return Optional.empty();
            }

            // Advance watermark
            kvStore.set(watermarkKey, new io.kestra.core.models.kv.KVEntry(watermarkKey, changedOn));

            Output output = Output.builder()
                .dashboardId(dashboardId)
                .dashboardTitle(dashboardTitle)
                .changedOn(changedOn)
                .build();

            Execution execution = Execution.builder()
                .id(runContext.getTriggerExecutionId())
                .namespace(context.getNamespace())
                .flowId(context.getFlowId())
                .flowRevision(context.getFlowRevision())
                .state(new io.kestra.core.models.flows.State())
                .trigger(ExecutionTrigger.of(this, output))
                .build();

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
}
