"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { ApiError, api } from "@/lib/api";
import { WEBHOOK_EVENT_TYPES } from "@/lib/webhook-text";

/**
 * 웹훅 등록 폼(`Q175`, `29`).
 *
 * <p><b>시크릿은 등록 응답에 한 번만 온다</b> — 서버가 암호문만 들고 있어서 다시 못 준다. 그래서 받은 자리에서 보여 주고
 * 「다시 못 본다」고 적는다. 화면을 떠나면 사라진다(상태에만 있고 어디에도 안 남긴다).
 */
export function WebhookForm({ sellerId }: { sellerId: number }) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const [secret, setSecret] = useState<string | null>(null);
  const [failure, setFailure] = useState<string | null>(null);

  const submit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const eventTypes = form.getAll("eventTypes").map(String);
    setFailure(null);
    if (eventTypes.length === 0) {
      setFailure("받을 사건을 하나 이상 골라 주세요.");
      return;
    }
    startTransition(async () => {
      try {
        const created = await api<{ webhookEndpointId: number; secret: string }>("/api/seller/webhooks", {
          method: "POST",
          body: { sellerId, url: String(form.get("url")), eventTypes },
        });
        setSecret(created.secret);
        router.refresh();
      } catch (error) {
        setFailure(messageOf(error));
      }
    });
  };

  return (
    <div className="grid gap-4">
      {secret === null ? null : (
        <div role="status" className="grid gap-1 rounded-ui border border-accent p-4 text-sm">
          <p className="font-semibold">서명 시크릿 — 지금 한 번만 보입니다</p>
          <code className="break-all font-mono">{secret}</code>
          <p className="text-text-muted">
            받는 서버에 저장해 주세요. 잃으면 이 엔드포인트를 지우고 다시 등록해야 합니다.
          </p>
        </div>
      )}

      <form onSubmit={submit} className="grid gap-3 text-sm">
        <div className="grid gap-1">
          <label htmlFor="webhook-url" className="font-medium">
            받을 주소 (https)
          </label>
          {/* 상한을 상수로 안 뺀다 — `ScreenLengthTest` 가 이 숫자를 서버 `@Size` 와 맞댄다 */}
          <input
            id="webhook-url"
            name="url"
            type="url"
            required
            maxLength={2000}
            placeholder="https://example.com/hooks/shop"
            className="rounded-ui border border-border bg-surface px-3 py-2"
          />
        </div>
        <fieldset className="grid gap-1">
          <legend className="font-medium">받을 사건</legend>
          {Object.entries(WEBHOOK_EVENT_TYPES).map(([type, label]) => (
            <label key={type} className="flex items-center gap-2">
              <input type="checkbox" name="eventTypes" value={type} />
              {label}
            </label>
          ))}
        </fieldset>
        <div>
          <button
            type="submit"
            disabled={pending}
            className="rounded-ui border border-border px-4 py-2 font-medium disabled:opacity-50"
          >
            등록
          </button>
        </div>
        <p role="alert" className="text-danger-text">
          {failure}
        </p>
      </form>
    </div>
  );
}

/** 엔드포인트를 지운다. 되돌릴 수 없어서 한 번 더 묻는다 — 지우면 발송 기록도 같이 간다 */
export function DeleteEndpointButton({ webhookEndpointId }: { webhookEndpointId: number }) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const [confirming, setConfirming] = useState(false);

  if (!confirming) {
    return (
      <button
        type="button"
        onClick={() => setConfirming(true)}
        className="rounded-ui border border-border px-3 py-1 text-xs font-medium"
      >
        지우기
      </button>
    );
  }
  return (
    <span className="flex flex-wrap items-center gap-2 text-xs">
      <span>지우면 발송 기록도 같이 사라집니다.</span>
      <button
        type="button"
        disabled={pending}
        onClick={() =>
          startTransition(async () => {
            await api(`/api/seller/webhooks/${webhookEndpointId}`, { method: "DELETE" });
            router.refresh();
          })
        }
        className="rounded-ui border border-danger-text px-3 py-1 font-medium text-danger-text disabled:opacity-50"
      >
        지우기
      </button>
      <button type="button" onClick={() => setConfirming(false)} className="rounded-ui px-3 py-1 text-text-muted">
        그만두기
      </button>
    </span>
  );
}

/** 실패로 닫힌 발송을 다시 보낸다(`31`). 버튼은 서버가 `RESEND` 를 줄 때만 선다 */
export function ResendButton({ webhookDeliveryId }: { webhookDeliveryId: number }) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const [failure, setFailure] = useState<string | null>(null);

  return (
    <span className="grid gap-1">
      <button
        type="button"
        disabled={pending}
        onClick={() =>
          startTransition(async () => {
            try {
              await api(`/api/seller/webhooks/deliveries/${webhookDeliveryId}/resend`, { method: "POST" });
              router.refresh();
            } catch (error) {
              setFailure(messageOf(error));
            }
          })
        }
        className="rounded-ui border border-border px-3 py-1 text-xs font-medium disabled:opacity-50"
      >
        다시 보내기
      </button>
      <span role="alert" className="text-xs text-danger-text">
        {failure}
      </span>
    </span>
  );
}

/** 서버가 준 오류를 화면 문구로. <b>`slug` 로 갈린다</b>(`D5`·`D20`) */
function messageOf(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return "서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
  switch (error.slug) {
    case "webhook-url-not-allowed":
      return "받을 수 없는 주소입니다. https 이고 내부망이 아닌 주소를 적어 주세요.";
    case "webhook-endpoint-limit":
      return "엔드포인트는 셀러마다 다섯 개까지입니다. 쓰지 않는 것을 지운 뒤 다시 등록해 주세요.";
    case "webhook-endpoint-duplicate":
      return "이미 등록한 주소입니다.";
    case "webhook-key-missing":
      return "지금은 웹훅을 쓸 수 없습니다. 관리자에게 알려 주세요.";
    case "webhook-delivery-not-resendable":
      return "이미 보냈거나 보내는 중입니다. 화면을 새로 고쳐 주세요.";
    default:
      return "처리하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
}
