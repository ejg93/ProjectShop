import js from "@eslint/js";
import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";
import jsxA11y from "eslint-plugin-jsx-a11y";

const eslintConfig = defineConfig([
  // **기본 묶음을 깐다**(`Q20-4`). `eslint-config-next` 는 이것을 안 포함해서
  // `no-empty`·`no-unused-private-class-members` 같은 것이 꺼져 있었다 —
  // 빈 `finally { }` 가 조용히 남아 있던 것을 9차 마무리의 독립 리뷰가 찾았다.
  //
  // **켜기 전에 쟀다**: 기존 코드에서 검출 0건이라 기준선을 안 만들었다(`2e` 와 같은 수).
  //
  // **버전을 eslint 와 맞춰 박는다** — `@eslint/js@latest` 는 eslint 10 을 peer 로 부르는데
  // 이 저장소는 9.39.5 다. 전이 의존으로만 두면 그 버전이 조용히 갈린다.
  js.configs.recommended,
  ...nextVitals,
  ...nextTs,

  // 접근성 규칙 전체(`D20`). eslint-config-next 도 jsx-a11y 를 일부 켜지만 그건 부분집합이라,
  // 라벨 없는 입력칸이나 키보드로 못 누르는 클릭 요소가 그물을 빠져나간다.
  //
  // 화면을 만들기 전에 켠다. 다 만든 뒤에 켜면 이미 나온 마크업을 되돌리는 일이 된다(`D23`).
  //
  // 이 도구는 글자만 보므로 빠뜨린 것만 잡는다 — alt="이미지" 처럼 성의 없이 채운 것은 못 잡고,
  // 대비비와 탭 순서도 못 본다. 그쪽은 `D20` 이 사람이 볼 규칙으로 따로 둔다.
  //
  // 규칙만 가져온다. 설정 통째로(`jsxA11y.flatConfigs.recommended`) 넣으면
  // "Cannot redefine plugin" 으로 죽는다 — eslint-config-next 가 이미 같은 이름으로 등록해 뒀다.
  { rules: jsxA11y.flatConfigs.recommended.rules },

  // 서버를 부르는 입구를 파일 둘에 가둔다(`Q29`, `D24` 「서버를 부르는 입구가 셋이다」).
  //
  // **문서에만 있던 규칙이다.** `fetch` 를 화면에서 직접 쓰면 표기 변환·CSRF·오류 변환을
  // 안 거친 응답이 화면에 닿고, `next/headers` 를 다른 파일이 들면 세션 운반이 두 군데가 된다
  // (`D24` 「그래서 운반을 한 군데에 가둔다」). 켜기 전에 쟀다 — 밖의 `fetch` 0건,
  // `next/headers` 는 `api-session.ts` 하나라 기준선 없이 바로 건다(`2e` 와 같은 수).
  //
  // **클라이언트 컴포넌트가 `api-session` 을 드는 것은 여기서 안 막는다** — `next/headers` 가
  // 클라이언트 번들에 들어가면 `next build` 가 자체로 선다(`Q29` 에서 실측). 빌드가 막는 것을
  // 린트가 또 막으면 규칙이 두 벌이 된다.
  //
  // `globalThis.fetch` 같은 멤버 접근은 이 규칙이 안 본다 — 테스트가 spy 를 거는 자리라 그게 맞다.
  {
    rules: {
      "no-restricted-globals": [
        "error",
        {
          name: "fetch",
          message: "서버는 api()·apiPublic()·apiSession() 으로만 부른다(frontend-rules.md 「서버를 부르는 입구가 셋이다」)",
        },
      ],
      "no-restricted-imports": [
        "error",
        {
          paths: [
            {
              name: "next/headers",
              message: "세션 운반은 src/lib/api-session.ts 한 곳이다(frontend-rules.md 「그래서 운반을 한 군데에 가둔다」)",
            },
          ],
        },
      ],
    },
  },
  {
    files: ["src/lib/api.ts", "src/lib/api-session.ts"],
    rules: { "no-restricted-globals": "off", "no-restricted-imports": "off" },
  },
  {
    // 테스트는 `fetch` 를 흉내 내는 자리라 부르는 것이 일이다.
    files: ["**/*.test.ts", "**/*.test.tsx", "vitest.setup.ts", "e2e/**"],
    rules: { "no-restricted-globals": "off" },
  },

  // Override default ignores of eslint-config-next.
  globalIgnores([
    // Default ignores of eslint-config-next:
    ".next/**",
    "out/**",
    "build/**",
    "next-env.d.ts",
  ]),
]);

export default eslintConfig;
