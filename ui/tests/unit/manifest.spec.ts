import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, test } from "vitest";

// A customAction without taskProp makes the host open a dialog rendering the topology-task-modal
// component; with taskProp it would open the side drawer with a redundant editor block.
describe("vite.config.ts exposes", () => {
    const config = readFileSync(resolve(process.cwd(), "vite.config.ts"), "utf8");

    test("builds the same dialog wiring for Sync and Status", () => {
        for (const type of ["connectors.Sync", "connectors.Status"]) {
            expect(config).toContain(`type: "${type}"`);
        }
        expect(config).toContain('plugin: "io.kestra.plugin.fivetran"');
        expect(config.match(/customAction: \{ label: "Show Details" \}/g)).toHaveLength(1);
        expect(config.match(/slotName: "topology-task-modal"/g)).toHaveLength(1);
        expect(config.match(/slotName: "topology-details"/g)).toHaveLength(1);
    });

    test("lists the Artifacts tab views per task", () => {
        expect(config).toContain('{ type: "connectors.Sync", height: 150, artifacts: ["tables"] }');
        expect(config).toContain(
            '{ type: "connectors.Status", height: 182, artifacts: ["connectors", "tables"] }',
        );
        expect(config).toContain("slotName: `execution-artifact/${id}`");
    });

    test("no taskProp and no drawer slot", () => {
        expect(config).not.toContain("taskProp");
        expect(config).not.toContain("topology-task-drawer");
    });
});
describe("asset card footer", () => {
    const config = readFileSync(resolve(process.cwd(), "vite.config.ts"), "utf8");

    test("ships one footer for both tasks, with its height", () => {
        expect(config.match(/slotName: "asset-card-footer"/g)).toHaveLength(1);
        expect(config).toContain("footerHeight: ASSET_FOOTER_HEIGHT");
    });
});