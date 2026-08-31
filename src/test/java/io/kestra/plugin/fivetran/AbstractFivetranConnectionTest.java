package io.kestra.plugin.fivetran;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

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

    @Test
    @DisplayName("Should leave an id within the 150-character cap untouched")
    void sanitizeAssetIdLeavesAShortIdUnchanged() {
        assertThat(AbstractFivetranConnection.sanitizeAssetId("analytics.salesforce.account"), is("analytics.salesforce.account"));
    }

    @Test
    @DisplayName("Should keep two over-long ids distinct when they share the first 150 characters")
    void sanitizeAssetIdKeepsTruncatedIdsDistinct() {
        // The table name is the last segment, so a `database.schema.` prefix at the cap would truncate every
        // table of that schema onto one id and collapse them into a single node in the graph.
        String prefix = "d".repeat(140) + ".schema.";
        String first = AbstractFivetranConnection.sanitizeAssetId(prefix + "account");
        String second = AbstractFivetranConnection.sanitizeAssetId(prefix + "opportunity");

        assertThat(first.length(), is(lessThanOrEqualTo(150)));
        assertThat(second.length(), is(lessThanOrEqualTo(150)));
        assertThat(first, is(not(second)));
    }

    @Test
    @DisplayName("Should fall back to a placeholder when sanitization leaves nothing usable")
    void sanitizeAssetIdFallsBackWhenTheIdIsEmptied() {
        assertThat(AbstractFivetranConnection.sanitizeAssetId("___"), is("connector"));
    }

    @Test
    @DisplayName("Should trim leading non-alphanumerics to satisfy the asset id contract")
    void sanitizeAssetIdTrimsLeadingNonAlphanumerics() {
        assertThat(AbstractFivetranConnection.sanitizeAssetId("_analytics.schema.table"), startsWith("analytics"));
    }
}
