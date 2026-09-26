"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { ApiError, api } from "@/lib/api";
import { dateTimeText } from "@/lib/format";

import type { SellerMembers } from "./page";

/**
 * 부를 수 있는 역할(`V3`·`V82`).
 *
 * <p><b>조직 역할만이다.</b> 전역 역할은 여기서 못 준다 — 표의 트리거가 막고(`V82`),
 * 관리자 화면이 그 자리다(`16`).
 */
const ROLES = [
  { code: "seller_staff", name: "담당자" },
  { code: "seller_owner", name: "대표" },
] as const;

/**
 * 멤버와 초대를 그리고 바꾼다.
 *
 * <p><b>여기만 클라이언트 컴포넌트다</b>(`D24` 「경계를 잎사귀로 내린다」).
 *
 * <p><b>초대 링크를 화면에 띄운다.</b> 메일 배관이 아직 없어서 부른 사람이 그 링크를 직접
 * 전한다 — <b>한 번만 보인다</b>: 표에는 해시만 있어서 다시 꺼낼 수가 없고, 놓치면 거두고
 * 다시 부른다. 메일을 붙이는 날 이 자리가 없어진다.
 *
 * <p><b>거두기에 확인을 안 붙인다.</b> `D20` 이 확인을 요구하는 것은 되돌리기 어려운
 * 조작인데, 거둔 초대는 다시 부르면 그만이다 — 아직 아무도 들어오지 않았다.
 */
export function MemberPanel({ sellerId, data }: { sellerId: number; data: SellerMembers }) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const [error, setError] = useState<string | null>(null);
  const [link, setLink] = useState<string | null>(null);

  const [email, setEmail] = useState("");
  const [roleCode, setRoleCode] = useState<string>(ROLES[0].code);

  const manages = data.canManage;

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
            : "처리하지 못했습니다. 잠시 뒤 다시 시도해 주세요.",
        );
      }
    });
  };

  const invite = (event: React.FormEvent) => {
    event.preventDefault();
    setError(null);
    setLink(null);
    startTransition(async () => {
      try {
        const issued = await api<{ invitationId: number; token: string }>(
          `/api/sellers/${sellerId}/invitations`,
          { method: "POST", body: { email, roleCode } },
        );
        setLink(`${window.location.origin}/invitations/${issued.token}`);
        setEmail("");
        router.refresh();
      } catch (caught) {
        setError(
          caught instanceof ApiError
            ? caught.userText
            : "초대하지 못했습니다. 잠시 뒤 다시 시도해 주세요.",
        );
      }
    });
  };

  const revoke = (invitationId: number) =>
    run(() =>
      api(`/api/sellers/${sellerId}/invitations/${invitationId}`, { method: "DELETE" }),
    );


  const changeRole = (userId: number, roleCode: string) =>
    run(() =>
      api(`/api/sellers/${sellerId}/members/${userId}`, {
        method: "PATCH",
        body: { roleCode },
      }),
    );

  /**
   * 내보내기에 확인을 붙인다. `D20` 이 확인을 요구하는 것은 되돌리기 어려운 조작인데,
   * **다시 부르려면 초대를 새로 내야 하고 그 사람이 다시 수락해야 한다.**
   */
  const remove = (userId: number, name: string) => {
    if (!window.confirm(`${name} 님을 내보냅니다. 다시 부르려면 초대를 새로 보내야 합니다.`)) {
      return;
    }
    run(() => api(`/api/sellers/${sellerId}/members/${userId}`, { method: "DELETE" }));
  };

  // 대표가 몇인가. 서버가 마지막 대표를 막는데(`Q165`) 그 상태를 응답의 역할 목록으로
  // 셀 수 있어서, 못 누를 버튼을 아예 안 그린다.
  const ownerCount = data.members.filter((member) =>
    member.roleCodes.includes("seller_owner"),
  ).length;

  return (
    <section className="grid gap-6">
      {error ? (
        <p role="alert" className="rounded-ui border border-border px-4 py-3 text-sm">
          {error}
        </p>
      ) : null}

      <table className="w-full border-collapse text-sm">
        <caption className="sr-only">멤버 목록. 이름, 역할, 조작 순</caption>
        <thead>
          <tr className="border-b border-border text-left text-xs text-text-muted">
            <th scope="col" className="px-3 py-2 font-medium">이름</th>
            <th scope="col" className="px-3 py-2 font-medium">역할</th>
            {manages ? <th scope="col" className="px-3 py-2 font-medium">조작</th> : null}
          </tr>
        </thead>
        <tbody>
          {data.members.map((member) => {
            /*
              **마지막 대표에게는 두 버튼을 안 그린다**(마무리 44차 독립 리뷰).
              서버가 SELLER_LAST_OWNER 로 막는 자리라 눌러야 422 가 오고,
              그건 갈 곳이 있는 것처럼 보이게 하는 것이다(`D20`).
              대표가 둘 이상이면 하나는 내려올 수 있어서 그때는 그린다.
            */
            const lastOwner =
              member.roleCodes.includes("seller_owner") && ownerCount === 1;

            return (
            <tr key={member.userId} className="border-b border-border">
              <td className="px-3 py-2">{member.displayName}</td>
              <td className="px-3 py-2">
                {member.roleCodes.length > 0
                  ? member.roleCodes
                      .map((code) => ROLES.find((role) => role.code === code)?.name ?? code)
                      .join(", ")
                  : "없음"}
              </td>
              {manages ? (
                <td className="flex flex-wrap gap-2 px-3 py-2">
                  {lastOwner ? (
                    <span className="text-xs text-text-muted">
                      한 분뿐인 대표라 바꾸거나 내보낼 수 없습니다
                    </span>
                  ) : (
                    <>
                      {ROLES.filter((role) => !member.roleCodes.includes(role.code)).map(
                        (role) => (
                          <button
                            key={role.code}
                            type="button"
                            disabled={pending}
                            onClick={() => changeRole(member.userId, role.code)}
                            className="rounded-ui border border-border px-3 py-1 text-xs font-medium disabled:opacity-50"
                          >
                            {role.name}로
                          </button>
                        ),
                      )}
                      <button
                        type="button"
                        disabled={pending}
                        onClick={() => remove(member.userId, member.displayName)}
                        className="rounded-ui border border-border px-3 py-1 text-xs font-medium disabled:opacity-50"
                      >
                        내보내기
                      </button>
                    </>
                  )}
                </td>
              ) : null}
            </tr>
            );
          })}
        </tbody>
      </table>

      {manages ? (
        <div className="grid gap-4">
          <form className="flex flex-wrap items-end gap-3" onSubmit={invite}>
            <div className="grid gap-1">
              <label className="text-xs text-text-muted" htmlFor="email">
                부를 사람의 주소
              </label>
              <input
                id="email"
                name="email"
                type="email"
                required
                maxLength={254}
                value={email}
                onChange={(event) => setEmail(event.target.value)}
                className="w-72 rounded-ui border border-border bg-surface px-3 py-2 text-sm"
              />
            </div>
            <div className="grid gap-1">
              <label className="text-xs text-text-muted" htmlFor="roleCode">
                줄 역할
              </label>
              <select
                id="roleCode"
                name="roleCode"
                value={roleCode}
                onChange={(event) => setRoleCode(event.target.value)}
                className="rounded-ui border border-border bg-surface px-3 py-2 text-sm"
              >
                {ROLES.map((role) => (
                  <option key={role.code} value={role.code}>
                    {role.name}
                  </option>
                ))}
              </select>
            </div>
            <button
              type="submit"
              disabled={pending}
              className="rounded-ui border border-border px-4 py-2 text-sm font-medium disabled:opacity-50"
            >
              부르기
            </button>
          </form>

          {link ? (
            <div className="grid gap-1 rounded-ui border border-border bg-surface-raised px-4 py-3">
              <p className="text-sm font-medium">초대 링크</p>
              <p className="text-xs text-text-muted">
                이 링크는 지금 한 번만 보입니다. 부른 사람에게 직접 전해 주세요.
              </p>
              <code className="break-all text-xs">{link}</code>
            </div>
          ) : null}

          {data.invitations.length > 0 ? (
            <table className="w-full border-collapse text-sm">
              <caption className="sr-only">보낸 초대 목록. 주소, 역할, 만료, 조작 순</caption>
              <thead>
                <tr className="border-b border-border text-left text-xs text-text-muted">
                  <th scope="col" className="px-3 py-2 font-medium">주소</th>
                  <th scope="col" className="px-3 py-2 font-medium">역할</th>
                  <th scope="col" className="px-3 py-2 font-medium">만료</th>
                  <th scope="col" className="px-3 py-2 font-medium">조작</th>
                </tr>
              </thead>
              <tbody>
                {data.invitations.map((invitation) => (
                  <tr key={invitation.invitationId} className="border-b border-border">
                    <td className="px-3 py-2">{invitation.email}</td>
                    <td className="px-3 py-2">
                      {ROLES.find((role) => role.code === invitation.roleCode)?.name ??
                        invitation.roleCode}
                    </td>
                    <td className="px-3 py-2 text-text-muted">
                      {dateTimeText(invitation.expiresAt)}
                    </td>
                    <td className="px-3 py-2">
                      <button
                        type="button"
                        disabled={pending}
                        onClick={() => revoke(invitation.invitationId)}
                        className="rounded-ui border border-border px-3 py-1 text-xs font-medium disabled:opacity-50"
                      >
                        거두기
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : null}
        </div>
      ) : null}
    </section>
  );
}
