import AxeBuilder from "@axe-core/playwright";
import { expect, type Page } from "@playwright/test";

import { DEMO_GROUPS, DEMO_PASSWORD } from "../src/app/login/demo-accounts";

/**
 * E2E 시나리오가 같이 쓰는 걸음(`Q207`). **역할을 오가는 흐름**이 이 층에 있을 이유다 — 한 역할 안의 것은 아래 층이 잰다
 * (`D15` 「E2E — 무엇을 보고 무엇을 안 보나」).
 *
 * <p><b>시나리오마다 자기 주문을 만든다.</b> 시드 주문을 쓰면 두 번째 실행이 다른 상태에서 시작한다.
 * <b>계정 전환은 쿠키를 지우고 다시 로그인</b>이다 — `storageState` 는 안 쓴다(셋이 역할 넷을 오간다).
 */

function 계정(title: string): string {
  return DEMO_GROUPS.find((group) => group.title === title)!.accounts[0].email;
}

/** 데모 티셔츠는 `demo-fashion` 것이고 판매자1 이 그 대표다(`V904`) */
export const 구매자 = 계정("구매자");
export const 판매자 = 계정("판매자");
export const 관리자 = 계정("시스템관리자");

export async function 로그인(page: Page, email: string) {
  await page.context().clearCookies();
  await page.goto("/login");
  await page.getByLabel("이메일").fill(email);
  await page.getByLabel("비밀번호").fill(DEMO_PASSWORD);
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(page).not.toHaveURL(/\/login/);
}

/** 되돌릴 수 없는 동작은 확인 창을 띄운다(`orders/status.ts`) — 사람이 「예」를 누르는 것과 같다 */
export function 확인창을_받는다(page: Page) {
  page.on("dialog", (dialog) => dialog.accept());
}

/**
 * 옵션 하나를 고른다. 라디오가 `sr-only` 라 라벨을 누른다(`checkout.spec.ts` 와 같은 이유).
 */
async function 고른다(page: Page, 값: string) {
  await page.getByRole("radio", { name: 값 }).locator("xpath=ancestor::label[1]").click();
  await expect(page.getByRole("radio", { name: 값 })).toBeChecked();
}

/**
 * 구매자로 데모 티셔츠 하나를 주문·결제하고 주문 상세로 간다. 주문 번호와 셀러 묶음 번호를 돌려준다 —
 * 묶음 번호는 화면에 글자로 안 나와서 묶음 구역의 `aria-labelledby`(`bundle-<번호>`)에서 읽는다.
 */
export async function 주문하고_결제한다(page: Page): Promise<{ 주문번호: string; 묶음번호: string }> {
  await 로그인(page, 구매자);
  await page.goto("/products");
  await page.getByRole("link", { name: /데모 티셔츠/ }).click();
  await 고른다(page, "검정");
  await 고른다(page, "M");
  await page.getByRole("button", { name: /장바구니에 담기/ }).click();
  await expect(page.getByText("장바구니에 담았습니다.")).toBeVisible();

  await page.goto("/checkout");
  await page.getByLabel("받는 분").fill("홍길동");
  await page.getByLabel("연락처").fill("010-1234-5678");
  await page.getByLabel("우편번호").fill("06236");
  await page.getByLabel("주소", { exact: true }).fill("서울특별시 강남구 테헤란로 123");
  await page.getByRole("button", { name: /결제하기/ }).click();
  await expect(page.getByRole("heading", { name: "결제가 완료되었습니다." })).toBeVisible();

  await page.getByRole("link", { name: "주문 상세 보기" }).click();
  await expect(page).toHaveURL(/\/orders\/[^/]+$/);
  const 주문번호 = decodeURIComponent(new URL(page.url()).pathname.split("/").pop()!);
  const labelledBy = await page.locator('section[aria-labelledby^="bundle-"]').first().getAttribute("aria-labelledby");
  return { 주문번호, 묶음번호: labelledBy!.replace(/^bundle-/, "") };
}

/**
 * 판매자가 보내고 배송완료로 옮긴다.
 *
 * <p><b>끝을 알림 문구로 안 잰다</b> — 조작 목록이 비면 `OrderActions` 가 통째로 사라져 문구도 같이 사라진다.
 * CI 는 새로 그리는 것이 빨라서 문구를 보기 전에 지워졌다(마무리 54차 실측). 버튼이 사라지는 것이 서버가 받아들인 신호다.
 */
export async function 보내고_배송완료한다(page: Page, 묶음번호: string) {
  await 로그인(page, 판매자);
  await page.goto(`/seller/orders/${묶음번호}`);
  await page.getByLabel("택배사").selectOption("CJ");
  await page.getByLabel("송장 번호").fill("123456789012");
  await page.getByRole("button", { name: "발송 처리" }).click();
  await expect(page.getByRole("button", { name: "배송완료 처리" })).toBeVisible();
  await page.getByRole("button", { name: "배송완료 처리" }).click();
  await expect(page.getByRole("button", { name: "배송완료 처리" })).toHaveCount(0);
}

/**
 * 지금 화면에 접근성 위반이 없는지 라이트·다크 두 모드로 본다(`Q277`). 대비는 장애인차별금지법 제20·21조 → KWCAG 2.2 의 4.5:1 이다.
 *
 * <p><b>jsdom 이 못 보는 것을 본다</b> — 색 대비는 진짜 CSS 가 있어야 돌아서 `src/test/axe.ts` 에서는 0이다.
 * 다크 토큰(`globals.css` 의 `prefers-color-scheme: dark`)은 `emulateMedia` 로 바꿔 한 번 더 잰다 — 프로젝트를 둘로
 * 가르면 e2e 전체가 2배라 화면마다 두 번이 싸다.
 *
 * <p><b>`target-size`(WCAG 2.2 2.5.8, 24px)는 axe 기본이 꺼져 있어서 켠다.</b>
 */
export async function expectNoAxeViolations(page: Page) {
  const reports: string[] = [];
  for (const colorScheme of ["light", "dark"] as const) {
    await page.emulateMedia({ colorScheme });
    // 색 전환(`transition-colors duration-200`)이 끝나기를 기다린다. 안 기다리면 axe 가 중간색을 재서
    // 없는 대비 위반을 낸다(`Q277` 첫 판 — 다크 #a1a1aa 가 #76767f 로 잡혔다).
    // `finished` 약속이 아니라 상태를 폴링한다 — `/login` 을 연 직후 그 약속이 30초 안 와서 시간 초과였다.
    await page.waitForFunction(() =>
      document.getAnimations().every((animation) => !(animation instanceof CSSTransition) || animation.playState !== "running"),
    );
    const result = await new AxeBuilder({ page }).options({ rules: { "target-size": { enabled: true } } }).analyze();
    // 규칙 이름만 내면 어디를 고쳐야 하는지 모른다. 어긴 마디를 같이 적는다(`src/test/axe.ts` 와 같은 꼴).
    for (const violation of result.violations) {
      const where = violation.nodes.map((node) => `      ${node.html}`).join("\n");
      reports.push(`  [${colorScheme}] ${violation.id} (${violation.impact}) — ${violation.help}\n${where}`);
    }
  }
  await page.emulateMedia({ colorScheme: "light" });
  // soft 다 — 한 화면에서 멈추면 뒤 화면의 위반이 안 보인다.
  expect.soft(reports, `접근성 위반 ${reports.length}건 — ${page.url()}\n${reports.join("\n")}`).toEqual([]);
}
