import type { KnownSlotProps } from "@kestra-io/artifact-sdk";
import { computed, ref, shallowRef, watch } from "vue";
import { useConnectorData } from "./useConnectorData";

type SlotProps = KnownSlotProps["execution-artifact"];

export type EndpointStatus =
    | "unsupported"
    | "noExecution"
    | "notRun"
    | "running"
    | "error"
    | "loading"
    | "ready";

export type EndpointParams = Record<string, string | number | boolean | undefined | null>;

// fetchEndpoint only takes string values, so unset filters are dropped.
export function toQuery(params: EndpointParams): Record<string, string> {
    const query: Record<string, string> = {};
    for (const [key, value] of Object.entries(params)) {
        if (value !== undefined && value !== null && String(value).trim() !== "") {
            query[key] = String(value);
        }
    }
    return query;
}

type TaskRunState = Pick<
    ReturnType<typeof useConnectorData>,
    "taskRun" | "hasExecution" | "isTerminal"
>;

// Calls one of this plugin's endpoints for the task's current run. The call goes to Kestra, never to Fivetran.
// fetchEndpoint is optional because hosts before 2.1 don't provide it, and failures are best-effort.
// The task run state comes from the caller's own useConnectorData, so the outputs are not fetched twice.
export function useEndpoint<T>(
    props: SlotProps,
    name: string,
    { taskRun, hasExecution, isTerminal }: TaskRunState,
    params: () => EndpointParams = () => ({}),
) {
    const data = shallowRef<T | null>(null);
    const loading = ref(false);
    const failed = ref(false);
    // A response (possibly empty) arrived and no later request failed: rows may be shown, or an empty note.
    const settled = ref(false);
    let requestId = 0;

    const supported = computed(() => typeof props.fetchEndpoint === "function");
    const taskRunId = computed(() => taskRun.value?.id as string | undefined);

    async function load() {
        // Drop a response that resolves after a newer request, e.g. after a quick filter change.
        const current = ++requestId;
        loading.value = true;
        failed.value = false;
        try {
            const response = await props.fetchEndpoint?.(name, toQuery(params()), {
                taskRunId: taskRunId.value,
            });
            if (current === requestId) {
                data.value = (response ?? null) as T | null;
                settled.value = true;
            }
        } catch {
            if (current === requestId) {
                // Rows of a previous connector or filter must not stay next to the error.
                data.value = null;
                settled.value = false;
                failed.value = true;
            }
        } finally {
            if (current === requestId) loading.value = false;
        }
    }

    // The breakdown file only exists once the task run ends: fetch then, and when a filter changes after that.
    watch(
        [taskRunId, isTerminal, () => JSON.stringify(toQuery(params()))],
        () => {
            if (supported.value && taskRunId.value && isTerminal.value) load();
        },
        { immediate: true },
    );

    // Why there is nothing to show yet, or "ready" once a response arrived (kept while a filter reloads).
    const status = computed<EndpointStatus>(() => {
        if (!supported.value) return "unsupported";
        if (!hasExecution.value) return "noExecution";
        if (!taskRunId.value) return "notRun";
        if (!isTerminal.value) return "running";
        if (failed.value) return "error";
        return settled.value ? "ready" : "loading";
    });

    return { data, loading, failed, status, taskRunId, reload: load };
}