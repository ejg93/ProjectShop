import { expect, test, type Page } from "@playwright/test";

import { 관리자, 구매자, 로그인, 보내고_배송완료한다, 주문하고_결제한다, 판매자, 확인창을_받는다 } from "./helpers";

/**
 * 반품 — 손님·셀러·관리자가 한 묶음을 오간다(`Q207`, `43a-5`·`Q212`).
 *
 * <p><b>이 층에 있는 이유는 넷이 실제로 오가서다.</b> 접수는 손님, 입고·소견은 셀러, 판정은 관리자고, 결과는 다시 손님 화면에 선다.
 * 한 역할 안의 규칙(입고 전 승인 금지·훼손 거절의 검수 요구)은 `ReturnDecisionTest` 가 컨테이너에서 잰다 — 여기는 그 사슬이 화면으로
 * 이어지는지만 본다.
 */

test.setTimeout(120_000);

/** 손님이 반품을 신청하고 셀러가 입고하며 소견을 남긴다 — 판정 앞까지 */
async function 반품을_입고까지(page: Page): Promise<string> {
  확인창을_받는다(page);
  const { 주문번호, 묶음번호 } = await 주문하고_결제한다(page);
  await 보내고_배송완료한다(page, 묶음번호);

  await 로그인(page, 구매자);
  await page.goto(`/orders/${주문번호}`);
  await page.getByRole("button", { name: "반품 신청" }).click();
  // 문구가 아니라 버튼이 사라지는 것으로 — 접수 뒤엔 손님 조작이 없어 알림째 사라진다(`helpers.ts`)
  await expect(page.getByRole("button", { name: "반품 신청" })).toHaveCount(0);

  await 로그인(page, 판매자);
  await page.goto(`/seller/orders/${묶음번호}`);
  await page.getByLabel("검수 소견 (선택)").fill("본품 표면에 긁힘. 사용 흔적이 있다");
  await page.getByRole("button", { name: "입고 확인" }).click();
  await expect(page.getByText("검수 마침")).toBeVisible();
  return 주문번호;
}

test("반품을 받아들이면 손님 화면에 반품 완료가 선다", async ({ page }) => {
  const 주문번호 = await 반품을_입고까지(page);

  await 로그인(page, 관리자);
  await page.goto(`/admin/orders/${주문번호}`);
  await expect(page.getByRole("radio", { name: "다시 판매합니다" })).toBeChecked();
  await page.getByLabel("승인 사유 (기록에 남습니다)").fill("검수 결과 재판매 가능");
  await page.getByRole("button", { name: "반품 승인" }).click();
  await expect(page.getByRole("button", { name: "반품 승인" })).toHaveCount(0);

  await 로그인(page, 구매자);
  await page.goto(`/orders/${주문번호}`);
  await expect(page.getByText("반품 완료", { exact: true })).toBeVisible();
});

test("검수 소견이 있으면 훼손으로 거절하고 손님 화면에 반품 거절이 선다", async ({ page }) => {
  const 주문번호 = await 반품을_입고까지(page);

  await 로그인(page, 관리자);
  await page.goto(`/admin/orders/${주문번호}`);
  // 검수 뒤라 서버가 「상품 훼손」을 실었다(`Q234`)
  await page.getByLabel("거절 사유 종류").selectOption({ label: "상품 훼손" });
  await page.getByLabel("거절 사유 (고객에게 알립니다)").fill("사용으로 인한 훼손이 확인되었습니다.");
  await page.getByRole("button", { name: "반품 거절" }).click();
  await expect(page.getByRole("button", { name: "반품 거절" })).toHaveCount(0);

  await 로그인(page, 구매자);
  await page.goto(`/orders/${주문번호}`);
  await expect(page.getByText("반품 거절", { exact: true })).toBeVisible();
});
