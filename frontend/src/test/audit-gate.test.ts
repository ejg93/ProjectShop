import { spawnSync } from "node:child_process";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { describe, expect, it } from "vitest";

/**
 * `scripts/audit-gate.mjs` 가 면제 목록 밖의 high 이상만 막는지 본다(`Q257`, `quality-gates.md` 「`npm audit`」).
 *
 * <p>CI `audit` 잡이 이 스크립트의 종료 코드로 빨강을 정한다. 가짜 `npm audit --json` 을 주고 코드를 잰다 —
 * 진짜 `npm audit` 은 그날의 권고에 따라 답이 바뀌어 시험이 안 된다.
 *
 * <p>면제 목록도 시험이 자기 것을 넘긴다(`Q308`) — 실제 `audit-exempt.json` 에 기대면 그 목록이 비는 날 시험이 거짓이 된다.
 */
const SCRIPT = join(process.cwd(), "scripts", "audit-gate.mjs");
const EXEMPT_ID = "GHSA-0000-test-0000";

function advisory(id: string, severity: string) {
  return { name: "pkg", title: "제목", severity, url: `https://github.com/advisories/${id}` };
}

function run(report: unknown, exempt: Record<string, string> = {}): number | null {
  const dir = mkdtempSync(join(tmpdir(), "audit-gate-"));
  const file = join(dir, "audit.json");
  const exemptFile = join(dir, "exempt.json");
  writeFileSync(file, JSON.stringify(report));
  writeFileSync(exemptFile, JSON.stringify(exempt));
  return spawnSync(process.execPath, [SCRIPT, file, exemptFile]).status;
}

describe("npm 감사 판정", () => {
  it("면제한 권고만 걸리면 통과한다 — 사슬 중간 패키지는 이름만 가리킨다", () => {
    expect(
      run({
        vulnerabilities: {
          braces: { via: [advisory(EXEMPT_ID, "high")] },
          micromatch: { via: ["braces"] },
        },
      }, { [EXEMPT_ID]: "시험" }),
    ).toBe(0);
  });

  it("면제 파일이 비면 그 권고도 막는다 — 면제는 파일이 정한다", () => {
    expect(run({ vulnerabilities: { braces: { via: [advisory(EXEMPT_ID, "high")] } } })).toBe(1);
  });

  it("면제 밖의 high 이상이 하나라도 있으면 1 이다", () => {
    expect(
      run({
        vulnerabilities: {
          braces: { via: [advisory(EXEMPT_ID, "high")] },
          sharp: { via: [advisory("GHSA-wq5f-xc86-pv6w", "high")] },
        },
      }, { [EXEMPT_ID]: "시험" }),
    ).toBe(1);
    expect(run({ vulnerabilities: { next: { via: [advisory("GHSA-0000-0000-0000", "critical")] } } })).toBe(1);
  });

  it("high 아래는 안 막는다", () => {
    expect(run({ vulnerabilities: { x: { via: [advisory("GHSA-1111-1111-1111", "moderate")] } } })).toBe(0);
  });

  it("형식이 바뀌면 2 다 — 0건으로 읽으면 그물이 조용히 사라진다", () => {
    expect(run({})).toBe(2);
  });
});
