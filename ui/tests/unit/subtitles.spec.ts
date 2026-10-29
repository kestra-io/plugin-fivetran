import { describe, expect, test } from "vitest";
import { connectorsSubtitle, tablesSubtitle } from "../../src/composables/subtitles";
import {
    execution,
    statusOutputs,
    statusTask,
    syncSchemaFailedOutputs,
    syncTask,
    syncWaitedOutputs,
} from "../storybook/fixtures";

const context = (task: Record<string, any>, outputs: unknown) => ({
    task,
    execution,
    fetchOutputs: async () => outputs as Record<string, any>,
});

describe("Artifacts tab subtitles", () => {
    test("Sync tables: enabled of total", async () => {
        expect(await tablesSubtitle(context(syncTask, syncWaitedOutputs))).toBe(
            "12 of 14 tables enabled",
        );
    });

    test("Status tables: summed over the connectors whose schema read succeeded", async () => {
        // conn_c has null counts, so only conn_a (8/10) and conn_b (5/5) count.
        expect(await tablesSubtitle(context(statusTask, statusOutputs))).toBe(
            "13 of 15 tables enabled",
        );
    });

    test("no counts, no subtitle", async () => {
        expect(await tablesSubtitle(context(syncTask, syncSchemaFailedOutputs))).toBeUndefined();
    });

    test("connectors: count and the ones not fresh", async () => {
        expect(await connectorsSubtitle(context(statusTask, statusOutputs))).toBe(
            "3 connectors · 1 not fresh",
        );
        const allFresh = { connectors: { conn_a: statusOutputs.connectors.conn_a } };
        expect(await connectorsSubtitle(context(statusTask, allFresh))).toBe("1 connector");
    });

    test("no task run, failing fetch or a non-object answer leave the line empty", async () => {
        const notRun = { ...context(statusTask, statusOutputs), execution: { taskRunList: [] } };
        expect(await connectorsSubtitle(notRun)).toBeUndefined();

        const failing = {
            task: statusTask,
            execution,
            fetchOutputs: async () => {
                throw new Error("boom");
            },
        };
        expect(await connectorsSubtitle(failing)).toBeUndefined();
        expect(await tablesSubtitle(context(syncTask, [1, 2]))).toBeUndefined();
    });
});