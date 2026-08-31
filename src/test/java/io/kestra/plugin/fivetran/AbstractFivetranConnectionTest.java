package io.kestra.plugin.fivetran;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class AbstractFivetranConnectionTest {

    @Test
    @DisplayName("Should leave a connector id without reserved characters unchanged")
    void encodePathSegmentLeavesSimpleIdUnchanged() {
        assertThat(AbstractFivetranConnection.encodePathSegment("warn_enable"), is("warn_enable"));
    }

    @Test
    @DisplayName("Should encode a slash so it cannot inject an extra path segment")
    void encodePathSegmentEncodesSlash() {
        assertThat(AbstractFivetranConnection.encodePathSegment("a/b"), is("a%2Fb"));
    }

    @Test
    @DisplayName("Should encode a space as %20, not the form-encoding '+', since this is a URL path segment")
    void encodePathSegmentEncodesSpaceAsPercent20NotPlus() {
        assertThat(AbstractFivetranConnection.encodePathSegment("a b"), is("a%20b"));
    }
}
