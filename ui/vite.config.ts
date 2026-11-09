import defaultViteConfig from "@kestra-io/artifact-sdk/vite.config";
import { mergeConfig } from "vite";

// Node base (56) + ~31.5px per row. Before a run the card shows 3 (Sync) or 4 (Status) settings rows.
// After a run, failed ones included, both show 5 result rows (56 + 5 x 31.5 = 214), plus the host's
// badge row (about 30px) under the node.
const HEIGHT_WITH_EXECUTION = 214;
const EXECUTION_BADGE_ROW = 30;
const TASKS = [
    { type: "connectors.Sync", height: 150 },
    { type: "connectors.Status", height: 182 },
];

// A customAction with no property to show makes the host open a dialog rendering the topology-task-modal slot.
const exposes = Object.fromEntries(
    TASKS.map(({ type, height }) => [
        type,
        [
            {
                slotName: "topology-details",
                path: "./src/components/ConnectorTopologyDetails.vue",
                additionalProperties: {
                    height,
                    heightWithExecution: HEIGHT_WITH_EXECUTION + EXECUTION_BADGE_ROW,
                    customAction: { label: "Show Details" },
                },
            },
            {
                slotName: "topology-task-modal",
                path: "./src/components/ConnectorDetails.vue",
            },
            {
                slotName: "execution-artifact/details",
                path: "./src/components/ConnectorDetails.vue",
                additionalProperties: { title: "Fivetran details" },
            },
        ],
    ]),
);

const config = defaultViteConfig({
    plugin: "io.kestra.plugin.fivetran",
    exposes,
});

// 255-byte file name limit: cap the readable part of chunk names, the hash keeps them unique.
export default mergeConfig(config, {
    build: {
        rollupOptions: {
            output: {
                chunkFileNames: (chunk: { name: string }) =>
                    `assets/${chunk.name.slice(0, 120)}-[hash].js`,
            },
        },
    },
});