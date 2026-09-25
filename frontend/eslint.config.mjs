import js from "@eslint/js";
import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";
import jsxA11y from "eslint-plugin-jsx-a11y";

// 화면 규칙 셋(`Q228`, `quality-gates.md` 원장 ⑩~⑫). 파일 범위가 둘이라 조각으로 두고 아래 두 블록이 섞어 쓴다 —
// 같은 규칙을 두 블록이 켜면 뒤 블록이 앞 블록을 **덮어서**(합치지 않는다) `page.tsx` 에는 둘을 다 싣는다.
// `ApiError` 는 `super(detail)` 이라 `message` 가 서버 문구고, 다른 오류는 영어 기술어(`TypeError: Failed to fetch`)다.
// **길이 넷이다**(마무리 52차 독립 리뷰가 둘을 더 찾았다) — 멤버 읽기 · 구조 분해 · `String(e)` · `${e}`.
// 뒤 둘은 흔한 오류 변수 이름(`e`·`err`·`error`·`caught`)만 본다 — 이름을 바꾸면 빠지지만 이 저장소의 `catch` 는 그 넷이다.
const ERROR_TEXT_MESSAGE =
  "화면은 오류의 message 를 안 그린다 — 영어 기술어이거나 서버 문구다. ApiError 는 slug 로 갈라 자기 문구를 고른다(screen-rules.md 「서버 문구를 그대로 안 쓴다」)";
const ERROR_NAME = "/^(e|err|error|caught)$/";
const NO_ERROR_MESSAGE = [
  { selector: "MemberExpression[property.name='message']", message: ERROR_TEXT_MESSAGE },
  { selector: ":matches(VariableDeclarator, CatchClause) > ObjectPattern > Property[key.name='message']", message: ERROR_TEXT_MESSAGE },
  { selector: `CallExpression[callee.name='String'] > Identifier[name=${ERROR_NAME}]`, message: ERROR_TEXT_MESSAGE },
  { selector: `TemplateLiteral > Identifier[name=${ERROR_NAME}]`, message: ERROR_TEXT_MESSAGE },
];
const NO_STATIC_SEGMENT = [
  {
    // `as const`·`satisfies` 로 감싸면 값이 한 층 안으로 들어간다 — 둘 다 본다
    selector:
      "ExportNamedDeclaration VariableDeclarator[id.name='dynamic']:matches([init.value='force-static'], [init.expression.value='force-static'])",
    message: "화면에 force-static 을 안 건다 — 로그인한 사람의 응답이 빌드 산출물로 굳는다(frontend-rules.md 「캐시 — 실수하면 남의 것이 보인다」)",
  },
  {
    selector: "ExportNamedDeclaration VariableDeclarator[id.name='fetchCache']",
    message: "fetchCache 를 안 건다 — no-store 는 입구 둘이 정한다(frontend-rules.md 「캐시 — 실수하면 남의 것이 보인다」)",
  },
];
const NO_USE_CLIENT_ROUTE = {
  selector: "Program > ExpressionStatement[directive='use client']",
  message: "page·layout 은 서버 컴포넌트로 둔다 — 클라이언트 경계는 잎사귀 컴포넌트에 둔다(frontend-rules.md 「서버 컴포넌트가 기본이다」)",
};

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

  // 남이 준 글자를 HTML 로 그리는 자리를 막는다(`Q34`, `D14` 「HTML 출력」, OWASP A03).
  //
  // React 는 기본으로 글자를 이스케이프하는데 `dangerouslySetInnerHTML` 은 그것을 끈다.
  // 상품 설명·문의 본문처럼 남이 쓴 글이 거기 닿으면 남의 스크립트가 우리 페이지에서 돈다.
  //
  // 켜기 전에 쟀다 — 저장소 전체에 0건이라 기준선 없이 바로 건다(`Q29`·`Q20-4` 와 같은 수).
  // `react` 플러그인은 `eslint-config-next` 가 이미 등록해 뒀으므로 규칙만 켠다.
  { rules: { "react/no-danger": "error" } },

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
            {
              // 빌드가 구글을 부른다 — 못 받으면 빌드 전체가 죽었다(PR #77·#79). 오프라인 빌드(`docker.yml`)보다 싼 층에서 먼저 막는다(`Q214`, 마무리 52차 리뷰 봇)
              name: "next/font/google",
              message: "글꼴은 npm 패키지 안의 파일을 쓴다(geist/font/*) — next/font/google 은 빌드 때 구글을 부른다(stack.md 「글꼴은 npm 안에 있다」)",
            },
          ],
        },
      ],
    },
  },
  // 화면 규칙 셋을 린트로 내린다(`Q228`). **켜기 전에 쟀다** — `message` 1건(상품 등록 폼, 같은 청크가 고쳤다),
  // page·layout 의 `"use client"` 0건, `force-static`·`fetchCache` 0건이라 기준선 없이 바로 건다.
  {
    files: ["src/app/**/*.{ts,tsx}", "src/components/**/*.{ts,tsx}"],
    ignores: ["**/*.test.ts", "**/*.test.tsx"],
    rules: { "no-restricted-syntax": ["error", ...NO_ERROR_MESSAGE, ...NO_STATIC_SEGMENT] },
  },
  {
    files: ["src/app/**/page.tsx", "src/app/**/layout.tsx"],
    rules: { "no-restricted-syntax": ["error", ...NO_ERROR_MESSAGE, ...NO_STATIC_SEGMENT, NO_USE_CLIENT_ROUTE] },
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
