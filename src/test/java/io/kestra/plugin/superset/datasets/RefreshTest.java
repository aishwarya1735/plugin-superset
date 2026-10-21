package io.kestra.plugin.superset.datasets;

import io.kestra.core.models.property.Property;
import io.kestra.core.junit.annotations.KestraTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@KestraTest
class RefreshTest {

    @Test
    void testTaskProperties() {
        Refresh refresh = Refresh.builder()
            .url(Property.ofValue("https://localhost:8088/api/v1"))
            .username(Property.ofValue("admin"))
            .password(Property.ofValue("admin"))
            .datasetId(Property.ofValue(1))
            .build();

        assertNotNull(refresh);
        assertNotNull(refresh.getUrl());
        assertNotNull(refresh.getDatasetId());
    }
}
