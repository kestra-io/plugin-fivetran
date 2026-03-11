package io.kestra.plugin.fivetran.connectors;

import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@KestraTest
class EmptyTest {
    @Test
    void run() throws Exception {
        assertThat(true, is(true));
    }
}
