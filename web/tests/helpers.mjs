// Shared test setup: the apps' wording and paths to the shared fixtures (no network, no browser).
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { installStrings } from "../js/logic/strings.js";

export const WEB = join(dirname(fileURLToPath(import.meta.url)), "..");
export const ROOT = join(WEB, "..");
export const readJson = (p) => JSON.parse(readFileSync(p, "utf8"));
export const asset = (p) => readFileSync(join(WEB, "assets", p), "utf8");

installStrings(readJson(join(WEB, "assets/strings.json")));
