import defaultViteConfig from "@kestra-io/artifact-sdk/vite.config";
import { mergeConfig } from "vite";

// Node base (56) + ~31.5px per row: 3/4 settings rows before a run, 5 rows with an execution.
const HEIGHT_WITH_EXECUTION = 214;
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
                    heightWithExecution: HEIGHT_WITH_EXECUTION,
                    customAction: { label: "Show Details" },
                },
            },
            {
                slotName: "topology-task-modal",
                path: "./src/components/ConnectorDetails.vue",
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