import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { Permission } from "@/lib/permissions";

import { PayoutActions, payoutActionsFor } from "./payout-actions";

// 조작 뒤에 부르는 것들이다. 여기서 고정하려는 것은 「무엇을 그리나」라 실제로 부르지 않게 막아 둔다.
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: () => {} }) }));

/** 셀러가 받는 것. 정산서를 볼 수는 있고 지급은 못 건드린다(`V56`·`V57`) */
const SELLER: Permission[] = [{ resource: "settlement", action: "read", scopes: ["SELLER"] }];

/** 관리자가 받는 것. 요청과 승인이 다른 동작이다(`V57`) */
const ADMIN: Permission[] = [
  { resource: "settlement", action: "read", scopes: ["ALL"] },
  { resource: "settlement", action: "request_payout", scopes: ["ALL"] },
  { resource: "settlement", action: "payout", scopes: ["ALL"] },
];

const REQUESTED = { payoutStatus: "REQUESTED", payoutAmount: 10_000 };
const PENDING = { payoutStatus: "PENDING", payoutAmount: 10_000 };

afterEach(() => {
  vi.restoreAllMocks();
});

/**
 * 지급 버튼이 무엇을 그리나(`20-1`·`D15`).
 *
 * <p><b>이 파일이 이 청크의 강제 지점이다.</b> 「권한 없는 버튼을 안 그린다」는 빌드·린트·타입
 * 검사를 전부 통과하면서 틀릴 수 있다 — 화면이 그리는 것은 타입이 아니라 <b>고른 결과</b>다.
 *
 * <p><b>상태와 권한을 둘 다 시험한다.</b> 한쪽만 보면 다른 쪽이 조용히 열린다 —
 * 권한만 보면 이미 지급한 정산서에 승인 버튼이 뜨고, 상태만 보면 셀러에게 지급 버튼이 뜬다.
 */
describe("지급 버튼", () => {
  it("볼 권한만 있는 사람에게는 아무것도 안 그린다", () => {
    const actions = payoutActionsFor(SELLER, REQUESTED);

    // 셀러에게 지급이 열리면 자기 지급을 스스로 올리고, 자기승인 제약은 둘이 짜면 통과한다(`V57`).
    expect(actions).toHaveLength(0);

    const { container } = render(<PayoutActions settlementNumber="ST-1" actions={actions} />);
    // 그려 놓고 감추면 DOM 에 남아서 화면낭독기가 읽고, 개발자 도구로 되살리면 눌러진다.
    expect(container).toBeEmptyDOMElement();
  });

  it("승인 권한이 있으면 올라온 지급에 승인과 반려가 난다", () => {
    render(
      <PayoutActions settlementNumber="ST-1" actions={payoutActionsFor(ADMIN, REQUESTED)} />,
    );

    expect(screen.getByRole("button", { name: "지급 승인" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "지급 반려" })).toBeInTheDocument();
    // 올라온 것을 또 올릴 수는 없다.
    expect(screen.queryByRole("button", { name: "지급 올리기" })).not.toBeInTheDocument();
  });

  it("다른 동작의 권한으로는 안 열린다", () => {
    const onlyRequest: Permission[] = [
      { resource: "settlement", action: "request_payout", scopes: ["ALL"] },
    ];

    // 요청과 승인을 가른 이유가 이것이다 — 하나로 보면 올리는 순간 승인이 같이 열린다(`V57`).
    expect(payoutActionsFor(onlyRequest, REQUESTED)).toHaveLength(0);
  });

  it("다른 자원의 같은 이름에는 안 열린다", () => {
    const elsewhere: Permission[] = [{ resource: "refund", action: "payout", scopes: ["ALL"] }];

    expect(payoutActionsFor(elsewhere, REQUESTED)).toHaveLength(0);
  });

  it("반려된 것은 다시 올릴 수 있다", () => {
    const actions = payoutActionsFor(ADMIN, { payoutStatus: "REJECTED", payoutAmount: 10_000 });

    expect(actions.map((action) => action.label)).toEqual(["지급 올리기"]);
  });

  it("지급이 끝난 것에는 아무것도 안 난다", () => {
    expect(payoutActionsFor(ADMIN, { payoutStatus: "PAID", payoutAmount: 10_000 })).toHaveLength(0);
  });

  it("모르는 상태에는 아무것도 안 난다", () => {
    // 서버가 상태를 하나 늘려도 무엇이 일어날지 모르는 버튼이 생기면 안 된다(`D5`).
    expect(payoutActionsFor(ADMIN, { payoutStatus: "SETTLED", payoutAmount: 10_000 })).toHaveLength(
      0,
    );
  });

  it("줄 돈이 없으면 올리기가 안 난다", () => {
    // 지급액이 0 이하면 이월로 넘어가지 지급 대상이 아니다(`settlement_payout_amount_check`).
    expect(payoutActionsFor(ADMIN, { payoutStatus: "PENDING", payoutAmount: 0 })).toHaveLength(0);
  });

  it("되돌릴 수 없는 조작은 확인을 받고, 거절하면 안 보낸다", () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    vi.spyOn(window, "confirm").mockReturnValue(false);

    render(
      <PayoutActions settlementNumber="ST-1" actions={payoutActionsFor(ADMIN, REQUESTED)} />,
    );
    fireEvent.click(screen.getByRole("button", { name: "지급 승인" }));

    // 승인은 돈이 나간 것으로 기록되므로 무엇이 일어나는지를 묻는다(`D20`).
    expect(window.confirm).toHaveBeenCalledWith(
      "승인하시면 지급한 것으로 기록되며 되돌릴 수 없습니다. 계속하시겠습니까?",
    );
    // **거절했는데 나가면 확인 자체가 장식이다.**
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it("결과를 알리는 자리가 소리로도 전해진다", () => {
    render(<PayoutActions settlementNumber="ST-1" actions={payoutActionsFor(ADMIN, PENDING)} />);

    // 동적으로 바뀌는 것은 역할이 있어야 스크린 리더가 읽는다(`D20`).
    expect(screen.getByRole("status")).toBeInTheDocument();
    expect(screen.getByRole("alert")).toBeInTheDocument();
  });
});
