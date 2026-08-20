import type { Metadata } from "next";
import Link from "next/link";

import { apiSession } from "@/lib/api-session";

import { WithdrawForm } from "./withdraw-form";

export const metadata: Metadata = { title: "회원 탈퇴 · ProjectShop" };

/**
 * 회원 탈퇴(`13e`).
 *
 * <p><b>화면을 따로 둔 이유가 이 위쪽 절반이다</b>(사용자 선택) — 되돌릴 수 없는 조작이라
 * 무엇이 사라지고 무엇이 남는지를 적을 자리가 필요하다. 「진행하시겠습니까?」만으로는
 * 사용자가 무엇을 잃는지 모른다(`D20` 「되돌릴 수 없는 조작」).
 *
 * <p><b>남는 것을 같이 적는다.</b> 주문·결제 기록은 전자상거래법 제6조가 5년 보존을 요구해서
 * 탈퇴해도 안 지워진다(`D2` R6, `D13`). 「전부 지웁니다」라고 적으면 사실이 아닌 고지가 된다.
 *
 * <p><b>이 화면 자체가 전자상거래법 제21조의2 4호 나목이 요구하는 자리다</b>(`D2` R24) —
 * 가입이 화면인데 탈퇴가 API 뿐이면 「가입과 다른 방법으로만 탈퇴」에 걸린다.
 *
 * <p>비로그인은 여기까지 못 온다 — 그리기 전에 {@code apiSession} 이 로그인으로 보낸다(`D24`).
 */
export default async function WithdrawPage() {
  // 그릴 값이 없어도 부른다. 로그인해야 보는 화면은 그리기 전에 막는다(`D24`).
  await apiSession<unknown>("/api/me");

  return (
    <div className="mx-auto grid w-full max-w-xl flex-1 content-start gap-8 px-4 py-16">
      <div className="grid gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">회원 탈퇴</h1>
        <p className="text-sm text-text-muted">
          탈퇴하시면 되돌릴 수 없습니다. 아래 내용을 확인해 주시기 바랍니다.
        </p>
      </div>

      <section aria-labelledby="losing-heading" className="grid gap-3">
        <h2 id="losing-heading" className="text-sm font-semibold">
          탈퇴하시면 이렇게 됩니다
        </h2>
        <ul className="grid list-disc gap-2 pl-5 text-sm text-text-muted">
          <li>로그인하신 모든 기기에서 바로 로그아웃됩니다.</li>
          <li>주문 내역과 장바구니를 더 이상 조회하실 수 없습니다.</li>
          <li>받고 계시던 광고성 정보를 포함해 동의하신 항목이 모두 철회됩니다.</li>
          <li>같은 이메일로 다시 가입하실 수 있지만, 이전 계정은 복구되지 않습니다.</li>
        </ul>
      </section>

      <section aria-labelledby="keeping-heading" className="grid gap-3">
        <h2 id="keeping-heading" className="text-sm font-semibold">
          탈퇴하셔도 남는 것이 있습니다
        </h2>
        <ul className="grid list-disc gap-2 pl-5 text-sm text-text-muted">
          <li>
            이름·이메일·비밀번호는 탈퇴하신 뒤 5일이 지나면 지웁니다. 그 5일은 실수로
            탈퇴하신 경우를 위한 기간입니다.
          </li>
          <li>
            주문·결제 기록은 전자상거래법 제6조에 따라 5년간 보관합니다. 이 기록은 지워
            드릴 수 없습니다.
          </li>
          <li>동의를 받은 사실은 3년간 보관합니다.</li>
        </ul>
        <p className="text-sm text-text-muted">
          자세한 내용은{" "}
          <Link
            href="/privacy"
            className="
              font-semibold text-accent-text underline underline-offset-4
              focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
            "
          >
            개인정보처리방침
          </Link>
          에 적어 두었습니다.
        </p>
      </section>

      <WithdrawForm />

      <Link
        href="/me"
        className="
          justify-self-start text-sm text-text-muted underline underline-offset-4
          focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
        "
      >
        탈퇴하지 않고 내 정보로 돌아가기
      </Link>
    </div>
  );
}
