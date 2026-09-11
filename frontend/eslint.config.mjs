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
