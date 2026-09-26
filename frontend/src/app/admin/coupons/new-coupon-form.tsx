"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { ApiError, api } from "@/lib/api";

/**
 * 쿠폰을 만든다(`Q163`).
 *
 * <p><b>정률의 상한 칸은 정률일 때만 그린다.</b> 정액에 상한을 두면 그 값이 곧 할인액이라
 * 뜻이 없고, 표도 그렇게 막는다({@code coupon_max_discount_check}, `49`) —
 * 못 채우는 칸을 그려 두면 눌러야 막히는 자리가 된다(`D20`).
 *
 * <p><b>셀러 번호 칸도 셀러 부담일 때만 그린다.</b> 한쪽만 찬 행은 정산에서 갈 곳이 없다
 * ({@code coupon_bearer_seller_check}).
 *
 * <p><b>할인 값의 뜻이 종류마다 다르다</b> — 정액은 원, 정률은 bp 다(1000 = 10.00%).
 * 라벨이 그때그때 바뀌어야 <b>10%를 만들려다 10원을 만드는</b> 자리가 안 생긴다.
 */
export function NewCouponForm() {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const [error, setError] = useState<string | null>(null);

  const [discountKind, setDiscountKind] = useState("amount");
  const [bearer, setBearer] = useState("mall");

  const submit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError(null);

    const form = new FormData(event.currentTarget);
    const element = event.currentTarget;

    startTransition(async () => {
      try {
        await api("/api/coupons", {
          method: "POST",
          body: {
            code: String(form.get("code")).trim().toUpperCase(),
            name: String(form.get("name")).trim(),
            discountKind,
            discountValue: Number(form.get("discountValue")),
            maxDiscountAmount:
              discountKind === "percent" && form.get("maxDiscountAmount")
                ? Number(form.get("maxDiscountAmount"))
                : null,
            minOrderAmount: Number(form.get("minOrderAmount") ?? 0),
            bearer,
            sellerId: bearer === "seller" ? Number(form.get("sellerId")) : null,
            validDays: Number(form.get("validDays")),
          },
        });
        element.reset();
        router.refresh();
      } catch (caught) {
        setError(
          caught instanceof ApiError
            ? caught.userText
            : "쿠폰을 만들지 못했습니다. 잠시 뒤 다시 시도해 주세요.",
        );
      }
    });
  };

  return (
    <form className="grid gap-4 rounded-ui border border-border bg-surface-raised p-4" onSubmit={submit}>
      <h2 className="text-sm font-semibold">쿠폰 만들기</h2>

      {error ? (
        <p role="alert" className="text-sm text-danger-text">
          {error}
        </p>
      ) : null}

      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="코드" hint="대문자·숫자·붙임표만">
          {/*
            상한을 상수로 빼지 않는다 — `ScreenLengthTest` 가 이 자리의 **숫자를** 서버
            `@Size` 와 맞대 보고, 이름으로 바꾸면 그 대조가 못 읽는다.
          */}
          <input
            name="code"
            type="text"
            required
            maxLength={50}
            pattern="[A-Za-z0-9-]+"
            placeholder="WELCOME-3000"
            className="rounded-ui border border-border bg-surface px-3 py-2 text-sm"
          />
        </Field>

        <Field label="이름">
          <input
            name="name"
            type="text"
            required
            maxLength={100}
            placeholder="첫 구매 쿠폰"
            className="rounded-ui border border-border bg-surface px-3 py-2 text-sm"
          />
        </Field>

        <Field label="할인 방식">
          <select
            name="discountKind"
            value={discountKind}
            onChange={(event) => setDiscountKind(event.target.value)}
            className="rounded-ui border border-border bg-surface px-3 py-2 text-sm"
          >
            <option value="amount">정액 (원)</option>
            <option value="percent">정률 (%)</option>
          </select>
        </Field>

        <Field
          label={discountKind === "percent" ? "할인율 (bp · 1000 = 10%)" : "할인 금액 (원)"}
        >
          <input
            name="discountValue"
            type="number"
            required
            min={1}
            max={discountKind === "percent" ? 10000 : undefined}
            className="rounded-ui border border-border bg-surface px-3 py-2 text-sm"
          />
        </Field>

        {discountKind === "percent" ? (
          <Field label="최대 할인 금액 (원)" hint="비우면 상한 없음">
            <input
              name="maxDiscountAmount"
              type="number"
              min={1}
              className="rounded-ui border border-border bg-surface px-3 py-2 text-sm"
            />
          </Field>
        ) : null}

        <Field label="최소 주문 금액 (원)">
          <input
            name="minOrderAmount"
            type="number"
            required
            min={0}
            defaultValue={0}
            className="rounded-ui border border-border bg-surface px-3 py-2 text-sm"
          />
        </Field>

        <Field label="부담 주체" hint="정산이 이 값으로 갈립니다">
          <select
            name="bearer"
            value={bearer}
            onChange={(event) => setBearer(event.target.value)}
            className="rounded-ui border border-border bg-surface px-3 py-2 text-sm"
          >
            <option value="mall">몰 부담</option>
            <option value="seller">셀러 부담</option>
          </select>
        </Field>

        {bearer === "seller" ? (
          <Field label="셀러 번호">
            <input
              name="sellerId"
              type="number"
              required
              min={1}
              className="rounded-ui border border-border bg-surface px-3 py-2 text-sm"
            />
          </Field>
        ) : null}

        <Field label="유효 기간 (일)" hint="받은 날부터 셉니다">
          <input
            name="validDays"
            type="number"
            required
            min={1}
            max={3650}
            defaultValue={30}
            className="rounded-ui border border-border bg-surface px-3 py-2 text-sm"
          />
        </Field>
      </div>

      <div>
        <button
          type="submit"
          disabled={pending}
          className="rounded-ui border border-border px-4 py-2 text-sm font-medium disabled:opacity-50"
        >
          만들기
        </button>
      </div>
    </form>
  );
}

/** 라벨과 칸을 잇는다. `htmlFor` 대신 감싸는 것은 칸마다 id 를 짓지 않으려는 것이다 */
function Field({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <label className="grid gap-1">
      <span className="text-xs text-text-muted">
        {label}
        {hint ? <span className="text-text-muted"> · {hint}</span> : null}
      </span>
      {children}
    </label>
  );
}
