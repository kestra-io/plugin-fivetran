import type { Meta, StoryObj } from "@storybook/vue3";
import { setup } from "@storybook/vue3";
import { within, expect, waitFor } from "storybook/test";
import { createI18n } from "vue-i18n";
import KestraDesignSystem from "@kestra-io/design-system";
import ConnectorTopologyDetails from "../../../src/components/ConnectorTopologyDetails.vue";
import { formatDateTime } from "../../../src/composables/format";
import {
    SUCCEEDED_AT,
    execution,
    fetchers,
    preReleaseStatusOutputs,
    preReleaseSyncOutputs,
    statusOutputs,
    statusTask,
    syncNotWaitedOutputs,
    syncSchemaFailedOutputs,
    syncTask,
    syncWaitedOutputs,
} from "../fixtures";

const i18n = createI18n({ legacy: false, locale: "en", messages: { en: {} } });
setup((app) => {
    app.use(i18n);
    app.use(KestraDesignSystem);
});

const meta: Meta<typeof ConnectorTopologyDetails> = {
    title: "Plugin UI / topology-details / ConnectorTopologyDetails",
    component: ConnectorTopologyDetails,
    tags: ["autodocs"],
};

export default meta;
type Story = StoryObj<typeof ConnectorTopologyDetails>;

const flow = { namespace: "company.team", flowId: "fivetran-sync" };

export const SyncNoExecution: Story = {
    name: "Sync: no execution shows the settings as written",
    args: { task: { ...syncTask, maxDuration: "PT30M" }, ...flow } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getByText("my_connector")).toBeInTheDocument());
        expect(canvas.getByText("Connector ID")).toBeInTheDocument();
        expect(canvas.getByText("Wait for completion")).toBeInTheDocument();
        expect(canvas.getByText("30 min")).toBeInTheDocument();
        expect(canvas.queryByText("Sync state")).toBeNull();
    },
};

export const StatusNoExecution: Story = {
    name: "Status: no execution shows the settings as written",
    args: {
        task: { ...statusTask, connectorIds: "{{ inputs.ids }}", freshnessBuffer: "PT10M" },
        ...flow,
    } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getByText("{{ inputs.ids }}")).toBeInTheDocument());
        expect(canvas.getByText("Freshness buffer")).toBeInTheDocument();
        expect(canvas.getByText("10 min")).toBeInTheDocument();
        expect(canvas.queryByText("Connectors")).toBeNull();
    },
};

export const SyncWaited: Story = {
    name: "Sync: waited",
    args: { task: syncTask, ...flow, ...fetchers(syncWaitedOutputs) } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getByText("scheduled")).toBeInTheDocument());
        expect(canvas.getByText(formatDateTime(SUCCEEDED_AT))).toBeInTheDocument();
        expect(canvas.getByText("Yes")).toBeInTheDocument();
        expect(canvas.getByText("12 / 14")).toBeInTheDocument();
        // The tables file is never rendered, only counted.
        expect(canvasElement.textContent).not.toContain("tables.ion");
    },
};

export const SyncNotWaited: Story = {
    name: "Sync: wait false shows a placeholder for freshness",
    args: {
        task: { ...syncTask, wait: false },
        ...flow,
        ...fetchers(syncNotWaitedOutputs),
    } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getByText("syncing")).toBeInTheDocument());
        expect(canvas.getByText("Fresh").nextElementSibling?.textContent).toContain("—");
    },
};

export const SyncSchemaReadFailed: Story = {
    name: "Sync: failed schema read",
    args: { task: syncTask, ...flow, ...fetchers(syncSchemaFailedOutputs) } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getByText("scheduled")).toBeInTheDocument());
        expect(canvas.getByText("Yes")).toBeInTheDocument();
        expect(canvas.getByText("Tables (enabled / total)")).toBeInTheDocument();
        expect(canvasElement.textContent).not.toContain("null");
    },
};

export const SyncPreRelease: Story = {
    name: "Sync: execution from before this release",
    args: { task: syncTask, ...flow, ...fetchers(preReleaseSyncOutputs) } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getByText("Sync state")).toBeInTheDocument());
        expect(canvas.getAllByText("—").length).toBe(5);
    },
};

export const StatusWithPausedAndAlerts: Story = {
    name: "Status: paused connector and alerts",
    args: { task: statusTask, ...flow, ...fetchers(statusOutputs) } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getByText("Connectors")).toBeInTheDocument());
        expect(canvas.getByText("3")).toBeInTheDocument();
        // conn_a fresh, conn_b stale, conn_c fresh.
        expect(canvas.getByText("2 of 3")).toBeInTheDocument();
        // Only conn_b is paused and has alerts.
        expect(canvas.getAllByText("1 of 3").length).toBe(2);
        // 8 + 5 enabled of 10 + 5 total, conn_c is unknown and left out.
        expect(canvas.getByText("13 / 15")).toBeInTheDocument();
    },
};

export const StatusPreRelease: Story = {
    name: "Status: execution from before this release",
    args: { task: statusTask, ...flow, ...fetchers(preReleaseStatusOutputs) } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getByText("Connectors")).toBeInTheDocument());
        expect(canvas.getAllByText("—").length).toBe(3);
    },
};

export const RunningNoOutputs: Story = {
    name: "Running: no outputs yet",
    args: {
        task: syncTask,
        ...flow,
        execution,
        fetchOutputs: async () => ({}),
    } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getByText("Sync state")).toBeInTheDocument());
        expect(canvas.getAllByText("—").length).toBe(5);
    },
};

export const FallsBackToTaskRunOutputs: Story = {
    name: "Falls back to the task run outputs without a fetcher",
    args: {
        task: syncTask,
        ...flow,
        execution: {
            ...execution,
            taskRunList: [
                {
                    id: "tr-sync",
                    taskId: "sync",
                    executionId: "exec-abc123",
                    outputs: syncWaitedOutputs,
                },
            ],
        },
    } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getByText("12 / 14")).toBeInTheDocument());
    },
};