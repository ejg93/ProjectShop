"use client";

import { useState } from "react";

import { Field } from "@/components/field";
import { SubmitButton } from "@/components/submit-button";
import { ApiError, api } from "@/lib/api";
import { totpCode } from "@/lib/totp";

import { DemoAccountsPanel } from "./demo-accounts-panel";
import {
  DEMO_PASSWORD,
  DEMO_SECOND_FACTOR_SECRET,
  type DemoAccount,
  needsSecondFactor,
} from "./demo-accounts";

/**
 * 로그인 응답. `step` 이 다음 단계를 고른다(`Q454` — 서버 `Q452`).
 *
 * <p>셀러·관리자·감사자는 비밀번호만으로 세션이 안 선다 — `CODE_REQUIRED` 면 코드를, `ENROLLMENT_REQUIRED` 면
 * 등록 뒤 첫 코드를 낸다(안전성 확보조치 기준 제6조②). 무엇을 할 수 있는지는 여기 안 온다(청크 8a).
 */
type LoginResponse = {
  userId: number;
  email: string;
  step: "DONE" | "CODE_REQUIRED" | "ENROLLMENT_REQUIRED";
};

/** 2차 인증 등록 — 인증 앱에 손으로 칠 키와 앱이 여는 주소 */
type Enrollment = {
  secret: string;
  otpauthUri: string;
};

/** 화면의 단계. 비밀번호 → (코드 | 등록 뒤 코드) */
type Step = "password" | "code" | "enrollment";

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

/** 서버가 준 오류를 화면 문구로 옮긴다. `slug` 로 갈린다(`D5`) */
function messageOf(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return "서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }

  switch (error.slug) {
    case "login-failed":
      // 없는 계정인지 틀린 비밀번호인지 가르지 않는다. 서버가 이미 한 문구로 내려주고,
      // 화면이 그걸 나누면 가입 여부를 물어보는 도구가 된다(`D14`).
      return "이메일 또는 비밀번호가 맞지 않습니다.";
    case "second-factor-code-invalid":
      return "인증 코드가 맞지 않습니다.";
    case "second-factor-not-pending":
      // 5분이 지났거나 세션이 바뀌었다. 코드 칸에서 다시 쳐도 안 되므로 비밀번호 단계로 돌린다.
      return "로그인을 처음부터 다시 해 주세요.";
    default:
      // 이름으로 못 가른 것은 이 화면의 문구다. 로그인은 이름마다 안내가 달라 슬러그로 가른다(`D20` 「서버 문구는 `message` 만 그린다」).
      return "로그인하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
}

export function LoginForm() {
  const [error, setError] = useState<string | null>(null);

  /**
   * 안내에서 고른 계정(`Q131`). 고르면 두 칸의 기본값이 바뀐다.
   *
   * <p><b>칸을 제어 요소로 안 바꾼다.</b> `Field` 는 네 화면이 같이 쓰는데, 값과 변경 처리를
   * 밖으로 빼면 그 넷이 다 상태를 들게 된다. 대신 `key` 를 바꿔 입력을 다시 그린다 —
   * 「고를 때만 값이 바뀐다」가 이 화면에서 필요한 전부다.
   */
  const [picked, setPicked] = useState<DemoAccount | null>(null);

  /** 비밀번호를 지난 뒤의 단계. 코드 칸은 제어 요소다 — 「코드 채우기」가 값을 넣는다 */
  const [step, setStep] = useState<Step>("password");
  const [code, setCode] = useState("");
  const [enrollment, setEnrollment] = useState<Enrollment | null>(null);

  /**
   * 통째로 다시 받는다. 클라이언트 이동(router)이 아니라 브라우저 이동이다.
   *
   * <p>세션 쿠키가 HttpOnly 라 서버만 읽는다. 로그인한 사실을 화면에 반영하려면 서버 컴포넌트가 전부 다시 그려져야 하고,
   * 그걸 확실히 하는 것이 통짜 이동이다. `router.refresh()` 와 `router.replace()` 를 이어서 부르던 것을 걷어냈다 —
   * 둘이 같은 전환 안에서 부딪혀서 **이동이 아예 안 일어났다.** replace 라 뒤로 가기가 로그인 화면으로 안 돌아온다.
   */
  function enter() {
    window.location.replace("/");
  }

  // **제출 중인지를 여기서 안 든다**(`Q20-1`). `SubmitButton` 이 `useFormStatus` 로 읽는다 —
  // 폼 `action` 은 전환 안에서 돌아서 여기서 `setState` 를 해도 액션이 끝날 때까지 안 비친다.
  async function submit(form: FormData) {
    setError(null);

    try {
      if (step !== "password") {
        await api<LoginResponse>("/api/auth/login/second-factor", {
          method: "POST",
          body: { code },
        });
        enter();
        return;
      }

      const login = await api<LoginResponse>("/api/auth/login", {
        method: "POST",
        body: {
          email: form.get("email"),
          password: form.get("password"),
        },
      });

      if (login.step === "CODE_REQUIRED") {
        setStep("code");
        return;
      }
      if (login.step === "ENROLLMENT_REQUIRED") {
        setEnrollment(await api<Enrollment>("/api/auth/login/second-factor/enrollment", { method: "POST" }));
        setStep("enrollment");
        return;
      }
      enter();
    } catch (thrown) {
      if (thrown instanceof ApiError && thrown.slug === "second-factor-not-pending") {
        setStep("password");
        setCode("");
        setEnrollment(null);
      }
      setError(messageOf(thrown));
    }
  }

  /** 공개된 연습 계정 비밀로 지금 코드를 칸에 넣는다. 운영 계정의 비밀은 화면에 없다 */
  async function fillDemoCode() {
    setCode(await totpCode(DEMO_SECOND_FACTOR_SECRET, new Date()));
  }

  return (
    <div className="grid gap-6">
      {/*
        폼 위에 둔다. 아래에 두면 「계정이 없다」고 판단한 사람이 이미 회원가입으로 간 뒤다.
      */}
      <DemoAccountsPanel onPick={setPicked} />

      <form action={submit} className="grid gap-5">
        {step === "password" ? (
          <>
          {/*
            `invalid` 를 안 넘긴다. 로그인 실패는 **어느 칸이 틀렸는지 서버가 안 알려주는 오류**라
            칸을 지목할 근거가 없다(`D20` 「모르는 칸을 지목하지 않는다」).

            두 칸에 다 걸었더니 화면낭독기가 **맞은 이메일까지 「잘못된 입력」이라고 읽었다.**
            `aria-invalid` 의 뜻은 「이 칸의 값이 유효하지 않다」고, 그건 여기서 사실이 아니다.
          */}
          <Field key={`email-${picked?.email ?? ""}`}
                  name="email" type="email" label="이메일" autoComplete="email"
                  defaultValue={picked?.email ?? TEST_ACCOUNT?.email} maxLength={254} />

          <Field key={`password-${picked?.email ?? ""}`}
                  name="password" type="password" label="비밀번호" autoComplete="current-password"
                  defaultValue={picked ? DEMO_PASSWORD : TEST_ACCOUNT?.password} />
          </>
        ) : (
          <SecondFactorStep
            enrollment={step === "enrollment" ? enrollment : null}
            code={code}
            onCode={setCode}
            onFill={picked && needsSecondFactor(picked) ? fillDemoCode : null}
          />
        )}

        {/*
          폼 전체 오류를 입력칸 아래, 버튼 위에 둔다. 위쪽에 두면 스크롤한 화면에서 안 보이고,
          role=alert 라 화면낭독기가 나타나는 순간 읽는다(`D20`).
        */}
        {error && (
          <p role="alert" className="text-sm text-danger-text">
            {error}
          </p>
        )}

        <SubmitButton label={step === "password" ? "로그인" : "확인"} pendingLabel="확인하는 중" />
      </form>
    </div>
  );
}

/**
 * 비밀번호 뒤의 단계 — 등록 안내(있으면)와 코드 칸(`Q454`).
 *
 * <p><b>코드 칸은 `Field` 를 안 쓴다.</b> 「코드 채우기」가 값을 넣어야 해서 제어 요소고, 숫자 자판(`inputMode`)과
 * 한 번 쓰는 코드 자동 채움(`autoComplete="one-time-code"`)이 필요하다 — `Field` 의 네 화면은 그 둘이 필요 없다.
 */
function SecondFactorStep({
  enrollment,
  code,
  onCode,
  onFill,
}: {
  enrollment: Enrollment | null;
  code: string;
  onCode: (code: string) => void;
  onFill: (() => void) | null;
}) {
  return (
    <div className="grid gap-4">
      {enrollment ? (
        <div className="grid gap-2 text-sm">
          <p>
            판매자·관리자 계정은 2차 인증이 필요합니다. 인증 앱(Google Authenticator 등)에 아래 키를 넣어 주세요.
            <br />
            넣은 뒤 앱이 보여 주는 여섯 자리 코드를 입력하시면 등록이 끝납니다.
          </p>
          <code className="break-all rounded-ui bg-surface px-3 py-2 font-mono">{enrollment.secret}</code>
          <a
            href={enrollment.otpauthUri}
            className="
              underline
              focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
            "
          >
            인증 앱에서 열기
          </a>
        </div>
      ) : (
        <p className="text-sm">인증 앱에 보이는 여섯 자리 코드를 입력해 주세요.</p>
      )}

      <div className="grid gap-2">
        <label htmlFor="secondFactorCode" className="text-sm font-semibold">
          인증 코드
        </label>
        <input
          id="secondFactorCode"
          name="secondFactorCode"
          type="text"
          inputMode="numeric"
          autoComplete="one-time-code"
          pattern="[0-9]{6}"
          maxLength={6}
          required
          value={code}
          onChange={(event) => onCode(event.target.value)}
          className="
            rounded-ui border border-border bg-surface-raised px-3 py-2.5 text-base
            transition-colors duration-200
            focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
          "
        />
      </div>

      {onFill ? (
        <button
          type="button"
          onClick={onFill}
          className="
            justify-self-start rounded-ui border border-border px-3 py-1.5 text-sm
            transition-colors duration-200 hover:bg-surface
            focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
          "
        >
          연습 계정 코드 채우기
        </button>
      ) : null}
    </div>
  );
}
