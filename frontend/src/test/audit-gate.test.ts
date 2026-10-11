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
 */
const SCRIPT = join(process.cwd(), "scripts", "audit-gate.mjs");
const EXEMPT_ID = "GHSA-vfj7-8cjw-p6xm";

function advisory(id: string, severity: string) {
  return { name: "pkg", title: "제목", severity, url: `https://github.com/advisories/${id}` };
}

function run(report: unknown): number | null {
  const dir = mkdtempSync(join(tmpdir(), "audit-gate-"));
  const file = join(dir, "audit.json");
  writeFileSync(file, JSON.stringify(report));
  return spawnSync(process.execPath, [SCRIPT, file]).status;
}

describe("npm 감사 판정", () => {
  it("면제한 권고만 걸리면 통과한다 — 사슬 중간 패키지는 이름만 가리킨다", () => {
    expect(
      run({
        vulnerabilities: {
          braces: { via: [advisory(EXEMPT_ID, "high")] },
          micromatch: { via: ["braces"] },
        },
      }),
    ).toBe(0);
  });

  it("면제 밖의 high 이상이 하나라도 있으면 1 이다", () => {
    expect(
      run({
        vulnerabilities: {
          braces: { via: [advisory(EXEMPT_ID, "high")] },
          sharp: { via: [advisory("GHSA-wq5f-xc86-pv6w", "high")] },
        },
      }),
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
