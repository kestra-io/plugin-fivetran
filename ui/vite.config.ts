import defaultViteConfig from "@kestra-io/artifact-sdk/vite.config";

// Node base (56) + KsTopologyDetails rows (~31.5px each).
// Before a run the card shows the task settings: three rows on Sync (connector ID, wait, max duration)
// and four on Status (connector IDs, wait, max duration, freshness buffer).
// With an execution both tasks show five rows (see ConnectorTopologyDetails.vue).
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
                    customAction: { label: "Fivetran details" },
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

// Module Federation names a few virtual chunks after the remote ("io.kestra.plugin.fivetran") plus
// the design-system module they wrap; the longest hits 256 characters, one over the 255-byte file
// name limit of ext4 and most Linux filesystems, so the build fails there. Cap the readable part
// of chunk names: the content hash keeps them unique.
export default {
    ...config,
    build: {
        ...config.build,
        rollupOptions: {
            output: {
                chunkFileNames: (chunk: { name: string }) =>
                    `assets/${chunk.name.slice(0, 120)}-[hash].js`,
            },
        },
    },
};