<script setup lang="ts">
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import { ElConfigProvider } from "element-plus";
import type { EndpointStatus } from "../composables/useEndpoint";

const { t } = useI18n({
    inheritLocale: true,
    useScope: "local",
    messages: {
        en: {
            unsupported: "This Kestra version can't load this view yet. Upgrade Kestra to see it.",
            noExecution: "Run the flow to see this view.",
            notRun: "This task has not run in this execution.",
            error: "Could not load this view.",
            loading: "Loading…",
        },
    },
});

const props = defineProps<{ status: EndpointStatus }>();
const message = computed(() => (props.status === "ready" ? undefined : t(props.status)));
</script>

<template>
    <!-- The plugin bundles its own Element Plus, which does not see the host's "kel" namespace: without it the
         design-system styles (tag, select, pagination) never match the rendered "el-*" classes. -->
    <ElConfigProvider namespace="kel">
        <div class="fivetran-artifact">
            <p v-if="message" class="fivetran-artifact__note">{{ message }}</p>
            <slot v-else />
        </div>
    </ElConfigProvider>
</template>

<!-- Not scoped: the slot content belongs to the artifact components, which share these classes. -->
<style>
.fivetran-artifact {
    padding: var(--ks-spacing-3);
    font-size: var(--ks-font-size-xs);
}

.fivetran-artifact__note {
    margin: 0;
    color: var(--ks-text-secondary);
}

.fivetran-artifact__filters {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--ks-spacing-2);
    margin-bottom: var(--ks-spacing-3);
}

.fivetran-artifact__scroll {
    overflow-x: auto;
}

.fivetran-artifact__table {
    width: 100%;
    border-collapse: collapse;
}

.fivetran-artifact__table th,
.fivetran-artifact__table td {
    padding: var(--ks-spacing-1) var(--ks-spacing-2);
    text-align: left;
    vertical-align: top;
    border-bottom: 1px solid var(--ks-border-primary);
}

.fivetran-artifact__muted {
    color: var(--ks-text-secondary);
}

.fivetran-artifact__pager {
    display: flex;
    justify-content: flex-end;
    margin-top: var(--ks-spacing-3);
}
</style>