export const syncTask = {
    id: "sync",
    type: "io.kestra.plugin.fivetran.connectors.Sync",
    connectorId: "my_connector",
};

export const statusTask = {
    id: "status",
    type: "io.kestra.plugin.fivetran.connectors.Status",
    connectorIds: ["conn_a", "conn_b", "conn_c"],
};

export const execution = {
    id: "exec-abc123",
    namespace: "company.team",
    flowId: "fivetran-sync",
    state: { current: "SUCCESS", startDate: "2024-01-15T10:00:00Z" } as any,
    taskRunList: [
        { id: "tr-sync", taskId: "sync", executionId: "exec-abc123" },
        { id: "tr-status", taskId: "status", executionId: "exec-abc123" },
    ],
} as any;

export const runningExecution = {
    ...execution,
    state: { current: "RUNNING", startDate: "2024-01-15T10:00:00Z" } as any,
    taskRunList: [
        {
            id: "tr-sync",
            taskId: "sync",
            executionId: "exec-abc123",
            state: { current: "RUNNING" },
        },
    ],
} as any;

export const SUCCEEDED_AT = "2024-01-15T10:03:15Z";
export const FAILED_AT = "2024-01-14T08:00:00Z";

// Sync with wait: true: the freshness is read after the sync completed.
export const syncWaitedOutputs = {
    connectorId: "my_connector",
    succeededAt: SUCCEEDED_AT,
    reattached: false,
    freshness: {
        syncState: "scheduled",
        setupState: "connected",
        updateState: "on_schedule",
        paused: false,
        succeededAt: SUCCEEDED_AT,
        failedAt: FAILED_AT,
        syncFrequency: 360,
        fresh: true,
        alertCount: 0,
        alertCodes: [],
    },
    tables: "kestra:///company/team/tables.ion",
    tableCount: 14,
    enabledTableCount: 12,
};

// wait: false: the snapshot taken before the trigger, no observed sync, freshness unknown.
export const syncNotWaitedOutputs = {
    connectorId: "my_connector",
    succeededAt: null,
    reattached: false,
    freshness: {
        syncState: "syncing",
        setupState: "connected",
        updateState: "on_schedule",
        paused: false,
        succeededAt: "2024-01-15T04:00:00Z",
        syncFrequency: 360,
        fresh: null,
        alertCount: 0,
        alertCodes: [],
    },
    tables: "kestra:///company/team/tables.ion",
    tableCount: 14,
    enabledTableCount: 12,
};

// The schema read failed: the connector is fine but there are no table counts.
export const syncSchemaFailedOutputs = {
    ...syncWaitedOutputs,
    tables: null,
    tableCount: null,
    enabledTableCount: null,
};

// An execution from before this release: only the connector ID.
export const preReleaseSyncOutputs = { connectorId: "my_connector" };

const state = (id: string, extra: Record<string, any> = {}) => ({
    id,
    name: `Connector ${id}`,
    paused: false,
    syncFrequency: 360,
    scheduleType: "auto",
    succeededAt: SUCCEEDED_AT,
    failedAt: FAILED_AT,
    completedDate: SUCCEEDED_AT,
    hasFailed: false,
    syncState: "scheduled",
    setupState: "connected",
    schemaStatus: "ready",
    fresh: true,
    updateState: "on_schedule",
    alertCount: 0,
    alertCodes: [],
    tables: `kestra:///company/team/${id}.ion`,
    tableCount: 10,
    enabledTableCount: 8,
    ...extra,
});

// One fresh connector, one paused with alerts, one with a failed schema read.
export const statusOutputs = {
    connectors: {
        conn_a: state("conn_a"),
        conn_b: state("conn_b", {
            paused: true,
            syncState: "paused",
            fresh: false,
            updateState: "delayed",
            alertCount: 2,
            alertCodes: ["task_failed", "warning_a"],
            tableCount: 5,
            enabledTableCount: 5,
        }),
        conn_c: state("conn_c", {
            tables: null,
            tableCount: null,
            enabledTableCount: null,
        }),
    },
};

// An execution from before this release: no freshness, alert or table fields.
export const preReleaseStatusOutputs = {
    connectors: {
        conn_a: {
            id: "conn_a",
            name: "Connector conn_a",
            paused: false,
            syncState: "scheduled",
            setupState: "connected",
        },
    },
};

export const fetchers = (outputs: Record<string, any>) => ({
    execution,
    fetchOutputs: async () => outputs,
});

export const configuredSyncTask = {
    ...syncTask,
    force: false,
    wait: true,
    maxDuration: "PT30M",
    pollFrequency: "PT5S",
    reattach: true,
    assets: { enableAuto: true },
};

export const configuredStatusTask = {
    ...statusTask,
    wait: true,
    maxDuration: "PT1H",
    freshnessBuffer: "PT10M",
    allowTerminal: false,
};