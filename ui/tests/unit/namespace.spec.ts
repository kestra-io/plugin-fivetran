import { describe, expect, test } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createI18n } from "vue-i18n";
import ConnectorDetails from "../../src/components/ConnectorDetails.vue";
import { fetchers, statusOutputs, statusTask } from "../storybook/fixtures";

// The host mounts the details component without installing the design system in the plugin, so Element Plus uses
// its default "el" namespace unless the component sets "kel", which is the one the design-system CSS targets.
describe("ConnectorDetails in the host", () => {
    test("renders tags with the design-system namespace", async () => {
        const wrapper = mount(ConnectorDetails, {
            props: {
                task: statusTask,
                namespace: "ns",
                flowId: "flow",
                ...fetchers(statusOutputs),
            } as any,
            global: {
                plugins: [createI18n({ legacy: false, locale: "en", messages: { en: {} } })],
            },
        });
        await flushPromises();

        expect(wrapper.find(".kel-tag").exists()).toBe(true);
        expect(wrapper.find(".el-tag").exists()).toBe(false);
    });
});