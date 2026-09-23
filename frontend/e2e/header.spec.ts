import { expect, test } from "@playwright/test";

/**
 * 머리의 글자가 꺾이거나 넘치지 않나(`Q213`). **레이아웃을 재는 층이 여기뿐이다** — jsdom 은 크기를 안 재서
 * 단위 시험으로는 「관리자 링크 열일곱이 한 줄에 눌려 글자 단위로 꺾인다」를 못 잡는다.
 *
 * <p>링크가 가장 많은 관리자로 들어가서, 좁은 폭과 넓은 폭 둘에서 본다.
 * <ul>
 *   <li><b>링크·버튼마다 한 줄이다</b> — 높이가 제 줄 높이의 한 배 반을 안 넘는다</li>
 *   <li><b>페이지가 가로로 안 넘친다</b> — 링크를 안 꺾게 하면서 줄을 못 바꾸게 하면 좁은 폭에서 넘친다(WCAG 1.4.10)</li>
 * </ul>
 */
const 관리자 = { 이메일: "admin@example.com", 비밀번호: "demo-password-1234" };

for (const 폭 of [360, 1280]) {
  test(`관리자 머리는 ${폭}px 에서 글자가 안 꺾이고 가로로 안 넘친다`, async ({ page }) => {
    await page.setViewportSize({ width: 폭, height: 800 });
    await page.goto("/login");
    await page.getByLabel("이메일").fill(관리자.이메일);
    await page.getByLabel("비밀번호").fill(관리자.비밀번호);
    await page.getByRole("button", { name: "로그인" }).click();
    await expect(page).not.toHaveURL(/\/login/);

    const header = page.locator("header");
    await expect(header.getByRole("navigation", { name: "관리" })).toBeVisible();

    const 꺾인것 = await header.locator("a, button").evaluateAll((elements) =>
      elements
        .filter((element) => {
          const style = getComputedStyle(element);
          const lineHeight = parseFloat(style.lineHeight) || parseFloat(style.fontSize) * 1.5;
          return element.getBoundingClientRect().height > lineHeight * 1.5;
        })
        .map((element) => element.textContent?.trim()),
    );
    expect(꺾인것, "두 줄 이상으로 꺾인 머리 링크").toEqual([]);

    const 넘침 = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    );
    expect(넘침, "페이지가 가로로 넘친 폭(px)").toBeLessThanOrEqual(0);
  });
}
