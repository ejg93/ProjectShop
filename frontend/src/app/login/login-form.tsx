"use client";

import { useState } from "react";

import { Field } from "@/components/field";
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

/**
 * 개발 중에 칸을 미리 채워 둘 계정(`V901__test_account.sql`).
 *
 * <p><b>운영 빌드에서는 없어진다.</b> `process.env.NODE_ENV` 는 번들러가 빌드 시점에
 * 상수로 바꾸므로 이 값이 `null` 로 접히고, 문자열 자체가 결과물에 안 남는다.
 * 조건 없이 두면 <b>배포한 화면에 계정과 비밀번호가 박혀 나간다.</b>
 *
 * <p>편의 하나를 잃는다 - 로그인 실패를 손으로 확인하려면 칸을 지우고 쳐야 한다.
 */
const TEST_ACCOUNT =
  process.env.NODE_ENV === "development"
    ? { email: "test@test.local", password: "test-account-1234" }
    : null;

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

      // 통째로 다시 받는다. 클라이언트 이동(router)이 아니라 브라우저 이동이다.
      //
      // 세션 쿠키가 HttpOnly 라 서버만 읽는다. 로그인한 사실을 화면에 반영하려면
      // 서버 컴포넌트가 전부 다시 그려져야 하고, 그걸 확실히 하는 것이 통짜 이동이다.
      //
      // `router.refresh()` 와 `router.replace()` 를 이어서 부르던 것을 걷어냈다 —
      // 둘이 같은 전환 안에서 부딪혀서 **이동이 아예 안 일어났다.** 로그인은 200 인데
      // 화면은 그대로고 버튼만 「확인하는 중」에 멈춰 있었다.
      //
      // replace 와 같은 자리다. 이력을 안 쌓아서 뒤로 가기가 로그인 화면으로 안 돌아온다.
      window.location.replace("/");
    } catch (thrown) {
      setError(messageOf(thrown));
      setPending(false);
    }
  }

  return (
    <form action={submit} className="grid gap-5">
      {/*
        `invalid` 를 안 넘긴다. 로그인 실패는 **어느 칸이 틀렸는지 서버가 안 알려주는 오류**라
        칸을 지목할 근거가 없다(`D20` 「모르는 칸을 지목하지 않는다」).

        두 칸에 다 걸었더니 화면낭독기가 **맞은 이메일까지 「잘못된 입력」이라고 읽었다.**
        `aria-invalid` 의 뜻은 「이 칸의 값이 유효하지 않다」고, 그건 여기서 사실이 아니다.
      */}
      <Field name="email" type="email" label="이메일" autoComplete="email"
              defaultValue={TEST_ACCOUNT?.email} />

      <Field name="password" type="password" label="비밀번호" autoComplete="current-password"
              defaultValue={TEST_ACCOUNT?.password} />

      {/*
        폼 전체 오류를 입력칸 아래, 버튼 위에 둔다. 위쪽에 두면 스크롤한 화면에서 안 보이고,
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

