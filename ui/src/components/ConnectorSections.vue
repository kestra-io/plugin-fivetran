<script setup lang="ts">
import { useI18n } from "vue-i18n";
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
import type { ConnectorView } from "../composables/useConnectorData";

// Past this many connectors, sections start collapsed.
const OPEN_LIMIT = 3;

const { t } = useI18n({
    inheritLocale: true,
    useScope: "local",
    messages: {
        en: {
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
        },
    },
});

// Shared by the details dialog and the Artifacts tab, so both show a connector the same way.
const props = defineProps<{ connectors: ConnectorView[]; isStatusOutput: boolean }>();

const rowsFor = (connector: ConnectorView) => {
    // Sync's freshness summary has no name, schema status, schedule type or completion date.
    const status = props.isStatusOutput;
    const rows = [
        { label: t("id"), value: connector.id ?? MISSING },
        ...(status ? [{ label: t("name"), value: connector.name ?? MISSING }] : []),
        { label: t("syncState"), value: formatState(connector.syncState) },
        { label: t("setupState"), value: formatState(connector.setupState) },
        { label: t("updateState"), value: formatState(connector.updateState) },
        ...(status
            ? [{ label: t("schemaStatus"), value: formatState(connector.schemaStatus) }]
            : []),
        { label: t("paused"), value: formatBool(connector.paused) },
        ...(status ? [{ label: t("scheduleType"), value: connector.scheduleType ?? MISSING }] : []),
        { label: t("syncFrequency"), value: formatMinutes(connector.syncFrequency) },
        { label: t("succeededAt"), value: formatDateTime(connector.succeededAt) },
    ];
    if (connector.observedSucceededAt !== undefined) {
        rows.push({
            label: t("observedSucceededAt"),
            value: formatDateTime(connector.observedSucceededAt),
        });
    }
    rows.push({ label: t("failedAt"), value: formatDateTime(connector.failedAt) });
    if (status) {
        rows.push({ label: t("completedDate"), value: formatDateTime(connector.completedDate) });
    }
    rows.push(
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
            <KsTag v-for="code in connector.alertCodes" :key="code" size="small" type="warning">{{
                code
            }}</KsTag>
        </div>
    </details>
</template>

<!-- Not scoped: ConnectorDetails styles its own settings section with the same classes. -->
<style>
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