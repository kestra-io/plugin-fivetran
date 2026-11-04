import defaultViteConfig from "@kestra-io/artifact-sdk/vite.config";
import { mergeConfig } from "vite";

// With an execution the host draws a badge row (about 30px) under the node, so the card needs that much more room.
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
                    heightWithExecution: height + EXECUTION_BADGE_ROW,
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