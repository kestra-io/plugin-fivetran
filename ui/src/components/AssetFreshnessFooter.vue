<script setup lang="ts">
import type { KnownSlotProps } from "@kestra-io/artifact-sdk";
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import { MISSING, ageOf, formatDateTime, formatState } from "../composables/format";
import { useSharedClock } from "../composables/useSharedClock";

const { t } = useI18n({
    inheritLocale: true,
    useScope: "local",
    messages: {
        en: {
            synced: "Synced {ago}",
            title: "Last successful Fivetran sync: {date}",
            justNow: "just now",
            minutesAgo: "{count} min ago",
            hoursAgo: "{count} h ago",
            daysAgo: "{count} d ago",
        },
    },
});

const props = defineProps<KnownSlotProps["asset-card-footer"]>();

// Only the asset metadata this plugin writes. dbt writes the same table ids, so an asset without a connectorId was
// last written by another plugin and gets no Fivetran footer.
const lastSyncedAt = computed(() => {
    const metadata = props.metadata ?? {};
    if (typeof metadata.connectorId !== "string") return undefined;
    return typeof metadata.lastSyncedAt === "string" ? metadata.lastSyncedAt : undefined;
});
const syncState = computed(() => {
    const value = props.metadata?.syncState;
    return typeof value === "string" && value ? formatState(value) : undefined;
});

const { now } = useSharedClock();

const AGO_KEYS = {
    now: "justNow",
    minute: "minutesAgo",
    hour: "hoursAgo",
    day: "daysAgo",
} as const;
const ago = computed(() => {
    const age = ageOf(lastSyncedAt.value, now.value);
    return age ? t(AGO_KEYS[age.unit], { count: age.count }) : MISSING;
});
</script>

<template>
    <div
        v-if="lastSyncedAt"
        class="fivetran-footer"
        :title="t('title', { date: formatDateTime(lastSyncedAt) })"
    >
        <span class="fivetran-footer__text">{{ t("synced", { ago }) }}</span>
        <span v-if="syncState" class="fivetran-footer__state">· {{ syncState }}</span>
    </div>
</template>

<style scoped>
.fivetran-footer {
    display: flex;
    align-items: center;
    gap: var(--ks-spacing-1);
    min-width: 0;
    font-size: var(--ks-font-size-xs);
    line-height: 1.25;
    color: var(--ks-text-secondary);
}

.fivetran-footer__text,
.fivetran-footer__state {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}

.fivetran-footer__state {
    flex-shrink: 0;
}
</style>