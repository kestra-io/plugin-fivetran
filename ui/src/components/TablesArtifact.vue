<script lang="ts">
import { tablesSubtitle } from "../composables/subtitles";

// The line under the title in the Artifacts tab, read from the table counts in the outputs.
export const getSubtitle = tablesSubtitle;
</script>

<script setup lang="ts">
import type { KnownSlotProps } from "@kestra-io/artifact-sdk";
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { KsInput, KsOption, KsPagination, KsSelect, KsTag } from "@kestra-io/design-system";
import { MISSING, formatState } from "../composables/format";
import { useConnectorData } from "../composables/useConnectorData";
import { useEndpoint } from "../composables/useEndpoint";
import ArtifactFrame from "./ArtifactFrame.vue";

const PAGE_SIZE = 50;

// One row of the breakdown, as the `tables` endpoint returns it: only these fields exist.
interface TableRow {
    schema?: string;
    schemaInDestination?: string;
    schemaEnabled?: boolean;
    table?: string;
    tableInDestination?: string;
    enabled?: boolean;
    syncMode?: string | null;
    enabledPatchAllowed?: boolean | null;
}

interface TablesPage {
    page: number;
    size: number;
    total: number;
    tableCount: number;
    results: TableRow[];
}

const { t } = useI18n({
    inheritLocale: true,
    useScope: "local",
    messages: {
        en: {
            connector: "Connector",
            search: "Filter by schema or table",
            state: "State",
            all: "All tables",
            synced: "Synced",
            notSynced: "Not synced",
            schema: "Schema",
            table: "Table",
            destination: "In destination",
            syncMode: "Sync mode",
            status: "Status",
            schemaDisabled: "Schema disabled",
            disabled: "Disabled",
            enabled: "Enabled",
            locked: "Locked",
            lockedHint: "Fivetran doesn't allow enabling or disabling this table.",
            noMatch: "No table matches.",
            empty: "No tables: the schema config could not be read, or the connector has none.",
        },
    },
});

const props = defineProps<KnownSlotProps["execution-artifact"]>();

// On Status, one breakdown per connector: the picker lists the ones that have one.
const { isStatusOutput, connectors } = useConnectorData(
    props as KnownSlotProps["topology-details"],
);
const connectorOptions = computed(() =>
    isStatusOutput.value ? connectors.value.filter((connector) => connector.id) : [],
);
const connectorId = ref<string>();
const selectedConnector = computed(() => connectorId.value ?? connectorOptions.value[0]?.id);

const query = ref("");
// The text is applied on Enter or blur, so typing does not call the endpoint on every keystroke.
const draft = ref("");
const state = ref<"" | "true" | "false">("");
const page = ref(1);
const resetPage = () => (page.value = 1);

const { data, status } = useEndpoint<TablesPage>(props, "tables", () => ({
    connectorId: isStatusOutput.value ? selectedConnector.value : undefined,
    q: query.value,
    enabled: state.value,
    page: page.value,
    size: PAGE_SIZE,
}));

const rows = computed(() =>
    (data.value?.results ?? []).map((row) => {
        const source = [row.schema, row.table].filter(Boolean).join(".") || MISSING;
        const destination = [row.schemaInDestination, row.tableInDestination]
            .filter(Boolean)
            .join(".");
        return {
            key: source,
            source,
            // Only shown when Fivetran renames it, otherwise it repeats the source name.
            destination: destination && destination !== source ? destination : "",
            syncMode: row.syncMode ? formatState(row.syncMode.toLowerCase()) : MISSING,
            schemaEnabled: row.schemaEnabled !== false,
            enabled: row.enabled !== false,
            locked: row.enabledPatchAllowed === false,
        };
    }),
);
const filtered = computed(() => !!query.value || !!state.value);
</script>

<template>
    <ArtifactFrame :status="status">
        <div class="fivetran-artifact__filters">
            <KsSelect
                v-if="connectorOptions.length > 1"
                :model-value="selectedConnector"
                size="small"
                class="fivetran-tables__connector"
                :aria-label="t('connector')"
                @update:model-value="
                    (value: string) => {
                        connectorId = value;
                        resetPage();
                    }
                "
            >
                <KsOption
                    v-for="connector in connectorOptions"
                    :key="connector.id"
                    :label="connector.name ?? connector.id"
                    :value="connector.id"
                />
            </KsSelect>
            <KsSelect
                v-model="state"
                size="small"
                class="fivetran-tables__state"
                :aria-label="t('state')"
                @change="resetPage"
            >
                <KsOption :label="t('all')" value="" />
                <KsOption :label="t('synced')" value="true" />
                <KsOption :label="t('notSynced')" value="false" />
            </KsSelect>
            <KsInput
                v-model="draft"
                clearable
                size="small"
                class="fivetran-tables__search"
                :placeholder="t('search')"
                @change="
                    () => {
                        query = draft;
                        resetPage();
                    }
                "
            />
        </div>

        <p v-if="!rows.length" class="fivetran-artifact__note">
            {{ filtered ? t("noMatch") : t("empty") }}
        </p>
        <div v-else class="fivetran-artifact__scroll">
            <table class="fivetran-artifact__table">
                <thead>
                    <tr>
                        <th>{{ t("table") }}</th>
                        <th>{{ t("destination") }}</th>
                        <th>{{ t("syncMode") }}</th>
                        <th>{{ t("status") }}</th>
                    </tr>
                </thead>
                <tbody>
                    <tr v-for="row in rows" :key="row.key">
                        <td>{{ row.source }}</td>
                        <td class="fivetran-artifact__muted">{{ row.destination }}</td>
                        <td>{{ row.syncMode }}</td>
                        <td>
                            <KsTag v-if="!row.schemaEnabled" size="small" type="info">{{
                                t("schemaDisabled")
                            }}</KsTag>
                            <KsTag v-else-if="!row.enabled" size="small" type="info">{{
                                t("disabled")
                            }}</KsTag>
                            <KsTag v-else size="small" type="success">{{ t("enabled") }}</KsTag>
                            <KsTag
                                v-if="row.locked"
                                size="small"
                                type="warning"
                                class="fivetran-tables__locked"
                                :title="t('lockedHint')"
                                >{{ t("locked") }}</KsTag
                            >
                        </td>
                    </tr>
                </tbody>
            </table>
        </div>

        <div class="fivetran-artifact__pager">
            <KsPagination
                v-model:current-page="page"
                :page-size="PAGE_SIZE"
                :total="data?.total ?? 0"
                layout="total, prev, pager, next"
                size="small"
                hide-on-single-page
            />
        </div>
    </ArtifactFrame>
</template>

<style scoped>
.fivetran-tables__connector,
.fivetran-tables__state {
    width: 12rem;
}

.fivetran-tables__search {
    width: 16rem;
}

.fivetran-tables__locked {
    margin-left: var(--ks-spacing-1);
}
</style>