package io.kestra.plugin.superset;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.kestra.core.http.HttpRequest;
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

    protected HttpClient createHttpClient(RunContext runContext) throws Exception {
        return HttpClient.builder().runContext(runContext).build();
    }

    public static String resolveBaseUrl(RunContext runContext, Property<String> urlProp) throws Exception {
        var rUrl = runContext.render(urlProp).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("url is required"));
        return rUrl.endsWith("/") ? rUrl.substring(0, rUrl.length() - 1) : rUrl;
    }

    protected String getBaseUrl(RunContext runContext) throws Exception {
        return resolveBaseUrl(runContext, this.url);
    }

    public static String authenticate(
        HttpClient client,
        RunContext runContext,
        String baseUrl,
        Property<String> usernameProp,
        Property<String> passwordProp,
        Property<String> providerProp
    ) throws Exception {
        var rUsername = runContext.render(usernameProp).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("username is required"));
        var rPassword = runContext.render(passwordProp).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("password is required"));
        var rProvider = runContext.render(providerProp).as(String.class).orElse("db");

        var loginReq = HttpRequest.builder()
            .uri(URI.create(baseUrl + "/security/login"))
            .method("POST")
            .body(HttpRequest.JsonRequestBody.builder().content(Map.of(
                "username", rUsername,
                "password", rPassword,
                "provider", rProvider
            )).build())
            .build();

        var loginResp = client.request(loginReq, String.class);
        if (loginResp.getBody() == null || loginResp.getBody().isBlank()) {
            throw new IllegalStateException("Superset login returned an empty response body, check url and credentials");
        }

        var response = JacksonMapper.ofJson().readValue(loginResp.getBody(), LoginResponse.class);
        if (response == null || response.getAccessToken() == null || response.getAccessToken().isBlank()) {
            throw new IllegalStateException("Superset login returned no access_token, check username, password and provider");
        }
        return response.getAccessToken();
    }

    protected String login(HttpClient client, RunContext runContext) throws Exception {
        var rBaseUrl = getBaseUrl(runContext);
        return authenticate(client, runContext, rBaseUrl, this.username, this.password, this.provider);
    }

    protected String fetchCsrfToken(HttpClient client, RunContext runContext, String accessToken) throws Exception {
        var rBaseUrl = getBaseUrl(runContext);
        var csrfReq = HttpRequest.builder()
            .uri(URI.create(rBaseUrl + "/security/csrf_token/"))
            .method("GET")
            .addHeader("Authorization", "Bearer " + accessToken)
            .build();

        var csrfResp = client.request(csrfReq, String.class);
        if (csrfResp.getBody() == null || csrfResp.getBody().isBlank()) {
            throw new IllegalStateException("Superset CSRF endpoint returned an empty response body");
        }

        var response = JacksonMapper.ofJson().readValue(csrfResp.getBody(), CsrfResponse.class);
        if (response == null || response.getResult() == null || response.getResult().isBlank()) {
            throw new IllegalStateException("Superset CSRF endpoint returned no token in result field");
        }
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
