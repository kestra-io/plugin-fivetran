import type { KnownSlotProps } from "@kestra-io/artifact-sdk";
import { computed, ref, shallowRef, watch } from "vue";
import { useConnectorData } from "./useConnectorData";

type SlotProps = KnownSlotProps["execution-artifact"];

export type EndpointStatus =
    | "unsupported"
    | "noExecution"
    | "notRun"
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

// Calls one of this plugin's endpoints for the task's current run. The call goes to Kestra, never to Fivetran.
// fetchEndpoint is optional because hosts before 2.1 don't provide it, and failures are best-effort.
export function useEndpoint<T>(
    props: SlotProps,
    name: string,
    params: () => EndpointParams = () => ({}),
) {
    const data = shallowRef<T | null>(null);
    const loading = ref(false);
    const failed = ref(false);
    let requestId = 0;

    const supported = computed(() => typeof props.fetchEndpoint === "function");
    const { taskRun, taskRunState, hasExecution, isTerminal } = useConnectorData(
        props as KnownSlotProps["topology-details"],
    );
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
            if (current === requestId) data.value = (response ?? null) as T | null;
        } catch {
            if (current === requestId) failed.value = true;
        } finally {
            if (current === requestId) loading.value = false;
        }
    }

    // The breakdown file only exists once the task run ends: reload then, and whenever a filter changes.
    watch(
        [taskRunId, taskRunState, isTerminal, () => JSON.stringify(toQuery(params()))],
        () => {
            if (supported.value && taskRunId.value) load();
        },
        { immediate: true },
    );

    // Why there is nothing to show yet, or "ready" once a response arrived (kept while a filter reloads).
    const status = computed<EndpointStatus>(() => {
        if (!supported.value) return "unsupported";
        if (!hasExecution.value) return "noExecution";
        if (!taskRunId.value) return "notRun";
        if (data.value) return "ready";
        return failed.value ? "error" : "loading";
    });

    return { data, loading, failed, status, taskRunId, reload: load };
}