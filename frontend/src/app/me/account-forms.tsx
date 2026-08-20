"use client";

import { useRouter } from "next/navigation";
import { useRef, useState, useTransition } from "react";

import { Field } from "@/components/field";
import { ApiError, api } from "@/lib/api";

/** 비밀번호 규칙의 유일한 출처는 서버다(`Password.java`). 여기는 그것을 사람 말로 옮긴 것뿐이다 */
const PASSWORD_HINT = "15자 이상 64자 이하, 영문·숫자·기호를 쓸 수 있습니다.";

/**
 * 계정에서 고칠 수 있는 것 둘. <b>이름과 비밀번호는 서버에서도 입구가 다르다</b>(`5e`).
 *
 * <p>한 폼으로 합치지 않는 이유가 그것이다 — 이름만 바꾸는 요청에 비밀번호 칸이 딸려 다니면
 * 브라우저 비밀번호 관리자가 매번 끼어들고, 서버는 두 요청으로 받는다.
 *
 * <p><b>고친 뒤에 목록을 손으로 안 고친다.</b> {@code router.refresh()} 로 서버 컴포넌트를
 * 다시 그린다 — 화면이 직접 값을 바꾸면 서버가 아는 것과 갈릴 수 있다.
 *
 * @param displayName 지금 이름. <b>볼 수 없으면 undefined 고, 그때는 이름 폼을 안 그린다</b>(`D20`) —
 *                    지금 값을 모르는 채로 고치게 하면 무엇을 덮어쓰는지 모르고 누르게 된다
 */
export function AccountForms({ displayName }: { displayName?: string }) {
  return (
    <div className="grid gap-6">
      {displayName === undefined ? null : <NameForm displayName={displayName} />}
      <PasswordForm />
    </div>
  );
}

function NameForm({ displayName }: { displayName: string }) {
  const router = useRouter();
  const [sending, setSending] = useState(false);
  const [refreshing, startRefresh] = useTransition();
  const [notice, setNotice] = useState<string | null>(null);
  const [failure, setFailure] = useState<string | null>(null);

  async function submit(form: FormData) {
    setSending(true);
    setNotice(null);
    setFailure(null);

    try {
      await api("/api/me", { method: "PATCH", body: { displayName: form.get("displayName") } });
      setNotice("이름을 바꿨습니다.");
      startRefresh(() => router.refresh());
    } catch (thrown) {
      setFailure(messageOf(thrown));
    } finally {
      setSending(false);
    }
  }

  return (
    <form action={submit} className="grid gap-4 rounded-ui border border-border p-5">
      <h3 className="text-sm font-semibold">이름 바꾸기</h3>

      <Field
        name="displayName"
        type="text"
        label="이름"
        autoComplete="name"
        defaultValue={displayName}
        maxLength={50}
      />

      <Result notice={notice} failure={failure} />

      <SubmitButton pending={sending || refreshing} label="이름 바꾸기" pendingLabel="바꾸는 중" />
    </form>
  );
}

/**
 * 비밀번호 바꾸기.
 *
 * <p><b>현재 비밀번호를 같이 받는다</b>(`5e`). 세션을 훔친 사람이 비밀번호까지 바꾸면
 * 주인이 계정을 영영 잃는다. 서버가 같은 판단을 이미 하고 있고 화면은 그 칸을 낼 뿐이다.
 *
 * <p><b>성공하면 칸을 비운다.</b> 안 비우면 다음 사람이 그 자리에 앉았을 때
 * 방금 넣은 비밀번호가 그대로 남아 있다.
 */
function PasswordForm() {
  const form = useRef<HTMLFormElement>(null);
  const [sending, setSending] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [failure, setFailure] = useState<string | null>(null);

  async function submit(data: FormData) {
    setSending(true);
    setNotice(null);
    setFailure(null);

    try {
      await api("/api/me/password", {
        method: "POST",
        body: {
          currentPassword: data.get("currentPassword"),
          newPassword: data.get("newPassword"),
        },
      });

      form.current?.reset();
      setNotice("비밀번호를 바꿨습니다. 다른 기기의 로그인은 그대로 유지됩니다.");
    } catch (thrown) {
      setFailure(messageOf(thrown));
    } finally {
      setSending(false);
    }
  }

  return (
    <form ref={form} action={submit} className="grid gap-4 rounded-ui border border-border p-5">
      <h3 className="text-sm font-semibold">비밀번호 바꾸기</h3>

      <Field
        name="currentPassword"
        type="password"
        label="현재 비밀번호"
        autoComplete="current-password"
      />
      <Field
        name="newPassword"
        type="password"
        label="새 비밀번호"
        autoComplete="new-password"
        hint={PASSWORD_HINT}
      />

      <Result notice={notice} failure={failure} />

      <SubmitButton pending={sending} label="비밀번호 바꾸기" pendingLabel="바꾸는 중" />
    </form>
  );
}

/**
 * 결과를 소리로도 전한다(`D20` 「동적으로 바뀌는 것」).
 *
 * <p>칸 아래·버튼 위다. 위에 두면 스크롤한 화면에서 안 보이고, 여기 두면 방금 누른 자리 바로 옆이다.
 */
function Result({ notice, failure }: { notice: string | null; failure: string | null }) {
  return (
    <>
      <p role="status" className="text-xs text-text-muted">
        {notice}
      </p>
      <p role="alert" className="text-xs text-danger-text">
        {failure}
      </p>
    </>
  );
}

function SubmitButton({
  pending,
  label,
  pendingLabel,
}: {
  pending: boolean;
  label: string;
  pendingLabel: string;
}) {
  return (
    <button
      type="submit"
      disabled={pending}
      className="
        justify-self-start rounded-ui bg-accent px-4 py-2.5 text-sm font-semibold text-accent-on
        transition-[background-color,transform,opacity] duration-200
        hover:bg-accent-hover
        motion-safe:active:translate-y-px
        focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
        disabled:cursor-not-allowed disabled:opacity-60
      "
    >
      {pending ? pendingLabel : label}
    </button>
  );
}

/** 서버가 준 오류를 화면 문구로. <b>`slug` 로 갈린다</b>(`D5`·`D20`) */
function messageOf(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return "서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }

  switch (error.slug) {
    case "password-mismatch":
      return "현재 비밀번호가 맞지 않습니다.";
    case "account-forbidden":
      return "이 계정을 고치실 권한이 없습니다.";
    case "validation-failed":
      // 어느 칸인지는 서버가 알려주지만, 그것을 화면 문구로 옮기는 표를 여기 두면
      // 서버가 칸을 바꿀 때 한쪽만 고쳐진다. 규칙을 다시 알리는 쪽을 고른다(가입 화면과 같다).
      return `입력하신 내용을 다시 확인해 주세요. 비밀번호는 ${PASSWORD_HINT}`;
    default:
      return "바꾸지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
}
