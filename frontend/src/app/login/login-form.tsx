"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";

import { ApiError, api } from "@/lib/api";

/**
 * 로그인 응답. 지금 화면이 쓰는 것은 없지만 형태를 적어 둔다.
 *
 * <p>무엇을 할 수 있는지는 여기 안 온다. 권한 목록은 따로 내려온다(청크 8a).
 */
type LoginResponse = {
  userId: number;
  email: string;
};

/** 서버가 준 오류를 화면 문구로 옮긴다. `type` 으로 갈린다(`D5`) */
function messageOf(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return "서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }

  switch (error.type) {
    case "urn:shop:error:login-failed":
      // 없는 계정인지 틀린 비밀번호인지 가르지 않는다. 서버가 이미 한 문구로 내려주고,
      // 화면이 그걸 나누면 가입 여부를 물어보는 도구가 된다(`D14`).
      return "이메일 또는 비밀번호가 맞지 않습니다.";
    default:
      // 서버 문구를 그대로 쓰지 않는다(`D20`). 그쪽은 개발자가 읽는 평서형이다.
      return "로그인하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
}

export function LoginForm() {
  const router = useRouter();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(form: FormData) {
    setPending(true);
    setError(null);

    try {
      await api<LoginResponse>("/api/auth/login", {
        method: "POST",
        body: {
          email: form.get("email"),
          password: form.get("password"),
        },
      });

      // 서버 컴포넌트가 들고 있는 것을 버리고 다시 받는다. 안 부르면 로그인 전 화면이 남는다.
      router.refresh();
      // replace 다. push 면 뒤로 가기가 로그인 화면으로 돌아온다.
      router.replace("/");
    } catch (thrown) {
      setError(messageOf(thrown));
      setPending(false);
    }
  }

  return (
    <form action={submit} className="grid gap-5">
      <Field
        name="email"
        type="email"
        label="이메일"
        autoComplete="email"
        invalid={error !== null}
      />

      <Field
        name="password"
        type="password"
        label="비밀번호"
        autoComplete="current-password"
        invalid={error !== null}
      />

      {/*
        오류를 입력칸 아래, 버튼 위에 둔다. 위쪽에 두면 스크롤한 화면에서 안 보이고,
        role=alert 라 화면낭독기가 나타나는 순간 읽는다(`D20`).
      */}
      {error && (
        <p role="alert" className="text-sm text-danger-text">
          {error}
        </p>
      )}

      <button
        type="submit"
        disabled={pending}
        className="
          rounded-ui bg-accent px-4 py-2.5 text-sm font-semibold text-accent-on
          transition-[background-color,transform,opacity] duration-200
          hover:bg-accent-hover
          motion-safe:active:translate-y-px
          focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
          disabled:cursor-not-allowed disabled:opacity-60
        "
      >
        {pending ? "확인하는 중" : "로그인"}
      </button>
    </form>
  );
}

/**
 * 라벨은 입력칸 위에 둔다. <b>자리표시 문구를 라벨로 쓰지 않는다</b> - 값을 넣는 순간
 * 무슨 칸이었는지가 사라지고, 화면낭독기는 "편집창" 이라고만 말한다.
 */
function Field({
  name,
  type,
  label,
  autoComplete,
  invalid,
}: {
  name: string;
  type: "email" | "password";
  label: string;
  autoComplete: string;
  invalid: boolean;
}) {
  return (
    <div className="grid gap-2">
      <label htmlFor={name} className="text-sm font-semibold">
        {label}
      </label>
      <input
        id={name}
        name={name}
        type={type}
        required
        autoComplete={autoComplete}
        aria-invalid={invalid}
        className="
          rounded-ui border border-border bg-surface-raised px-3 py-2.5 text-base
          transition-colors duration-200
          focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
          aria-invalid:border-danger-text
        "
      />
    </div>
  );
}
