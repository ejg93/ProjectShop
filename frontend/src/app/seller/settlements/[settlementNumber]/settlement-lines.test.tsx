import { screen } from "@testing-library/dom";
import { prerender } from "react-dom/static";
import { afterEach, describe, expect, it, vi } from "vitest";

// `vi.mock` 은 파일 맨 위로 끌어올려진다. 그래서 공장 안에서 바깥 변수를 못 읽고,
// `vi.hoisted` 로 그 변수를 같이 끌어올린다.
const { apiSession } = vi.hoisted(() => ({ apiSession: vi.fn() }));

vi.mock("@/lib/api-session", () => ({ apiSession }));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ refresh: () => {} }),
  notFound: () => {
    throw new Error("notFound");
  },
}));

import SettlementDetailPage from "./page";

const detail = {
  summary: {
    settlementNumber: "STL-1",
    sellerCode: "demo-fashion",
    periodStart: "2026-09-01",
    periodEnd: "2026-09-30",
    payoutDate: "2026-10-10",
    payoutAmount: 9_000,
    carriedOver: 0,
    payoutStatus: "PENDING",
    createdAt: "2026-10-01T00:00:00Z",
  },
  totals: [],
  allowedActions: [],
};

const lines = {
  items: [
    {
      kind: "SALE",
      supplier: "SELLER",
      amount: 10_000,
      commissionBp: null,
      commissionBaseAmount: null,
      sellerOrderNumber: "SO-1",
      productName: "티셔츠",
      supplyAmount: null,
      vatAmount: null,
    },
    {
      kind: "COMMISSION",
      supplier: "PLATFORM",
      amount: -1_000,
      commissionBp: 1_000,
      commissionBaseAmount: 10_000,
      sellerOrderNumber: "SO-1",
      productName: "티셔츠",
      supplyAmount: -909,
      vatAmount: -91,
    },
  ],
  page: 0,
  size: 50,
  total: 2,
};

/**
 * <b>컴포넌트가 아니라 쪽을 그린다</b>(`ship-delay.test.tsx` 와 같은 까닭) — 쪽이 응답의 칸을 줄 조각에 안 넘기는 결함은
 * 조각만 시험하면 안 걸린다. 서버 렌더러({@code prerender})로 그린 문자열을 문서에 넣는다.
 */
async function renderSettlement() {
  apiSession.mockImplementation((path: string) => Promise.resolve(path.includes("/lines") ? lines : detail));

  const { prelude } = await prerender(
    await SettlementDetailPage({
      params: Promise.resolve({ settlementNumber: "STL-1" }),
      searchParams: Promise.resolve({}),
    }),
  );

  const reader = prelude.getReader();
  const decoder = new TextDecoder();
  let html = "";
  for (;;) {
    const { done, value } = await reader.read();
    if (done) {
      break;
    }
    html += decoder.decode(value, { stream: true });
  }

  document.body.innerHTML = html;
}

afterEach(() => {
  document.body.innerHTML = "";
  apiSession.mockReset();
});

/**
 * 수수료 줄의 공급가액·부가세(`Q275`, `D2` R17 — 부가가치세법 제32조제1항제3호).
 * 나누는 셈은 서버의 {@code VatSplit} 이 한다 — 이 화면은 받은 두 금액을 그 줄에 그리기만 한다.
 */
describe("정산서 줄의 부가세", () => {
  it("수수료 줄에 공급가액과 부가세를 그린다", async () => {
    await renderSettlement();

    const commissionRow = screen.getAllByRole("row").find((row) => row.textContent?.includes("10%"));
    expect(commissionRow).toBeDefined();
    expect(commissionRow).toHaveTextContent("공급가액 -909원 · 부가세 -91원");
  });

  it("셀러가 공급한 줄에는 그리지 않는다", async () => {
    await renderSettlement();

    // 세금계산서는 셀러가 낸다 — 우리가 나눌 근거가 없는 금액이다.
    expect(screen.getAllByText(/공급가액/)).toHaveLength(1);
  });
});
