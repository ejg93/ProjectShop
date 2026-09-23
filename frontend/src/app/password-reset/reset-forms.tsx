"use client";

import { useState, useTransition } from "react";

import { Field } from "@/components/field";
import { ApiError, api } from "@/lib/api";
import { PASSWORD_HINT } from "@/lib/password-hint";

/**
 * 재설정 메일을 달라고 한다(`Q180`).
 *
 * <p><b>가입 여부와 무관하게 같은 말을 한다.</b> 서버가 없는 주소에도 같은 202 를 주는데(`5c-1`, `D14`)
 * 화면이 「없는 주소입니다」라고 하면 이 칸이 가입 여부를 물어보는 도구가 된다.
 */
export function ResetRequestForm() {
  const [pending, startTransition] = useTransition();
  const [sent, setSent] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  const submit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setFailure(null);
    const email = String(new FormData(event.currentTarget).get("email"));
    startTransition(async () => {
      try {
        await api("/api/auth/password-reset", { method: "POST", body: { email } });
        setSent(true);
      } catch (error) {
        setFailure(messageOf(error));
      }
    });
  };

  if (sent) {
    return (
      <p role="status" className="rounded-ui border border-border bg-surface-raised px-4 py-3 text-sm">
        가입된 주소라면 비밀번호를 바꾸는 링크를 보냈습니다. 메일함을 확인해 주세요.
        <br />
        링크는 한 번만 쓸 수 있고, 시간이 지나면 다시 요청하셔야 합니다.
      </p>
    );
  }

  return (
    <form onSubmit={submit} className="grid gap-4">
      <Field name="email" type="email" label="가입한 이메일" autoComplete="email" maxLength={254} />
      <p aria-live="polite" className="text-sm text-danger-text">
        {failure}
      </p>
      <button
        type="submit"
        disabled={pending}
        className="justify-self-start rounded-ui bg-accent px-4 py-2 text-sm font-semibold text-surface disabled:opacity-60"
      >
        {pending ? "보내는 중" : "재설정 링크 받기"}
      </button>
    </form>
  );
}

/**
 * 메일의 링크로 들어와 새 비밀번호를 정한다(`Q180`).
 *
 * <p><b>성공하면 뒤로 가기로 못 돌아오게 나간다</b>({@code location.replace}) — 이 화면의 주소에는 토큰이 있고,
 * 쓴 토큰의 화면이 기록에 남으면 다시 눌러 「쓸 수 없는 토큰」만 보게 된다.
 */
export function ResetConfirmForm({ token }: { token: string }) {
  const [pending, startTransition] = useTransition();
  const [failure, setFailure] = useState<string | null>(null);

  const submit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setFailure(null);
    const newPassword = String(new FormData(event.currentTarget).get("newPassword"));
    startTransition(async () => {
      try {
        await api("/api/auth/password-reset/confirm", { method: "POST", body: { token, newPassword } });
        window.location.replace("/login?reason=password-reset");
      } catch (error) {
        setFailure(messageOf(error));
      }
    });
  };

  return (
    <form onSubmit={submit} className="grid gap-4">
      <Field
        name="newPassword"
        type="password"
        label="새 비밀번호"
        autoComplete="new-password"
        hint={PASSWORD_HINT}
      />
      <p role="alert" className="text-sm text-danger-text">
        {failure}
      </p>
      <button
        type="submit"
        disabled={pending}
        className="justify-self-start rounded-ui bg-accent px-4 py-2 text-sm font-semibold text-surface disabled:opacity-60"
      >
        {pending ? "바꾸는 중" : "비밀번호 바꾸기"}
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
    case "password-reset-token-invalid":
      return "쓸 수 없는 링크입니다. 이미 썼거나 시간이 지났을 수 있습니다. 재설정 링크를 다시 받아 주세요.";
    case "password-too-common":
      return "흔하거나 추측하기 쉬운 비밀번호입니다. 이메일이나 이름이 들어가지 않은 다른 비밀번호를 써 주세요.";
    case "validation-failed":
      return `입력하신 내용을 다시 확인해 주세요. 비밀번호는 ${PASSWORD_HINT}`;
    case "too-many-requests":
      return "요청이 너무 잦습니다. 잠시 후 다시 시도해 주세요.";
    default:
      return "처리하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
}
