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

import OrderDetailPage from "./page";

/**
 * 보내기로 한 날이 지난 것이 사는 사람 화면에 뜨나(`43a-4a`, `D2` R38·R39).
 *
 * <p><b>컴포넌트가 아니라 쪽을 그린다.</b> {@code ShipDelayNotice} 만 시험하면
 * <b>쪽이 그 값을 안 넘기는 결함</b>이 안 걸린다 — 그리고 그것이 이 청크가 고친 결함이다.
 * 서버는 {@code ship_overdue} 를 이미 주고 있었고 화면만 그 칸을 안 읽었다.
 *
 * <p><b>브라우저 렌더러가 아니라 {@code prerender} 로 그린다.</b> 이 쪽에는 다시 서버를 부르는
 * 비동기 조각이 있고({@code ContractDocuments}), <b>React 의 클라이언트 렌더러는 비동기
 * 컴포넌트를 통째로 거부한다</b> — 그러면 쪽 전체가 빈 채로 나와서 무엇을 단언하든 빨갛다.
 * 서버 렌더러로 문자열을 뽑아 문서에 넣으면 같은 질문을 {@code getByRole} 로 물을 수 있다.
 */
function orderWith(bundle: Record<string, unknown>) {
  return {
    orderNumber: "ORD-1",
    status: "PAID",
    createdAt: "2026-09-01T00:00:00Z",
    totalAmount: 10_000,
    shippingFeeTotal: 0,
    payableAmount: 10_000,
    sellerOrders: [
      {
        sellerOrderNumber: "SO-1",
        sellerName: "가게",
        status: "preparing",
        shippingFee: 0,
        deliveredAt: null,
        withdrawalExpireAt: null,
        autoConfirmAt: null,
        shipDueAt: null,
        shippedAt: null,
        shipOverdue: false,
        items: [{ productName: "물건", optionLabel: null, quantity: 1, unitPriceInclVat: 10_000, lineAmount: 10_000 }],
        allowedActions: [],
        ...bundle,
      },
    ],
    history: [],
    contractDocuments: [],
  };
}

async function renderOrder(bundle: Record<string, unknown>) {
  apiSession.mockResolvedValue(orderWith(bundle));

  const { prelude } = await prerender(
    await OrderDetailPage({ params: Promise.resolve({ orderNumber: "ORD-1" }) }),
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
});

/**
 * 자동 구매확정을 <b>미리 알리는 자리</b>다(`R31`, 약관규제법 제12조1호).
 *
 * **미리 알리지 않으면 그 의제가 무효다** — 기한만 지켜도 안 되고 화면이 말해야 한다.
 * 기한 자체는 `OrderStatusBatchTest.AutoConfirm` 이 잰다.
 */
describe("발송 지연 안내", () => {
  it("기한이 지나고 안 보냈으면 취소할 수 있다고 말한다", async () => {
    await renderOrder({
      shipDueAt: "2026-09-10T09:00:00Z",
      shipOverdue: true,
      shippedAt: null,
    });

    // 고시가 미인도를 계약해제로 정한다(별표2 「인터넷쇼핑몰업」 2)·6), `D2` R38).
    expect(screen.getByRole("note")).toHaveTextContent("지났습니다");
    expect(screen.getByRole("note")).toHaveTextContent("취소하실 수 있으며");
  });

  it("늦게라도 보냈으면 취소를 말하지 않는다", async () => {
    await renderOrder({
      shipDueAt: "2026-09-10T09:00:00Z",
      shipOverdue: true,
      shippedAt: "2026-09-12T09:00:00Z",
      status: "shipping",
    });

    // 떠난 물건을 되돌리는 것은 취소가 아니라 반품이다(`glossary.md`) — 그 말을 하면 거짓이다.
    expect(screen.getByRole("note")).toHaveTextContent("발송되었습니다");
    expect(screen.getByRole("note")).not.toHaveTextContent("취소하실 수 있으며");
  });

  it("기한 안이면 아무것도 안 그린다", async () => {
    await renderOrder({
      shipDueAt: "2026-09-30T09:00:00Z",
      shipOverdue: false,
      shippedAt: null,
    });

    // 안 늦었는데 안내가 뜨면 사는 사람이 멀쩡한 주문을 취소한다.
    expect(screen.queryByRole("note")).not.toBeInTheDocument();
  });

  it("기한이 없으면 아무것도 안 그린다", async () => {
    await renderOrder({ shipDueAt: null, shipOverdue: true, shippedAt: null });

    // 취소된 묶음에는 기한이 없다(`V30`). 판정만 참인 행에 날짜를 그리면 빈 칸이 나간다.
    expect(screen.queryByRole("note")).not.toBeInTheDocument();
  });
});
