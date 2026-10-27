import { describe, expect, test } from "vitest";
import {
    formatBool,
    formatDuration,
    formatMinutes,
    formatRatio,
    formatState,
    formatTables,
} from "../../src/composables/format";
import { headlineSettings, taskSettings } from "../../src/composables/settings";

describe("format", () => {
    test("formats ISO-8601 and numeric durations", () => {
        expect(formatDuration("PT3M15S")).toBe("3 min 15 s");
        expect(formatDuration("PT0.25S")).toBe("250 ms");
        expect(formatDuration(42)).toBe("42 s");
        expect(formatDuration(undefined)).toBe("—");
        expect(formatDuration("{{ vars.max }}")).toBe("{{ vars.max }}");
    });

    test("formats sync frequency in minutes", () => {
        expect(formatMinutes(15)).toBe("15 min");
        expect(formatMinutes(360)).toBe("6 h");
        expect(formatMinutes(90)).toBe("1 h 30 min");
        expect(formatMinutes(null)).toBe("—");
    });

    test("formats booleans, states and counts with placeholders", () => {
        expect(formatBool(true)).toBe("Yes");
        expect(formatBool(false)).toBe("No");
        expect(formatBool(null)).toBe("—");
        expect(formatState("on_schedule")).toBe("on schedule");
        expect(formatState(undefined)).toBe("—");
        expect(formatTables(12, 14)).toBe("12 / 14");
        expect(formatTables(null, null)).toBe("—");
        expect(formatTables(0, 0)).toBe("0 / 0");
        expect(formatRatio(2, 3)).toBe("2 of 3");
        expect(formatRatio(undefined, 3)).toBe("—");
        expect(formatRatio(0, 0)).toBe("—");
    });
});

describe("settings", () => {
    test("headline rows use the real property names, with placeholders", () => {
        const sync = headlineSettings({
            type: "io.kestra.plugin.fivetran.connectors.Sync",
            connectorId: "c1",
        });
        expect(sync.map((s) => s.key)).toEqual(["connectorId", "wait", "maxDuration"]);
        expect(sync.map((s) => s.value)).toEqual(["c1", "—", "—"]);

        const status = headlineSettings({
            type: "io.kestra.plugin.fivetran.connectors.Status",
            connectorIds: ["a", "b"],
            freshnessBuffer: "PT10M",
        });
        expect(status.map((s) => s.key)).toEqual([
            "connectorIds",
            "wait",
            "maxDuration",
            "freshnessBuffer",
        ]);
        expect(status[0].value).toBe("a, b");
        expect(status[3].value).toBe("10 min");
    });

    test("never reads credentials", () => {
        const settings = taskSettings({
            type: "io.kestra.plugin.fivetran.connectors.Sync",
            connectorId: "c1",
            apiKey: "KEY-SECRET",
            apiSecret: "SECRET-SECRET",
        });
        expect(JSON.stringify(settings)).not.toContain("SECRET");
    });

    test("joins a JSON-encoded connectorIds list like an array", () => {
        const status = (connectorIds: unknown) =>
            headlineSettings({
                type: "io.kestra.plugin.fivetran.connectors.Status",
                connectorIds,
            })[0].value;
        expect(status('["a","b","c"]')).toBe("a, b, c");
        expect(status("[not json")).toBe("[not json");
        expect(status("{{ vars.ids }}")).toBe("{{ vars.ids }}");
    });

    test("lists reattachMaxAge on Sync as a readable duration", () => {
        const row = taskSettings({
            type: "io.kestra.plugin.fivetran.connectors.Sync",
            reattachMaxAge: "PT2H",
        }).find((s) => s.key === "reattachMaxAge");
        expect(row).toEqual({
            key: "reattachMaxAge",
            label: "Max age of a reattached sync",
            value: "2 h",
        });
    });
});