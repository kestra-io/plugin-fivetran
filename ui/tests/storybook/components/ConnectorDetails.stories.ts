import type { Meta, StoryObj } from "@storybook/vue3";
import { setup } from "@storybook/vue3";
import { within, expect, waitFor } from "storybook/test";
import { createI18n } from "vue-i18n";
import KestraDesignSystem from "@kestra-io/design-system";
import ConnectorDetails from "../../../src/components/ConnectorDetails.vue";
import {
    configuredStatusTask,
    configuredSyncTask,
    fetchers,
    preReleaseStatusOutputs,
    runningExecution,
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

const meta: Meta<typeof ConnectorDetails> = {
    title: "Plugin UI / topology-task-modal / ConnectorDetails",
    component: ConnectorDetails,
    tags: ["autodocs"],
};

export default meta;
type Story = StoryObj<typeof ConnectorDetails>;

const flow = { namespace: "company.team", flowId: "fivetran-sync" };

export const SyncNoExecution: Story = {
    name: "Sync: no execution shows settings and an empty state",
    args: { task: configuredSyncTask, ...flow } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getByText("Task settings")).toBeInTheDocument());
        expect(canvas.getByText("my_connector")).toBeInTheDocument();
        expect(canvas.getByText("30 min")).toBeInTheDocument();
        expect(canvas.getByText("5 s")).toBeInTheDocument();
        expect(canvas.getByText("Reattach to running sync")).toBeInTheDocument();
        expect(canvas.getByText("Automatic assets")).toBeInTheDocument();
        expect(canvas.getByText("Run the flow to see the connector details.")).toBeInTheDocument();
    },
};

export const StatusNoExecution: Story = {
    name: "Status: no execution lists the connector IDs",
    args: { task: configuredStatusTask, ...flow } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getByText("conn_a, conn_b, conn_c")).toBeInTheDocument());
        expect(canvas.getByText("10 min")).toBeInTheDocument();
        expect(canvas.getByText("1 h")).toBeInTheDocument();
    },
};

export const SyncWaited: Story = {
    name: "Sync: waited",
    args: { task: syncTask, ...flow, ...fetchers(syncWaitedOutputs) } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getByText("scheduled")).toBeInTheDocument());
        expect(canvas.getByText("on schedule")).toBeInTheDocument();
        expect(canvas.getByText("6 h")).toBeInTheDocument();
        expect(canvas.getByText("12 / 14")).toBeInTheDocument();
        expect(canvas.queryByText(/snapshot taken before/)).toBeNull();
        expect(canvasElement.textContent).not.toContain("tables.ion");
    },
};

export const SyncNotWaited: Story = {
    name: "Sync: wait false shows the pre-trigger note",
    args: {
        task: { ...syncTask, wait: false },
        ...flow,
        ...fetchers(syncNotWaitedOutputs),
    } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getByText(/snapshot taken before/)).toBeInTheDocument());
        expect(canvas.getByText("syncing")).toBeInTheDocument();
    },
};

export const SyncSchemaReadFailed: Story = {
    name: "Sync: failed schema read",
    args: { task: syncTask, ...flow, ...fetchers(syncSchemaFailedOutputs) } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getByText("scheduled")).toBeInTheDocument());
        expect(canvas.queryByText(/snapshot taken before/)).toBeNull();
        expect(canvasElement.textContent).not.toContain("null");
    },
};

export const StatusWithPausedAndAlerts: Story = {
    name: "Status: paused connector with alert codes",
    args: { task: statusTask, ...flow, ...fetchers(statusOutputs) } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() =>
            expect(
                canvas.getByText("Connector conn_b", { selector: "summary" }),
            ).toBeInTheDocument(),
        );
        expect(canvas.getByText("Connector conn_a", { selector: "summary" })).toBeInTheDocument();
        expect(canvas.getByText("Connector conn_c", { selector: "summary" })).toBeInTheDocument();
        expect(canvas.getByText("paused", { selector: "div,span,td,dd" })).toBeInTheDocument();
        expect(canvas.getByText("task_failed")).toBeInTheDocument();
        expect(canvas.getByText("warning_a")).toBeInTheDocument();
        expect(canvas.getByText("5 / 5")).toBeInTheDocument();
        expect(canvasElement.textContent).not.toContain("null");
    },
};

export const StatusPreRelease: Story = {
    name: "Status: execution from before this release",
    args: { task: statusTask, ...flow, ...fetchers(preReleaseStatusOutputs) } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() =>
            expect(
                canvas.getByText("Connector conn_a", { selector: "summary" }),
            ).toBeInTheDocument(),
        );
        expect(canvas.queryByText("Alert codes")).toBeNull();
        expect(canvasElement.textContent).not.toContain("undefined");
    },
};

export const Running: Story = {
    name: "Running: hint until the outputs exist",
    args: {
        task: syncTask,
        ...flow,
        execution: runningExecution,
        fetchOutputs: async () => ({}),
    } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() =>
            expect(
                canvas.getByText("The connector details appear when the task finishes."),
            ).toBeInTheDocument(),
        );
    },
};

// Alert messages and connector config are not in the outputs, and the component only reads the
// documented fields: anything else that shows up must not reach the DOM.
export const IgnoresUnknownFields: Story = {
    name: "Does not render alert messages or config",
    args: {
        task: syncTask,
        ...flow,
        ...fetchers({
            ...syncWaitedOutputs,
            freshness: {
                ...syncWaitedOutputs.freshness,
                alertCodes: ["task_failed"],
                message: "SECRET-ALERT-MESSAGE",
            },
            config: { password: "SECRET-CONFIG-VALUE" },
        }),
    } as any,
    play: async ({ canvasElement }) => {
        const canvas = within(canvasElement);
        await waitFor(() => expect(canvas.getByText("task_failed")).toBeInTheDocument());
        expect(canvasElement.textContent).not.toContain("SECRET");
    },
};