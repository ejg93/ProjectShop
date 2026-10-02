// 세 회차의 p95 중앙값을 기준선과 견준다(`70`, `D21` 「회귀 문턱」).
//
//   node load/compare.mjs                    기준선의 1.5배를 넘는 입구가 하나라도 있으면 exit 1
//   node load/compare.mjs --update-baseline  중앙값을 기준선으로 쓴다 — `performance-goals.md` 표를 같은 커밋에서 고친다
//
// **한 번이 아니라 중앙값이다.** 로컬 Docker 는 회차마다 흔들려서 한 번으로 재면 잡음이 회귀로 읽힌다.
import { readdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const THRESHOLD = 1.5;
const dir = fileURLToPath(new URL(".", import.meta.url));
const outDir = join(dir, "out");
const baselinePath = join(dir, "baseline.json");

const runs = readdirSync(outDir)
  .filter((name) => /^run-\d+\.json$/.test(name))
  .map((name) => JSON.parse(readFileSync(join(outDir, name), "utf8")));
if (runs.length < 3) {
  console.error(`회차가 ${runs.length} 개다 — 셋을 돌린 뒤 견준다`);
  process.exit(1);
}

const median = (values) => {
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.floor(sorted.length / 2)];
};
const keys = Object.keys(runs[0]);
const medians = Object.fromEntries(keys.map((key) => [key, median(runs.map((run) => run[key]))]));

if (process.argv.includes("--update-baseline")) {
  writeFileSync(baselinePath, JSON.stringify(medians, null, 2) + "\n");
  console.log(`기준선을 썼다: ${baselinePath}`);
  for (const key of keys) console.log(`  ${key.padEnd(14)} ${medians[key]} ms`);
  process.exit(0);
}

const baseline = JSON.parse(readFileSync(baselinePath, "utf8"));
let failed = false;
for (const key of keys) {
  const base = baseline[key];
  if (base === undefined) {
    console.log(`  ${key.padEnd(14)} ${medians[key]} ms — 기준선에 없다`);
    failed = true;
    continue;
  }
  const ratio = medians[key] / base;
  const over = ratio > THRESHOLD;
  failed ||= over;
  console.log(`  ${key.padEnd(14)} ${medians[key]} ms / 기준 ${base} ms = ${ratio.toFixed(2)}배${over ? "  ← 회귀" : ""}`);
}
for (const key of Object.keys(baseline)) {
  if (!(key in medians)) {
    console.log(`  ${key.padEnd(14)} 이번에 안 쟀다 — 기준선에만 있다`);
    failed = true;
  }
}
console.log(failed ? `빨강 — 기준선의 ${THRESHOLD}배를 넘었거나 입구가 어긋났다` : "초록");
process.exit(failed ? 1 : 0);
