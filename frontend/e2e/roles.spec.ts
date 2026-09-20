import { expect, test, type Page } from "@playwright/test";

import { DEMO_GROUPS, DEMO_PASSWORD } from "../src/app/login/demo-accounts";

/**
 * 세 사용자 그룹이 각자 갈 곳까지 가나(`Q137`).
 *
 * <p><b>`smoke.spec.ts` 와 단위가 다르다.</b> 저쪽은 프록시·세션 쿠키·CSRF 셋이 얽힌 자리를
 * 한 계정으로 밟는 것이고, 이쪽은 <b>역할마다 화면군이 열리나</b>를 밟는다. 그 셋은 이미
 * 저쪽이 보므로 여기서 다시 안 본다 — 여기가 보는 것은 권한과 화면군의 짝이다.
 *
 * <p><b>손으로 한 번 확인한 것을 시험으로 내린다.</b> 2026-09-20 에 배포한 사이트를 브라우저로
 * 밟아 세 역할이 도는 것을 봤는데, 그것은 <b>그 시점의 사실이지 지켜지는 규칙이 아니다</b> —
 * 다음 사람이 셀러 화면을 깨도 CI 가 초록이었다. `D15` 가 「기록은 재발을 못 막는다」고 적은 자리다.
 *
 * <p><b>계정은 화면이 공개한 것을 그대로 쓴다</b>(`Q131`의 `demo-accounts.ts`). 여기서 따로 적으면
 * 사본이 하나 더 생기고, 안내가 바뀔 때 이 시험만 낡는다. 시드와의 대조는
 * `demo-accounts.test.ts` 가 이미 한다 — 그래서 이 파일은 <b>목록을 믿고 쓴다.</b>
 *
 * <p><b>그룹마다 첫 계정만 쓴다.</b> 셋을 둔 이유는 사람이 동시에 보라는 것이지 시험이
 * 셋 다 밟으라는 것이 아니다. 같은 역할을 세 번 밟으면 시간만 세 배다.
 */

/** 그 그룹의 첫 계정. 목록 순서가 화면 순서이므로 「구매자1」·「판매자1」·「시스템관리자1」이다 */
function 첫계정(그룹이름: string): string {
  const 그룹 = DEMO_GROUPS.find((it) => it.title === 그룹이름);
  if (!그룹) {
    throw new Error(`안내에 없는 그룹이다: ${그룹이름}`);
  }
  return 그룹.accounts[0].email;
}

/**
 * 로그인하고 홈으로 나온다.
 *
 * <p><b>안내 패널을 지나서 들어간다.</b> 칸에 직접 치면 안내가 깨져도 이 시험이 초록이다 —
 * 눌러서 채우는 것까지가 `Q131` 이 사용자에게 약속한 것이라, 그 길로 들어가야 그 약속을 잰다.
 */
async function 안내로로그인(page: Page, 계정이름: string) {
  await page.goto("/login");

  // `<details>` 는 접혀 있어도 DOM 에 있지만, 안 펴면 사람이 못 누른다. 사람과 같은 순서로 간다.
  await page.getByText("계정 없이 둘러보기").click();
  await page.getByRole("button", { name: `${계정이름} 계정으로 칸 채우기` }).click();

  await expect(page.getByLabel("비밀번호")).toHaveValue(DEMO_PASSWORD);

  await page.getByRole("button", { name: "로그인" }).click();
  await expect(page).not.toHaveURL(/\/login/);
}

/** 셸의 주요 메뉴. 역할마다 여기 뜨는 링크가 갈린다(`D20` 「권한 없는 것은 숨긴다」) */
function 주요메뉴(page: Page) {
  return page.getByRole("navigation", { name: "주요 메뉴" });
}

test("구매자는 결제까지 간다", async ({ page }) => {
  expect(첫계정("구매자")).toBe("buyer1@example.com");
  await 안내로로그인(page, "구매자1");

  // ── 옵션이 있는 상품을 고른다. 조합을 안 고르면 담기 버튼이 안 뜬다
  await page.goto("/products/1");
  await page.getByText("검정", { exact: true }).click();
  await page.getByText("M", { exact: true }).click();

  await page.getByRole("button", { name: /장바구니에 담기/ }).click();
  await expect(page.getByText("장바구니에 담았습니다.")).toBeVisible();

  // ── 주문서. 배송지를 채우고 모의 결제를 보낸다
  await page.goto("/checkout");
  await page.getByLabel("받는 분").fill("홍길동");
  await page.getByLabel("연락처").fill("010-1234-5678");
  await page.getByLabel("우편번호").fill("06236");
  await page.getByLabel("주소", { exact: true }).fill("서울특별시 강남구 테헤란로 123");

  // 카드번호는 화면이 승인되는 값으로 미리 채워 둔다. 그대로 보낸다.
  await page.getByRole("button", { name: /결제하기/ }).click();

  // **승인번호까지 본다.** 「완료」 문구만 보면 결제가 거절돼도 그 화면이 뜨는지 못 가린다.
  await expect(page.getByRole("heading", { name: "결제가 완료되었습니다." })).toBeVisible();
  await expect(page.getByText("승인번호")).toBeVisible();
});

test("판매자는 받은 주문을 본다", async ({ page }) => {
  expect(첫계정("판매자")).toBe("seller1@example.com");
  await 안내로로그인(page, "판매자1");

  // 셸이 셀러 링크를 그린다. 권한으로 가리는 자리라 여기가 어긋나면 갈 입구가 없다.
  await expect(주요메뉴(page).getByRole("link", { name: "받은 주문" })).toBeVisible();

  await 주요메뉴(page).getByRole("link", { name: "받은 주문" }).click();
  await expect(page.getByRole("heading", { name: "받은 주문", level: 1 })).toBeVisible();

  // **관리자 입구는 안 보여야 한다.** 파는 사람에게 감사 기록 권한이 없다(`V12`).
  await expect(주요메뉴(page).getByRole("link", { name: "감사 기록" })).toHaveCount(0);
});

test("시스템관리자는 감사 기록을 본다", async ({ page }) => {
  expect(첫계정("시스템관리자")).toBe("admin1@example.com");
  await 안내로로그인(page, "시스템관리자1");

  await expect(주요메뉴(page).getByRole("link", { name: "감사 기록" })).toBeVisible();

  await 주요메뉴(page).getByRole("link", { name: "감사 기록" }).click();
  await expect(page.getByRole("heading", { name: "감사 기록", level: 1 })).toBeVisible();
});

test("구매자는 관리자 화면을 못 연다", async ({ page }) => {
  await 안내로로그인(page, "구매자2");

  // 셸이 안 그리는 것과 서버가 막는 것은 다른 일이다. **주소를 직접 쳐서 뒤엣것을 본다.**
  await expect(주요메뉴(page).getByRole("link", { name: "감사 기록" })).toHaveCount(0);

  await page.goto("/admin/audit");
  await expect(page.getByRole("heading", { name: "감사 기록", level: 1 })).toHaveCount(0);
});
