import { createI18n } from "vue-i18n";

// The Artifacts tab calls getSubtitle outside any component, so it cannot use the host's useI18n scope: it gets its
// own instance over this map. Only `en` ships today, like the components' local messages.
export const subtitleMessages = {
    en: {
        tablesEnabled: "{enabled} of {count} table enabled | {enabled} of {count} tables enabled",
        connectors: "{count} connector | {count} connectors",
        notFresh: "{count} not fresh",
    },
};

const { t } = createI18n({
    legacy: false,
    locale: "en",
    fallbackLocale: "en",
    messages: subtitleMessages,
}).global;

export { t as translate };