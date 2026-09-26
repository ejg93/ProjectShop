import { expect, test } from "@playwright/test";

import { 구매자, 로그인, 보내고_배송완료한다, 주문하고_결제한다, 확인창을_받는다 } from "./helpers";

/**
 * 구매 확정 — 손님과 셀러가 한 묶음을 오간다(`Q207`). 셀러가 보내고 배송완료로 옮긴 뒤 손님이 확정한다.
 * 자동 확정·정산은 배치 몫이라 이 층에서 안 밟는다(`Q207` 결정 (가) — 배치를 부르는 입구가 생기면 새 행).
 */

test.setTimeout(120_000);

test("배송완료된 묶음을 손님이 구매 확정한다", async ({ page }) => {
  확인창을_받는다(page);
  const { 주문번호, 묶음번호 } = await 주문하고_결제한다(page);
  await 보내고_배송완료한다(page, 묶음번호);

  await 로그인(page, 구매자);
  await page.goto(`/orders/${주문번호}`);
  await page.getByRole("button", { name: "구매 확정" }).click();
  await expect(page.getByText(/구매 확정 처리가 끝났습니다/)).toBeVisible();
  // 확정은 다시 못 한다. 「반품 신청」은 남는다 — 하자 반품은 확정 뒤에도 열린다(제17조제3항, `OrderTransitions`)
  await expect(page.getByRole("button", { name: "구매 확정" })).toHaveCount(0);
});
