/**
 * `package-lock.json` 의 패키지마다 라이선스를 허용 목록과 맞춘다(`Q379b`, `coding-rules.md` 「라이선스」).
 *
 * <b>lock 을 읽는다.</b> 2026-11-08 lock 의 패키지 654 가 전부 문자열 `license` 를 들고 있어서 새 의존 없이 된다 —
 * `node_modules` 를 걷는 도구는 설치가 먼저고, 선언(`package.json`)만 보면 전이 의존이 빠진다.
 * dev·optional 도 센다. 실려 나가는 것만 고르면 「dev 라서 괜찮다」는 판단을 사람 없이 하게 된다.
 *
 * <b>허용 목록이다.</b> 모르는 라이선스는 빨갛다 — 그 자리에서 판단해 근거와 함께 `license-allow.json` 에 넣는다.
 * 그 파일은 두 맵이다: `licenses`(SPDX 이름 → 근거)와 `packages`(패키지 이름 glob → `{license, why}`, `*` 하나만 쓴다).
 * <b>패키지 맵은 식까지 맞아야 한다</b>(`Q396`) — glob 만 맞추면 다음 판의 라이선스 변경이 지나간다.
 * `license` 는 lock 의 식 글자 그대로고 쪼개지 않는다 — 순서만 바뀌어도 「바뀌었다」로 보고 사람이 다시 판단한다.
 * 식은 `AND` 면 전부, `OR` 면 하나가 `licenses` 에 있어야 한다. 식 전체가 `licenses` 의 키면 쪼개지 않는다 —
 * `SIL OPEN FONT LICENSE` 처럼 SPDX 이름이 아닌 꼴이 그 자리다.
 *
 * 종료 코드 — 0 통과 · 1 목록 밖이 있다 · 2 lock 이나 패키지 맵의 형식이 바뀌었다(0건으로 읽으면 그물이 조용히 사라진다).
 * 인자 둘로 다른 lock·허용 파일을 줄 수 있다 — 시험이 쓴다.
 *
 *   node scripts/license-gate.mjs [package-lock.json] [license-allow.json]
 */
import { readFileSync } from "node:fs";

const lock = JSON.parse(readFileSync(process.argv[2] ?? new URL("../package-lock.json", import.meta.url), "utf8"));
const allow = JSON.parse(readFileSync(process.argv[3] ?? new URL("./license-allow.json", import.meta.url), "utf8"));

const LICENSES = new Map(Object.entries(allow.licenses ?? {}));
const badPackages = Object.entries(allow.packages ?? {})
  .filter(([, value]) => typeof value !== "object" || value === null || typeof value.license !== "string")
  .map(([glob]) => glob);
if (badPackages.length > 0) {
  console.error(`[Q396] 패키지 맵 값은 {license, why} 객체다 — 아닌 것: ${badPackages.join(", ")}`);
  process.exit(2);
}
const PACKAGES = Object.entries(allow.packages ?? {}).map(([glob, value]) => ({
  pattern: new RegExp(`^${glob.replace(/[.+?^${}()|[\]\\/]/g, "\\$&").replace(/\*/g, ".*")}$`),
  license: value.license,
}));

/** 식 하나가 목록을 지나는가. 모르는 꼴(이름 둘이 연산자 없이 붙는 것)은 못 지난다 */
function allowed(expression) {
  if (LICENSES.has(expression)) {
    return true;
  }
  const tokens = expression.replace(/[()]/g, " $& ").trim().split(/\s+/);
  let at = 0;
  const peek = () => tokens[at];
  function factor() {
    const token = tokens[at++];
    if (token === "(") {
      const value = or();
      if (tokens[at++] !== ")") {
        throw new Error("괄호");
      }
      return value;
    }
    if (token === undefined || token === ")" || token === "AND" || token === "OR") {
      throw new Error("이름 자리");
    }
    return LICENSES.has(token);
  }
  function and() {
    let value = factor();
    while (peek() === "AND") {
      at++;
      value = factor() && value;
    }
    return value;
  }
  function or() {
    let value = and();
    while (peek() === "OR") {
      at++;
      value = and() || value;
    }
    return value;
  }
  try {
    const value = or();
    return at === tokens.length && value;
  } catch {
    return false;
  }
}

if (!lock.packages || typeof lock.packages !== "object") {
  console.error("[Q379b] package-lock.json 에 packages 가 없다 — lock 형식이 바뀌었나 본다");
  process.exit(2);
}

const malformed = [];
const blocked = [];
let counted = 0;
for (const [path, entry] of Object.entries(lock.packages)) {
  if (path === "") {
    continue;
  }
  counted++;
  const name = path.slice(path.lastIndexOf("node_modules/") + "node_modules/".length);
  if (typeof entry.license !== "string") {
    malformed.push(name);
    continue;
  }
  const kind = [entry.dev ? "dev" : null, entry.optional ? "optional" : null].filter(Boolean).join("·") || "운영";
  const mapped = PACKAGES.find((item) => item.pattern.test(name));
  if (mapped) {
    if (entry.license !== mapped.license) {
      blocked.push(`${name} — ${entry.license} — ${kind} (패키지 맵은 '${mapped.license}' 을 적었다)`);
    }
    continue;
  }
  if (allowed(entry.license)) {
    continue;
  }
  blocked.push(`${name} — ${entry.license} — ${kind}`);
}

if (malformed.length > 0) {
  console.error(`[Q379b] license 가 문자열이 아닌 패키지 ${malformed.length}: ${malformed.slice(0, 5).join(", ")}`);
  process.exit(2);
}
if (blocked.length > 0) {
  for (const line of blocked) {
    console.error(`허용 목록 밖: ${line}`);
  }
  console.error(`[Q379b] ${blocked.length}건 — 판단해서 scripts/license-allow.json 에 근거와 함께 넣거나 의존을 뺀다`);
  process.exit(1);
}
console.log(`라이선스 허용 목록 밖 0건(패키지 ${counted})`);
