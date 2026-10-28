import { formatNumber } from "./format";
import { translate } from "./messages";
import { latestTaskRun } from "./useConnectorData";

export interface ArtifactContext {
    task?: Record<string, any>;
    execution?: unknown;
    fetchOutputs?: (query?: { taskRunId?: string }) => Promise<Record<string, any>>;
}

// The Artifacts tab calls getSubtitle with the host props: any failure just leaves the line empty.
async function outputsOf({ task, execution, fetchOutputs }: ArtifactContext) {
    try {
        const taskRunId = latestTaskRun(execution, task?.id)?.id;
        // No task run yet: without an ID the host has to guess one, so don't ask.
        if (!taskRunId) return undefined;
        const outputs = await fetchOutputs?.({ taskRunId });
        return outputs && typeof outputs === "object" && !Array.isArray(outputs)
            ? outputs
            : undefined;
    } catch {
        return undefined;
    }
}

// Sync carries the counts at the top level, Status per connector: the counts of a failed schema read are null.
function tableCounts(outputs: Record<string, any>) {
    const sources = outputs.connectors
        ? Object.values(outputs.connectors as Record<string, any>)
        : [outputs];
    let total = 0;
    let enabled = 0;
    let known = 0;
    for (const source of sources) {
        if (typeof source?.tableCount !== "number" || typeof source?.enabledTableCount !== "number")
            continue;
        total += source.tableCount;
        enabled += source.enabledTableCount;
        known++;
    }
    return known ? { total, enabled } : undefined;
}

export async function tablesSubtitle(context: ArtifactContext): Promise<string | undefined> {
    const outputs = await outputsOf(context);
    const counts = outputs && tableCounts(outputs);
    if (!counts) return undefined;
    return translate(
        "tablesEnabled",
        { enabled: formatNumber(counts.enabled), count: formatNumber(counts.total) },
        counts.total,
    );
}

export async function connectorsSubtitle(context: ArtifactContext): Promise<string | undefined> {
    const connectors = (await outputsOf(context))?.connectors;
    if (!connectors || typeof connectors !== "object") return undefined;
    const states = Object.values(connectors as Record<string, any>);
    const stale = states.filter((state) => state?.fresh === false).length;
    const line = translate("connectors", { count: formatNumber(states.length) }, states.length);
    return stale ? `${line} · ${translate("notFresh", { count: formatNumber(stale) })}` : line;
}