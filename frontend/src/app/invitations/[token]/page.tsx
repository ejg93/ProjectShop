import type { Metadata } from "next";

import { AcceptButton } from "./accept-button";

export const metadata: Metadata = { title: "초대 수락 · ProjectShop" };

/**
 * 받은 초대를 수락한다(`16a`).
 *
 * <p><b>누르기 전에는 아무 일도 안 한다.</b> 링크를 여는 것만으로 수락되면 메신저·메일
 * 미리보기가 대신 눌러 버린다 — 상태를 바꾸는 것은 `GET` 이 아니다(`D5`).
 *
 * <p><b>토큰을 화면이 안 검사한다.</b> 살아 있는지·내 것인지는 서버만 안다 — 여기서 미리
 * 재려면 토큰을 조회하는 입구가 필요하고, 그 입구가 곧 <b>토큰을 두드려 보는 자리</b>가 된다.
 *
 * <p>로그인이 먼저다. 안 한 사람은 `apiSession` 이 아니라 <b>이 조작의 401</b> 이 로그인으로
 * 보낸다(`api.ts`) — 수락은 계정에 붙는 일이라 누구인지부터 정해져야 한다.
 */
export default async function InvitationPage({
  params,
}: {
  params: Promise<{ token: string }>;
}) {
  const { token } = await params;

  return (
    <div className="mx-auto grid w-full max-w-md flex-1 content-start gap-6 px-4 py-16">
      <div className="grid gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">초대를 받으셨습니다</h1>
        <p className="text-sm text-text-muted">
          수락하면 이 계정이 그 셀러에 속하게 되고, 초대에 적힌 역할을 받습니다.<br />
          초대는 받은 주소의 계정으로만 수락할 수 있습니다.
        </p>
      </div>

      <AcceptButton token={token} />
    </div>
  );
}
