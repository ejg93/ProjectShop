"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { ApiError, api } from "@/lib/api";

import type { UserDetail } from "./page";

/**
 * 이 화면에서 줄 수 있는 전역 역할(`V3`·`V5`).
 *
 * <p><b>서버에서 안 받아 온다.</b> 역할 목록을 내려주는 입구가 아직 없고, 그것을 만드는 것은
 * 이 청크의 일이 아니다 — <b>목록이 갈리면 서버가 막는다</b>({@code ROLE_NOT_ASSIGNABLE}).
 * 조직 역할({@code seller_owner}·{@code seller_staff})은 여기 없다: 소속과 함께 움직여서
 * 그 셀러의 화면이 든다(`16a`).
 */
const ASSIGNABLE = [
  { code: "customer", name: "고객" },
  { code: "admin", name: "관리자" },
  { code: "auditor", name: "감사자" },
] as const;

/**
 * 한 사람의 역할을 주고 뺀다.
 *
 * <p><b>여기만 클라이언트 컴포넌트다</b>(`D24` 「경계를 잎사귀로 내린다」). 목록은 서버가 그리고
 * 이 조각이 쓰기를 맡는다.
 *
 * <p><b>바뀐 값을 화면이 직접 안 고친다.</b> {@code router.refresh()} 로 서버에 다시 그리게 한다 —
 * 서버가 아는 것과 화면이 아는 것이 갈리면, 회수가 먹었는지를 화면이 거짓으로 답한다.
 *
 * <p><b>회수에 확인을 붙인다.</b> `D20` 이 확인을 요구하는 것은 되돌리기 어려운 조작인데,
 * 역할 회수는 <b>그 사람이 하던 일이 그 자리에서 막히는</b> 것이라 되돌려도 그 사이가 남는다.
 * 부여는 안 붙인다 — 잘못 준 것은 빼면 그만이다.
 */
export function RoleEditor({ user }: { user: UserDetail }) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const [error, setError] = useState<string | null>(null);

  const globalCodes = new Set(
    user.roles.filter((role) => role.sellerId === null).map((role) => role.roleCode),
  );

  const run = (call: () => Promise<unknown>) => {
    setError(null);
    startTransition(async () => {
      try {
        await call();
        router.refresh();
      } catch (caught) {
        setError(
          caught instanceof ApiError
            ? caught.userText
            : "역할을 바꾸지 못했습니다. 잠시 뒤 다시 시도해 주세요.",
        );
      }
    });
  };

  const grant = (roleCode: string) =>
    run(() => api(`/api/users/${user.userId}/roles`, { method: "POST", body: { roleCode } }));

  const revoke = (roleCode: string, roleName: string) => {
    if (!window.confirm(`${roleName} 역할을 회수합니다. 그 사람이 하던 일이 곧바로 막힙니다.`)) {
      return;
    }
    run(() => api(`/api/users/${user.userId}/roles/${roleCode}`, { method: "DELETE" }));
  };

  return (
    <section className="grid gap-4">
      <div className="grid gap-1 rounded-ui border border-border bg-surface-raised px-4 py-3">
        <p className="text-sm font-medium">
          {user.displayName}
          {user.deleted ? <span className="ml-2 text-xs text-text-muted">(탈퇴한 계정)</span> : null}
        </p>
        <p className="text-xs text-text-muted">번호 {user.userId}</p>
      </div>

      {error ? (
        <p role="alert" className="rounded-ui border border-border px-4 py-3 text-sm">
          {error}
        </p>
      ) : null}

      <table className="w-full border-collapse text-sm">
        <caption className="sr-only">전역 역할 목록. 역할, 가진 상태, 조작 순</caption>
        <thead>
          <tr className="border-b border-border text-left text-xs text-text-muted">
            <th scope="col" className="px-3 py-2 font-medium">역할</th>
            <th scope="col" className="px-3 py-2 font-medium">가짐</th>
            <th scope="col" className="px-3 py-2 font-medium">조작</th>
          </tr>
        </thead>
        <tbody>
          {ASSIGNABLE.map((role) => {
            const has = globalCodes.has(role.code);
            return (
              <tr key={role.code} className="border-b border-border">
                <td className="px-3 py-2">{role.name}</td>
                <td className="px-3 py-2">{has ? "예" : "아니오"}</td>
                <td className="px-3 py-2">
                  {user.canAssign ? (
                    <button
                      type="button"
                      disabled={pending}
                      onClick={() => (has ? revoke(role.code, role.name) : grant(role.code))}
                      className="rounded-ui border border-border px-3 py-1 text-xs font-medium disabled:opacity-50"
                    >
                      {has ? "회수" : "부여"}
                    </button>
                  ) : null}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>

      {user.roles.some((role) => role.sellerId !== null) ? (
        <div className="grid gap-1">
          <h2 className="text-sm font-medium">셀러 역할</h2>
          <p className="text-xs text-text-muted">
            소속과 함께 움직이므로 여기서 바꿀 수 없습니다. 그 셀러의 멤버 화면에서 바꿉니다.
          </p>
          <ul className="grid gap-1 text-sm">
            {user.roles
              .filter((role) => role.sellerId !== null)
              .map((role) => (
                <li key={`${role.roleCode}-${role.sellerId}`}>
                  {role.sellerName} · {role.roleName}
                </li>
              ))}
          </ul>
        </div>
      ) : null}
    </section>
  );
}
