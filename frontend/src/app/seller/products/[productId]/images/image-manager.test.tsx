import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError, api, apiUpload } from "@/lib/api";
import { expectNoAxeViolations } from "@/test/axe";

import { ImageManager, type ProductImage } from "./image-manager";

vi.mock("@/lib/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api")>()),
  api: vi.fn(),
  apiUpload: vi.fn(),
}));

const refresh = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh }) }));

const IMAGE: ProductImage = {
  productImageId: 11,
  thumbnailUrl: "https://example.test/thumb.jpg",
  originalName: "앞면.jpg",
  sortNo: 0,
};

beforeEach(() => {
  vi.mocked(api).mockClear();
  vi.mocked(api).mockResolvedValue(undefined);
  vi.mocked(apiUpload).mockClear();
  vi.mocked(apiUpload).mockResolvedValue(undefined);
  refresh.mockClear();
});

afterEach(() => vi.restoreAllMocks());

const renderManager = (images: ProductImage[] = [IMAGE]) =>
  render(<ImageManager productId={7} images={images} />);

const picker = () => screen.getByLabelText("사진 올리기") as HTMLInputElement;

/**
 * 파일을 골라 넣는다.
 *
 * <p><b>`fireEvent.change(input, { target: { files } })` 로는 안 들어간다</b> — jsdom 의
 * `files` 는 읽기 전용이라 그 대입이 조용히 무시되고, `onChange` 가 빈 손으로 돌아서
 * <b>아무 일도 안 났는데 시험이 지나간다.</b> 이 시험이 실제로 그렇게 초록이었다.
 */
const pick = (file: File) => {
  const input = picker();
  Object.defineProperty(input, "files", { value: [file], configurable: true });
  fireEvent.change(input);
};

const jpeg = () => new File(["x"], "앞면.jpg", { type: "image/jpeg" });

/** 오류 이름의 접두어. `api.ts` 의 `ERROR_TYPE_PREFIX` 와 같아야 슬러그가 잘린다 */
const ERROR_TYPE = "tag:projectshop.example,2026:error:";

const rejectWith = (status: number, slug: string) =>
  vi.mocked(apiUpload).mockRejectedValue(new ApiError(status, `${ERROR_TYPE}${slug}`, ""));

/**
 * 사진 관리 화면이 무엇을 막나(`Q140`).
 *
 * <p><b>거부의 이유가 화면에 남는 것이 이 시험의 요지다.</b> 서버가 넷을 막는데
 * (남의 상품·5MB·열 장·형식) 화면이 그것을 「실패했습니다」 하나로 뭉치면,
 * 사용자는 무엇을 고쳐야 다시 되는지를 모른 채 같은 파일을 다시 올린다.
 */
describe("상품 사진 관리", () => {
  it("고르면 바로 올리고 목록을 다시 그린다", async () => {
    renderManager([]);

    pick(jpeg());

    await waitFor(() => expect(apiUpload).toHaveBeenCalledWith("/api/seller/products/7/images", expect.any(File)));
    await waitFor(() => expect(refresh).toHaveBeenCalled());
  });

  it("지우면 그 사진 번호로 부른다", async () => {
    renderManager();

    fireEvent.click(screen.getByRole("button", { name: "지우기" }));

    await waitFor(() =>
      expect(api).toHaveBeenCalledWith("/api/seller/products/images/11", { method: "DELETE" }),
    );
  });

  it.each([
    // **도움말에 없는 문장으로 잰다.** 입력 위 도움말이 「JPG 또는 PNG, 한 장에 5MB까지,
    // 상품마다 10장까지」라서 조건을 그대로 찾으면 **오류가 안 떠도 그 도움말이 걸린다.**
    [413, "image-too-large", "더 작은 파일로 다시 시도해"],
    [415, "image-type-not-allowed", "파일만 올릴 수 있습니다"],
    [422, "image-limit-reached", "먼저 한 장을 지워"],
    [403, "product-forbidden", "내 상품이 아닙니다"],
  ])("거부 %s 는 그 이유를 적는다", async (status, slug, wording) => {
    rejectWith(status, slug);
    renderManager([]);

    pick(jpeg());

    expect(await screen.findByText(new RegExp(wording))).toBeTruthy();
  });

  it("모르는 거부는 기본 문구로 적는다", async () => {
    rejectWith(500, "server-error");
    renderManager([]);

    pick(jpeg());

    expect(await screen.findByText(/사진을 바꾸지 못했습니다/)).toBeTruthy();
  });

  it("사진이 없으면 무엇이 일어나는지 적는다", () => {
    renderManager([]);

    expect(screen.getByText(/아직 올린 사진이 없습니다/)).toBeTruthy();
  });

  it("접근성 위반이 없다", async () => {
    const { container } = renderManager();

    await expectNoAxeViolations(container);
  });
});
