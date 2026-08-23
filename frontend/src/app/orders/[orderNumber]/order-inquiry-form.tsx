"use client";

import { useRouter } from "next/navigation";
import { useRef, useState, useTransition } from "react";

import { ApiError, api } from "@/lib/api";

/**
 * 이 주문에 대해 묻는다(`58-2`).
 *
 * <p><b>불만·분쟁 접수와 다른 자리다.</b> 분쟁 접수는 전자상거래법 시행령 제6조 4호의
 * <b>불만·분쟁처리 기록</b>(3년)이라, 「이 주문 언제 와요?」까지 거기 쌓이면
 * <b>「분쟁이 몇 건이었나」에 답을 못 한다</b> — 그래서 종류를 갈랐다.
 *
 * <p><b>공개가 성립하지 않는다.</b> 남의 주문을 남이 보면 안 되고, 그 판단은 화면이 아니라
 * `inquiry_visibility_check` 가 든다 — 여기서 무엇을 보내든 비공개가 된다.
 */
export function OrderInquiryForm({ sellerOrderNumber }: { sellerOrderNumber: string }) {
  const router = useRouter();
  const form = useRef<HTMLFormElement>(null);
  const [open, setOpen] = useState(false);
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
          kind: "ORDER",
          sellerOrderNumber,
          question: String(data.get("question")),
        },
      });
      form.current?.reset();
      setOpen(false);
      setNotice("문의를 보냈습니다. 내 문의에서 진행 상황을 보실 수 있습니다.");
      startRefresh(() => router.refresh());
    } catch (error) {
      setFailure(error instanceof ApiError ? error.detail : "보내지 못했습니다.");
    } finally {
      setSending(false);
    }
  }

  return (
    <div className="grid gap-2">
      {open ? null : (
        <button
          type="button"
          onClick={() => setOpen(true)}
          className="
            justify-self-start rounded-ui border border-border px-3 py-2 text-sm font-medium
            transition-colors duration-200
            hover:bg-surface
            focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent
          "
        >
          이 주문 문의하기
        </button>
      )}

      {open ? (
        <form ref={form} onSubmit={submit} className="grid gap-2">
          <label className="grid gap-1.5 text-sm">
            <span className="font-medium">판매자에게 물어보기</span>
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
              이 문의는 판매자와 저희만 봅니다. 다른 분께는 보이지 않습니다.
            </span>
          </label>

          <div className="flex gap-2">
            <button
              type="submit"
              disabled={busy}
              className="
                rounded-ui bg-accent px-4 py-2 text-sm font-semibold text-surface
                transition-colors duration-200
                hover:bg-accent-strong
                focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent
                disabled:opacity-60
              "
            >
              {busy ? "보내는 중" : "보내기"}
            </button>
            <button
              type="button"
              onClick={() => setOpen(false)}
              className="
                rounded-ui border border-border px-4 py-2 text-sm font-medium
                transition-colors duration-200
                hover:bg-surface
                focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent
              "
            >
              닫기
            </button>
          </div>
        </form>
      ) : null}

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
    </div>
  );
}
