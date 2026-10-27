export const MISSING = "—";

export function formatNumber(value?: number | null): string {
    return typeof value === "number" ? value.toLocaleString("en-US") : MISSING;
}

export function formatBool(value?: boolean | null): string {
    return typeof value === "boolean" ? (value ? "Yes" : "No") : MISSING;
}

// Fivetran states are lower snake case ("on_schedule"): show them as words.
export function formatState(value?: string | null): string {
    return value ? value.replace(/_/g, " ") : MISSING;
}

// "enabled / total"; one missing count (failed schema read) means the breakdown is unknown.
export function formatTables(enabled?: number | null, total?: number | null): string {
    if (typeof enabled !== "number" || typeof total !== "number") return MISSING;
    return `${formatNumber(enabled)} / ${formatNumber(total)}`;
}

// "n of m"; m is the number of connectors, so a missing total is a placeholder.
export function formatRatio(count?: number | null, total?: number | null): string {
    if (typeof count !== "number" || !total) return MISSING;
    return `${formatNumber(count)} of ${formatNumber(total)}`;
}

// Sync frequency is reported in minutes.
export function formatMinutes(value?: number | null): string {
    if (typeof value !== "number") return MISSING;
    if (value < 60) return `${value} min`;
    const hours = Math.floor(value / 60);
    return value % 60 ? `${hours} h ${value % 60} min` : `${hours} h`;
}

// Task properties carry durations as ISO-8601 strings ("PT3M15.5S") or as seconds.
export function formatDuration(value?: string | number | null): string {
    if (value === undefined || value === null || value === "") return MISSING;

    let seconds: number;
    if (typeof value === "number") {
        seconds = value;
    } else {
        const match = /^P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?$/.exec(value);
        if (!match) return value;
        seconds =
            Number(match[1] ?? 0) * 86400 +
            Number(match[2] ?? 0) * 3600 +
            Number(match[3] ?? 0) * 60 +
            Number(match[4] ?? 0);
    }

    if (seconds < 1) return `${Math.round(seconds * 1000)} ms`;
    if (seconds < 60) return `${seconds.toFixed(seconds % 1 === 0 ? 0 : 1)} s`;
    const minutes = Math.floor(seconds / 60);
    const rest = Math.round(seconds % 60);
    if (minutes < 60) return rest ? `${minutes} min ${rest} s` : `${minutes} min`;
    const hours = Math.floor(minutes / 60);
    return minutes % 60 ? `${hours} h ${minutes % 60} min` : `${hours} h`;
}

// Instants arrive as ISO strings; tolerate epoch seconds or milliseconds too.
export function formatDateTime(value?: string | number | null): string {
    if (value === undefined || value === null || value === "") return MISSING;
    const date = new Date(typeof value === "number" && value < 1e11 ? value * 1000 : value);
    return Number.isNaN(date.getTime()) ? MISSING : date.toLocaleString();
}