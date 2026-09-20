import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError, api } from "@/lib/api";

import { CheckoutForm } from "./checkout-form";

vi.mock("@/lib/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api")>()),
  api: vi.fn(),
}));

/**
 * 주문서가 틀린 칸을 짚나(`Q129`).
 *
 * <p><b>배포한 사이트를 사람이 써서 나온 자리다.</b> 우편번호를 잘못 적었더니 화면이
 * 「결제하지 못했습니다. 잠시 후 다시 시도해 주세요.」를 띄웠다 — 결제는 시작도 안 했고
 * 고칠 곳은 입력칸인데, 다시 시도해도 같은 값이면 또 틀린다.
 *
 * <p>서버 쪽 절반은 `Q127` 이 고쳤다(검증 실패가 한 이름으로 나가고 {@code errors} 가 실린다).
 * 여기는 그 {@code errors} 를 화면이 쓰는지를 본다.
 */
const validationFailed = (errors: { field: string; message: string }[]) =>
  new ApiError(400, "tag:projectshop.example,2026:error:validation-failed",
               "Validation failure", "trace", errors);

const fill = (name: string, value: string) => {
  fireEvent.change(screen.getByLabelText(name), { target: { value } });
};

function renderForm() {
  return render(<CheckoutForm cartItemIds={[1]} payableAmount={32000} madeToOrderNames={[]} />);
}

function fillShipping() {
  fill("받는 분", "홍길동");
  fill("연락처", "010-1234-5678");
  fill("우편번호", "06236");
  fill("주소", "서울특별시 강남구");
}

beforeEach(() => {
  vi.mocked(api).mockReset();
  vi.stubGlobal("crypto", { randomUUID: () => "11111111-2222-3333-4444-555555555555" });
});

describe("주문서", () => {
  it("틀린 칸 아래에 사유를 띄운다", async () => {
    vi.mocked(api).mockRejectedValue(
      validationFailed([{ field: "shipping.postal_code", message: "숫자 5자리여야 합니다" }]),
    );

    const { container } = renderForm();
    fillShipping();
    fireEvent.submit(container.querySelector("form")!);

    expect(await screen.findByText("숫자 5자리여야 합니다")).toBeInTheDocument();

    // 칸에 묶여 있어야 화면낭독기가 초점이 갈 때 읽는다(WCAG 3.3.1).
    const box = screen.getByLabelText(/우편번호/);
    expect(box).toHaveAttribute("aria-invalid", "true");
    expect(box.getAttribute("aria-describedby")).toContain("postalCode-error");
  });

  it("틀린 첫 칸으로 초점을 보낸다", async () => {
    vi.mocked(api).mockRejectedValue(
      validationFailed([
        { field: "card_number", message: "카드번호를 확인해 주세요" },
        { field: "shipping.receiver_name", message: "받는 분을 적어 주세요" },
      ]),
    );

    const { container } = renderForm();
    fillShipping();
    fireEvent.submit(container.querySelector("form")!);

    // 서버가 준 순서가 아니라 화면 순서다 — 위에 있는 칸부터 고치게 한다.
    await waitFor(() => expect(screen.getByLabelText(/받는 분/)).toHaveFocus());
  });

  it("칸이 없는 사유는 폼 오류 자리에 같이 띄운다", async () => {
    vi.mocked(api).mockRejectedValue(
      validationFailed([{ field: "cart_item_ids", message: "주문할 상품이 없습니다" }]),
    );

    const { container } = renderForm();
    fillShipping();
    fireEvent.submit(container.querySelector("form")!);

    // 버리면 「다시 확인해 주세요」만 남고 무엇을 확인할지 알 수 없다(`13h`).
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("입력하신 내용을 다시 확인해 주세요.");
    expect(alert).toHaveTextContent("주문할 상품이 없습니다");
  });

  it("다시 보내면 앞의 칸 오류를 지운다", async () => {
    vi.mocked(api).mockRejectedValueOnce(
      validationFailed([{ field: "shipping.postal_code", message: "숫자 5자리여야 합니다" }]),
    );

    const { container } = renderForm();
    fillShipping();
    fireEvent.submit(container.querySelector("form")!);
    expect(await screen.findByText("숫자 5자리여야 합니다")).toBeInTheDocument();

    // 안 지우면 고친 칸이 계속 빨갛고, 사용자는 무엇이 남았는지 못 센다.
    vi.mocked(api)
      .mockResolvedValueOnce({ orderNumber: "20260920-AAAAAA" })
      .mockResolvedValueOnce({
        orderNumber: "20260920-AAAAAA", status: "APPROVED", amount: 32000,
        approvalNumber: "M1", cardIssuer: "비자", cardLast4: "4242", declineReason: null,
      });

    fill("우편번호", "06236");
    fireEvent.submit(container.querySelector("form")!);

    await waitFor(() =>
      expect(screen.queryByText("숫자 5자리여야 합니다")).not.toBeInTheDocument());
  });
});
