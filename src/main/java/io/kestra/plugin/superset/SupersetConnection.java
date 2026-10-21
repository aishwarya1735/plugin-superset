package io.kestra.plugin.superset;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class SupersetConnection extends Task {

    @Schema(title = "The Superset API base URL", description = "Base URL of your Apache Superset instance (e.g. https://superset.example.com/api/v1)")
    @NotNull
    @PluginProperty(group = "connection")
    protected Property<String> url;

    @Schema(title = "The Superset username", description = "Username used to authenticate against Apache Superset")
    @NotNull
    @PluginProperty(group = "connection")
    protected Property<String> username;

    @Schema(title = "The Superset password", description = "Password associated with the username")
    @NotNull
    @PluginProperty(group = "connection", secret = true)
    @ToString.Exclude
    protected Property<String> password;

    @Schema(title = "Authentication provider", description = "Authentication provider, defaults to db")
    @Builder.Default
    @PluginProperty(group = "connection")
    protected Property<String> provider = Property.ofValue("db");

    protected HttpClient createHttpClient(RunContext runContext) {
        return HttpClient.builder().runContext(runContext).build();
    }

    protected String getBaseUrl(RunContext runContext) throws Exception {
        String baseUrl = runContext.render(this.url).as(String.class).orElseThrow();
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    protected String login(HttpClient client, RunContext runContext) throws Exception {
        String baseUrl = getBaseUrl(runContext);
        String user = runContext.render(this.username).as(String.class).orElseThrow();
        String pass = runContext.render(this.password).as(String.class).orElseThrow();
        String loginProvider = runContext.render(this.provider).as(String.class).orElse("db");

        HttpRequest loginReq = HttpRequest.builder()
            .uri(URI.create(baseUrl + "/security/login"))
            .method("POST")
            .body(HttpRequest.JsonRequestBody.builder().content(Map.of(
                "username", user,
                "password", pass,
                "provider", loginProvider,
                "refresh", true
            )).build())
            .build();

        HttpResponse<String> loginResp = client.request(loginReq, String.class);
        LoginResponse response = JacksonMapper.ofJson().readValue(loginResp.getBody(), LoginResponse.class);
        return response.getAccessToken();
    }

    protected String fetchCsrfToken(HttpClient client, RunContext runContext, String accessToken) throws Exception {
        String baseUrl = getBaseUrl(runContext);
        HttpRequest csrfReq = HttpRequest.builder()
            .uri(URI.create(baseUrl + "/security/csrf_token/"))
            .method("GET")
            .addHeader("Authorization", "Bearer " + accessToken)
            .build();

        HttpResponse<String> csrfResp = client.request(csrfReq, String.class);
        CsrfResponse response = JacksonMapper.ofJson().readValue(csrfResp.getBody(), CsrfResponse.class);
        return response.getResult();
    }

    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LoginResponse {
        @JsonProperty("access_token")
        private String accessToken;
    }

    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CsrfResponse {
        private String result;
    }
}
