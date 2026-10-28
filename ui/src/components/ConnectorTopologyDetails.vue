<script setup lang="ts">
import type { KnownSlotProps } from "@kestra-io/artifact-sdk";
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import { KsTopologyDetails } from "@kestra-io/design-system";
import {
    MISSING,
    formatBool,
    formatDateTime,
    formatNumber,
    formatRatio,
    formatState,
    formatTables,
} from "../composables/format";
import { headlineSettings, isStatusTask } from "../composables/settings";
import { useConnectorData, type ConnectorView } from "../composables/useConnectorData";

const { t } = useI18n({
    inheritLocale: true,
    useScope: "local",
    messages: {
        en: {
            syncState: "Sync state",
            lastSuccess: "Last success",
            fresh: "Fresh",
            alerts: "Alerts",
            tables: "Tables (enabled / total)",
            connectors: "Connectors",
            paused: "Paused",
            withAlerts: "With alerts",
        },
    },
});

const props = defineProps<KnownSlotProps["topology-details"]>();
const { hasExecution, connectors } = useConnectorData(props);

// props.task holds the unrendered task definition, so expressions are shown as written.
const task = computed(() => props.task as Record<string, any> | undefined);

// Counts only the connectors that report the field, so a pre-release execution shows a placeholder.
const countWhere = (
    pick: (connector: ConnectorView) => unknown,
    test: (value: any) => boolean,
): number | undefined =>
    connectors.value.some((connector) => pick(connector) != null)
        ? connectors.value.filter((connector) => test(pick(connector))).length
        : undefined;

function sumTables(pick: (connector: ConnectorView) => number | null | undefined) {
    const known = connectors.value.filter((connector) => typeof pick(connector) === "number");
    return known.length
        ? known.reduce((total, connector) => total + pick(connector)!, 0)
        : undefined;
}

const syncRows = computed(() => {
    const connector = connectors.value[0];
    return [
        { label: t("syncState"), value: formatState(connector?.syncState) },
        { label: t("lastSuccess"), value: formatDateTime(connector?.succeededAt) },
        { label: t("fresh"), value: formatBool(connector?.fresh) },
        { label: t("alerts"), value: formatNumber(connector?.alertCount) },
        {
            label: t("tables"),
            value: formatTables(connector?.enabledTableCount, connector?.tableCount),
        },
    ];
});

const statusRows = computed(() => {
    const total = connectors.value.length;
    return [
        { label: t("connectors"), value: total ? formatNumber(total) : MISSING },
        {
            label: t("fresh"),
            value: formatRatio(
                countWhere(
                    (c) => c.fresh,
                    (value) => value === true,
                ),
                total,
            ),
        },
        {
            label: t("paused"),
            value: formatRatio(
                countWhere(
                    (c) => c.paused,
                    (value) => value === true,
                ),
                total,
            ),
        },
        {
            label: t("withAlerts"),
            value: formatRatio(
                countWhere(
                    (c) => c.alertCount,
                    (value) => typeof value === "number" && value > 0,
                ),
                total,
            ),
        },
        {
            label: t("tables"),
            value: formatTables(
                sumTables((c) => c.enabledTableCount),
                sumTables((c) => c.tableCount),
            ),
        },
    ];
});

const rows = computed(() => {
    if (!hasExecution.value) {
        return headlineSettings(task.value).map(({ label, value }) => ({ label, value }));
    }
    return isStatusTask(task.value) ? statusRows.value : syncRows.value;
});
</script>

<template>
    <KsTopologyDetails :rows="rows" />
</template>