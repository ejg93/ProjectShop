"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { api } from "@/lib/api";

/**
 * 쿠폰을 내린다(`Q186`).
 *
 * <p><b>받은 사람도 더는 못 쓴다</b> — 서버가 내린 쿠폰의 발급을 전부 거른다(`CouponService.withdraw`). 잘못 만든
 * 쿠폰을 멈추는 동작이라 그게 맞지만, 누르는 사람이 모르면 「발급만 닫힌다」로 읽는다. 그래서 묻는 말에 그것을 적는다.
 */
export function WithdrawCouponButton({ couponId, code }: { couponId: number; code: string }) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const [confirming, setConfirming] = useState(false);
  const [failed, setFailed] = useState(false);

  const withdraw = () => {
    setFailed(false);
    startTransition(async () => {
      try {
        await api(`/api/coupons/${couponId}`, { method: "DELETE" });
        router.refresh();
      } catch {
        setFailed(true);
      }
    });
  };

  if (!confirming) {
    return (
      <button type="button" onClick={() => setConfirming(true)}
              className="rounded-ui border border-border px-2 py-0.5 text-xs">
        내리기<span className="sr-only"> — {code}</span>
      </button>
    );
  }

  return (
    <div className="grid gap-1 text-xs">
      <span>이미 받은 분도 더는 못 씁니다. 내리시겠습니까?</span>
      <div className="flex gap-2">
        <button type="button" disabled={pending} onClick={withdraw}
                className="rounded-ui border border-danger-text px-2 py-0.5 text-danger-text disabled:opacity-50">
          내리기
        </button>
        <button type="button" onClick={() => setConfirming(false)} className="px-2 py-0.5 text-text-muted">
          그만두기
        </button>
      </div>
      {failed ? <span role="alert">내리지 못했습니다. 화면을 새로 고쳐 주세요.</span> : null}
    </div>
  );
}
