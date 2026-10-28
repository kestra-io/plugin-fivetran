<script lang="ts">
import { connectorsSubtitle } from "../composables/subtitles";

// The line under the title in the Artifacts tab: how many connectors, and how many are not fresh.
export const getSubtitle = connectorsSubtitle;
</script>

<script setup lang="ts">
import type { KnownSlotProps } from "@kestra-io/artifact-sdk";
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import { ElConfigProvider } from "element-plus";
import { RUNNING_STATES } from "../composables/states";
import { useConnectorData } from "../composables/useConnectorData";
import ConnectorSections from "./ConnectorSections.vue";

const { t } = useI18n({
    inheritLocale: true,
    useScope: "local",
    messages: {
        en: {
            empty: "Run the flow to see this view.",
            running: "The connector details appear when the task finishes.",
            noOutputs: "This execution has no connector details.",
        },
    },
});

const props = defineProps<KnownSlotProps["execution-artifact"]>();
// Read from the task run's outputs only, like the details dialog.
const { taskRunState, hasExecution, hasOutputs, isStatusOutput, connectors } = useConnectorData(
    props as KnownSlotProps["topology-details"],
);

const note = computed(() => {
    if (!hasExecution.value) return t("empty");
    if (!hasOutputs.value && RUNNING_STATES.includes(taskRunState.value ?? "")) return t("running");
    return connectors.value.length ? undefined : t("noOutputs");
});
</script>

<template>
    <!-- The plugin bundles its own Element Plus, which does not see the host's "kel" namespace. -->
    <ElConfigProvider namespace="kel">
        <div class="fivetran-artifact">
            <p v-if="note" class="fivetran-artifact__note">{{ note }}</p>
            <ConnectorSections v-else :connectors="connectors" :is-status-output="isStatusOutput" />
        </div>
    </ElConfigProvider>
</template>

<style scoped>
.fivetran-artifact {
    padding: var(--ks-spacing-3);
    font-size: var(--ks-font-size-xs);
}

.fivetran-artifact__note {
    margin: 0;
    color: var(--ks-text-secondary);
}
</style>