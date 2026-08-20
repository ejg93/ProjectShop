"use client";

import { useState } from "react";

import { Field } from "@/components/field";
import { ApiError, api } from "@/lib/api";

/**
 * 탈퇴를 실행한다.
 *
 * <p><b>비밀번호를 다시 받는다</b>(`D20` 「되돌릴 수 없는 조작」). 확인 대화상자만으로는
 * 부족하다 — 세션을 훔친 사람이 탈퇴까지 할 수 있으면 주인이 계정을 영영 잃는다.
 * 서버도 같은 판단을 하고 있고(`5g`) 화면은 그 칸을 낼 뿐이다.
 *
 * <p><b>대화상자를 겹치지 않는다.</b> 무엇이 사라지는지는 이 화면이 위에서 이미 말했고,
 * 확인이 흔해지면 사용자가 읽지 않고 누른다(`D20`).
 *
 * <p><b>성공하면 로그인 화면으로 보낸다.</b> 서버가 이 사람의 세션을 전부 만료시켜서(`5g`)
 * 어디로 가든 다음 요청은 401 이다 — 로그인 화면이 「탈퇴 처리되었습니다」를 말하는 것이
 * 그 상태와 맞는다(사용자 선택).
 *
 * <p>{@code router.push} 가 아니라 {@code location.replace} 다. 뒤로 가기로 이 화면에
 * 돌아오면 이미 없는 계정으로 탈퇴를 한 번 더 누르게 된다.
 */
export function WithdrawForm() {
  const [sending, setSending] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  async function submit(form: FormData) {
    setSending(true);
    setFailure(null);

    try {
      await api("/api/me/withdraw", { method: "POST", body: { password: form.get("password") } });
      window.location.replace("/login?reason=withdrawn");
    } catch (thrown) {
      setFailure(messageOf(thrown));
      setSending(false);
    }
  }

  return (
    <form action={submit} className="grid gap-4 rounded-ui border border-danger-text p-5">
      <h2 className="text-sm font-semibold">비밀번호를 확인합니다</h2>

      <Field
        name="password"
        type="password"
        label="현재 비밀번호"
        autoComplete="current-password"
        hint="본인 확인을 위해 다시 한 번 입력해 주시기 바랍니다."
      />

      {/* 입력칸 아래, 버튼 위다. 위에 두면 스크롤한 화면에서 안 보인다(`D20`) */}
      <p role="alert" className="text-xs text-danger-text">
        {failure}
      </p>

      <button
        type="submit"
        disabled={sending}
        className="
          justify-self-start rounded-ui bg-danger-text px-4 py-2.5 text-sm font-semibold text-surface
          transition-[background-color,transform,opacity] duration-200
          motion-safe:active:translate-y-px
          focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-danger-text
          disabled:cursor-not-allowed disabled:opacity-60
        "
      >
        {sending ? "탈퇴하는 중" : "탈퇴하기"}
      </button>
    </form>
  );
}

/** 서버가 준 오류를 화면 문구로. <b>`slug` 로 갈린다</b>(`D5`·`D20`) */
function messageOf(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return "서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }

  switch (error.slug) {
    case "password-mismatch":
      return "비밀번호가 맞지 않습니다.";
    case "already-withdrawn":
      // 401 이라 `api.ts` 가 먼저 로그인으로 보낸다. 그래도 적어 두는 것은 그쪽 규칙이
      // 바뀌었을 때 이 화면이 아무 말도 안 하는 상태로 남지 않게 하려는 것이다.
      return "이미 탈퇴 처리된 계정입니다.";
    default:
      return "탈퇴하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
}
