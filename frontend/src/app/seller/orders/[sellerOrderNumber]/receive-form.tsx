"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { ApiError, api } from "@/lib/api";

/**
 * 돌아온 물건을 받았다고 적는다(`43a-5`).
 *
 * <p><b>검수 소견은 고르는 칸이다</b>(2026-09-23 결정). 적으면 한 번에 검수까지 마친 것으로 남고, 비우면 입고만 남는다 —
 * 검수를 따로 세우지 않아서 화면이 하나 덜 는다. 소견은 훼손 책임을 가릴 때 근거가 된다(전자상거래법 제17조제5항, `D2` R37).
 *
 * <p>이 폼은 서버가 반품 진행에 {@code RECEIVE} 를 실은 묶음에만 그려진다 — 부르는 쪽이 가른다.
 */
export function ReceiveForm({ sellerOrderNumber }: { sellerOrderNumber: string }) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const [note, setNote] = useState("");
  const [failure, setFailure] = useState<string | null>(null);

  const submit = () => {
    setFailure(null);
    const inspectionNote = note.trim();
    startTransition(async () => {
      try {
        await api(`/api/returns/${encodeURIComponent(sellerOrderNumber)}/receive`, {
          method: "POST",
          body: inspectionNote === "" ? {} : { inspectionNote },
        });
        router.refresh();
      } catch (error) {
        setFailure(
          error instanceof ApiError && error.status === 409
            ? "이미 입고를 적은 반품입니다. 화면을 새로 고쳐 주세요."
            : "처리하지 못했습니다. 잠시 후 다시 시도해 주세요.",
        );
      }
    });
  };

  return (
    <form
      className="grid gap-3 rounded-ui border border-border bg-surface-raised p-4 text-sm"
      onSubmit={(event) => {
        event.preventDefault();
        submit();
      }}
    >
      <h2 className="font-semibold">반품 입고</h2>
      <div className="grid gap-1">
        <label htmlFor="receive-note">검수 소견 (선택)</label>
        {/* 상한을 상수로 안 뺀다 — `ScreenLengthTest` 가 이 숫자를 서버 `@Size` 와 맞댄다 */}
        <textarea
          id="receive-note"
          name="inspectionNote"
          maxLength={500}
          rows={3}
          value={note}
          onChange={(event) => setNote(event.target.value)}
          aria-describedby="receive-note-hint"
          className="rounded-ui border border-border px-2 py-1"
        />
        <p id="receive-note-hint" className="text-text-muted">
          적으면 검수까지 마친 것으로 기록합니다.<br />
          물건 상태를 적어 두면 훼손 책임을 가릴 때 근거가 됩니다.
        </p>
      </div>
      <div>
        <button
          type="submit"
          disabled={pending}
          className="rounded-ui border border-border px-4 py-2 font-medium disabled:opacity-50"
        >
          입고 확인
        </button>
      </div>
      <p role="alert" className="text-danger-text">
        {failure}
      </p>
    </form>
  );
}
