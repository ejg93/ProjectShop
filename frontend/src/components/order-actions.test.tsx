import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { OrderActions, type OrderAction } from "./order-actions";

// 조작 뒤에 부르는 것들이다. 여기서 고정하려는 것은 「무엇을 그리나」라 실제로 부르지 않게 막아 둔다.
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: () => {} }) }));

const actions: Record<string, OrderAction> = {
  cancel: { label: "주문 취소", confirm: "취소하면 되돌릴 수 없다" },
  confirm_receipt: { label: "구매 확정", confirm: "확정하면 되돌릴 수 없다" },
};

afterEach(() => {
  vi.restoreAllMocks();
});

/**
 * 주문 조작 버튼이 무엇을 그리나(`D15`·`Q9`).
 *
 * <p><b>이 컴포넌트가 주석으로 못 박은 판단 넷이 지금까지 아무 데도 안 걸려 있었다.</b>
 * 넷 다 빌드·린트·타입 검사를 전부 통과하면서 틀릴 수 있다 — 화면이 그리는 것은
 * 타입이 아니라 <b>고른 결과</b>다.
 */
describe("주문 조작 버튼", () => {
  it("서버가 허락한 것만 그린다", () => {
    render(
      <OrderActions sellerOrderNumber="SO-1" allowedActions={["cancel"]} actions={actions} />,
    );

    expect(screen.getByRole("button", { name: "주문 취소" })).toBeInTheDocument();
    // 화면이 상태로 판단하면 규칙이 두 벌이 되고, 어긋난 쪽 버튼은 눌러야 403 이 난다(`11c-3b`).
    expect(screen.queryByRole("button", { name: "구매 확정" })).not.toBeInTheDocument();
  });

  it("모르는 동작은 건너뛴다", () => {
    render(
      <OrderActions
        sellerOrderNumber="SO-1"
        allowedActions={["cancel", "teleport"]}
        actions={actions}
      />,
    );

    // 서버가 동작을 하나 늘려도 빈 버튼이 생기면 안 된다(`D5` 「모르는 열거값은 무시한다」).
    expect(screen.getAllByRole("button")).toHaveLength(1);
  });

  it("할 수 있는 것이 없으면 아무것도 안 그린다", () => {
    const { container } = render(
      <OrderActions sellerOrderNumber="SO-1" allowedActions={[]} actions={actions} />,
    );

    // 빈 상자만 남으면 테두리가 그려져서 「여기 뭔가 있는데 안 보인다」로 읽힌다.
    expect(container).toBeEmptyDOMElement();
  });

  it("되돌릴 수 없는 조작은 확인을 받고, 거절하면 안 보낸다", () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    vi.spyOn(window, "confirm").mockReturnValue(false);

    render(
      <OrderActions sellerOrderNumber="SO-1" allowedActions={["cancel"]} actions={actions} />,
    );
    fireEvent.click(screen.getByRole("button", { name: "주문 취소" }));

    // 무엇이 사라지는지를 묻는다(`D20` 「되돌릴 수 없는 조작」).
    expect(window.confirm).toHaveBeenCalledWith("취소하면 되돌릴 수 없다");
    // **거절했는데 나가면 확인 자체가 장식이다.**
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it("결과를 알리는 자리가 소리로도 전해진다", () => {
    render(
      <OrderActions sellerOrderNumber="SO-1" allowedActions={["cancel"]} actions={actions} />,
    );

    // 동적으로 바뀌는 것은 역할이 있어야 스크린 리더가 읽는다(`D20`).
    expect(screen.getByRole("status")).toBeInTheDocument();
    expect(screen.getByRole("alert")).toBeInTheDocument();
  });
});
