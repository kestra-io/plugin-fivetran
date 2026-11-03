import defaultViteConfig from "@kestra-io/artifact-sdk/vite.config";
import { mergeConfig } from "vite";

const TASKS = ["connectors.Sync", "connectors.Status"];

// Only the execution Artifacts tab shows the connector details; the topology cards and dialog are gone.
const exposes = Object.fromEntries(
    TASKS.map((type) => [
        type,
        [
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