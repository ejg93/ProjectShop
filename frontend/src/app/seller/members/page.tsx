import type { Metadata } from "next";

import { apiSession } from "@/lib/api-session";

import { MemberPanel } from "./member-panel";

export const metadata: Metadata = { title: "멤버 · ProjectShop" };

/** 서버의 `SellerMemberService.Members` 와 짝이다 */
export type SellerMembers = {
  members: {
    userId: number;
    displayName: string;
    /** 이 셀러에서 받은 역할. 소속만 있고 역할이 없으면 빈 배열이다 */
    roleCodes: string[];
  }[];
  /** 부르고 거둘 수 있나. **빈 초대 목록과 권한 없음이 같은 모양이라** 칸으로 가른다 */
  canManage: boolean;
  /** 관리 권한이 없으면 빈 배열이다 — 주소가 실려서 아무에게나 안 보인다 */
  invitations: {
    invitationId: number;
    email: string;
    roleCode: string;
    expiresAt: string;
  }[];
};

/** 내가 속한 셀러. **화면이 번호를 안 고른다** — 서버가 알려 준다 */
type Memberships = {
  sellerIds: number[];
};

/**
 * 셀러 멤버(`16a`).
 *
 * <p><b>전역 역할 편집과 화면을 가른다</b>(`16`). 저쪽은 관리자가 아무나 고르는 자리고,
 * 여기는 <b>내 셀러 안</b>이다 — 같은 화면에 두면 셀러 번호를 고르는 칸이 생기고,
 * 그 칸이 곧 <b>남의 조직을 편집하려 드는 입구</b>가 된다.
 *
 * <p><b>셀러 번호를 화면이 안 고른다.</b> 내가 속한 셀러를 서버가 내려주고 그중 첫째를 쓴다 —
 * 여러 셀러에 속한 계정은 아직 없다(그 자리가 생기면 고르는 칸이 필요하고, 그때 정한다).
 *
 * <p><b>판정은 서버가 한다.</b> 번호를 바꿔 넣어도 조직 역할로 받은 사람은 받은 그 셀러에서만
 * 통과한다 — 화면이 가리는 것은 방어가 아니다(`D20` 「권한 없는 것은 숨긴다」).
 */
export default async function SellerMembersPage() {
  const memberships = await apiSession<Memberships>("/api/seller/memberships");
  const sellerId = memberships.sellerIds[0];

  if (sellerId === undefined) {
    return (
      <>
        <h1 className="text-2xl font-semibold tracking-tight">멤버</h1>
        <p className="rounded-ui border border-border bg-surface-raised px-4 py-6 text-sm text-text-muted">
          속한 셀러가 없습니다. 초대를 받으면 이 화면에서 같이 일하는 사람을 볼 수 있습니다.
        </p>
      </>
    );
  }

  const data = await apiSession<SellerMembers>(`/api/sellers/${sellerId}/members`);

  return (
    <>
      <div className="grid gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">멤버</h1>
        <p className="text-sm text-text-muted">
          이 셀러에서 같이 일하는 사람입니다.<br />
          사람을 부르거나 부른 것을 거두는 일은 대표만 할 수 있습니다.
        </p>
      </div>

      <MemberPanel sellerId={sellerId} data={data} />
    </>
  );
}
