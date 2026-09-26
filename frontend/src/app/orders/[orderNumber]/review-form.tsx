"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { ApiError, api } from "@/lib/api";

/**
 * 후기를 쓴다(`Q160`).
 *
 * <p><b>주문 상세에만 있다.</b> 후기는 <b>산 주문 줄</b>에 붙는 것이라 어느 주문인지를
 * 알아야 하고, 그것을 아는 화면이 여기뿐이다 — 상품 상세에 쓰기 칸을 두면
 * 「무엇을 샀는지 고르는 칸」이 같이 생긴다.
 *
 * <p><b>받기 전에는 안 그린다.</b> 서버가 거부하는 것을 화면이 미리 가린다 —
 * 눌러야 막히는 버튼은 갈 곳이 있는 것처럼 보이게 하는 것이다(`D20`).
 *
 * <p><b>이미 썼는지는 화면이 모른다.</b> 주문 상세가 그 사실을 안 내린다 — 두 번째로
 * 쓰면 서버가 409 로 답하고 그 문구를 그대로 그린다. <b>안 내리는 값으로 가르지 않는다.</b>
 */
export function ReviewForm({
  orderItemId,
  productName,
}: {
  orderItemId: number;
  productName: string;
}) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const [open, setOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [rating, setRating] = useState(5);
  const [body, setBody] = useState("");

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    setError(null);
    startTransition(async () => {
      try {
        await api("/api/reviews", { method: "POST", body: { orderItemId, rating, body } });
        setOpen(false);
        setBody("");
        router.refresh();
      } catch (caught) {
        setError(
          caught instanceof ApiError
            ? caught.userText
            : "후기를 남기지 못했습니다. 잠시 뒤 다시 시도해 주세요.",
        );
      }
    });
  };

  if (!open) {
    return (
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="rounded-ui border border-border px-3 py-1 text-xs font-medium"
      >
        후기 쓰기
      </button>
    );
  }

  return (
    <form className="grid gap-3 rounded-ui border border-border px-4 py-3" onSubmit={submit}>
      <p className="text-sm font-medium">{productName} 후기</p>

      {error ? (
        <p role="alert" className="text-sm">
          {error}
        </p>
      ) : null}

      <div className="grid gap-1">
        <label className="text-xs text-text-muted" htmlFor={`rating-${orderItemId}`}>
          별점
        </label>
        <select
          id={`rating-${orderItemId}`}
          name="rating"
          value={rating}
          onChange={(event) => setRating(Number(event.target.value))}
          className="w-32 rounded-ui border border-border bg-surface px-3 py-2 text-sm"
        >
          {[5, 4, 3, 2, 1].map((value) => (
            <option key={value} value={value}>
              {"★".repeat(value)}
            </option>
          ))}
        </select>
      </div>

      <div className="grid gap-1">
        <label className="text-xs text-text-muted" htmlFor={`body-${orderItemId}`}>
          쓰실 내용 (10자 이상)
        </label>
        {/*
          상한을 상수로 빼지 않는다 — `ScreenLengthTest` 가 이 자리의 **숫자를** 서버
          `@Size(min = 10, max = 2000)` 과 맞대 보고, 이름으로 바꾸면 그 대조가 못 읽는다.
        */}
        <textarea
          id={`body-${orderItemId}`}
          name="body"
          required
          minLength={10}
          maxLength={2000}
          rows={4}
          value={body}
          onChange={(event) => setBody(event.target.value)}
          className="rounded-ui border border-border bg-surface px-3 py-2 text-sm"
        />
      </div>

      <div className="flex gap-2">
        <button
          type="submit"
          disabled={pending}
          className="rounded-ui border border-border px-4 py-2 text-sm font-medium disabled:opacity-50"
        >
          남기기
        </button>
        <button
          type="button"
          onClick={() => setOpen(false)}
          className="rounded-ui px-4 py-2 text-sm text-text-muted"
        >
          그만두기
        </button>
      </div>
    </form>
  );
}
