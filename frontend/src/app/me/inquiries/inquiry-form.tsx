"use client";

import { useRouter } from "next/navigation";
import { useRef, useState, useTransition } from "react";

import { ApiError, api } from "@/lib/api";

/**
 * 계정에 붙는 요구 셋. <b>상품 문의는 여기 없다</b> — 그것은 상품을 보면서 묻는 것이라
 * 상품 상세에 있다.
 *
 * <p>설명을 같이 두는 이유는 <b>사람이 무엇을 고르는지 알아야 해서</b>다(`D20`) —
 * 「처리 정지」라는 말만으로는 그것이 탈퇴와 어떻게 다른지가 안 갈린다.
 */
const KINDS = [
  {
    value: "PROCESSING_STOP",
    label: "처리 정지 요구",
    hint: "제 개인정보를 그만 쓰라고 요구합니다(개인정보 보호법 제37조). 열흘 안에 답변해 드립니다.",
  },
  {
    value: "ACCESS_OBJECTION",
    label: "열람·정정 결과에 대한 이의제기",
    hint: "열람이나 정정 요구의 처리 결과에 이의가 있을 때 쓰십니다(같은 법 제38조제5항).",
  },
  {
    value: "DISPUTE",
    label: "불만·분쟁 접수",
    hint: "거래에서 생긴 불만이나 분쟁을 알려 주십니다(전자상거래법 제20조제3항).",
  },
] as const;

/**
 * 권리 행사 요구를 접수한다(`59-1`).
 *
 * <p><b>이 폼이 있어야 `R28` 이 닫힌다.</b> 개인정보법 제38조제4항이 열람등요구의 방법을
 * <b>수집보다 어렵지 않게</b> 하라고 하는데, 수집은 `/signup` 화면이고 이쪽은 API 뿐이었다.
 *
 * <p><b>보낸 뒤 목록을 손으로 안 고친다.</b> {@code router.refresh()} 로 서버 컴포넌트를
 * 다시 그린다 — 화면이 직접 값을 바꾸면 서버가 아는 것과 갈릴 수 있다(`AccountForms` 와 같다).
 */
export function InquiryForm() {
  const router = useRouter();
  const form = useRef<HTMLFormElement>(null);
  const [sending, setSending] = useState(false);
  const [refreshing, startRefresh] = useTransition();
  const [notice, setNotice] = useState<string | null>(null);
  const [failure, setFailure] = useState<string | null>(null);

  const busy = sending || refreshing;

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setNotice(null);
    setFailure(null);
    setSending(true);

    const data = new FormData(event.currentTarget);
    try {
      await api("/api/inquiries", {
        method: "POST",
        body: {
          kind: String(data.get("kind")),
          question: String(data.get("question")),
        },
      });
      form.current?.reset();
      setNotice("접수했습니다. 아래 목록에서 진행 상황을 보실 수 있습니다.");
      startRefresh(() => router.refresh());
    } catch (error) {
      setFailure(error instanceof ApiError ? error.detail : "보내지 못했습니다.");
    } finally {
      setSending(false);
    }
  }

  return (
    <form ref={form} onSubmit={submit} className="grid gap-4 rounded-ui border border-border bg-surface-raised p-5">
      <fieldset className="grid gap-3">
        {/* 라디오 묶음의 이름은 legend 다. 화면낭독기가 그것으로 묶음을 읽는다(WCAG) */}
        <legend className="text-sm font-semibold">무엇을 요구하시겠습니까?</legend>

        {KINDS.map((kind, index) => (
          <label key={kind.value} className="grid cursor-pointer gap-1">
            <span className="flex items-center gap-2 text-sm font-medium">
              <input
                type="radio"
                name="kind"
                value={kind.value}
                defaultChecked={index === 0}
                className="size-4"
              />
              {kind.label}
            </span>
            <span className="pl-6 text-sm text-text-muted">{kind.hint}</span>
          </label>
        ))}
      </fieldset>

      <label className="grid gap-1.5 text-sm">
        <span className="font-medium">내용</span>
        <textarea
          name="question"
          required
          maxLength={2000}
          rows={5}
          className="
            rounded-ui border border-border bg-surface px-3 py-2 text-sm
            focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent
          "
        />
        <span className="text-text-muted">
          연락처나 주민등록번호 같은 정보는 적지 말아 주십시오. 답변은 이 화면에서 보실 수 있습니다.
        </span>
      </label>

      {/*
        결과를 aria-live 로 알린다. 화면낭독기를 쓰는 사람에게는 화면이 바뀐 것이
        저절로 읽히지 않는다(WCAG, `D20`).
      */}
      <p aria-live="polite" className="text-sm">
        {failure === null ? (
          notice === null ? null : (
            <span className="text-text-muted">{notice}</span>
          )
        ) : (
          <span className="text-danger-text">{failure}</span>
        )}
      </p>

      <button
        type="submit"
        disabled={busy}
        className="
          justify-self-start rounded-ui bg-accent px-4 py-2.5 text-sm font-semibold text-surface
          transition-colors duration-200
          hover:bg-accent-strong
          focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent
          disabled:opacity-60
        "
      >
        {busy ? "보내는 중" : "접수하기"}
      </button>
    </form>
  );
}
