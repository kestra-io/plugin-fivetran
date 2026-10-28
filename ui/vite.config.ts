import defaultViteConfig from "@kestra-io/artifact-sdk/vite.config";
import { mergeConfig } from "vite";

// Node base (56) + ~31.5px per row: 3/4 settings rows before a run, 5 rows with an execution.
const HEIGHT_WITH_EXECUTION = 214;
const TASKS = [
    { type: "connectors.Sync", height: 150, artifacts: ["tables"] },
    { type: "connectors.Status", height: 182, artifacts: ["connectors", "tables"] },
];

// One entry per view in the execution Artifacts tab. "tables" reads the plugin's `tables` endpoint, "connectors"
// only the task run's outputs.
const ARTIFACTS: Record<string, { path: string; title: string; description: string }> = {
    connectors: {
        path: "./src/components/ConnectorsArtifact.vue",
        title: "Connectors",
        description: "State, freshness, alerts and table counts of every connector.",
    },
    tables: {
        path: "./src/components/TablesArtifact.vue",
        title: "Tables",
        description: "The schemas and tables the connector writes to the destination.",
    },
};

// One line under each asset card in the dependencies graph. The graph reserves it on every card, so it is kept to a
// single line of extra-small text.
const ASSET_FOOTER_HEIGHT = 20;

// A customAction with no property to show makes the host open a dialog rendering the topology-task-modal slot.
const exposes = Object.fromEntries(
    TASKS.map(({ type, height, artifacts }) => [
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
            // Both tasks emit the table and connector assets, so both ship the footer of their cards.
            {
                slotName: "asset-card-footer",
                path: "./src/components/AssetFreshnessFooter.vue",
                additionalProperties: { footerHeight: ASSET_FOOTER_HEIGHT },
            },
            ...artifacts.map((id) => ({
                slotName: `execution-artifact/${id}`,
                path: ARTIFACTS[id].path,
                additionalProperties: {
                    title: ARTIFACTS[id].title,
                    description: ARTIFACTS[id].description,
                },
            })),
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