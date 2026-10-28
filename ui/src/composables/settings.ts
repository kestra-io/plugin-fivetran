import { MISSING, formatDuration } from "./format";

export interface Setting {
    key: string;
    label: string;
    value: string;
}

// Credentials (apiKey, apiSecret) and the base URL are deliberately never read.
const SYNC_KEYS = [
    "connectorId",
    "force",
    "wait",
    "maxDuration",
    "pollFrequency",
    "reattach",
    "reattachMaxAge",
];
const STATUS_KEYS = [
    "connectorIds",
    "wait",
    "maxDuration",
    "pollFrequency",
    "freshnessBuffer",
    "allowTerminal",
];

const LABELS: Record<string, string> = {
    connectorId: "Connector ID",
    connectorIds: "Connector IDs",
    force: "Force restart",
    wait: "Wait for completion",
    maxDuration: "Max duration",
    pollFrequency: "Poll frequency",
    reattach: "Reattach to running sync",
    reattachMaxAge: "Max age of a reattached sync",
    freshnessBuffer: "Freshness buffer",
    allowTerminal: "Allow paused or broken connectors",
    "assets.enableAuto": "Automatic assets",
};

const DURATION_KEYS = ["maxDuration", "pollFrequency", "freshnessBuffer", "reattachMaxAge"];

export function isStatusTask(task?: Record<string, any>): boolean {
    return String(task?.type ?? "").endsWith(".Status");
}

// connectorIds is a list, a JSON-encoded list (the card), or an expression resolving to one.
function show(key: string, value: unknown): string {
    if (value === undefined || value === null) return MISSING;
    if (Array.isArray(value)) return value.map(String).join(", ") || MISSING;
    if (typeof value === "object") return MISSING;
    if (typeof value === "string" && value.trimStart().startsWith("[")) {
        try {
            const parsed = JSON.parse(value);
            if (Array.isArray(parsed)) return parsed.map(String).join(", ") || MISSING;
        } catch {
            /* not JSON: show it as written */
        }
    }
    const text = String(value);
    return DURATION_KEYS.includes(key) && /^PT?\d/.test(text) ? formatDuration(text) : text;
}

// Card rows before a run: values come from the unrendered task, so expressions show as written.
export function headlineSettings(task?: Record<string, any>): Setting[] {
    if (!task) return [];
    const keys = isStatusTask(task)
        ? ["connectorIds", "wait", "maxDuration", "freshnessBuffer"]
        : ["connectorId", "wait", "maxDuration"];
    return keys.map((key) => ({ key, label: LABELS[key] ?? key, value: show(key, task[key]) }));
}

export function taskSettings(task?: Record<string, any>): Setting[] {
    if (!task) return [];

    const settings: Setting[] = [];
    for (const key of isStatusTask(task) ? STATUS_KEYS : SYNC_KEYS) {
        if (task[key] === undefined || task[key] === null) continue;
        settings.push({ key, label: LABELS[key] ?? key, value: show(key, task[key]) });
    }

    const autoAssets = task.assets?.enableAuto;
    if (autoAssets !== undefined && autoAssets !== null) {
        settings.push({
            key: "assets.enableAuto",
            label: LABELS["assets.enableAuto"],
            value: String(autoAssets),
        });
    }
    return settings;
}