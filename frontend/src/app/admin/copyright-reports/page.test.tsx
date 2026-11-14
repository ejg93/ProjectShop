import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { expectNoAxeViolations } from "@/test/axe";

const { apiSession } = vi.hoisted(() => ({ apiSession: vi.fn() }));

vi.mock("@/lib/api-session", () => ({ apiSession }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: () => {} }) }));

import CopyrightReportsPage from "./page";

afterEach(() => {
  vi.clearAllMocks();
});

function report(id: number, reinstatement: Record<string, string | null> | null) {
  return {
    copyrightReportId: id,
    productId: 1,
    productName: "티셔츠",
    target: "PRODUCT_IMAGE",
    productImageId: 3,
    thumbnailUrl: null,
    reporterName: "권리자",
    reporterEmail: "r@test.local",
    claimedWork: "화보",
    reportedAt: "2026-11-01T00:00:00Z",
    decision: "TAKEN_DOWN",
    decidedAt: "2026-11-02T00:00:00Z",
    reinstatement,
  };
}

const BASE = { requestedAt: "2026-11-03T00:00:00Z", decision: null, scheduledFor: null, suitNotifiedAt: null, reinstatedAt: null };

async function renderWith(items: unknown[]) {
  apiSession.mockResolvedValue({ items, offset: 0, limit: 50, total: items.length });
  return render(await CopyrightReportsPage({ searchParams: Promise.resolve({ status: "DECIDED" }) }));
}

/**
 * 관리자 신고 화면이 중단을 「숨김」으로 말하고 재개 절차의 단계를 보이는지 본다(`Q467`, `Q464`·`Q465`).
 */
describe("저작권 신고 판정 화면", () => {
  it("중단은 숨김이고 47일 뒤 지운다고 말한다", async () => {
    await renderWith([report(1, null)]);

    expect(screen.getByText(/게시를 중단하면 사진이 비공개로 옮겨지고 47일 뒤 재개 예정이 없으면 지워집니다\./)).toBeInTheDocument();
    expect(screen.getByText(/게시 중단\(사진 숨김\)/)).toBeInTheDocument();
    expect(screen.queryByText(/사진 삭제/)).not.toBeInTheDocument();
  });

  it("재개 절차의 단계를 보인다", async () => {
    await renderWith([
      report(1, BASE),
      report(2, { ...BASE, decision: "SCHEDULED", scheduledFor: "2026-11-12" }),
      report(3, { ...BASE, decision: "SCHEDULED", scheduledFor: "2026-11-12", reinstatedAt: "2026-11-12T00:10:00Z" }),
      report(4, { ...BASE, decision: "REFUSED" }),
      report(5, { ...BASE, decision: "SCHEDULED", scheduledFor: "2026-11-12", suitNotifiedAt: "2026-11-10T00:00:00Z" }),
    ]);

    expect(screen.getByText(/재개 요구 접수 2026-11-03/)).toBeInTheDocument();
    expect(screen.getByText(/재개 예정 2026-11-12/)).toBeInTheDocument();
    expect(screen.getByText(/재개됨 2026-11-12/)).toBeInTheDocument();
    expect(screen.getByText("재개 거절")).toBeInTheDocument();
    expect(screen.getByText(/소 제기 통보 2026-11-10/)).toBeInTheDocument();
  });

  it("접근성 위반이 없다", async () => {
    const { container } = await renderWith([report(1, null), report(2, BASE)]);

    await expectNoAxeViolations(container);
  });
});
