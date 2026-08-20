"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { ApiError, api } from "@/lib/api";

/**
 * 담긴 것 하나.
 *
 * @param optionLabel 고른 조합. 옵션이 없는 상품은 {@code null} 이다(`8c`)
 * @param available   지금 살 수 있나. 품절이거나 상품이 내려갔으면 거짓이다
 */
export type CartItem = {
  cartItemId: number;
  skuId: number;
  productId: number;
  productName: string;
  optionLabel: string | null;
  sellerId: number;
  sellerName: string;
  priceInclVat: number;
  /** 이 셀러의 배송비. <b>묶음마다 한 번 붙어서</b> 줄이 아니라 묶음 바닥에 그린다(`D2` R24) */
  shippingFee: number;
  quantity: number;
  available: boolean;
};

/** 한 조합을 몇 개까지. 서버가 거는 상한과 같다(`CartService.MAX_QUANTITY`) */
const MAX_QUANTITY = 99;

/**
 * 담긴 줄 하나. <b>여기만 클라이언트 컴포넌트다</b>(`D24` 「경계를 잎사귀로 내린다」).
 *
 * <p>수량과 빼기가 서버 상태를 바꾸므로 「읽기는 서버, 쓰기는 클라이언트」의 쓰기 쪽이다.
 * 목록 자체는 서버가 그린다.
 *
 * <p><b>바뀐 값을 화면이 직접 안 고친다.</b> {@code router.refresh()} 로 서버에 다시 그리게 한다 —
 * 수량이 바뀌면 합계도 바뀌고 품절 여부도 바뀌는데, 그걸 화면에서 다시 계산하면
 * <b>서버가 아는 값과 화면이 아는 값이 갈린다.</b>
 *
 * <p><b>빼기에 확인 대화상자를 안 붙인다.</b> `D20` 이 확인을 요구하는 것은 되돌릴 수 없는
 * 조작인데, 장바구니에서 빼는 것은 다시 담으면 그만이다. 되돌릴 수 있는 것에 확인을 붙이면
 * 확인이 흔해져서 사용자가 읽지 않고 누른다.
 */
export function CartLine({ item }: { item: CartItem }) {
  const router = useRouter();
  const [failure, setFailure] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [sending, setSending] = useState(false);

  // 서버가 다시 그리는 동안에도 버튼을 잠가 둔다. refresh 는 await 로 안 끝나서
  // 이것 없이는 응답과 새 화면 사이에 옛 수량으로 한 번 더 누를 수 있다.
  const [refreshing, startRefresh] = useTransition();
  const busy = sending || refreshing;

  async function send(action: () => Promise<void>, done: string) {
    setSending(true);
    setFailure(null);
    setNotice(null);
    try {
      await action();
      setNotice(done);
      startRefresh(() => router.refresh());
    } catch (error) {
      setFailure(messageFor(error));
    } finally {
      setSending(false);
    }
  }

  const changeQuantity = (quantity: number) =>
    send(
      () => api(`/api/cart/items/${item.skuId}`, { method: "PUT", body: { quantity } }),
      `수량을 ${quantity}개로 바꿨습니다.`,
    );

  const remove = () =>
    send(
      () => api(`/api/cart/items/${item.skuId}`, { method: "DELETE" }),
      `${item.productName}을(를) 뺐습니다.`,
    );

  return (
    <div className="grid gap-2 border-t border-border pt-3 first:border-t-0 first:pt-0">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="grid gap-1">
          <Link
            href={`/products/${item.productId}`}
            className="
              text-sm font-semibold underline-offset-4 hover:text-accent-text hover:underline
              focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
            "
          >
            {item.productName}
          </Link>

          {/* 옵션이 없는 상품은 이 줄을 안 그린다. 빈 줄을 두면 조합이 있는 척 보인다 */}
          {item.optionLabel ? (
            <p className="text-xs text-text-muted">{item.optionLabel}</p>
          ) : null}

          {/*
            줄 합계를 크게, 단가를 작게 둔다. 단가만 적으면 <b>수량이 2인데 19,000원</b> 이 보여서
            그 값이 한 개 값인지 이 줄의 값인지 안 갈린다 — 합계와 견줄 때 특히 헷갈린다.
          */}
          <p className="text-sm font-semibold">
            {priceText(item.priceInclVat * item.quantity)}
          </p>
          {item.quantity > 1 ? (
            <p className="text-xs text-text-muted">
              개당 {priceText(item.priceInclVat)} × {item.quantity}개
            </p>
          ) : null}
        </div>

        <div className="grid justify-items-end gap-2">
          {item.available ? (
            <Quantity
              quantity={item.quantity}
              busy={busy}
              onChange={changeQuantity}
            />
          ) : (
            /* 색으로만 알리지 않는다(`D20`). 글로 적고, 살 수 없는 줄에는 수량 조절을 안 그린다 */
            <p className="text-sm text-danger-text">지금 구매할 수 없습니다.</p>
          )}

          <button
            type="button"
            onClick={remove}
            disabled={busy}
            className="
              text-xs text-text-muted underline underline-offset-4
              transition-colors duration-200 hover:text-danger-text
              focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
              disabled:opacity-60
            "
          >
            빼기
          </button>
        </div>
      </div>

      {/*
        결과가 소리로도 전해져야 한다(`D20` 「동적으로 바뀌는 것」).
        빈 채로 미리 두는 이유는, 나중에 만들어 붙이면 보조기술이 그 영역을 안 보고 있어서다.
      */}
      <p role="status" className="text-xs text-text-muted">
        {notice}
      </p>
      <p role="alert" className="text-xs text-danger-text">
        {failure}
      </p>
    </div>
  );
}

/**
 * 수량 조절.
 *
 * <p><b>입력칸이 아니라 버튼 둘이다.</b> 입력칸은 「몇 개를 쳤을 때 보내나」를 정해야 하고
 * (칸을 벗어날 때? 엔터? 잠시 멈췄을 때?) 어느 쪽이든 사용자가 그 규칙을 모른다.
 *
 * <p>1개에서 더 줄이는 버튼을 <b>안 그린다.</b> 0 을 보내면 서버가 행을 지우는데,
 * 그건 「빼기」가 하는 일이라 같은 결과에 이르는 길이 둘이 된다.
 */
function Quantity({
  quantity,
  busy,
  onChange,
}: {
  quantity: number;
  busy: boolean;
  onChange: (quantity: number) => void;
}) {
  return (
    <div className="flex items-center gap-1">
      <StepButton
        label="수량 줄이기"
        disabled={busy || quantity <= 1}
        onClick={() => onChange(quantity - 1)}
      >
        −
      </StepButton>

      {/* 값 자체는 버튼이 아니다. 눌러도 할 일이 없는 것을 누를 수 있게 두지 않는다 */}
      <span className="min-w-8 text-center text-sm tabular-nums" aria-live="off">
        {quantity}개
      </span>

      <StepButton
        label="수량 늘리기"
        disabled={busy || quantity >= MAX_QUANTITY}
        onClick={() => onChange(quantity + 1)}
      >
        +
      </StepButton>
    </div>
  );
}

/** 글자가 기호 하나뿐이라 <b>이름을 따로 준다</b> — 「−」만으로는 무엇이 줄어드는지 안 들린다 */
function StepButton({
  label,
  disabled,
  onClick,
  children,
}: {
  label: string;
  disabled: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      aria-label={label}
      disabled={disabled}
      onClick={onClick}
      className="
        grid h-8 w-8 place-items-center rounded-ui border border-border text-sm
        transition-colors duration-200
        hover:border-accent-text
        focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
        disabled:opacity-40
      "
    >
      {children}
    </button>
  );
}

/**
 * 실패를 사람이 읽는 말로.
 *
 * <p><b>`slug` 로 갈린다</b>(`D5`·`D20`). 상태 코드로 가르면 서버가 코드를 조정할 때
 * 화면이 같이 틀어지고, `detail` 을 그대로 쓰면 개발자 문체가 사용자에게 나간다.
 */
function messageFor(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return "잠시 후 다시 시도해 주세요.";
  }

  switch (error.slug) {
    case "out-of-stock":
      return "재고가 모자랍니다. 개수를 줄여 주세요.";
    case "sku-not-buyable":
      return "지금은 구매할 수 없는 상품입니다.";
    case "cart-item-not-found":
      return "이미 빠진 상품입니다. 새로고침해 주세요.";
    default:
      return "장바구니를 바꾸지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
}

/** 부가세가 이미 포함된 값이다(`D8`). 화면이 다시 더하지 않는다 */
function priceText(amount: number): string {
  return `${amount.toLocaleString("ko-KR")}원`;
}
