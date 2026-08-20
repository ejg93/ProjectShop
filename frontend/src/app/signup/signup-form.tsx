"use client";

import type { ReactNode } from "react";
import { useState } from "react";

import { Field } from "@/components/field";
import { ApiError, api } from "@/lib/api";

/** 동의받을 항목 하나(`13d-1`). 본문은 여기 없고 서버가 그려서 넘긴다 */
export type ConsentItem = {
  code: string;
  title: string;
  version: number;
  isRequired: boolean;
  purpose: string | null;
  collectedItems: string | null;
  retentionPeriod: string | null;
  refusalDisadvantage: string | null;
  dependsOn: string | null;
};

/** 비밀번호 규칙의 유일한 출처는 서버다(`Password.java`). 여기는 그것을 사람 말로 옮긴 것뿐이다 */
const PASSWORD_HINT = "15자 이상 64자 이하, 영문·숫자·기호를 쓸 수 있습니다.";

/**
 * 회원가입 폼.
 *
 * <p><b>여기만 클라이언트 컴포넌트다</b>(`D24`). 입력을 받고 체크 상태에 반응해야 해서다 —
 * 약관 본문은 서버가 마크다운으로 그려서 {@code bodies} 로 넘긴다.
 *
 * <p><b>화면이 먼저 막고 서버가 다시 막는다</b>(`5-2`). 화면 검사는 편의고 판정이 아니다 —
 * 필수 미동의와 야간 종속은 서버가 422 로 거부한다.
 *
 * @param items 동의받을 항목. <b>순서를 서버가 정했다</b>(`13d-1`) — 필수 먼저, 종속은 부모 뒤
 * @param bodies 항목 코드 → 서버가 그린 본문. 없는 항목은 키가 없다
 */
export function SignupForm({
  items,
  bodies,
}: {
  items: ConsentItem[];
  bodies: Record<string, ReactNode>;
}) {
  const [granted, setGranted] = useState<Record<string, boolean>>({});
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  /**
   * 부모를 끄면 종속도 같이 꺼진다(`D2` R14).
   *
   * <p>서버가 어차기 같이 거두지만 화면이 켜진 채로 두면 <b>보낼 수 없는 동의가 켜져 보인다.</b>
   */
  function toggle(code: string, next: boolean) {
    setGranted((current) => {
      const updated = { ...current, [code]: next };
      if (!next) {
        for (const child of items.filter((item) => item.dependsOn === code)) {
          updated[child.code] = false;
        }
      }
      return updated;
    });
    setError(null);
  }

  const missingRequired = items.filter((item) => item.isRequired && !granted[item.code]);

  async function submit(form: FormData) {
    setPending(true);
    setError(null);

    try {
      await api("/api/auth/signup", {
        method: "POST",
        body: {
          email: form.get("email"),
          password: form.get("password"),
          displayName: form.get("displayName"),
          // 건드린 적 없는 선택 항목도 false 로 보낸다. 서버가 거부(행 있음)와
          // 안 건드림(행 없음)을 가르는데, 가입 화면은 전부를 물었으므로 안 건드린 것이 없다.
          consents: Object.fromEntries(items.map((item) => [item.code, granted[item.code] ?? false])),
        },
      });

      // 가입은 로그인이 아니다. 서버가 세션을 안 만들고 userId 만 준다(`5-2`).
      //
      // 왜 로그인 화면에 왔는지를 `reason` 하나로 말한다(`13e`). 여기만 다른 이름을 쓰던 탓에
      // 로그인 화면이 그 값을 못 알아보고 가입한 사람에게 아무 말도 안 했다.
      window.location.replace("/login?reason=signed-up");
    } catch (thrown) {
      setError(messageOf(thrown));
      setPending(false);
    }
  }

  return (
    <form action={submit} className="grid gap-8">
      <div className="grid gap-5">
        <Field name="email" type="email" label="이메일" autoComplete="email" />
        <Field
          name="password"
          type="password"
          label="비밀번호"
          autoComplete="new-password"
          hint={PASSWORD_HINT}
        />
        <Field name="displayName" type="text" label="이름" autoComplete="name" />
      </div>

      <fieldset className="grid gap-4">
        <legend className="text-sm font-semibold">약관 동의</legend>

        {items.map((item) => (
          <ConsentRow
            key={item.code}
            item={item}
            body={bodies[item.code]}
            checked={granted[item.code] ?? false}
            // 부모에 동의하기 전에는 종속 항목을 켤 수 없다. 숨기지 않고 이유를 적는다 —
            // 숨기면 무엇을 더 켤 수 있는지 알 방법이 없다(`5f` 목록이 같은 판단을 했다).
            blockedBy={
              item.dependsOn && !granted[item.dependsOn]
                ? items.find((parent) => parent.code === item.dependsOn)?.title
                : undefined
            }
            onChange={(next) => toggle(item.code, next)}
          />
        ))}
      </fieldset>

      {/*
        미동의 안내와 서버 오류를 한 자리에 둔다. 입력칸 아래, 버튼 위다 —
        위에 두면 스크롤한 화면에서 안 보인다(`D20`).
      */}
      {error ? (
        <p role="alert" className="text-sm text-danger-text">
          {error}
        </p>
      ) : null}

      {missingRequired.length > 0 ? (
        <p className="text-sm text-text-muted">
          필수 항목에 동의하셔야 가입하실 수 있습니다.
        </p>
      ) : null}

      <button
        type="submit"
        // 필수 미동의면 못 누른다. 누를 수 있게 두고 422 를 받게 하면 왕복이 헛돈다.
        disabled={pending || missingRequired.length > 0}
        className="
          justify-self-start rounded-ui bg-accent px-4 py-2.5 text-sm font-semibold text-accent-on
          transition-[background-color,transform,opacity] duration-200
          hover:bg-accent-hover
          motion-safe:active:translate-y-px
          focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
          disabled:cursor-not-allowed disabled:opacity-60
        "
      >
        {pending ? "가입하는 중" : "가입하기"}
      </button>
    </form>
  );
}

/**
 * 동의 항목 한 줄.
 *
 * <p><b>정형 넷은 접지 않는다.</b> 개인정보법 제15조제2항이 동의받을 때 알리라고 한 것이라
 * 펼쳐야 보이는 자리에 두면 <b>안 읽고 동의한 것</b>이 된다.
 * 접는 것은 약관 본문뿐이고, 그건 길어서다.
 *
 * @param blockedBy 켤 수 없는 이유가 되는 부모 항목의 이름. 켤 수 있으면 undefined
 */
function ConsentRow({
  item,
  body,
  checked,
  blockedBy,
  onChange,
}: {
  item: ConsentItem;
  body: ReactNode;
  checked: boolean;
  blockedBy?: string;
  onChange: (next: boolean) => void;
}) {
  const reasonId = `${item.code}-reason`;

  return (
    <div className="grid gap-2 rounded-ui border border-border bg-surface-raised p-4">
      <div className="flex items-start gap-3">
        <input
          id={item.code}
          type="checkbox"
          checked={checked}
          disabled={blockedBy !== undefined}
          aria-describedby={blockedBy ? reasonId : undefined}
          onChange={(event) => onChange(event.target.checked)}
          className="
            mt-0.5 size-4 shrink-0 accent-accent
            focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
            disabled:opacity-50
          "
        />
        <label
          htmlFor={item.code}
          className={`text-sm ${blockedBy ? "text-text-muted" : ""}`}
        >
          {item.title}{" "}
          {/* 필수 여부를 색이 아니라 글로 말한다(`D20` 「색만으로 알리지 않는다」) */}
          <span className={item.isRequired ? "font-semibold" : "text-text-muted"}>
            ({item.isRequired ? "필수" : "선택"})
          </span>
        </label>
      </div>

      {blockedBy ? (
        <p id={reasonId} className="pl-7 text-xs text-text-muted">
          {blockedBy}에 먼저 동의하셔야 받으실 수 있습니다.
        </p>
      ) : null}

      {/* 개인정보 고지 넷. 동의받는 그 자리에 펼쳐 둔다(개인정보법 제15조제2항) */}
      {item.purpose ? (
        <dl className="grid gap-1 pl-7 text-xs sm:grid-cols-[6rem_1fr]">
          <NoticeRow label="수집 목적" value={item.purpose} />
          <NoticeRow label="수집 항목" value={item.collectedItems} />
          <NoticeRow label="보유 기간" value={item.retentionPeriod} />
          <NoticeRow label="거부 시" value={item.refusalDisadvantage} />
        </dl>
      ) : null}

      {/*
        약관 본문은 접는다. 길어서 펼쳐 두면 아래 칸이 화면 밖으로 밀린다.
        `details` 를 쓰는 이유는 브라우저가 키보드·보조기술 동작을 기본으로 주기 때문이다 —
        직접 만들면 초점 순서와 펼침 상태 알림을 우리가 다 걸어야 한다.
      */}
      {body ? (
        <details className="pl-7">
          <summary
            className="
              cursor-pointer text-xs font-semibold text-accent-text underline underline-offset-4
              focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
            "
          >
            {item.title} 전문 보기 (제{item.version}판)
          </summary>
          <div className="mt-3 grid gap-3 border-l border-border pl-4">{body}</div>
        </details>
      ) : null}
    </div>
  );
}

function NoticeRow({ label, value }: { label: string; value: string | null }) {
  if (!value) {
    return null;
  }

  return (
    <>
      <dt className="text-text-muted">{label}</dt>
      <dd className="text-text">{value}</dd>
    </>
  );
}

/** 서버가 준 오류를 화면 문구로 옮긴다. `type` 으로 갈린다(`D5`·`D20`) */
function messageOf(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return "서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }

  switch (error.type) {
    case "urn:shop:error:email-taken":
      return "이미 가입된 이메일입니다. 로그인해 주세요.";
    case "urn:shop:error:required-consent-missing":
      return "필수 항목에 동의하셔야 가입하실 수 있습니다.";
    case "urn:shop:error:consent-dependency":
      return "야간 수신은 이메일 수신에 동의하셔야 받으실 수 있습니다.";
    case "urn:shop:error:validation-failed":
      // 어느 칸인지는 서버가 필드 이름으로 알려주지만, 그것을 화면 문구로 옮기는 표를
      // 여기 두면 서버가 칸을 바꿀 때 한쪽만 고쳐진다. 규칙을 다시 알리는 쪽을 고른다.
      return `입력하신 내용을 다시 확인해 주세요. 비밀번호는 ${PASSWORD_HINT}`;
    default:
      return "가입하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
}
