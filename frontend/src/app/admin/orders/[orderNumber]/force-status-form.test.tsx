import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { expectNoAxeViolations } from "@/test/axe";

import { api } from "@/lib/api";

import { ForceStatusForm } from "./force-status-form";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: () => {} }) }));

vi.mock("@/lib/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api")>()),
  api: vi.fn(),
}));

beforeEach(() => {
  vi.mocked(api).mockResolvedValue(undefined);
});

afterEach(() => {
  vi.restoreAllMocks();
});

/**
 * 관리자 강제 전이(`16c`).
 *
 * <p><b>되돌리는 길이 없어서 한 번 더 묻는다</b> — 끝난 상태에서 나가는 강제 전이를 안 열었다(2026-09-23 사용자 선택).
 * 그리고 <b>갈 곳을 서버가 고른다</b> — 목록이 비면 폼이 아예 안 서야 권한 없는 사람이 버튼을 안 본다.
 */
describe("강제 전이", () => {
  it("갈 곳이 없으면 폼이 없다", () => {
    const { container } = render(<ForceStatusForm sellerOrderNumber="S-1" forcibleStatuses={[]} />);

    expect(container).toBeEmptyDOMElement();
  });

  it("한 번 더 묻고, 그다음에야 갈 곳과 사유를 보낸다", async () => {
    render(<ForceStatusForm sellerOrderNumber="S-1" forcibleStatuses={["DELIVERED", "CANCELLED"]} />);

    fireEvent.change(screen.getByLabelText("옮길 상태"), { target: { value: "CANCELLED" } });
    fireEvent.change(screen.getByLabelText(/사유/), { target: { value: "택배 분실 확인" } });
    fireEvent.click(screen.getByRole("button", { name: "강제로 옮기기" }));
    expect(api).not.toHaveBeenCalled();
    expect(screen.getByText(/되돌릴 수 없습니다/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "옮기기" }));
    await waitFor(() =>
      expect(api).toHaveBeenCalledWith("/api/shipments/S-1/force-status", {
        method: "POST",
        body: { to: "CANCELLED", reason: "택배 분실 확인" },
      }),
    );
  });

  it("접근성 위반이 없다", async () => {
    const { container } = render(
      <ForceStatusForm sellerOrderNumber="S-1" forcibleStatuses={["SHIPPING", "DELIVERED", "CANCELLED"]} />,
    );

    await expectNoAxeViolations(container);
  });
});
