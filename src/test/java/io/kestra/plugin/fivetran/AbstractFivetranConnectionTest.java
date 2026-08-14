package io.kestra.plugin.fivetran;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class AbstractFivetranConnectionTest {

    @Test
    @DisplayName("Should leave a connector id without reserved characters unchanged")
    void encodeConnectorIdLeavesSimpleIdUnchanged() {
        assertThat(AbstractFivetranConnection.encodeConnectorId("warn_enable"), is("warn_enable"));
    }

    @Test
    @DisplayName("Should encode a slash so it cannot inject an extra path segment")
    void encodeConnectorIdEncodesSlash() {
        assertThat(AbstractFivetranConnection.encodeConnectorId("a/b"), is("a%2Fb"));
    }

    @Test
    @DisplayName("Should encode a space as %20, not the form-encoding '+', since this is a URL path segment")
    void encodeConnectorIdEncodesSpaceAsPercent20NotPlus() {
        assertThat(AbstractFivetranConnection.encodeConnectorId("a b"), is("a%20b"));
    }
}
