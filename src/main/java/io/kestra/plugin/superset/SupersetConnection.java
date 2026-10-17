package io.kestra.plugin.superset;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class SupersetConnection extends Task {

    @Schema(title = "The Superset API base URL", description = "For example: https://superset.example.com/api/v1")
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
    @SuperBuilder.Default
    protected Property<String> provider = Property.of("db");
}
