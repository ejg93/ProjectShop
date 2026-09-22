"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { ApiError, api } from "@/lib/api";

/**
 * 수락 한 번.
 *
 * <p><b>성공하면 멤버 화면으로 보낸다.</b> 그 자리가 수락의 결과를 보여 주는 곳이라
 * 여기서 「됐습니다」만 그리면 사람이 어디로 갈지 다시 고른다.
 *
 * <p><b>넷을 안 가른다.</b> 없는 초대·남의 초대·이미 쓴 것·기한이 지난 것이 서버에서
 * 같은 응답이라(`D14`) 화면도 그대로 그린다 — 가르면 링크를 주워 온 사람이
 * 그 초대가 실재했는지를 알게 된다.
 */
export function AcceptButton({ token }: { token: string }) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const [error, setError] = useState<string | null>(null);

  const accept = () => {
    setError(null);
    startTransition(async () => {
      try {
        await api(`/api/invitations/${encodeURIComponent(token)}/acceptance`, { method: "POST" });
        router.replace("/seller/members");
      } catch (caught) {
        setError(
          caught instanceof ApiError && caught.detail
            ? caught.detail
            : "수락하지 못했습니다. 링크를 보낸 분께 다시 요청해 주세요.",
        );
      }
    });
  };

  return (
    <div className="grid gap-3">
      {error ? (
        <p role="alert" className="rounded-ui border border-border px-4 py-3 text-sm">
          {error}
        </p>
      ) : null}

      <button
        type="button"
        disabled={pending}
        onClick={accept}
        className="rounded-ui border border-border px-4 py-2 text-sm font-medium disabled:opacity-50"
      >
        수락하기
      </button>
    </div>
  );
}
