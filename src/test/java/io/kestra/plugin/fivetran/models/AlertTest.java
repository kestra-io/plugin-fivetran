package io.kestra.plugin.fivetran.models;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.kestra.core.serializers.JacksonMapper;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class AlertTest {
    @Test
    @DisplayName("Should populate code and message when deserializing an alert")
    void deserializesCodeAndMessage() throws Exception {
        var alert = JacksonMapper.ofJson(false).readValue(
            """
                {"code": "resync_table_warning", "message": "Table needs a resync", "details": {"table": "t"}}
                """,
            Alert.class
        );

        assertThat(alert.getCode(), is("resync_table_warning"));
        assertThat(alert.getMessage(), is("Table needs a resync"));
    }
}
