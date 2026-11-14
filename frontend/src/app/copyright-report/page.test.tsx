import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { expectNoAxeViolations } from "@/test/axe";

const { apiPublic } = vi.hoisted(() => ({ apiPublic: vi.fn() }));

vi.mock("@/lib/api-session", () => ({ apiPublic }));
vi.mock("next/navigation", () => ({ notFound: () => { throw new Error("notFound"); }, useRouter: () => ({ refresh: () => {} }) }));

import CopyrightReportPage from "./page";

afterEach(() => {
  vi.clearAllMocks();
});

const PRODUCT = { productId: 1, name: "티셔츠", imageUrls: ["https://example.test/a.jpg"], imageIds: [3] };
const NO_REVIEWS = { items: [] };

async function renderWith(recipient: Record<string, string | null>) {
  apiPublic.mockImplementation(async (path: string) => {
    if (path === "/api/copyright-reports/recipient") {
      return recipient;
    }
    return path.includes("/reviews") ? NO_REVIEWS : PRODUCT;
  });
  return render(await CopyrightReportPage({ searchParams: Promise.resolve({ productId: "1" }) }));
}

/**
 * 신고 화면이 중단·재개 요구를 받을 수령인을 밝히는지 본다(`Q467`, 저작권법 제103조④ · 시행령 제44조).
 */
describe("저작권 신고 화면 — 수령인", () => {
  it("값 있는 칸을 다 보인다", async () => {
    const { container } = await renderWith({
      name: "김수령", department: "운영팀", email: "copyright@test.local", phone: "02-000-0000", address: "서울시 어딘가 1",
    });

    const section = screen.getByRole("region", { name: "중단·재개 요구를 받는 수령인" });
    expect(section).toHaveTextContent("김수령(운영팀)");
    expect(screen.getByRole("link", { name: "copyright@test.local" })).toHaveAttribute("href", "mailto:copyright@test.local");
    expect(screen.getByRole("link", { name: "02-000-0000" })).toHaveAttribute("href", "tel:02-000-0000");
    expect(section).toHaveTextContent("서울시 어딘가 1");
    await expectNoAxeViolations(container);
  });

  it("전부 비면 지정되지 않았다고 쓴다", async () => {
    await renderWith({ name: null, department: null, email: null, phone: null, address: null });

    expect(screen.getByText("수령인이 아직 지정되지 않았습니다.")).toBeInTheDocument();
  });
});
