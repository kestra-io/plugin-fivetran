import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, test } from "vitest";

describe("vite.config.ts exposes", () => {
    const config = readFileSync(resolve(process.cwd(), "vite.config.ts"), "utf8");

    test("registers execution-artifact/details for Sync and Status", () => {
        for (const type of ["connectors.Sync", "connectors.Status"]) {
            expect(config).toContain(`"${type}"`);
        }
        expect(config).toContain('plugin: "io.kestra.plugin.fivetran"');
        expect(config.match(/slotName: "execution-artifact\/details"/g)).toHaveLength(1);
        expect(config.match(/slotName:/g)).toHaveLength(1);
    });

    test("no topology slot and no custom action", () => {
        expect(config).not.toContain('slotName: "topology');
        expect(config).not.toContain("customAction");
        expect(config).not.toContain("taskProp");
    });
});