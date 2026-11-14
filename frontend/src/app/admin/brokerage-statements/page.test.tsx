import { render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { expectNoAxeViolations } from "@/test/axe";

const { apiSession } = vi.hoisted(() => ({ apiSession: vi.fn() }));

vi.mock("@/lib/api-session", () => ({ apiSession }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: () => {} }) }));

import BrokerageStatementsPage from "./page";

afterEach(() => {
  vi.clearAllMocks();
});

function statement(id: number, status: string) {
  return {
    brokerageStatementId: id,
    periodYear: 2026,
    periodQuarter: id,
    dueDate: "2026-04-15",
    status,
    lineCount: 2,
    totalAmount: 45000,
  };
}

async function renderWith(canSubmit: boolean) {
  apiSession.mockResolvedValue({
    items: [statement(1, "SUBMITTED"), statement(2, "OVERDUE"), statement(3, "OPEN")],
    offset: 0,
    limit: 50,
    total: 3,
    canSubmit,
  });
  return render(await BrokerageStatementsPage());
}

/**
 * 중개 명세 화면이 서버가 실은 상태와 권한대로 그리는지 본다(`Q457`, `D2` R46).
 *
 * <p><b>권한 없는 버튼은 안 그린다</b>(`D20`) — 감사자에게 「제출 기록」이 보이면 눌러야 403 이 온다.
 */
describe("중개 명세 화면", () => {
  it("분기 셋을 상태 문구와 함께 그린다", async () => {
    await renderWith(true);

    const rows = screen.getAllByRole("row").slice(1);
    expect(rows).toHaveLength(3);
    expect(within(rows[0]).getByText("제출됨")).toBeInTheDocument();
    expect(within(rows[1]).getByText("기한 지남")).toBeInTheDocument();
    expect(within(rows[2]).getByText("제출 전")).toBeInTheDocument();
  });

  it("제출된 분기에는 제출 버튼이 없다", async () => {
    await renderWith(true);

    const rows = screen.getAllByRole("row").slice(1);
    expect(within(rows[0]).queryByRole("button", { name: /제출 기록/ })).not.toBeInTheDocument();
    expect(within(rows[1]).getByRole("button", { name: /제출 기록/ })).toBeInTheDocument();
    expect(within(rows[2]).getByRole("button", { name: /제출 기록/ })).toBeInTheDocument();
  });

  it("제출 권한이 없으면 버튼이 하나도 없다", async () => {
    await renderWith(false);

    expect(screen.queryByRole("button", { name: /제출 기록/ })).not.toBeInTheDocument();
  });

  it("내려받기는 그 분기 파일 주소다", async () => {
    await renderWith(true);

    const links = screen.getAllByRole("link", { name: /내려받기/ });
    expect(links[1]).toHaveAttribute("href", "/api/admin/brokerage-statements/2/file");
  });

  it("접근성 위반이 없다", async () => {
    const { container } = await renderWith(true);

    await expectNoAxeViolations(container);
  });
});
