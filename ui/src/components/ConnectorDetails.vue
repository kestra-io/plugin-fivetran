<script setup lang="ts">
import type { KnownSlotProps } from "@kestra-io/artifact-sdk";
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import { ElConfigProvider } from "element-plus";
import { KsTag, KsTopologyDetails } from "@kestra-io/design-system";
import {
    MISSING,
    formatBool,
    formatDateTime,
    formatMinutes,
    formatNumber,
    formatState,
    formatTables,
} from "../composables/format";
import { taskSettings } from "../composables/settings";
import { RUNNING_STATES } from "../composables/states";
import { useConnectorData, type ConnectorView } from "../composables/useConnectorData";

// Past this many connectors, sections start collapsed.
const OPEN_LIMIT = 3;

const { t } = useI18n({
    inheritLocale: true,
    useScope: "local",
    messages: {
        en: {
            settings: "Task settings",
            connectors: "Connectors",
            id: "Connector ID",
            name: "Name",
            syncState: "Sync state",
            setupState: "Setup state",
            updateState: "Update state",
            schemaStatus: "Schema status",
            paused: "Paused",
            scheduleType: "Schedule type",
            syncFrequency: "Sync frequency",
            succeededAt: "Last success",
            observedSucceededAt: "Success of this sync",
            failedAt: "Last failure",
            completedDate: "Last completion",
            fresh: "Fresh",
            alerts: "Alerts",
            alertCodes: "Alert codes",
            tables: "Tables (enabled / total)",
            running: "Running",
            runningHint: "The connector details appear when the task finishes.",
            empty: "Run the flow to see the connector details.",
            noOutputs: "This execution has no connector details.",
            notWaited:
                "The task didn't wait for the sync to finish: these values are the snapshot taken before the sync was triggered.",
        },
    },
});

const props = defineProps<KnownSlotProps["topology-task-modal"]>();
const { taskRunState, hasExecution, hasOutputs, connectors, notWaited } = useConnectorData(
    props as KnownSlotProps["topology-details"],
);

const task = computed(() => props.task as Record<string, any> | undefined);
const settingRows = computed(() =>
    taskSettings(task.value).map(({ label, value }) => ({ label, value })),
);

const running = computed(
    () =>
        hasExecution.value &&
        !hasOutputs.value &&
        RUNNING_STATES.includes(taskRunState.value ?? ""),
);

const rowsFor = (connector: ConnectorView) => {
    const rows = [
        { label: t("id"), value: connector.id ?? MISSING },
        { label: t("name"), value: connector.name ?? MISSING },
        { label: t("syncState"), value: formatState(connector.syncState) },
        { label: t("setupState"), value: formatState(connector.setupState) },
        { label: t("updateState"), value: formatState(connector.updateState) },
        { label: t("schemaStatus"), value: formatState(connector.schemaStatus) },
        { label: t("paused"), value: formatBool(connector.paused) },
        { label: t("scheduleType"), value: connector.scheduleType ?? MISSING },
        { label: t("syncFrequency"), value: formatMinutes(connector.syncFrequency) },
        { label: t("succeededAt"), value: formatDateTime(connector.succeededAt) },
    ];
    if (connector.observedSucceededAt !== undefined) {
        rows.push({
            label: t("observedSucceededAt"),
            value: formatDateTime(connector.observedSucceededAt),
        });
    }
    rows.push(
        { label: t("failedAt"), value: formatDateTime(connector.failedAt) },
        { label: t("completedDate"), value: formatDateTime(connector.completedDate) },
        { label: t("fresh"), value: formatBool(connector.fresh) },
        { label: t("alerts"), value: formatNumber(connector.alertCount) },
        {
            label: t("tables"),
            value: formatTables(connector.enabledTableCount, connector.tableCount),
        },
    );
    return rows;
};
</script>

<template>
    <!-- The plugin bundles its own Element Plus, which does not see the host's "kel" namespace: without it the
         design-system styles (tag) never match the rendered "el-*" classes. -->
    <ElConfigProvider namespace="kel">
        <div class="fivetran-details">
            <div v-if="running" class="fivetran-header">
                <KsTag size="small" type="info">{{ t("running") }}</KsTag>
            </div>

            <details v-if="settingRows.length" class="fivetran-section" :open="!hasOutputs">
                <summary class="fivetran-section__title">{{ t("settings") }}</summary>
                <KsTopologyDetails :rows="settingRows" />
            </details>

            <p v-if="!hasExecution" class="fivetran-note">{{ t("empty") }}</p>
            <p v-else-if="running" class="fivetran-note">{{ t("runningHint") }}</p>
            <p v-else-if="!connectors.length" class="fivetran-note">{{ t("noOutputs") }}</p>
            <p v-if="notWaited" class="fivetran-note">{{ t("notWaited") }}</p>

            <details
                v-for="(connector, index) in connectors"
                :key="connector.id ?? index"
                class="fivetran-section"
                :open="connectors.length <= OPEN_LIMIT"
            >
                <summary class="fivetran-section__title">
                    {{ connector.name ?? connector.id ?? t("connectors") }}
                </summary>
                <KsTopologyDetails :rows="rowsFor(connector)" />
                <div v-if="connector.alertCodes.length" class="fivetran-tags">
                    <span class="fivetran-muted">{{ t("alertCodes") }}</span>
                    <KsTag
                        v-for="code in connector.alertCodes"
                        :key="code"
                        size="small"
                        type="warning"
                        >{{ code }}</KsTag
                    >
                </div>
            </details>
        </div>
    </ElConfigProvider>
</template>

<style scoped>
.fivetran-details {
    font-size: var(--ks-font-size-xs);
}

.fivetran-header {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--ks-spacing-2);
    margin: var(--ks-spacing-3) var(--ks-spacing-3) 0;
}

.fivetran-section {
    margin: var(--ks-spacing-3) var(--ks-spacing-3) 0;
    padding-top: var(--ks-spacing-2);
    border-top: 1px solid var(--ks-border-primary);
}

.fivetran-section__title {
    display: flex;
    align-items: center;
    gap: var(--ks-spacing-1);
    margin-bottom: var(--ks-spacing-2);
    font-weight: 600;
    color: var(--ks-text-primary, inherit);
    cursor: pointer;
    list-style: none;
    user-select: none;
}

.fivetran-section__title::-webkit-details-marker {
    display: none;
}

.fivetran-section__title::before {
    content: "▶";
    font-size: 0.5rem;
    transition: transform 0.15s;
}

details[open] > .fivetran-section__title::before {
    transform: rotate(90deg);
}

.fivetran-note {
    margin: var(--ks-spacing-3);
    color: var(--ks-text-secondary);
}

.fivetran-tags {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--ks-spacing-1);
    margin-top: var(--ks-spacing-2);
}

.fivetran-muted {
    color: var(--ks-text-secondary);
}
</style>