<script lang="ts">
import { connectorsSubtitle } from "../composables/subtitles";

// The line under the title in the Artifacts tab: how many connectors, and how many are not fresh.
export const getSubtitle = connectorsSubtitle;
</script>

<script setup lang="ts">
import type { KnownSlotProps } from "@kestra-io/artifact-sdk";
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import { RUNNING_STATES } from "../composables/states";
import { useConnectorData } from "../composables/useConnectorData";
import ArtifactFrame from "./ArtifactFrame.vue";
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
    <ArtifactFrame :note="note">
        <ConnectorSections :connectors="connectors" :is-status-output="isStatusOutput" />
    </ArtifactFrame>
</template>