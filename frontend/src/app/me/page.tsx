import type { Metadata } from "next";
import Link from "next/link";

import { apiSession } from "@/lib/api-session";
import { dateText } from "@/lib/format";

import { AccountForms } from "./account-forms";
import { ConsentList, type ConsentView } from "./consent-list";

export const metadata: Metadata = { title: "내 정보 · ProjectShop" };

/**
 * 계정 열람 응답(`5e`). <b>볼 수 없는 필드는 키가 아예 없다</b>(`D5`) —
 * 그래서 셋 다 선택 타입이고, 화면은 `null` 검사가 아니라 키의 존재로 가른다.
 */
type Account = {
  userId: number;
  displayName?: string;
  createdAt?: string;
  email?: string;
};

/**
 * 마이페이지(`13e`).
 *
 * <p><b>법 요건 둘이 이 화면에서 닫힌다.</b>
 *
 * <ul>
 *   <li>개인정보법 제38조제4항(`D2` R28) — 열람등요구의 방법과 절차는 수집보다 어렵지 않아야 한다.
 *       가입은 `/signup` 화면인데 열람·정정·철회는 API 뿐이었다
 *   <li>전자상거래법 제21조의2 4호 나목(`D2` R24) — 가입과 다른 방법으로만 탈퇴하게 두는 것이
 *       금지행위다. 탈퇴가 API 뿐이면 여기 걸린다
 * </ul>
 *
 * <p><b>처리방침 제7절이 이 화면을 이미 가리키고 있었다</b>(`V21`) —
 * 「열람·정정·동의 철회·탈퇴는 마이페이지에서 직접 하실 수 있습니다」.
 * 화면이 서면서 그 문안이 사실이 된다.
 *
 * <p><b>탈퇴만 자기 화면을 갖는다</b>(사용자 선택). 되돌릴 수 없는 조작이라
 * 무엇이 사라지고 무엇이 5년 남는지를 적을 자리가 필요하다(`/me/withdraw`).
 *
 * <p><b>읽기는 여기, 쓰기는 잎사귀</b>(`D24`). 이 컴포넌트는 서버에서 두 번 읽고,
 * 입력을 받는 조각만 클라이언트로 뗐다.
 */
export default async function MyPage() {
  // 로그인해야 보는 화면이라 그리기 전에 막힌다 — 401 은 `apiSession` 이 로그인으로 보낸다(`D24`).
  const [account, consents] = await Promise.all([
    apiSession<Account>("/api/me"),
    apiSession<ConsentView[]>("/api/me/consents"),
  ]);

  return (
    <div className="mx-auto grid w-full max-w-2xl flex-1 content-start gap-10 px-4 py-16">
      <div className="grid gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">내 정보</h1>
        <p className="text-sm text-text-muted">
          가입하실 때 주신 정보를 여기서 보시고 고치실 수 있습니다.
        </p>
      </div>

      <section aria-labelledby="account-heading" className="grid gap-4">
        <h2 id="account-heading" className="text-lg font-semibold">
          계정
        </h2>

        {/*
          볼 수 없는 필드는 그 줄을 통째로 안 그린다(`D20`). `-` 로 자리를 채우면
          「값이 없다」와 「볼 수 없다」가 화면에서 같아 보인다.

          `_visible_field_groups` 는 안 본다 — 무엇이 보이는지는 키의 유무가 이미 답했고,
          빈 배열이 「전부 본다」인지 「아무것도 못 본다」인지는 아직 안 정했다(`13b`).
        */}
        <dl className="grid gap-3 rounded-ui border border-border bg-surface-raised p-5 text-sm sm:grid-cols-[7rem_1fr]">
          {account.displayName === undefined ? null : (
            <Row label="이름" value={account.displayName} />
          )}
          {account.email === undefined ? null : <Row label="이메일" value={account.email} />}
          {account.createdAt === undefined ? null : (
            <Row label="가입일" value={dateText(account.createdAt)} />
          )}
        </dl>

        <AccountForms displayName={account.displayName} email={account.email} />
      </section>

      <section aria-labelledby="consent-heading" className="grid gap-4">
        <h2 id="consent-heading" className="text-lg font-semibold">
          동의 내역
        </h2>
        <ConsentList items={consents} />
      </section>

      <section aria-labelledby="rights-heading" className="grid gap-4">
        <h2 id="rights-heading" className="text-lg font-semibold">
          권리 행사와 탈퇴
        </h2>

        {/*
          처리정지(개인정보법 제37조)는 **탈퇴와 다른 권리다**(`Q13`). 제37조제1항이 요구권 자체를
          인정하고 거절은 제2항 각 호에 해당할 때만 되는데, 「탈퇴해 주십시오」라고만 하면
          요구를 받을 자리가 없는 상태에서 탈퇴를 유일한 길로 제시하는 것이 된다.

          **접수 창구가 화면 안에 섰다**(`59-1`). 제38조제4항이 열람등요구의 방법을 **수집보다
          어렵지 않게** 하라고 하는데, 가입은 화면이고 이쪽은 API 뿐이었다 — 그 자리가 여기서 닫힌다.
        */}
        <p className="text-sm text-text-muted">
          처리 정지를 요구하실 수 있습니다(개인정보 보호법 제37조). 요구하신 날부터 열흘 안에
          답변해 드립니다.
          <br />
          열람·정정 결과에 이의가 있으실 때, 거래에서 불만이나 분쟁이 생기셨을 때도 같은 자리로
          알려 주시면 됩니다.
          <br />
          탈퇴하시면 이후의 처리가 함께 멈춥니다.
        </p>

        <div className="flex flex-wrap items-center gap-4">
          <Link
            href="/me/inquiries"
            className="
              rounded-ui bg-accent px-4 py-2.5 text-sm font-semibold text-surface
              transition-colors duration-200
              hover:bg-accent-strong
              focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent
            "
          >
            권리 행사 요구·문의
          </Link>

          <Link
            href="/me/withdraw"
            className="
              rounded-ui border border-danger-text px-4 py-2.5 text-sm font-semibold text-danger-text
              transition-colors duration-200
              hover:bg-danger-text hover:text-surface
              focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-danger-text
            "
          >
            회원 탈퇴
          </Link>

          <PolicyLink>내 권리 전체 보기 (개인정보처리방침 제7절)</PolicyLink>
        </div>
      </section>
    </div>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <>
      <dt className="text-text-muted">{label}</dt>
      <dd className="font-medium">{value}</dd>
    </>
  );
}

/**
 * 개인정보처리방침으로 보낸다.
 *
 * <p><b>절 번호를 주소가 아니라 글로 가리킨다.</b> 방침 본문에 앵커가 없어서(`13a-2`)
 * `#절-제목` 을 붙여도 브라우저가 못 찾고 맨 위에 그대로 선다 —
 * 안 되는 것을 되는 것처럼 적으면 다음 사람이 그 링크를 고칠 자리를 못 찾는다.
 */
function PolicyLink({ children }: { children: string }) {
  return (
    <Link
      href="/privacy"
      className="
        text-sm font-semibold text-accent-text underline underline-offset-4
        focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
      "
    >
      {children}
    </Link>
  );
}
