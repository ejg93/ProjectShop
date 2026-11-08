import { expect, test, type BrowserContext, type Page } from "@playwright/test";

import { expectNoAxeViolations, 로그인, 관리자, 구매자, 판매자 } from "./helpers";

/**
 * 화면 전수를 390px 진짜 브라우저로 밟는다(`점검 AG`, 2026-11-08). `점검 D-2`(2026-09-11)가 스크래치패드의
 * 일회용 스크립트로 열일곱을 밟은 것을 저장소에 남긴 것이다 — 기록은 재발을 못 막고 시험은 막는다.
 *
 * <p><b>화면마다 다섯을 잰다.</b> axe(라이트·다크 — 이름 없는 조작은 `button-name`·`link-name`·`label` 이 든다) ·
 * `h1` 하나 · 제목 단계 건너뜀 0 · 양수 `tabindex` 0(초점 역행) · 390px 에서 가로 스크롤 0. 전부 `expect.soft` 라
 * 한 화면이 빨개도 뒤 화면을 다 본다.
 *
 * <p><b>`D-2` 의 헛것 셋이 여기서는 안 걸린다</b>(`stack.md` 「화면을 진짜 브라우저로 밟는 방법이 있다」).
 * `<label for>` 를 못 읽던 것은 axe 가 접근성 트리로 묻는다 · `<nextjs-portal>` 은 개발 오버레이라 `next start`
 * (`playwright.config.ts` 의 `webServer`)에는 없다 · 미리 채운 로그인 칸은 `로그인` 이 `fill` 로 덮는다.
 *
 * <p><b>동적 화면은 id 를 안 박는다</b> — 목록 화면에서 첫 링크를 따라간다. 시드가 바뀌어도 산다.
 * 목록이 비면 그 화면은 「안 밟음」 주석을 남기고 지나간다. `/invitations/[token]` 은 토큰이 메일로만 나가서 안 밟는다.
 */

type Screen =
  | { path: string }
  /** `from` 목록에서 `link` 로 시작하는 첫 링크(쿼리 없는 것)를 따라간다 */
  | { from: string; link: string; suffix?: string };

const GROUPS: { role: string; email?: string; screens: Screen[] }[] = [
  {
    role: "공개",
    screens: [
      { path: "/" },
      { path: "/login" },
      { path: "/signup" },
      { path: "/password-reset" },
      { path: "/email-confirm" },
      { path: "/privacy" },
      { path: "/terms" },
      { path: "/review-policy" },
      { path: "/withdrawal-guide" },
      { path: "/copyright-report" },
      { path: "/products" },
      { from: "/products", link: "/products/" },
    ],
  },
  {
    role: "구매자",
    email: 구매자,
    screens: [
      { path: "/cart" },
      { path: "/checkout" },
      { path: "/me" },
      { path: "/me/coupons" },
      { path: "/me/inquiries" },
      { path: "/me/reviews" },
      { path: "/me/withdraw" },
      { path: "/orders" },
      { from: "/orders", link: "/orders/" },
    ],
  },
  {
    role: "판매자",
    email: 판매자,
    screens: [
      { path: "/seller/inquiries" },
      { path: "/seller/members" },
      { path: "/seller/orders" },
      { from: "/seller/orders", link: "/seller/orders/" },
      { path: "/seller/products" },
      { from: "/seller/products", link: "/seller/products/", suffix: "/images" },
      { path: "/seller/products/new" },
      { path: "/seller/reviews" },
      { path: "/seller/sales" },
      { path: "/seller/settlements" },
      { from: "/seller/settlements", link: "/seller/settlements/" },
      { path: "/seller/webhooks" },
      { from: "/seller/webhooks", link: "/seller/webhooks/" },
    ],
  },
  {
    role: "관리자",
    email: 관리자,
    screens: [
      { path: "/admin/audit" },
      { path: "/admin/copyright-reports" },
      { path: "/admin/coupons" },
      { path: "/admin/orders" },
      { from: "/admin/orders", link: "/admin/orders/" },
      { path: "/admin/products" },
      { path: "/admin/refunds" },
      { path: "/admin/review-reports" },
      { path: "/admin/roles" },
    ],
  },
];

/** 모바일 폭. `D-2` 와 같은 값이다 */
const VIEWPORT = { width: 390, height: 844 };

function nameOf(screen: Screen): string {
  return "path" in screen ? screen.path : `${screen.from} → ${screen.link}…${screen.suffix ?? ""}`;
}

/** 목록에서 첫 링크의 주소. 없으면 null — 시드에 그 행이 없다 */
async function firstLink(page: Page, screen: Extract<Screen, { from: string }>): Promise<string | null> {
  await page.goto(screen.from);
  return page.evaluate(({ link, suffix }) => {
    const hrefs = [...document.querySelectorAll("a[href]")].map((a) => a.getAttribute("href") ?? "");
    return (
      hrefs.find((href) => href.startsWith(link) && href.length > link.length && !href.includes("?") && (!suffix || href.endsWith(suffix))) ??
      null
    );
  }, screen);
}

async function expectScreenRules(page: Page) {
  const url = page.url();
  // `load` 때는 뿌리 `loading.tsx` 뼈대가 그려져 있고 진짜 내용은 `[hidden]` 안에서 교체를 기다린다(2026-11-08 실측 —
  // `/signup` 의 `h1` 이 `[hidden]` 안). 그대로 재면 axe 가 「h1 이 없다」고 한다. 보이는 `h1` 이 설 때까지 기다린다 —
  // 끝내 안 서면 아래 셈이 0 으로 빨갛다.
  await page
    .waitForFunction(() => [...document.querySelectorAll("h1")].some((h) => !h.closest("[hidden]")), null, { timeout: 10_000 })
    .catch(() => undefined);
  const measured = await page.evaluate(() => {
    const levels = [...document.querySelectorAll("h1, h2, h3, h4, h5, h6")]
      .filter((h) => !h.closest("[hidden]"))
      .map((h) => Number(h.tagName[1]));
    let skipped = 0;
    for (let i = 1; i < levels.length; i++) {
      if (levels[i] > levels[i - 1] + 1) skipped++;
    }
    const positiveTabindex = [...document.querySelectorAll("[tabindex]")].filter(
      (el) => Number(el.getAttribute("tabindex")) > 0,
    ).length;
    const root = document.documentElement;
    return { h1: levels.filter((level) => level === 1).length, skipped, positiveTabindex, overflow: root.scrollWidth - root.clientWidth };
  });
  expect.soft(measured.h1, `h1 은 하나 — ${url}`).toBe(1);
  expect.soft(measured.skipped, `제목 단계 건너뜀 — ${url}`).toBe(0);
  expect.soft(measured.positiveTabindex, `양수 tabindex — ${url}`).toBe(0);
  expect.soft(measured.overflow, `390px 가로 스크롤(px) — ${url}`).toBeLessThanOrEqual(0);
  await expectNoAxeViolations(page);
}

for (const group of GROUPS) {
  // `serial` 이 아니다 — 한 화면이 빨가면 뒤를 건너뛰어서 soft 로 끝까지 보는 뜻이 사라진다. 워커가 하나라
  // `beforeAll` 은 역할마다 한 번 돌고, 빨간 시험 뒤 워커가 새로 뜰 때만 다시 로그인한다.
  test.describe(`화면 전수 — ${group.role}`, () => {
    let context: BrowserContext;
    let page: Page;

    test.beforeAll(async ({ browser }) => {
      // axe 는 `browser.newPage()` 의 페이지를 거부한다 — 문맥을 먼저 연다
      context = await browser.newContext({ viewport: VIEWPORT });
      page = await context.newPage();
      if (group.email) {
        await 로그인(page, group.email);
      }
    });

    test.afterAll(async () => {
      await context.close();
    });

    for (const screen of group.screens) {
      test(nameOf(screen), async () => {
        let target: string;
        if ("path" in screen) {
          target = screen.path;
        } else {
          const href = await firstLink(page, screen);
          if (href === null) {
            test.info().annotations.push({ type: "안 밟음", description: `${screen.from} 목록이 비었다 — 시드 없음` });
            return;
          }
          target = href;
        }
        const response = await page.goto(target);
        expect.soft(response?.status() ?? 0, `응답 — ${target}`).toBeLessThan(400);
        await expectScreenRules(page);
      });
    }
  });
}
