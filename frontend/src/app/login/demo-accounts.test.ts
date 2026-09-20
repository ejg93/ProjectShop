import { readFileSync } from "node:fs";
import { join } from "node:path";

import { describe, expect, it } from "vitest";

import { DEMO_GROUPS, DEMO_PASSWORD } from "./demo-accounts";

/**
 * 안내가 시드와 같은 값을 드나(`Q131`).
 *
 * <p><b>갈리면 아무도 모른다.</b> 안내를 보고 친 사람은 「이메일 또는 비밀번호가 맞지 않습니다」를
 * 받는데, 그 문구는 계정이 없는 것과 안내가 낡은 것을 안 가른다(`D14` 가 일부러 그렇게 뭉쳤다).
 *
 * <p><b>화면 쪽에 건다.</b> 시드를 고치는 사람이 화면을 잊는 쪽이 흔한데, 백엔드 테스트에서
 * 화면 소스를 읽으면 그 모듈이 <b>backend 지문에 안 들어 있어서</b> 대조 레인을 한 번 더
 * 손봐야 한다(`Q111`). 여기서는 상대 경로 하나로 끝난다.
 *
 * <p><b>경로가 `frontend/` 기준이다.</b> jsdom 에서는 `import.meta.url` 이 파일 URL 이 아니라
 * `fileURLToPath` 가 터진다 — Vitest 가 그 디렉터리에서 도므로 `process.cwd()` 가 그 자리를 든다.
 *
 * <p><b>글자로 잰다.</b> 시드는 `local` 프로필에서만 돌아서 실물로 물을 자리가 없다 —
 * `SeedOutboxTest` 가 같은 이유로 같은 방법을 쓴다.
 */
const SEED = readFileSync(
  join(process.cwd(), "..", "backend", "src", "main", "resources",
       "db", "seed", "V904__demo_accounts.sql"),
  "utf8",
);

/**
 * 주석을 걷은 SQL. <b>문구가 주석에만 있어도 통과하던 것을 막는다</b>(독립 리뷰).
 *
 * <p>이 파일의 머리말이 비밀번호를 그대로 적어 두어서, 그냥 찾으면 `crypt(...)` 를 지워도
 * 초록이다 — 대조가 재려는 것은 <b>시드가 실제로 만드는 값</b>이다.
 */
const SEED_SQL = SEED.split("\n").filter((line) => !line.trimStart().startsWith("--")).join("\n");

describe("데모 계정 안내", () => {
  it("아홉이고 그룹마다 셋이다", () => {
    expect(DEMO_GROUPS).toHaveLength(3);
    for (const group of DEMO_GROUPS) {
      expect(group.accounts, group.title).toHaveLength(3);
    }
  });

  it("안내에 적은 계정이 시드에 다 있다", () => {
    for (const group of DEMO_GROUPS) {
      for (const account of group.accounts) {
        expect(SEED_SQL, `${account.name} 이 시드에 없다`).toContain(`'${account.email}'`);
        expect(SEED_SQL, `${account.name} 의 이름이 시드와 다르다`).toContain(`'${account.name}'`);
      }
    }
  });

  it("시드가 만든 계정이 안내에 다 있다", () => {
    // 시드의 `values` 줄에서 이메일을 걷는다. 안내에만 없는 계정이 생기면
    // **로그인 화면에 안 실려서** 세션이 겹치는 문제가 그대로 남는다.
    // **좁게 잡으면 못 보고 지나간다**(독립 리뷰). `-`·`_`·대문자가 든 주소나 다른 도메인을
    // 시드에 넣으면 이 대조가 그것을 아예 안 세고, 안내에 없는 계정이 조용히 생긴다.
    const seeded = [...SEED_SQL.matchAll(/\('([^']+@[^']+)',\s*'/g)].map((match) => match[1]);
    const listed = DEMO_GROUPS.flatMap((group) => group.accounts.map((it) => it.email));

    expect(seeded.length).toBeGreaterThan(0);
    expect([...new Set(seeded)].sort()).toEqual([...listed].sort());
  });

  it("비밀번호가 시드가 만든 것과 같다", () => {
    expect(SEED_SQL).toContain(`crypt('${DEMO_PASSWORD}'`);
  });
});
