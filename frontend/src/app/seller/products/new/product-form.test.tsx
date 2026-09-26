import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { expectNoAxeViolations } from "@/test/axe";

import { ApiError, api } from "@/lib/api";

import { ProductForm } from "./product-form";

vi.mock("@/lib/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api")>()),
  api: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: () => {}, refresh: () => {} }),
}));

/**
 * 서버가 지목한 칸이 <b>화면의 그 칸에 붙나</b>(`Q136`).
 *
 * <p><b>이름이 안 맞는 것이 이 폼의 함정이다.</b> 서버는 `skus[0].price_incl_vat` 로 말하고
 * 화면은 `priceInclVat` 한 칸을 그린다 — 옵션 없는 상품이라 조합이 하나뿐이고 화면이 펴서
 * 그리기 때문이다. 그 사이를 {@code placeErrors} 의 사다리가 잇는다.
 */
function validationFailed(errors: { field: string; message: string }[]): ApiError {
  return new ApiError(
    400,
    "tag:projectshop.example,2026:error:validation-failed",
    "입력값을 다시 확인해 주세요.",
    "trace",
    errors,
    "입력한 값을 확인해 주세요.",
  );
}

/**
 * 칸을 채우고 낸다.
 *
 * <p><b>버튼을 누르지 않고 폼에 직접 낸다.</b> jsdom 은 버튼 누르기를 제출로 안 잇고,
 * React 가 `A React form was unexpectedly submitted` 로 답한다 — 가입 폼 시험이 쓰는 수와 같다.
 */
function fillAndSubmit(container: HTMLElement, price = "-1") {
  fireEvent.change(screen.getByLabelText(/상품명/), { target: { value: "데모 상품" } });
  fireEvent.change(screen.getByLabelText(/판매가/), { target: { value: price } });
  fireEvent.change(screen.getByLabelText(/재고/), { target: { value: "1" } });
  fireEvent.submit(container.querySelector("form")!);
}

afterEach(() => {
  vi.restoreAllMocks();
  vi.mocked(api).mockReset();
});

describe("상품 등록 폼의 오류 표기", () => {
  it("판매가를 지목하면 그 칸 아래에 사유가 뜬다", async () => {
    vi.mocked(api).mockRejectedValue(
      validationFailed([{ field: "skus[0].price_incl_vat", message: "0 이상이어야 합니다" }]),
    );

    const { container } = render(<ProductForm sellerId={1} />);
    fillAndSubmit(container);

    const price = await screen.findByLabelText(/판매가/);
    await waitFor(() => expect(price).toHaveAttribute("aria-invalid", "true"));

    const message = screen.getByText("0 이상이어야 합니다");
    expect(price).toHaveAttribute("aria-describedby", message.id);
  });

  it("그 칸으로 초점이 간다", async () => {
    // 안 보내면 어디가 빨간지 찾아 내려가야 한다(WCAG 3.3.1).
    vi.mocked(api).mockRejectedValue(
      validationFailed([{ field: "skus[0].price_incl_vat", message: "0 이상이어야 합니다" }]),
    );

    const { container } = render(<ProductForm sellerId={1} />);
    fillAndSubmit(container);

    await waitFor(() => expect(screen.getByLabelText(/판매가/)).toHaveFocus());
  });

  it("칸이 없는 이름은 버리지 않고 같이 그린다", async () => {
    // `13h` — 못 붙였다고 안 보여 주면 사용자는 한 줄짜리 문구만 보고 무엇을 고칠지 모른다.
    vi.mocked(api).mockRejectedValue(
      validationFailed([{ field: "seller_id", message: "판매자를 찾지 못했습니다" }]),
    );

    const { container } = render(<ProductForm sellerId={1} />);
    fillAndSubmit(container);

    expect(await screen.findByText("판매자를 찾지 못했습니다")).toBeInTheDocument();
  });

  it("맞은 칸까지 잘못됐다고 하지 않는다", async () => {
    // 두 칸에 다 걸면 화면낭독기가 맞은 값까지 「잘못된 입력」이라고 읽는다.
    vi.mocked(api).mockRejectedValue(
      validationFailed([{ field: "skus[0].price_incl_vat", message: "0 이상이어야 합니다" }]),
    );

    const { container } = render(<ProductForm sellerId={1} />);
    fillAndSubmit(container);

    await waitFor(() =>
      expect(screen.getByLabelText(/판매가/)).toHaveAttribute("aria-invalid", "true"),
    );
    expect(screen.getByLabelText(/재고/)).toHaveAttribute("aria-invalid", "false");
  });

  it("다시 내면 앞의 표기가 사라진다", async () => {
    vi.mocked(api).mockRejectedValueOnce(
      validationFailed([{ field: "skus[0].price_incl_vat", message: "0 이상이어야 합니다" }]),
    );

    const { container } = render(<ProductForm sellerId={1} />);
    fillAndSubmit(container);
    await screen.findByText("0 이상이어야 합니다");

    vi.mocked(api).mockResolvedValueOnce({ productId: 1 });
    fillAndSubmit(container, "1000");

    await waitFor(() => expect(screen.queryByText("0 이상이어야 합니다")).toBeNull());
  });

  it("오류가 떠 있어도 접근성 위반이 없다", async () => {
    vi.mocked(api).mockRejectedValue(
      validationFailed([{ field: "skus[0].price_incl_vat", message: "0 이상이어야 합니다" }]),
    );

    const { container } = render(<ProductForm sellerId={1} />);
    fillAndSubmit(container);
    await screen.findByText("0 이상이어야 합니다");

    await expectNoAxeViolations(container);
  });
});
