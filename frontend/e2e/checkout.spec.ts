import { expect, test, type Page } from "@playwright/test";

import { DEMO_GROUPS, DEMO_PASSWORD } from "../src/app/login/demo-accounts";

/**
 * 결제까지 관통(`Q137`).
 *
 * <p><b>이 층의 조건을 다시 물어서 남은 하나다.</b> 처음엔 세 역할을 다 밟는 파일을 썼는데,
 * `D15` 「E2E — 무엇을 보고 무엇을 안 보나」가 <b>「여기 있어야 할 이유가 『그 셋이 실제로
 * 오가나』가 아니면 아래로 내린다」</b>고 정해 뒀다. 독립 리뷰가 그것을 짚었고, 셀러·관리자
 * 링크가 권한대로 갈리나는 <b>이미 아래에 있다</b> — {@code site-header.test.tsx} 넷과
 * {@code AuditLogQueryTest} 가 같은 것을 jsdom·컨테이너에서 잰다. 브라우저로 다시 밀 이유가 없다.
 *
 * <p><b>결제만 남은 이유는 그 셋이 여기서 한 번 더 얽히기 때문이다.</b> 주문과 결제는
 * 쓰기가 둘이고 <b>멱등키 헤더</b>가 붙는다(`D11`). 그 헤더에 파라미터 제약이 걸려 있어
 * 검증 실패의 예외 갈래가 갈리는 자리고(`Q127`), 프록시를 지나며 CSRF 토큰이 실려야 통과한다.
 * {@code smoke.spec.ts} 는 담기까지라 그 구간을 안 지난다.
 *
 * <p><b>문구가 아니라 역할·레이블로 잡는다</b>(`Q9`). 상품도 번호가 아니라 이름으로 연다 —
 * 시드가 하나 더 붙으면 번호가 밀린다.
 *
 * <p>계정은 로그인 화면이 공개하는 목록에서 가져온다(`Q131`). 시드와의 대조는
 * {@code demo-accounts.test.ts} 가 한다.
 */

/**
 * 옵션 하나를 고른다.
 *
 * <p><b>`check()` 가 안 먹는다.</b> 라디오가 `sr-only` 라 크기가 0 이고, 보이는 것은 그것을
 * 감싼 라벨이다 — Playwright 의 actionability 검사가 30초를 기다리다 죽는다(실측).
 *
 * <p><b>그래도 역할로 찾는다</b>(`Q9`). 찾는 것은 라디오고 <b>누르는 것은 라벨</b>이다 —
 * 사람이 하는 것과 같다. 텍스트로 찾으면 같은 글자가 다른 데 있을 때 엉뚱한 것을 집는다.
 */
async function 고른다(page: Page, 값: string) {
  await page.getByRole("radio", { name: 값 }).locator("xpath=ancestor::label[1]").click();
  await expect(page.getByRole("radio", { name: 값 })).toBeChecked();
}

/** 안내의 첫 구매자. <b>이 값이 시험을 움직인다</b> — 이름을 박으면 목록이 바뀌어도 안 걸린다 */
const 구매자 = DEMO_GROUPS.find((it) => it.title === "구매자")!.accounts[0];

test("안내 계정으로 들어가 결제까지 간다", async ({ page }) => {
  // ── 안내 패널을 지나서 로그인한다. 칸에 직접 치면 안내가 깨져도 초록이다
  await page.goto("/login");
  await page.getByText("계정 없이 둘러보기").click();
  await page.getByRole("button", { name: `${구매자.name} 계정으로 칸 채우기` }).click();

  // 두 칸을 다 본다. 비밀번호는 아홉이 같아서 **어느 계정을 골랐는지 못 가린다**.
  await expect(page.getByLabel("이메일")).toHaveValue(구매자.email);
  await expect(page.getByLabel("비밀번호")).toHaveValue(DEMO_PASSWORD);

  await page.getByRole("button", { name: "로그인" }).click();
  await expect(page).not.toHaveURL(/\/login/);

  // ── 옵션이 있는 상품. 조합을 안 고르면 담기 버튼이 안 뜬다
  await page.goto("/products");
  await page.getByRole("link", { name: /데모 티셔츠/ }).click();
  await 고른다(page, "검정");
  await 고른다(page, "M");

  await page.getByRole("button", { name: /장바구니에 담기/ }).click();
  await expect(page.getByText("장바구니에 담았습니다.")).toBeVisible();

  // ── 주문서. 여기서 주문과 결제 두 번의 쓰기가 멱등키를 달고 프록시를 지난다
  await page.goto("/checkout");
  await page.getByLabel("받는 분").fill("홍길동");
  await page.getByLabel("연락처").fill("010-1234-5678");
  await page.getByLabel("우편번호").fill("06236");
  await page.getByLabel("주소", { exact: true }).fill("서울특별시 강남구 테헤란로 123");

  // 카드번호는 화면이 승인되는 값으로 채워 둔다. 그대로 보낸다.
  await page.getByRole("button", { name: /결제하기/ }).click();

  // **승인번호에 값이 있는지까지 본다.** 제목만 보면 거절은 걸러지지만
  // 「승인인데 번호가 비었다」는 안 걸린다 — 그 값은 서버가 채운다.
  await expect(page.getByRole("heading", { name: "결제가 완료되었습니다." })).toBeVisible();
  const 승인번호 = page.getByRole("term").filter({ hasText: "승인번호" });
  await expect(승인번호).toBeVisible();
  await expect(page.getByRole("definition").nth(2)).not.toBeEmpty();
});
