import type { KnownSlotProps } from "@kestra-io/artifact-sdk";
import { computed, ref, watch } from "vue";
import { TERMINAL_STATES } from "./states";

type SlotProps = KnownSlotProps["topology-details"];

// One connector, whichever task produced it; every field is optional (old executions, failed schema read).
export interface ConnectorView {
    id?: string;
    name?: string;
    paused?: boolean | null;
    syncFrequency?: number | null;
    scheduleType?: string | null;
    succeededAt?: string | null;
    failedAt?: string | null;
    completedDate?: string | null;
    syncState?: string | null;
    setupState?: string | null;
    schemaStatus?: string | null;
    updateState?: string | null;
    fresh?: boolean | null;
    alertCount?: number | null;
    alertCodes: string[];
    tableCount?: number | null;
    enabledTableCount?: number | null;
    // Sync only: completion of the sync it observed (null on wait: false).
    observedSucceededAt?: string | null;
}

function fromState(state: Record<string, any>, fallbackId?: string): ConnectorView {
    return {
        id: state.id ?? fallbackId,
        name: state.name,
        paused: state.paused,
        syncFrequency: state.syncFrequency,
        scheduleType: state.scheduleType,
        succeededAt: state.succeededAt,
        failedAt: state.failedAt,
        completedDate: state.completedDate,
        syncState: state.syncState,
        setupState: state.setupState,
        schemaStatus: state.schemaStatus,
        updateState: state.updateState,
        fresh: state.fresh,
        alertCount: state.alertCount,
        alertCodes: Array.isArray(state.alertCodes) ? state.alertCodes.map(String) : [],
        tableCount: state.tableCount,
        enabledTableCount: state.enabledTableCount,
    };
}

// Uses the host's lazy fetcher, never its own HTTP call; the `tables` file is never read.
export function useConnectorData(props: SlotProps) {
    const taskId = computed(() => props.task?.id as string | undefined);
    const hasExecution = computed(() => !!props.execution?.id);
    const executionId = computed(() => props.execution?.id as string | undefined);

    const taskRun = computed(() => {
        const list = props.execution?.taskRunList as any[] | undefined;
        return list?.filter((run: any) => run.taskId === taskId.value).at(-1);
    });

    const outputs = ref<Record<string, any> | null>(null);

    async function load() {
        try {
            outputs.value = (await props.fetchOutputs?.({ taskRunId: taskRun.value?.id })) ?? null;
        } catch {
            /* best-effort */
        }
    }

    // Reload once the task run ends: a card opened mid-run has no outputs yet.
    const taskRunState = computed(() => taskRun.value?.state?.current as string | undefined);
    const isTerminal = computed(() => TERMINAL_STATES.includes(taskRunState.value ?? ""));

    watch(
        [executionId, isTerminal],
        ([id]) => {
            if (id) load();
        },
        { immediate: true },
    );

    const taskOutputs = computed<Record<string, any> | null>(
        () => outputs.value ?? taskRun.value?.outputs ?? null,
    );
    const hasOutputs = computed(
        () => !!taskOutputs.value && Object.keys(taskOutputs.value).length > 0,
    );

    // Status outputs a `connectors` map; Sync outputs a single connector's freshness summary.
    const isStatusOutput = computed(() => {
        const connectors = taskOutputs.value?.connectors;
        return !!connectors && typeof connectors === "object";
    });

    const connectors = computed<ConnectorView[]>(() => {
        const out = taskOutputs.value;
        if (!out) return [];
        if (isStatusOutput.value) {
            return Object.entries(out.connectors as Record<string, Record<string, any>>).map(
                ([id, state]) => fromState(state ?? {}, id),
            );
        }
        if (!out.connectorId && !out.freshness) return [];
        const view = fromState(out.freshness ?? {}, out.connectorId);
        view.tableCount = out.tableCount;
        view.enabledTableCount = out.enabledTableCount;
        view.observedSucceededAt = out.succeededAt;
        return [view];
    });

    // wait: false returns the pre-trigger snapshot, so no sync was observed and freshness is unknown.
    const notWaited = computed(() => {
        const out = taskOutputs.value;
        if (!hasOutputs.value || isStatusOutput.value || !out?.freshness) return false;
        const wait = props.task?.wait;
        return (
            wait === false || wait === "false" || (out.freshness.fresh == null && !out.succeededAt)
        );
    });

    return {
        taskRunState,
        isTerminal,
        hasExecution,
        taskOutputs,
        hasOutputs,
        isStatusOutput,
        connectors,
        notWaited,
    };
}