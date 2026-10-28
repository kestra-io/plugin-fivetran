import { describe, expect, test, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createI18n } from "vue-i18n";
import { KsInput } from "@kestra-io/design-system";
import TablesArtifact from "../../src/components/TablesArtifact.vue";
import ConnectorsArtifact from "../../src/components/ConnectorsArtifact.vue";
import {
    execution,
    runningExecution,
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
describe("TablesArtifact loading states", () => {
    test("a null response is an empty result, not a view stuck on Loading", async () => {
        const wrapper = mountTables({
            task: syncTask,
            execution,
            fetchOutputs: async () => syncWaitedOutputs,
            fetchEndpoint: async () => null,
        });
        await flushPromises();

        expect(wrapper.text()).not.toContain("Loading");
        expect(wrapper.text()).toContain("No tables");
    });

    test("a failed reload shows the error and drops the rows of the previous request", async () => {
        const fetchEndpoint = vi
            .fn()
            .mockResolvedValueOnce(page([row()]))
            .mockRejectedValue(new Error("boom"));
        const wrapper = mountTables({
            task: syncTask,
            execution,
            fetchOutputs: async () => syncWaitedOutputs,
            fetchEndpoint,
        });
        await flushPromises();
        expect(wrapper.text()).toContain("salesforce.account");

        const search = wrapper.findComponent(KsInput);
        search.vm.$emit("update:modelValue", "lead");
        search.vm.$emit("change", "lead");
        await flushPromises();

        expect(wrapper.text()).toContain("Could not load this view.");
        expect(wrapper.text()).not.toContain("salesforce.account");
    });

    test("fetches nothing while the task run is still running", async () => {
        const fetchEndpoint = vi.fn(async () => page([row()]));
        const wrapper = mountTables({
            task: syncTask,
            execution: runningExecution,
            fetchOutputs: async () => ({}),
            fetchEndpoint,
        });
        await flushPromises();

        expect(fetchEndpoint).not.toHaveBeenCalled();
        expect(wrapper.text()).toContain("Available when the task run ends");
        expect(wrapper.text()).not.toContain("No tables");
    });

    test("reads the task run's outputs once per mount", async () => {
        const fetchOutputs = vi.fn(async () => syncWaitedOutputs);
        mountTables({
            task: syncTask,
            execution,
            fetchOutputs,
            fetchEndpoint: async () => page([row()]),
        });
        await flushPromises();

        expect(fetchOutputs).toHaveBeenCalledTimes(1);
    });
});