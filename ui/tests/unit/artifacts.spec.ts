import { describe, expect, test, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createI18n } from "vue-i18n";
import TablesArtifact from "../../src/components/TablesArtifact.vue";
import ConnectorsArtifact from "../../src/components/ConnectorsArtifact.vue";
import {
    execution,
    statusOutputs,
    statusTask,
    syncTask,
    syncWaitedOutputs,
} from "../storybook/fixtures";

const i18n = () => createI18n({ legacy: false, locale: "en", messages: { en: {} } });

const page = (results: Record<string, unknown>[]) => ({
    page: 1,
    size: 50,
    total: results.length,
    tableCount: results.length,
    results,
});

const row = (extra: Record<string, unknown> = {}) => ({
    schema: "salesforce",
    schemaInDestination: "salesforce",
    schemaEnabled: true,
    table: "account",
    tableInDestination: "account",
    enabled: true,
    syncMode: "SOFT_DELETE",
    enabledPatchAllowed: true,
    ...extra,
});

function mountTables(props: Record<string, unknown>) {
    return mount(TablesArtifact, {
        props: { taskType: "io.kestra.plugin.fivetran.connectors.Sync", ...props } as any,
        global: { plugins: [i18n()] },
    });
}

describe("TablesArtifact", () => {
    test("asks the tables endpoint for the task's run and renders its rows", async () => {
        const fetchEndpoint = vi.fn(async () =>
            page([
                row(),
                row({ table: "lead", tableInDestination: "leads", enabled: false }),
                row({
                    schema: "hubspot",
                    schemaEnabled: false,
                    table: "contact",
                    enabledPatchAllowed: false,
                }),
            ]),
        );
        const wrapper = mountTables({
            task: syncTask,
            execution,
            fetchOutputs: async () => syncWaitedOutputs,
            fetchEndpoint,
        });
        await flushPromises();

        expect(fetchEndpoint).toHaveBeenCalledWith(
            "tables",
            { page: "1", size: "50" },
            { taskRunId: "tr-sync" },
        );
        const text = wrapper.text();
        expect(text).toContain("salesforce.account");
        expect(text).toContain("soft delete");
        // A renamed table shows its destination name, an unrenamed one doesn't repeat it.
        expect(text).toContain("salesforce.leads");
        expect(text).toContain("Disabled");
        expect(text).toContain("Schema disabled");
        expect(text).toContain("Locked");
    });

    test("on Status, picks the first connector and passes it to the endpoint", async () => {
        const fetchEndpoint = vi.fn(async () => page([row()]));
        mountTables({
            taskType: "io.kestra.plugin.fivetran.connectors.Status",
            task: statusTask,
            execution,
            fetchOutputs: async () => statusOutputs,
            fetchEndpoint,
        });
        await flushPromises();

        expect(fetchEndpoint).toHaveBeenLastCalledWith(
            "tables",
            { connectorId: "conn_a", page: "1", size: "50" },
            { taskRunId: "tr-status" },
        );
    });

    test("a host without fetchEndpoint shows an upgrade note instead of failing", async () => {
        const wrapper = mountTables({
            task: syncTask,
            execution,
            fetchOutputs: async () => syncWaitedOutputs,
        });
        await flushPromises();

        expect(wrapper.text()).toContain("Upgrade Kestra");
    });

    test("an empty breakdown and a failing endpoint render notes", async () => {
        const empty = mountTables({
            task: syncTask,
            execution,
            fetchOutputs: async () => syncWaitedOutputs,
            fetchEndpoint: async () => page([]),
        });
        await flushPromises();
        expect(empty.text()).toContain("No tables");

        const failing = mountTables({
            task: syncTask,
            execution,
            fetchOutputs: async () => syncWaitedOutputs,
            fetchEndpoint: async () => {
                throw new Error("boom");
            },
        });
        await flushPromises();
        expect(failing.text()).toContain("Could not load this view.");
    });

    test("no execution asks to run the flow and calls nothing", async () => {
        const fetchEndpoint = vi.fn();
        const wrapper = mountTables({ task: syncTask, fetchEndpoint });
        await flushPromises();

        expect(fetchEndpoint).not.toHaveBeenCalled();
        expect(wrapper.text()).toContain("Run the flow");
    });
});

describe("ConnectorsArtifact", () => {
    test("renders every connector from the outputs, with the design-system namespace", async () => {
        const wrapper = mount(ConnectorsArtifact, {
            props: {
                taskType: "io.kestra.plugin.fivetran.connectors.Status",
                task: statusTask,
                execution,
                fetchOutputs: async () => statusOutputs,
            } as any,
            global: { plugins: [i18n()] },
        });
        await flushPromises();

        const text = wrapper.text();
        expect(text).toContain("Connector conn_a");
        expect(text).toContain("Connector conn_c");
        expect(text).toContain("task_failed");
        expect(wrapper.find(".kel-tag").exists()).toBe(true);
    });

    test("no execution shows a note", async () => {
        const wrapper = mount(ConnectorsArtifact, {
            props: {
                taskType: "io.kestra.plugin.fivetran.connectors.Status",
                task: statusTask,
            } as any,
            global: { plugins: [i18n()] },
        });
        await flushPromises();

        expect(wrapper.text()).toContain("Run the flow");
    });
});
describe("TablesArtifact state filter", () => {
    test("shows 'All tables' by default and sends no enabled filter", async () => {
        const fetchEndpoint = vi.fn(async () => page([row()]));
        const wrapper = mountTables({
            task: syncTask,
            execution,
            fetchOutputs: async () => syncWaitedOutputs,
            fetchEndpoint,
        });
        await flushPromises();

        // An empty value made the select show its placeholder instead of the selected option.
        expect(wrapper.find(".fivetran-tables__state").text()).toContain("All tables");
        expect(fetchEndpoint.mock.calls[0][1]).not.toHaveProperty("enabled");
    });
});