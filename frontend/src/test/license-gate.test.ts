import { spawnSync } from "node:child_process";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { describe, expect, it } from "vitest";

/**
 * `scripts/license-gate.mjs` 가 lock 의 패키지 라이선스를 허용 목록과 맞추는지 본다(`Q379b`, `coding-rules.md` 「라이선스」).
 *
 * <p>CI `audit` 잡과 `verify.sh` 프론트 레인이 이 스크립트의 종료 코드로 빨강을 정한다. 가짜 lock 과 가짜 허용 목록을
 * 임시 폴더에 쓰고 코드를 잰다 — 실제 lock·목록에 기대면 의존이 바뀌는 날 시험의 뜻이 바뀐다.
 */
const SCRIPT = join(process.cwd(), "scripts", "license-gate.mjs");

const ALLOW = {
  licenses: { MIT: "시험", "Apache-2.0": "시험" },
  packages: { "@img/sharp-libvips-*": { license: "LGPL-3.0-or-later", why: "시험 — 패키지 단위 허용" } },
};

function lock(packages: Record<string, unknown>) {
  return { lockfileVersion: 3, packages: { "": { name: "frontend" }, ...packages } };
}

function run(lockfile: unknown, allow: unknown = ALLOW): number | null {
  const dir = mkdtempSync(join(tmpdir(), "license-gate-"));
  const lockFile = join(dir, "package-lock.json");
  const allowFile = join(dir, "license-allow.json");
  writeFileSync(lockFile, JSON.stringify(lockfile));
  writeFileSync(allowFile, JSON.stringify(allow));
  return spawnSync(process.execPath, [SCRIPT, lockFile, allowFile]).status;
}

describe("npm 의존 라이선스 판정", () => {
  it("허용 목록 안의 라이선스만 있으면 통과한다 — 뿌리 항목(빈 키)은 안 센다", () => {
    expect(
      run(lock({ "node_modules/a": { license: "MIT" }, "node_modules/b": { license: "Apache-2.0", dev: true } })),
    ).toBe(0);
  });

  it("목록 밖 라이선스가 하나라도 있으면 1 이다 — dev 도 센다", () => {
    expect(run(lock({ "node_modules/a": { license: "MIT" }, "node_modules/c": { license: "GPL-3.0", dev: true } }))).toBe(1);
  });

  it("AND 식은 전부가, OR 식은 하나가 목록에 있어야 한다", () => {
    expect(run(lock({ "node_modules/a": { license: "MIT AND GPL-3.0" } }))).toBe(1);
    expect(run(lock({ "node_modules/a": { license: "(MIT AND Apache-2.0)" } }))).toBe(0);
    expect(run(lock({ "node_modules/a": { license: "(GPL-3.0 OR MIT)" } }))).toBe(0);
  });

  it("패키지 맵은 glob 이 맞고 식이 같을 때만 통과시킨다 — 중첩 node_modules 도 이름으로 맞춘다(`Q396`)", () => {
    expect(run(lock({ "node_modules/@img/sharp-libvips-linux-x64": { license: "LGPL-3.0-or-later" } }))).toBe(0);
    expect(run(lock({ "node_modules/x/node_modules/@img/sharp-libvips-darwin-arm64": { license: "LGPL-3.0-or-later" } }))).toBe(0);
    expect(run(lock({ "node_modules/@img/sharp-linux-x64": { license: "LGPL-3.0-or-later" } }))).toBe(1);
  });

  it("glob 은 맞는데 식이 맵과 다르면 1 이다 — 다음 판의 라이선스 변경이 지나가지 않는다(`Q396`)", () => {
    expect(run(lock({ "node_modules/@img/sharp-libvips-linux-x64": { license: "GPL-3.0-only" } }))).toBe(1);
  });

  it("패키지 맵 값이 객체가 아니거나 license 가 문자열이 아니면 2 다 — 옛 꼴을 조용히 통과시키지 않는다(`Q396`)", () => {
    const ok = lock({ "node_modules/a": { license: "MIT" } });
    expect(run(ok, { ...ALLOW, packages: { "@img/sharp-libvips-*": "옛 꼴 — 근거 문장만" } })).toBe(2);
    expect(run(ok, { ...ALLOW, packages: { "@img/sharp-libvips-*": { why: "license 가 없다" } } })).toBe(2);
  });

  it("license 가 없거나 문자열이 아니면 2 다 — 형식이 바뀐 것을 0건으로 읽지 않는다", () => {
    expect(run(lock({ "node_modules/a": {} }))).toBe(2);
    expect(run(lock({ "node_modules/a": { license: { type: "MIT" } } }))).toBe(2);
    expect(run({ lockfileVersion: 3 })).toBe(2);
  });
});
