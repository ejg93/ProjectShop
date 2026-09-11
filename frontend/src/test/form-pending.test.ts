import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";

import { describe, expect, it } from "vitest";

/**
 * `<form action={fn}>` 폼이 제출 중인지를 **손으로 들지 않는지** 본다(`Q20-3`, `D24`).
 *
 * <p>React 19 의 폼 `action` 은 `fn` 을 전환 안에서 돌리고 **`fn` 이 끝날 때까지 그 안의
 * 상태 갱신을 커밋하지 않는다.** 그래서 액션 첫 줄의 `setPending(true)` 가 화면에 못 닿고,
 * `disabled={pending}` 이 **아무것도 안 막는다** — `Q20-1` 이 실측으로 잡았다: 비행 중에
 * 세 번 제출하면 세 번 다 나간다.
 *
 * **린트로는 못 막는다**(`Q20-3` 이 재 봤다). ESLint 선택자는 노드 단위라 「이 파일에
 * `<form action=` 이 있고 **동시에** pending `useState` 가 있다」를 표현할 수 없다 —
 * 커스텀 플러그인을 새로 만들어야 하고, 그 값이 이 규칙 하나보다 크다.
 *
 * **`onSubmit` 폼은 반대다.** 거기서는 `useState` 로 드는 것이 맞다(`D24` 「`onSubmit` 폼은 반대다」).
 * 그래서 `action={` 을 가진 파일만 본다.
 */
/** vitest 는 `frontend/` 에서 돈다. `import.meta.url` 은 Windows 에서 앞에 슬래시가 붙어 안 맞는다 */
const SRC = join(process.cwd(), "src");

/** `<form ... action={` — 사이에 `ref=` 같은 것이 끼어도 잡는다 */
const FORM_ACTION = /<form[^>]*\saction=\{/;

/** `const [pending, setPending] = useState(` — 이름이 제출 중을 뜻하는 것만 */
const PENDING_STATE =
  /const\s*\[\s*(pending|sending|submitting|saving|posting)\s*,[^\]]*\]\s*=\s*useState\s*\(/i;

function tsxFilesUnder(dir: string): string[] {
  const found: string[] = [];
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) {
      found.push(...tsxFilesUnder(path));
    } else if (entry.endsWith(".tsx") && !entry.endsWith(".test.tsx")) {
      found.push(path);
    }
  }
  return found;
}

describe("폼이 제출 중인지를 손으로 들지 않는다", () => {
  const files = tsxFilesUnder(SRC);

  it("훑을 화면이 있다", () => {
    // 경로가 틀리면 0개를 읽고 조용히 통과한다. 그쪽이 규칙이 깨진 것보다 나쁘다.
    expect(files.length).toBeGreaterThan(10);
  });

  it("`<form action={}>` 을 쓰는 파일이 실제로 있다", () => {
    // 이것이 0이 되면 위 규칙이 아무 파일에도 안 걸린다 — 그때는 이 테스트가 뜻을 잃는다.
    const forms = files.filter((f) => FORM_ACTION.test(readFileSync(f, "utf8")));
    expect(forms.length).toBeGreaterThan(0);
  });

  it("그 파일들이 pending 을 `useState` 로 들지 않는다", () => {
    const offenders = files.filter((path) => {
      const source = readFileSync(path, "utf8");
      return FORM_ACTION.test(source) && PENDING_STATE.test(source);
    });

    expect(
      offenders.map((p) => p.slice(SRC.length)),
      "`<form action={fn}>` 은 fn 이 끝날 때까지 그 안의 상태 갱신을 커밋하지 않는다 — " +
        "`disabled={pending}` 이 비행 중에 아무것도 안 막는다. " +
        "`components/submit-button.tsx` 를 쓴다(`D24`)",
    ).toEqual([]);
  });
});
