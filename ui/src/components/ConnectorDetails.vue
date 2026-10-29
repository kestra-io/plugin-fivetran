<script setup lang="ts">
import type { KnownSlotProps } from "@kestra-io/artifact-sdk";
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import { ElConfigProvider } from "element-plus";
import { KsTag, KsTopologyDetails } from "@kestra-io/design-system";
import { taskSettings } from "../composables/settings";
import { RUNNING_STATES } from "../composables/states";
import { useConnectorData } from "../composables/useConnectorData";
import ConnectorSections from "./ConnectorSections.vue";

const { t } = useI18n({
    inheritLocale: true,
    useScope: "local",
    messages: {
        en: {
            settings: "Task settings",
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
const { taskRunState, hasExecution, hasOutputs, isStatusOutput, connectors, notWaited } =
    useConnectorData(props as KnownSlotProps["topology-details"]);

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

            <ConnectorSections :connectors="connectors" :is-status-output="isStatusOutput" />
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

.fivetran-note {
    margin: var(--ks-spacing-3);
    color: var(--ks-text-secondary);
}
</style>