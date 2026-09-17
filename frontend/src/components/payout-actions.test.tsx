import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";


import { PayoutActions, payoutActionsFor } from "./payout-actions";

// 조작 뒤에 부르는 것들이다. 여기서 고정하려는 것은 「무엇을 그리나」라 실제로 부르지 않게 막아 둔다.
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: () => {} }) }));

afterEach(() => {
  vi.restoreAllMocks();
});

/**
 * 지급 버튼이 무엇을 그리나(`20-1`·`D15`).
 *
 * <p><b>재는 것이 바뀌었다</b>(`Q81`). 전에는 상태와 권한을 화면이 맞춰 봐서 그 표를 여기서 쟀는데,
 * 지금은 <b>서버가 셋(권한·상태·누가 올렸나)을 보고 이름 목록으로 준다</b> —
 * 그 판단은 {@code SettlementPayoutTest.AllowedActions} 가 잰다.
 *
 * <p><b>여기 남은 것은 이름을 버튼으로 바꾸는 자리</b>와 확인·알림 흐름이다.
 * 모르는 이름이 오면 버리는 것도 여기서 잰다 — 서버가 새 동작을 내려도 화면이 안 깨진다.
 */
describe("지급 버튼", () => {
  // **상태와 권한을 여기서 안 잰다**(`Q81`). 그 판단은 서버가 하고
  // `SettlementPayoutTest.AllowedActions` 가 잰다 — 화면은 이름을 버튼으로 바꾸기만 한다.

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
