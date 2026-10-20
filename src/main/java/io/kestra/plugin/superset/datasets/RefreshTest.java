package io.kestra.plugin.superset.datasets;

import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.junit.annotations.KestraTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@KestraTest
class RefreshTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void testTaskProperties() {
        Refresh refresh = Refresh.builder()
            .url(Property.of("https://localhost:8088/api/v1"))
            .username(Property.of("admin"))
            .password(Property.of("admin"))
            .datasetId(Property.of(1))
            .build();

        assertNotNull(refresh);
        assertNotNull(refresh.getUrl());
        assertNotNull(refresh.getDatasetId());
    }
}
