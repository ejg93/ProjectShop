# frontend

Next.js 앱. 로그인, 상품 화면, 주문 흐름, 관리자 권한 편집 화면을 여기서 만든다.

## 돌리기

```bash
npm install     # 처음 한 번
npm run dev     # http://localhost:3000
npm run build   # 타입 검사까지 같이 돈다
npm run lint    # 접근성 규칙 포함(D20)
```

`npm run dev` 는 백엔드가 떠 있다고 가정한다. `../backend` 에서 `./gradlew bootRun` 을 먼저 띄운다.

## 백엔드를 어떻게 부르나

**같은 출처로 부른다.** 화면 코드는 `/api/...` 만 쓰고 포트를 모른다.

```
브라우저 → localhost:3000/api/orders → (Next rewrite) → localhost:8080/api/orders
```

주소는 `next.config.ts` 의 `BACKEND_ORIGIN` 환경변수로 바꾼다. 기본값이 `http://localhost:8080` 이다.

CORS 를 여는 대신 프록시를 쓴 이유는 `next.config.ts` 주석에 있다.

## 구성

| 것 | 값 |
|---|---|
| Next.js | 16, App Router |
| 스타일링 | Tailwind v4 — `design-taste-frontend` 스킬이 이걸로 쓰여 있다(`D20`) |
| 접근성 검사 | `eslint-plugin-jsx-a11y` recommended 전체 |

**`AGENTS.md` 를 먼저 읽는다.** Next 16 은 이전 판과 API 가 갈려서, 코드를 쓰기 전에
`node_modules/next/dist/docs/` 를 보라고 그 파일이 지시한다. `next dev` 가 다시 써 넣으므로 지우지 않는다.
