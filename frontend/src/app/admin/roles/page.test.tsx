import { render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { expectNoAxeViolations } from "@/test/axe";

const { apiSession, apiSessionOptional } = vi.hoisted(() => ({
  apiSession: vi.fn(),
  apiSessionOptional: vi.fn(),
}));

vi.mock("@/lib/api-session", () => ({ apiSession, apiSessionOptional }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: () => {} }) }));

import AdminRolesPage from "./page";

afterEach(() => {
  vi.clearAllMocks();
});

function role(roleCode: string) {
  return { roleCode, roleName: roleCode, sellerId: null, sellerName: null };
}

async function renderWith(actions: string[], roles: string[]) {
  apiSession.mockResolvedValue({ userId: 5, displayName: "분실", deleted: false, canAssign: true, roles: roles.map(role) });
  apiSessionOptional.mockResolvedValue({
    userId: 1,
    permissions: actions.map((action) => ({ resource: "user", action, scopes: ["ALL"] })),
  });
  return render(await AdminRolesPage({ searchParams: Promise.resolve({ userId: "5" }) }));
}

/**
 * 역할 편집 화면이 2차 인증 초기화 버튼을 권한과 대상 역할대로 그리는지 본다(`Q462`).
 *
 * <p><b>권한 없는 버튼은 안 그린다</b>(`D20`). 고객만인 계정은 2차 인증 대상이 아니라 지울 등록이 없다.
 */
describe("역할 편집 — 2차 인증 초기화", () => {
  it("권한이 있고 대상 계정이면 버튼을 그린다", async () => {
    await renderWith(["reset_second_factor"], ["customer", "seller_owner"]);

    expect(screen.getByRole("button", { name: "2차 인증 초기화" })).toBeInTheDocument();
  });

  it("권한이 없으면 버튼이 없다", async () => {
    await renderWith(["impersonate"], ["admin"]);

    expect(screen.queryByRole("button", { name: "2차 인증 초기화" })).not.toBeInTheDocument();
  });

  it("고객만인 계정에는 버튼이 없다", async () => {
    await renderWith(["reset_second_factor"], ["customer"]);

    expect(screen.queryByRole("button", { name: "2차 인증 초기화" })).not.toBeInTheDocument();
  });

  it("접근성 위반이 없다", async () => {
    const { container } = await renderWith(["reset_second_factor"], ["admin"]);

    await expectNoAxeViolations(container);
  });
});
