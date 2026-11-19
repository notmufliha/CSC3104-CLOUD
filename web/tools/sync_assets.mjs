// Copies the iOS app's bundled data into web/assets/ so the web app is self-contained static files (the same files the
// phone apps ship; the output is committed). Run from web/ after the scripts or models change: npm run sync
//   assets/scripts/*.json + index.json   the call scripts (android-app/app/src/main/assets/scripts, = iOS Resources/Scripts)
//   assets/models/*.json                 the three small on-device models + manifest.json (iOS Resources/Models)
// strings.json and learn.json are written by tools/i18n/gen_strings.py and tools/learn/build_learn.py.
import { readdirSync, readFileSync, writeFileSync, copyFileSync, mkdirSync, rmSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const web = join(dirname(fileURLToPath(import.meta.url)), "..");
const root = join(web, "..");
const scriptsSrc = join(root, "ios-app/ScamGuard/Resources/Scripts");
const modelsSrc = join(root, "ios-app/ScamGuard/Resources/Models");

rmSync(join(web, "assets/scripts"), { recursive: true, force: true });
mkdirSync(join(web, "assets/scripts"), { recursive: true });
mkdirSync(join(web, "assets/models"), { recursive: true });

const names = readdirSync(scriptsSrc).filter((n) => n.endsWith(".json")).sort();
for (const n of names) copyFileSync(join(scriptsSrc, n), join(web, "assets/scripts", n));
writeFileSync(join(web, "assets/scripts/index.json"), JSON.stringify(names, null, 1) + "\n");

const models = readdirSync(modelsSrc).filter((n) => n.endsWith(".json"));
for (const n of models) copyFileSync(join(modelsSrc, n), join(web, "assets/models", n));

// sanity: the Android copies are the same files
const android = join(root, "android-app/app/src/main/assets/scripts");
const diff = names.filter((n) => { try { return readFileSync(join(android, n), "utf8") !== readFileSync(join(scriptsSrc, n), "utf8"); } catch { return true; } });
console.log(`${names.length} scripts, ${models.length} model files` + (diff.length ? `; differ from Android: ${diff.join(", ")}` : ""));
