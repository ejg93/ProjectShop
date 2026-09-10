import { expect, test } from "@playwright/test";

/**
 * 로그인 → 상품 → 장바구니 관통(청크 `Q18`).
 *
 * **jsdom 이 못 밟는 셋을 본다** — Next 의 rewrite 프록시(3000 → 8080),
 * 세션 쿠키 왕복, CSRF 토큰. 화면 테스트는 `vi.mock` 으로 서버를 대신하므로
 * 이 셋이 어긋나 있어도 초록이다.
 *
 * **문구가 아니라 역할·레이블로 잡는다**(`Q9`). 문구는 바뀌라고 있는 것이고,
 * 굳히면 `D20` 이 문구를 다듬을 때마다 빨개진다. 셀렉터가 안 잡히면
 * `data-testid` 를 더하지 말고 **접근성 이름을 고친다** — 그것이 `D20` 이 요구하는 것과 같다.
 *
 * 시드 계정을 쓴다(`local` 프로필). 없으면 백엔드를 `--spring.profiles.active=local` 로 띄운다.
 */

const 계정 = { 이메일: "customer@example.com", 비밀번호: "demo-password-1234" };

test("로그인하고 상품을 장바구니에 담는다", async ({ page }) => {
  // ── 로그인. 여기서 세션 쿠키와 CSRF 가 처음 오간다
  await page.goto("/login");
  await page.getByLabel("이메일").fill(계정.이메일);
  await page.getByLabel("비밀번호").fill(계정.비밀번호);
  await page.getByRole("button", { name: "로그인" }).click();

  await expect(page).not.toHaveURL(/\/login/);

  // ── 상품 하나를 연다
  await page.goto("/products");
  const 첫상품 = page.locator('a[href^="/products/"]').first();
  await expect(첫상품).toBeVisible();
  await 첫상품.click();
  await expect(page).toHaveURL(/\/products\/\d+/);

  // ── 장바구니에 담는다. 쓰기라 CSRF 토큰이 실려야 통과한다
  await page.getByRole("button", { name: /장바구니에 담기/ }).click();

  // **결과를 기다리고 넘어간다.** 안 기다리고 `/cart` 로 가면 요청이 끝나기 전에
  // 화면을 읽어서 「비었다」가 나온다 — 첫 판이 실제로 그랬고, 그것은 이 층이 아니면 안 보인다.
  // 실패 문구가 뜨면 그 문장이 그대로 실패 메시지가 된다.
  await expect(page.getByText("장바구니에 담았습니다.")).toBeVisible();

  // ── 담긴 것이 장바구니 화면에 보인다
  await page.goto("/cart");
  await expect(page.getByRole("heading", { name: "장바구니", level: 1 })).toBeVisible();
  await expect(page.getByText("담아 두신 상품이 없습니다.")).toHaveCount(0);
});
