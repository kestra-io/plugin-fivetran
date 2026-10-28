import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { mount } from "@vue/test-utils";
import { createI18n } from "vue-i18n";
import AssetFreshnessFooter from "../../src/components/AssetFreshnessFooter.vue";
import { formatAgo } from "../../src/composables/format";

const NOW = new Date("2024-01-15T12:00:00Z").getTime();

const footer = (metadata: Record<string, unknown>) =>
    mount(AssetFreshnessFooter, {
        props: {
            assetId: "analytics.salesforce.account",
            assetType: "io.kestra.plugin.ee.assets.Table",
            metadata,
        },
        global: { plugins: [createI18n({ legacy: false, locale: "en", messages: { en: {} } })] },
    });

describe("AssetFreshnessFooter", () => {
    beforeEach(() => {
        vi.useFakeTimers();
        vi.setSystemTime(NOW);
    });
    afterEach(() => vi.useRealTimers());

    test("shows the age of the last Fivetran sync and the sync state", () => {
        const wrapper = footer({
            connectorId: "conn_a",
            lastSyncedAt: "2024-01-15T10:00:00Z",
            syncState: "scheduled",
        });

        expect(wrapper.find(".fivetran-footer__text").text()).toBe("Synced 2 h ago");
        expect(wrapper.find(".fivetran-footer__state").text()).toBe("· scheduled");
    });

    test("renders nothing on an asset another plugin last wrote, or without a sync date", () => {
        expect(footer({ lastSyncedAt: "2024-01-15T10:00:00Z" }).html()).not.toContain(
            "fivetran-footer",
        );
        expect(footer({ connectorId: "conn_a" }).html()).not.toContain("fivetran-footer");
        expect(footer({}).html()).not.toContain("fivetran-footer");
    });
});

describe("formatAgo", () => {
    test("rounds down to the largest unit", () => {
        expect(formatAgo("2024-01-15T11:59:40Z", NOW)).toBe("just now");
        expect(formatAgo("2024-01-15T11:15:00Z", NOW)).toBe("45 min ago");
        expect(formatAgo("2024-01-14T12:00:00Z", NOW)).toBe("24 h ago");
        expect(formatAgo("2024-01-10T12:00:00Z", NOW)).toBe("5 d ago");
    });

    test("a missing, unreadable or future date is a placeholder", () => {
        expect(formatAgo(undefined, NOW)).toBe("—");
        expect(formatAgo("nope", NOW)).toBe("—");
        expect(formatAgo("2024-01-16T12:00:00Z", NOW)).toBe("—");
    });
});