"use client";

import { useRouter } from "next/navigation";
import { useRef, useState, useTransition } from "react";

import { ApiError, api } from "@/lib/api";

/**
 * 상품에 묻는다(`59-1`).
 *
 * <p><b>공개 여부를 여기서 고른다.</b> 기본은 공개다 — 상품 Q&A 는 남이 읽는 것이 쓸모의
 * 절반이고, 숨길 이유가 있는 분이 끄는 쪽이 맞다. <b>서버의 DB 기본값은 반대로 비공개</b>인데
 * (`V54`) 그것은 빠뜨렸을 때 새지 않고 안 보이는 쪽으로 떨어지게 한 것이다.
 *
 * <p><b>로그인이 없으면 로그인으로 보낸다.</b> 401 을 {@code api} 가 그렇게 다룬다(`D24`) —
 * 여기서 미리 막지 않는 이유는 <b>로그인 상태를 클라이언트가 다시 세면 서버와 갈릴 수 있어서</b>다.
 */
export function AskForm({ productId }: { productId: number }) {
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
          kind: "PRODUCT",
          productId,
          question: String(data.get("question")),
          isPublic: data.get("isPublic") !== null,
        },
      });
      form.current?.reset();
      setNotice("문의를 보냈습니다. 답변이 등록되면 내 문의에서 보실 수 있습니다.");
      startRefresh(() => router.refresh());
    } catch (error) {
      setFailure(error instanceof ApiError ? error.detail : "보내지 못했습니다.");
    } finally {
      setSending(false);
    }
  }

  return (
    <form
      ref={form}
      onSubmit={submit}
      className="grid gap-3 rounded-ui border border-border bg-surface-raised p-4"
    >
      <label className="grid gap-1.5 text-sm">
        <span className="font-medium">이 상품에 대해 물어보기</span>
        <textarea
          name="question"
          required
          maxLength={2000}
          rows={3}
          className="
            rounded-ui border border-border bg-surface px-3 py-2 text-sm
            focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent
          "
        />
        <span className="text-text-muted">
          연락처나 주소 같은 정보는 적지 말아 주십시오. 공개 문의는 다른 분께도 보입니다.
        </span>
      </label>

      <label className="flex items-center gap-2 text-sm">
        <input type="checkbox" name="isPublic" defaultChecked className="size-4" />
        다른 분께도 보이게 합니다
      </label>

      {/* 결과를 aria-live 로 알린다 — 화면이 바뀐 것이 저절로 읽히지 않는다(WCAG, `D20`) */}
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
          justify-self-start rounded-ui border border-border px-4 py-2 text-sm font-semibold
          transition-colors duration-200
          hover:bg-surface
          focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent
          disabled:opacity-60
        "
      >
        {busy ? "보내는 중" : "문의 보내기"}
      </button>
    </form>
  );
}
