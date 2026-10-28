import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { mount, type VueWrapper } from "@vue/test-utils";
import { createI18n } from "vue-i18n";
import AssetFreshnessFooter from "../../src/components/AssetFreshnessFooter.vue";
import { ageOf } from "../../src/composables/format";

const NOW = new Date("2024-01-15T12:00:00Z").getTime();

const mounted: VueWrapper[] = [];
const footer = (metadata: Record<string, unknown>) => {
    const wrapper = mount(AssetFreshnessFooter, {
        props: {
            assetId: "analytics.salesforce.account",
            assetType: "io.kestra.plugin.ee.assets.Table",
            metadata,
        },
        global: { plugins: [createI18n({ legacy: false, locale: "en", messages: { en: {} } })] },
    });
    mounted.push(wrapper);
    return wrapper;
};

describe("AssetFreshnessFooter", () => {
    beforeEach(() => {
        vi.useFakeTimers();
        vi.setSystemTime(NOW);
    });
    afterEach(() => {
        // The clock is shared across mounts, so a footer left mounted would leak its interval into the next test.
        mounted.splice(0).forEach((wrapper) => wrapper.unmount());
        vi.useRealTimers();
    });

    test("shows the age of the last Fivetran sync and the sync state", () => {
        const wrapper = footer({
            connectorId: "conn_a",
            lastSyncedAt: "2024-01-15T10:00:00Z",
            syncState: "scheduled",
        });

        expect(wrapper.find(".fivetran-footer__text").text()).toBe("Synced 2 h ago");
        expect(wrapper.find(".fivetran-footer__state").text()).toBe("· scheduled");
    });

    test("keeps the age current while the graph stays open", async () => {
        const wrapper = footer({ connectorId: "conn_a", lastSyncedAt: "2024-01-15T11:59:30Z" });
        expect(wrapper.find(".fivetran-footer__text").text()).toBe("Synced just now");

        await vi.advanceTimersByTimeAsync(5 * 60_000);

        expect(wrapper.find(".fivetran-footer__text").text()).toBe("Synced 5 min ago");
    });

    test("stops ticking once unmounted", () => {
        const wrapper = footer({ connectorId: "conn_a", lastSyncedAt: "2024-01-15T11:59:30Z" });
        expect(vi.getTimerCount()).toBe(1);

        wrapper.unmount();

        expect(vi.getTimerCount()).toBe(0);
    });

    test("shares one clock between footers and stops it with the last one", () => {
        const first = footer({ connectorId: "conn_a", lastSyncedAt: "2024-01-15T11:59:30Z" });
        const second = footer({ connectorId: "conn_b", lastSyncedAt: "2024-01-15T11:59:30Z" });
        expect(vi.getTimerCount()).toBe(1);

        first.unmount();
        expect(vi.getTimerCount()).toBe(1);

        second.unmount();
        expect(vi.getTimerCount()).toBe(0);
    });

    test("renders nothing on an asset another plugin last wrote, or without a sync date", () => {
        expect(footer({ lastSyncedAt: "2024-01-15T10:00:00Z" }).html()).not.toContain(
            "fivetran-footer",
        );
        expect(footer({ connectorId: "conn_a" }).html()).not.toContain("fivetran-footer");
        expect(footer({}).html()).not.toContain("fivetran-footer");
    });
});

describe("ageOf", () => {
    test("rounds down to the largest unit", () => {
        expect(ageOf("2024-01-15T11:59:40Z", NOW)).toEqual({ unit: "now", count: 0 });
        expect(ageOf("2024-01-15T11:15:00Z", NOW)).toEqual({ unit: "minute", count: 45 });
        expect(ageOf("2024-01-14T12:00:00Z", NOW)).toEqual({ unit: "hour", count: 24 });
        expect(ageOf("2024-01-10T12:00:00Z", NOW)).toEqual({ unit: "day", count: 5 });
    });

    test("a missing, unreadable or future date is null", () => {
        expect(ageOf(undefined, NOW)).toBeNull();
        expect(ageOf("nope", NOW)).toBeNull();
        expect(ageOf("2024-01-16T12:00:00Z", NOW)).toBeNull();
    });
});