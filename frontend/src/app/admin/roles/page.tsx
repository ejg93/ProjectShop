import type { Metadata } from "next";

import { apiSession, apiSessionOptional } from "@/lib/api-session";
import { can, type Me } from "@/lib/permissions";

import { ImpersonateButton } from "./impersonate-button";
import { RoleEditor } from "./role-editor";

export const metadata: Metadata = { title: "역할 편집 · ProjectShop" };

/**
 * 한 사람과 그가 가진 역할. 서버의 `UserRoleService.Detail` 과 짝이다.
 */
export type UserDetail = {
  userId: number;
  displayName: string;
  /** 탈퇴한 계정. 감사에서 「누가 무엇을 가졌었나」를 물으면 이미 나갔을 수 있다 */
  deleted: boolean;
  /** 주거나 회수할 수 있나. **조회와 편집이 다른 권한이라** 칸으로 가른다 */
  canAssign: boolean;
  roles: {
    roleCode: string;
    roleName: string;
    /** 조직 역할이면 어느 셀러인지. 전역이면 없다 */
    sellerId: number | null;
    sellerName: string | null;
  }[];
};

/**
 * 역할 편집(`16`).
 *
 * <p><b>권한을 데이터로 둔 보람이 나오는 자리다.</b> 역할이 표에 있으니 화면이 그것을
 * 주고 뺄 수 있고, 코드를 고쳐 배포하지 않아도 사람의 권한이 바뀐다.
 *
 * <p><b>사람을 번호로 찾는다.</b> 이름·이메일 검색은 안 만들었다 — 검색은 목록 규약과
 * 부분일치 정책을 같이 정해야 하는 일이고(`D5`), 이 화면이 지금 답해야 하는 물음은
 * 「이 사람의 역할을 어떻게 바꾸나」다. 번호는 감사 기록이 들고 있다.
 *
 * <p><b>조직 역할은 여기서 못 바꾼다.</b> 셀러 역할은 소속과 함께 움직여서 그 셀러의
 * 화면이 든다(`16a`) — 보이기는 하되 뺄 수 있는 것은 전역 역할뿐이고, 그 경계는
 * 서버가 든다({@code ROLE_NOT_ASSIGNABLE}).
 *
 * <p><b>판정은 서버가 한다.</b> 여기까지 와도 권한이 없으면 응답이 안 온다 —
 * 셸이 링크를 가리는 것은 방어가 아니다(`D20` 「권한 없는 것은 숨긴다」).
 *
 * <p>바깥 틀은 {@code admin/layout.tsx} 가 진다.
 */
export default async function AdminRolesPage({
  searchParams,
}: {
  searchParams: Promise<{ userId?: string }>;
}) {
  const requested = await searchParams;
  const userId = Number(requested.userId);
  const found = Number.isInteger(userId) && userId > 0;

  const user = found ? await apiSession<UserDetail>(`/api/users/${userId}`) : null;
  // 대행 버튼은 그 권한이 있는 사람에게만 그린다(`16b`). 누구를 대행할 수 있나(관리자·본인·탈퇴 제외)는
  // 서버가 정하고 여기서는 안 가른다 — 화면이 가르면 규칙이 두 벌이 된다.
  const canImpersonate = can(await apiSessionOptional<Me>("/api/me/permissions"), "user", "impersonate");

  return (
    <>
      <div className="grid gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">역할 편집</h1>
        <p className="text-sm text-text-muted">
          사용자 번호로 찾아서 전역 역할을 주거나 회수합니다.<br />
          셀러 역할은 소속과 함께 움직이므로 여기서 바꿀 수 없습니다.
        </p>
      </div>

      <form className="flex flex-wrap items-end gap-3" action="/admin/roles" method="get">
        <div className="grid gap-1">
          <label className="text-xs text-text-muted" htmlFor="userId">
            사용자 번호
          </label>
          <input
            id="userId"
            name="userId"
            type="number"
            min={1}
            required
            defaultValue={found ? String(userId) : ""}
            className="w-40 rounded-ui border border-border bg-surface px-3 py-2 text-sm"
          />
        </div>
        <button
          type="submit"
          className="rounded-ui border border-border px-4 py-2 text-sm font-medium"
        >
          찾기
        </button>
      </form>

      {user ? (
        <>
          <RoleEditor user={user} />
          {canImpersonate && !user.deleted ? <ImpersonateButton userId={user.userId} /> : null}
        </>
      ) : (
        <p className="rounded-ui border border-border bg-surface-raised px-4 py-6 text-sm text-text-muted">
          사용자 번호를 넣으면 그 사람의 역할이 나옵니다. 번호는 감사 기록에 남아 있습니다.
        </p>
      )}
    </>
  );
}
