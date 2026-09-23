"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { ApiError, api } from "@/lib/api";
import { CARRIERS } from "@/lib/order-text";

/**
 * 송장을 적고 발송한다(`57`).
 *
 * <p><b>공용 동작 버튼({@code OrderActions})에서 뺐다</b> — 그 버튼은 본문 없이 부르는데 발송은 택배사와 송장 번호가 필수다
 * ({@code ShipmentController.ShipRequest}). 버튼 하나로 두면 송장 없는 발송이 400 으로 떨어진다.
 *
 * <p><b>하이픈을 그대로 받는다</b> — 택배사 화면의 번호를 붙여 넣는 자리라서다. 떼는 것은 서버다.
 *
 * <p>이 폼은 서버가 {@code SHIP} 을 허용한 묶음에만 그려진다 — 부르는 쪽이 {@code allowedActions} 로 가른다.
 */
export function ShipForm({ sellerOrderNumber }: { sellerOrderNumber: string }) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const [carrierCode, setCarrierCode] = useState("");
  const [trackingNo, setTrackingNo] = useState("");
  const [failure, setFailure] = useState<string | null>(null);

  const submit = () => {
    setFailure(null);
    startTransition(async () => {
      try {
        await api(`/api/shipments/${encodeURIComponent(sellerOrderNumber)}/ship`, {
          method: "POST",
          body: { carrierCode, trackingNo: trackingNo.trim() },
        });
        router.refresh();
      } catch (error) {
        setFailure(failureText(error));
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
      <h2 className="font-semibold">발송 처리</h2>
      <div className="flex flex-wrap items-end gap-3">
        <div className="grid gap-1">
          <label htmlFor="ship-carrier">택배사</label>
          <select
            id="ship-carrier"
            name="carrierCode"
            required
            value={carrierCode}
            onChange={(event) => setCarrierCode(event.target.value)}
            className="rounded-ui border border-border px-2 py-1"
          >
            <option value="">고르세요</option>
            {Object.entries(CARRIERS).map(([code, label]) => (
              <option key={code} value={code}>
                {label}
              </option>
            ))}
          </select>
        </div>
        <div className="grid gap-1">
          <label htmlFor="ship-tracking">송장 번호</label>
          <input
            id="ship-tracking"
            name="trackingNo"
            inputMode="numeric"
            autoComplete="off"
            required
            maxLength={27}
            value={trackingNo}
            onChange={(event) => setTrackingNo(event.target.value)}
            aria-describedby="ship-tracking-hint"
            className="rounded-ui border border-border px-2 py-1"
          />
        </div>
        <button
          type="submit"
          disabled={pending || carrierCode === "" || trackingNo.trim() === ""}
          className="rounded-ui border border-accent px-3 py-1 disabled:opacity-50"
        >
          발송 처리
        </button>
      </div>
      <p id="ship-tracking-hint" className="text-xs text-text-muted">
        숫자 10~14자리입니다. 하이픈은 넣어도 됩니다.<br />
        누르면 발송으로 기록되고, 구매자가 이 송장으로 배송을 따라갑니다.
      </p>
      <p role="alert" className="text-danger-text">
        {failure}
      </p>
    </form>
  );
}

function failureText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.slug === "validation-failed") {
      return "택배사와 송장 번호를 확인해 주세요.";
    }
    if (error.slug === "order-transition-not-allowed") {
      return "이미 처리된 주문입니다. 화면을 새로 고쳐 주세요.";
    }
  }
  return "발송하지 못했습니다. 잠시 후 다시 시도해 주세요.";
}
