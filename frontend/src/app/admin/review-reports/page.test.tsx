import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

const { apiSession } = vi.hoisted(() => ({ apiSession: vi.fn() }));

vi.mock("@/lib/api-session", () => ({ apiSession }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: () => {} }) }));

import ReviewReportsPage from "./page";

afterEach(() => {
  vi.clearAllMocks();
});

function report(status: string, allowedActions: string[], reviewActions: string[]) {
  return {
    reviewReportId: 1,
    reviewId: 2,
    productId: 3,
    productName: "데모 티셔츠",
    rating: 1,
    body: "별로다",
    reason: "ABUSE",
    status,
    createdAt: "2026-09-26T00:00:00Z",
    resolvedAt: status === "PENDING" ? null : "2026-09-26T01:00:00Z",
    reviewBlockedReason: status === "ACCEPTED" ? "ABUSE" : null,
    reviewDeleted: false,
    allowedActions,
    reviewActions,
  };
}

async function renderWith(status: string, item: ReturnType<typeof report>) {
  apiSession.mockResolvedValue({ items: [item], page: 0, size: 50, total: 1 });
  render(await ReviewReportsPage({ searchParams: Promise.resolve({ status }) }));
}

/**
 * 신고 처리와 후기 되살리기는 서버가 실은 것만 그린다(`Q235`). 전에는 `PENDING`·`ACCEPTED` 상태를 보고 그렸다 —
 * 상태가 맞아도 목록이 비면 버튼이 없어야 상태가 아니라 서버를 보는 것이다.
 */
describe("후기 신고", () => {
  it("ACCEPT 가 오면 처리 버튼을 그린다", async () => {
    await renderWith("PENDING", report("PENDING", ["ACCEPT", "REJECT"], []));

    expect(screen.getByRole("button", { name: "받아들이고 게시 중단" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "물리기" })).toBeInTheDocument();
  });

  it("목록에 있는 것만 그린다 — ACCEPT 하나면 물리기가 없다", async () => {
    await renderWith("PENDING", report("PENDING", ["ACCEPT"], []));

    expect(screen.getByRole("button", { name: "받아들이고 게시 중단" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "물리기" })).not.toBeInTheDocument();
  });

  it("접수 상태라도 목록에 없으면 처리 버튼이 없다", async () => {
    await renderWith("PENDING", report("PENDING", [], []));

    expect(screen.queryByRole("button", { name: "받아들이고 게시 중단" })).not.toBeInTheDocument();
  });

  it("RESTORE 는 후기 칸이 싣고, 없으면 되살리기가 없다", async () => {
    await renderWith("ACCEPTED", report("ACCEPTED", [], ["RESTORE"]));
    expect(screen.getByRole("button", { name: "후기 되살리기" })).toBeInTheDocument();
  });

  it("받아들인 신고라도 후기 칸이 비면 되살리기가 없다", async () => {
    await renderWith("ACCEPTED", report("ACCEPTED", [], []));
    expect(screen.queryByRole("button", { name: "후기 되살리기" })).not.toBeInTheDocument();
  });
});
