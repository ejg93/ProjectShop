import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { api, apiUpload } from "@/lib/api";

import { MyReviewPhotos } from "./my-review-photos";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: () => {} }) }));

vi.mock("@/lib/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api")>()),
  api: vi.fn(),
  apiUpload: vi.fn(),
}));

beforeEach(() => {
  vi.mocked(api).mockResolvedValue(undefined);
  vi.mocked(apiUpload).mockResolvedValue({ reviewImageId: 3 });
});

afterEach(() => {
  vi.restoreAllMocks();
});

/**
 * 내 후기의 사진 칸(`Q174`).
 *
 * <p><b>개인정보 경고가 올리는 칸 앞에 있는 것이 판단이다</b> — 후기 사진을 공개 게시물로 다루기로 한
 * 결정(`Q159`)의 짝이라, 누가 문구를 줄이다 빼면 그 결정의 전제가 사라진다.
 */
describe("내 후기 사진", () => {
  it("올리는 칸이 개인정보 경고를 설명으로 든다", () => {
    render(<MyReviewPhotos reviewId={4} photos={[]} />);

    expect(screen.getByLabelText("사진 붙이기")).toHaveAccessibleDescription(/개인정보가 담긴 사진은 올리지 마세요/);
  });

  it("고른 파일을 그 후기로 올린다", async () => {
    render(<MyReviewPhotos reviewId={4} photos={[]} />);
    const file = new File(["x"], "photo.jpg", { type: "image/jpeg" });

    fireEvent.change(screen.getByLabelText("사진 붙이기"), { target: { files: [file] } });

    await waitFor(() => expect(apiUpload).toHaveBeenCalledWith("/api/reviews/4/images", file));
  });

  it("떼기는 그 사진 번호로 부른다", async () => {
    render(
      <MyReviewPhotos
        reviewId={4}
        photos={[{ reviewImageId: 11, thumbnailUrl: "https://example.test/t.jpg" }]}
      />,
    );

    expect(screen.getByAltText("후기 사진 1")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "사진 1 떼기" }));

    await waitFor(() =>
      expect(api).toHaveBeenCalledWith("/api/review-images/11", { method: "DELETE" }),
    );
  });
});
