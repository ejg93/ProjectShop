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
  // **상태와 권한을 여기서 안 잰다**(`Q81`). 그 판단은 서버가 하고
  // `SettlementPayoutActionsTest` 가 잰다 — 화면은 이름을 버튼으로 바꾸기만 한다.

  it("서버가 준 이름만 버튼이 된다", () => {
    expect(payoutActionsFor(["PAYOUT"]).map((action) => action.label)).toEqual(["지급 승인"]);
  });

  it("빈 목록이면 아무것도 안 그린다", () => {
    expect(payoutActionsFor([])).toEqual([]);
  });

  it("모르는 이름은 버리고 아는 것만 남긴다", () => {
    expect(payoutActionsFor(["PAYOUT_REQUEST", "PAYOUT_CANCEL"]).map((a) => a.label))
      .toEqual(["지급 올리기"]);
  });

  it("승인과 반려는 같이 온다", () => {
    expect(payoutActionsFor(["PAYOUT", "PAYOUT_REJECTION"]).map((a) => a.path))
      .toEqual(["payout", "payout-rejection"]);
  });

  it("되돌릴 수 없는 조작은 확인을 받고, 거절하면 안 보낸다", () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    vi.spyOn(window, "confirm").mockReturnValue(false);

    render(
      <PayoutActions settlementNumber="ST-1" actions={payoutActionsFor(["PAYOUT", "PAYOUT_REJECTION"])} />,
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
    render(<PayoutActions settlementNumber="ST-1" actions={payoutActionsFor(["PAYOUT_REQUEST"])} />);

    // 동적으로 바뀌는 것은 역할이 있어야 스크린 리더가 읽는다(`D20`).
    expect(screen.getByRole("status")).toBeInTheDocument();
    expect(screen.getByRole("alert")).toBeInTheDocument();
  });
});
