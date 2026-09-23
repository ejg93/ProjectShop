"use client";

import { useState, useTransition } from "react";

import { ApiError, api } from "@/lib/api";

/**
 * 저작권 침해 신고서(`Q183`).
 *
 * <p><b>사진 하나를 고른다.</b> 서버가 사진 단위로 받는다 — 판정하면 그 사진이 저장소에서 사라진다(`Q94`).
 */
export function CopyrightReportForm({ images }: { images: { imageId: number; url: string }[] }) {
  const [pending, startTransition] = useTransition();
  const [imageId, setImageId] = useState(images[0].imageId);
  const [receipt, setReceipt] = useState<number | null>(null);
  const [failure, setFailure] = useState<string | null>(null);

  const submit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setFailure(null);
    const data = new FormData(event.currentTarget);
    startTransition(async () => {
      try {
        const received = await api<{ copyrightReportId: number }>(
          `/api/copyright-reports/images/${imageId}`,
          {
            method: "POST",
            body: {
              reporterName: String(data.get("reporterName")),
              reporterEmail: String(data.get("reporterEmail")),
              claimedWork: String(data.get("claimedWork")),
            },
          },
        );
        setReceipt(received.copyrightReportId);
      } catch (error) {
        setFailure(messageOf(error));
      }
    });
  };

  if (receipt !== null) {
    return (
      <p role="status" className="rounded-ui border border-border bg-surface-raised px-4 py-3 text-sm">
        신고를 받았습니다. 접수 번호는 {receipt}입니다.
        <br />
        관리자가 확인한 뒤 처리합니다.
      </p>
    );
  }

  return (
    <form onSubmit={submit} className="grid gap-5 text-sm">
      <fieldset className="grid gap-2">
        <legend className="font-medium">신고할 사진</legend>
        <div className="flex flex-wrap gap-3">
          {images.map((image, index) => (
            <label key={image.imageId} className="grid cursor-pointer gap-1">
              <input
                type="radio"
                name="imageId"
                value={image.imageId}
                checked={imageId === image.imageId}
                onChange={() => setImageId(image.imageId)}
              />
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img
                src={image.url}
                alt={`상품 사진 ${index + 1}`}
                className="h-24 w-24 rounded-ui border border-border object-cover"
              />
            </label>
          ))}
        </div>
      </fieldset>

      <label className="grid gap-1.5">
        <span className="font-medium">신고하시는 분</span>
        {/* 상한을 상수로 안 뺀다 — `ScreenLengthTest` 가 이 숫자를 서버 `@Size` 와 맞댄다 */}
        <input name="reporterName" required maxLength={100} autoComplete="name"
               className="rounded-ui border border-border bg-surface px-3 py-2" />
      </label>
      <label className="grid gap-1.5">
        <span className="font-medium">연락받을 이메일</span>
        <input name="reporterEmail" type="email" required maxLength={320} autoComplete="email"
               className="rounded-ui border border-border bg-surface px-3 py-2" />
      </label>
      <div className="grid gap-1.5">
        <label htmlFor="claimedWork" className="font-medium">권리가 있는 저작물</label>
        {/* 도움말은 이름이 아니라 설명으로 잇는다 — 라벨 안에 두면 칸 이름이 문장 하나가 된다 */}
        <p id="claimedWork-hint" className="text-text-muted">어떤 저작물이고 어디에 먼저 공개했는지를 적어 주세요.</p>
        <textarea id="claimedWork" name="claimedWork" required maxLength={2000} rows={4}
                  aria-describedby="claimedWork-hint"
                  className="rounded-ui border border-border bg-surface px-3 py-2" />
      </div>

      <p role="alert" className="text-danger-text">
        {failure}
      </p>
      <button
        type="submit"
        disabled={pending}
        className="justify-self-start rounded-ui bg-accent px-4 py-2 font-semibold text-surface disabled:opacity-60"
      >
        {pending ? "보내는 중" : "신고하기"}
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
    case "product-not-found":
      return "이미 내려간 사진입니다.";
    case "validation-failed":
      return "입력하신 내용을 다시 확인해 주세요.";
    case "too-many-requests":
      return "요청이 너무 잦습니다. 잠시 후 다시 시도해 주세요.";
    default:
      return "신고를 보내지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
}
