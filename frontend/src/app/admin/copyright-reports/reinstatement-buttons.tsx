"use client";

import { useRouter } from "next/navigation";
import { useId, useState, useTransition } from "react";

import { ApiError, api } from "@/lib/api";

/** 재개 요구 하나(`CopyrightReportQuery.Reinstatement`) */
export type Reinstatement = {
  requestedAt: string;
  decision: "SCHEDULED" | "REFUSED" | null;
  scheduledFor: string | null;
  suitNotifiedAt: string | null;
  reinstatedAt: string | null;
};

/** 예정일 범위 — 요구받은 날의 7일 뒤부터 14일 뒤까지(저작권법 시행령 제43조). 서버 제약이 들고 화면은 안내한다 */
const SCHEDULE_MIN_DAYS = 7;
const SCHEDULE_MAX_DAYS = 14;

/** 한국 날짜 `yyyy-mm-dd` 에 날을 더한다 */
function kstDatePlus(iso: string, days: number): string {
  const shifted = new Date(new Date(iso).getTime() + days * 86_400_000);
  return shifted.toLocaleDateString("sv-SE", { timeZone: "Asia/Seoul" });
}

/** `datetime-local` 값은 한국 시각이다 — 서버에 오프셋을 붙여 보낸다 */
function kstInstant(local: string): string {
  return `${local}:00+09:00`;
}

/** 서버 오류를 화면 문구로. `slug` 로 갈린다(`D5`) */
function messageOf(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.slug) {
      case "copyright-reinstatement-late":
        return "통보 뒤 30일이 지나 재개 요구를 받을 수 없습니다.";
      case "copyright-reinstatement-window":
        return "재개 예정일은 요구를 받은 날의 7일 뒤부터 14일 뒤 사이여야 합니다.";
      case "copyright-reinstatement-exists":
        return "이미 재개 요구를 기록했습니다.";
      case "copyright-not-taken-down":
        return "게시가 중단된 사진이 아니어서 재개 요구를 받을 수 없습니다.";
      default:
        break;
    }
  }
  return "처리하지 못했습니다. 화면을 새로 고쳐 주세요.";
}

/**
 * 게시를 중단한 신고의 재개 절차 버튼(`Q467`, 저작권법 제103조③ · 시행령 제42·43조).
 *
 * <p>단계마다 하나다 — 요구가 없으면 「재개 요구 접수」, 판단 전이면 「재개 예정일 정하기」·「거절」, 예정이면
 * 「소 제기 통보 받음」. 수령인이 받은 요구를 관리자가 기록하는 자리다 — 복제·전송자가 직접 누르는 입구는 없다.
 */
export function ReinstatementButtons({ reportId, reinstatement }: { reportId: number; reinstatement: Reinstatement | null }) {
  const router = useRouter();
  const ids = useId();
  const [pending, startTransition] = useTransition();
  const [error, setError] = useState<string | null>(null);
  const [claim, setClaim] = useState("");
  const [scheduledFor, setScheduledFor] = useState("");
  // 수령인이 받은 시각 — 기록하는 시각과 다를 수 있다. 30일 기한과 예정일 창이 이 값을 기준으로 잰다(시행령 제42·43조).
  const [receivedAt, setReceivedAt] = useState("");
  const base = `/api/copyright-reports/${reportId}/reinstatement`;

  const call = (path: string, body: Record<string, string>) => {
    setError(null);
    startTransition(async () => {
      try {
        await api(path, { method: "POST", body });
        router.refresh();
      } catch (thrown) {
        setError(messageOf(thrown));
      }
    });
  };

  const button = "justify-self-start rounded-ui border border-border px-3 py-1 text-xs disabled:opacity-50";

  if (reinstatement === null) {
    return (
      <div className="grid gap-2 text-xs">
        <label htmlFor={`${ids}-claim`} className="font-semibold">소명</label>
        <textarea
          id={`${ids}-claim`}
          value={claim}
          maxLength={2000}
          rows={3}
          onChange={(event) => setClaim(event.target.value)}
          className="rounded-ui border border-border bg-surface px-2 py-1"
        />
        <label htmlFor={`${ids}-received`} className="font-semibold">요구받은 시각</label>
        <input
          id={`${ids}-received`}
          type="datetime-local"
          value={receivedAt}
          onChange={(event) => setReceivedAt(event.target.value)}
          className="justify-self-start rounded-ui border border-border bg-surface px-2 py-1"
        />
        <button
          type="button"
          disabled={pending || claim.trim() === "" || receivedAt === ""}
          onClick={() => call(base, { requestedAt: kstInstant(receivedAt), requesterClaim: claim })}
          className={button}
        >
          재개 요구 접수
        </button>
        <p role="alert" className="text-danger-text">{error}</p>
      </div>
    );
  }

  if (reinstatement.decision === null) {
    return (
      <div className="grid gap-2 text-xs">
        <label htmlFor={`${ids}-date`} className="font-semibold">재개 예정일</label>
        <input
          id={`${ids}-date`}
          type="date"
          value={scheduledFor}
          min={kstDatePlus(reinstatement.requestedAt, SCHEDULE_MIN_DAYS)}
          max={kstDatePlus(reinstatement.requestedAt, SCHEDULE_MAX_DAYS)}
          onChange={(event) => setScheduledFor(event.target.value)}
          className="justify-self-start rounded-ui border border-border bg-surface px-2 py-1"
        />
        <div className="flex gap-2">
          <button
            type="button"
            disabled={pending || scheduledFor === ""}
            onClick={() => call(`${base}/decision`, { decision: "SCHEDULED", scheduledFor })}
            className={button}
          >
            재개 예정일 정하기
          </button>
          <button type="button" disabled={pending} onClick={() => call(`${base}/decision`, { decision: "REFUSED" })} className={button}>
            거절
          </button>
        </div>
        <p role="alert" className="text-danger-text">{error}</p>
      </div>
    );
  }

  if (reinstatement.decision === "SCHEDULED" && reinstatement.reinstatedAt === null && reinstatement.suitNotifiedAt === null) {
    return (
      <div className="grid gap-2 text-xs">
        <label htmlFor={`${ids}-suit`} className="font-semibold">소 제기 통보를 받은 시각</label>
        <input
          id={`${ids}-suit`}
          type="datetime-local"
          value={receivedAt}
          onChange={(event) => setReceivedAt(event.target.value)}
          className="justify-self-start rounded-ui border border-border bg-surface px-2 py-1"
        />
        <button
          type="button"
          disabled={pending || receivedAt === ""}
          onClick={() => call(`${base}/suit-notice`, { notifiedAt: kstInstant(receivedAt) })}
          className={button}
        >
          소 제기 통보 받음
        </button>
        <p role="alert" className="text-danger-text">{error}</p>
      </div>
    );
  }
  return null;
}
