/**
 * `npm audit` 의 high 이상 권고를 세되, 면제 목록에 적은 권고는 뺀다(`Q257` 의 강제 지점).
 *
 * `npm audit --audit-level=high` 는 권고를 하나도 못 빼서, **고친 판이 없는 권고 하나**가 잡을 영영 빨갛게 둔다 —
 * 빨간 채로 두면 그 뒤에 오는 진짜 경보가 가려지고, 머지 훅(`pr-guard.sh`)이 머지를 막는다.
 * 그래서 권고 단위로 면제하고 나머지는 그대로 막는다(2026-10-11 사용자 결정).
 *
 * <b>면제는 권고 id 하나씩이다.</b> 패키지 이름으로 빼면 같은 패키지의 다음 권고까지 같이 빠진다.
 * 사슬 중간의 패키지(`micromatch` 등)는 권고를 직접 안 들고 원본 패키지 이름만 가리켜서, 원본이 빠지면 같이 빠진다.
 *
 * <b>고친 판이 나오면 그 줄을 지운다.</b> 면제가 늘면 이 파일의 diff 에 뜬다.
 *
 *   npm audit --json > audit.json; node scripts/audit-gate.mjs audit.json
 */
import { readFileSync } from "node:fs";

/** 권고 id → 왜 면제하나. 고친 판이 없을 때만 적는다 */
const EXEMPT = new Map([
  [
    "GHSA-vfj7-8cjw-p6xm",
    "braces <= 3.0.3 전부, 고친 판 없음. eslint-config-next → fast-glob → micromatch 사슬(개발 도구)에만 있다(Q257)",
  ],
]);

const BLOCKING = new Set(["high", "critical"]);

const report = JSON.parse(readFileSync(process.argv[2], "utf8"));
if (!report.vulnerabilities) {
  // 형식이 바뀌었거나 npm audit 이 실패했다. 0건으로 읽으면 그물이 조용히 사라진다.
  console.error("[Q257] npm audit --json 에 vulnerabilities 가 없다 — 형식이 바뀌었나 본다");
  process.exit(2);
}

const found = new Map();
for (const vulnerability of Object.values(report.vulnerabilities)) {
  for (const via of vulnerability.via) {
    if (typeof via === "object" && BLOCKING.has(via.severity)) {
      const id = via.url.split("/").pop();
      found.set(id, `${via.name} — ${via.title}`);
    }
  }
}

const exempted = [...found.keys()].filter((id) => EXEMPT.has(id));
const blocking = [...found.entries()].filter(([id]) => !EXEMPT.has(id));

for (const id of exempted) {
  console.log(`면제 ${id}: ${EXEMPT.get(id)}`);
}
for (const id of EXEMPT.keys()) {
  if (!found.has(id)) {
    console.log(`면제 ${id} 는 이제 안 걸린다 — 고친 판이 들어왔으면 이 줄을 지운다`);
  }
}
if (blocking.length > 0) {
  for (const [id, what] of blocking) {
    console.error(`high 이상 ${id}: ${what}`);
  }
  process.exit(1);
}
console.log("high 이상 0건(면제 제외)");
