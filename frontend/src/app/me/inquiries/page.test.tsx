import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

const { apiSession } = vi.hoisted(() => ({ apiSession: vi.fn() }));

vi.mock("@/lib/api-session", () => ({ apiSession }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: () => {} }) }));

import MyInquiriesPage from "./page";

afterEach(() => {
  vi.clearAllMocks();
});

function inquiry(allowedActions: string[]) {
  return {
    inquiryNumber: "Q-20260926-ABCDEF",
    kind: "PRODUCT",
    productId: 1,
    productName: "데모 티셔츠",
    question: "언제 오나요",
    status: "RECEIVED",
    isPublic: false,
    createdAt: "2026-09-26T00:00:00Z",
    overdue: false,
    allowedActions,
  };
}

/**
 * 거두기 버튼은 서버가 실은 것만 그린다(`Q235`). 전에는 `status === "RECEIVED"` 만 보고 그렸다 — 접수 상태인데 목록이 비면
 * 버튼이 없어야 상태가 아니라 서버를 보는 것이다.
 */
describe("내 문의", () => {
  it("WITHDRAWAL 이 오면 거두기 버튼을 그린다", async () => {
    apiSession.mockResolvedValue({ items: [inquiry(["WITHDRAWAL"])], page: 0, size: 50, total: 1 });
    render(await MyInquiriesPage());

    expect(screen.getByRole("button", { name: /거두기/ })).toBeInTheDocument();
  });

  it("접수 상태라도 목록에 없으면 거두기 버튼이 없다", async () => {
    apiSession.mockResolvedValue({ items: [inquiry([])], page: 0, size: 50, total: 1 });
    render(await MyInquiriesPage());

    expect(screen.queryByRole("button", { name: /거두기/ })).not.toBeInTheDocument();
  });
});
