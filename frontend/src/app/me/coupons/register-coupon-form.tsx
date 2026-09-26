"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { ApiError, api } from "@/lib/api";

/**
 * 쿠폰 코드를 받아 담는다(`Q163`).
 *
 * <p><b>고르는 것이 아니라 친다.</b> 받을 수 있는 쿠폰을 목록으로 뿌리면 아직 안 알린 코드가
 * 통째로 새서, 사는 사람에게 정의 조회를 안 줬다(`V91`) — 코드는 밖에서 듣고 와서 친다.
 *
 * <p><b>대문자로 바꿔 보낸다.</b> 서버가 대문자·숫자·붙임표만 받는데, 소문자로 친 것을
 * 그대로 보내면 <b>맞는 코드를 치고도 형식 오류</b>가 돌아온다 — 사람이 고칠 수 없는 거절이다.
 */
export function RegisterCouponForm() {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    setError(null);
    setDone(false);
    startTransition(async () => {
      try {
        await api("/api/me/coupons", {
          method: "POST",
          body: { code: code.trim().toUpperCase() },
        });
        setCode("");
        setDone(true);
        router.refresh();
      } catch (caught) {
        setError(
          caught instanceof ApiError
            ? caught.userText
            : "쿠폰을 받지 못했습니다. 잠시 뒤 다시 시도해 주세요.",
        );
      }
    });
  };

  return (
    <form className="grid gap-3 rounded-ui border border-border bg-surface-raised p-4" onSubmit={submit}>
      <label className="text-sm font-medium" htmlFor="coupon-code">
        쿠폰 코드
      </label>

      {error ? (
        <p role="alert" className="text-sm text-danger-text">
          {error}
        </p>
      ) : null}
      {done ? (
        <p role="status" className="text-sm">
          쿠폰을 받았습니다. 아래 목록에서 확인하실 수 있습니다.
        </p>
      ) : null}

      <div className="flex flex-wrap gap-2">
        {/*
          상한을 상수로 빼지 않는다 — `ScreenLengthTest` 가 이 자리의 **숫자를** 서버
          `@Size(max = 50)` 과 맞대 보고, 이름으로 바꾸면 그 대조가 못 읽는다.
        */}
        <input
          id="coupon-code"
          name="code"
          type="text"
          required
          maxLength={50}
          value={code}
          onChange={(event) => setCode(event.target.value)}
          placeholder="WELCOME-3000"
          className="min-w-52 flex-1 rounded-ui border border-border bg-surface px-3 py-2 text-sm"
        />
        <button
          type="submit"
          disabled={pending}
          className="rounded-ui border border-border px-4 py-2 text-sm font-medium disabled:opacity-50"
        >
          받기
        </button>
      </div>
    </form>
  );
}
