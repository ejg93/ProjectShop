"use client";

import Link from "next/link";
import { useRef, useState } from "react";

import { Field } from "@/components/field";
import { ApiError, api } from "@/lib/api";

/** 셀러 묶음 하나의 금액. 화면이 이미 계산해 둔 것을 폼이 그대로 쓴다 */
export type OrderLine = { sellerId: number; itemsAmount: number; shippingFee: number };

/** 승인되는 모의 카드. 뒷 4자리가 결과를 가른다(`MockPaymentGateway`) */
const SAMPLE_APPROVED = "4242-4242-4242-4242";
const SAMPLE_DECLINED = "4242-4242-4242-0000";

/**
 * 배송지를 적고 결제한다.
 *
 * <p><b>요청이 둘이다.</b> 주문 만들기와 결제가 다른 자원이라 한 번에 안 끝난다.
 *
 * <pre>
 * POST /api/orders    재고를 잡고 payment_pending 주문을 만든다  ← 이것이 청약이다
 * POST /api/payments  모의 PG 에 승인을 요청하고 결과를 적는다
 * </pre>
 *
 * <p><b>사이에서 끊기면 주문번호를 들고 있다가 결제만 다시 부른다</b>(사용자 선택).
 * 주문을 또 만들면 재고가 두 번 빠지고, 앞 주문은 30분 만료 배치가 치울 때까지 재고를 문다.
 * Shopware·Sylius·Spree 가 같은 모양이다.
 *
 * <p><b>멱등키가 둘이다</b>(`D11`). 주문과 결제가 다른 요청이라 같은 키를 쓰면 뒤엣것이
 * 「같은 키로 다른 본문」으로 422 가 된다. 각각 만들어 두고 <b>재시도에서도 같은 값을 쓴다</b> —
 * 새로 만들면 서버가 재전송이 아니라 새 요청으로 본다.
 *
 * <p><b>입력을 비제어로 둔다.</b> 다른 폼과 같은 방식이고(`Field`), 글자마다 다시 그릴 이유가 없다.
 */
export function CheckoutForm({
  cartItemIds,
  payableAmount,
}: {
  cartItemIds: number[];
  payableAmount: number;
}) {
  // 주문이 만들어졌으면 여기 찬다. 값이 있으면 버튼이 「결제 다시 시도」로 바뀐다.
  const [placedOrderNumber, setPlacedOrderNumber] = useState<string | null>(null);
  const [result, setResult] = useState<PaymentResult | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [sending, setSending] = useState(false);

  // 한 번 만든 키를 그대로 다시 쓴다. 다시 그릴 때 새로 만들어지면 재시도가 재전송이 아니게 된다.
  const orderKey = useRef(crypto.randomUUID());
  const paymentKey = useRef(crypto.randomUUID());

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);

    setSending(true);
    setFailure(null);

    try {
      const orderNumber = placedOrderNumber ?? (await placeOrder(form));
      setPlacedOrderNumber(orderNumber);

      setResult(await pay(orderNumber, text(form, "cardNumber")));
    } catch (error) {
      setFailure(messageFor(error));
    } finally {
      setSending(false);
    }
  }

  async function placeOrder(form: FormData): Promise<string> {
    const created = await api<{ orderNumber: string }>("/api/orders", {
      method: "POST",
      idempotencyKey: orderKey.current,
      body: {
        cartItemIds,
        shipping: {
          receiverName: text(form, "receiverName"),
          receiverPhone: text(form, "receiverPhone"),
          postalCode: text(form, "postalCode"),
          address1: text(form, "address1"),
          // 빈 문자열 대신 없는 것으로 보낸다. 안 적은 칸이 "" 로 저장되면
          // 「안 적음」과 「빈칸을 적음」이 데이터에서 안 갈린다.
          address2: optional(form, "address2"),
          deliveryMemo: optional(form, "deliveryMemo"),
        },
      },
    });
    return created.orderNumber;
  }

  async function pay(orderNumber: string, cardNumber: string): Promise<PaymentResult> {
    return api<PaymentResult>("/api/payments", {
      method: "POST",
      idempotencyKey: paymentKey.current,
      body: { orderNumber, method: "card", cardNumber },
    });
  }

  if (result) {
    return <Done result={result} />;
  }

  return (
    <form onSubmit={submit} className="grid gap-6 border-t border-border pt-6">
      <fieldset className="grid gap-4">
        <legend className="mb-2 text-sm font-semibold">배송지</legend>

        <Field name="receiverName" type="text" label="받는 분" autoComplete="name" maxLength={50} />
        <Field
          name="receiverPhone"
          type="text"
          label="연락처"
          autoComplete="tel"
          maxLength={30}
          hint="배송 기사가 연락할 번호입니다."
        />
        <Field
          name="postalCode"
          type="text"
          label="우편번호"
          autoComplete="postal-code"
          maxLength={10}
        />
        <Field
          name="address1"
          type="text"
          label="주소"
          autoComplete="address-line1"
          maxLength={200}
        />
        <Field
          name="address2"
          type="text"
          label="상세 주소"
          autoComplete="address-line2"
          maxLength={200}
          required={false}
        />
        <Field
          name="deliveryMemo"
          type="text"
          label="배송 요청사항"
          autoComplete="off"
          maxLength={200}
          required={false}
          hint="문 앞에 두어 주세요 같은 요청을 적으실 수 있습니다."
        />
      </fieldset>

      <fieldset className="grid gap-4">
        <legend className="mb-2 text-sm font-semibold">결제 수단</legend>

        <Field
          name="cardNumber"
          type="text"
          label="카드번호"
          autoComplete="off"
          maxLength={25}
          defaultValue={SAMPLE_APPROVED}
          hint="하이픈을 넣으셔도 됩니다."
        />

        {/*
          모의 결제라는 사실을 화면이 말한다. 안 적으면 진짜 카드를 치는 사람이 생기고,
          그 번호는 우리 서버까지 왔다가 버려진다 — 저장을 안 해도 안 받는 편이 낫다(`D2` R18).

          자동완성을 끈 것도 같은 이유다. 브라우저에 저장된 진짜 카드가 여기 채워지면 안 된다.
        */}
        <div className="grid gap-1 rounded-ui border border-border bg-surface-raised p-3 text-xs text-text-muted">
          <p className="font-semibold text-text">모의 결제입니다. 실제 카드번호를 넣지 마세요.</p>
          <p>{SAMPLE_APPROVED} — 승인됩니다.</p>
          <p>{SAMPLE_DECLINED} — 한도 초과로 거절됩니다.</p>
        </div>
      </fieldset>

      {placedOrderNumber ? (
        <p role="status" className="text-sm">
          주문이 접수됐고 결제가 남았습니다. 주문번호{" "}
          <span className="font-semibold">{placedOrderNumber}</span>
        </p>
      ) : null}

      {/* 폼 전체 오류는 제출 버튼 위, 입력칸 아래다(`D20`) */}
      <p role="alert" className="text-sm text-danger-text">
        {failure}
      </p>

      <button
        type="submit"
        disabled={sending}
        className="
          justify-self-end rounded-ui bg-accent px-5 py-2.5 text-sm font-semibold text-accent-on
          transition-[background-color,transform] duration-200
          hover:bg-accent-hover
          motion-safe:active:translate-y-px
          focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
          disabled:opacity-60
        "
      >
        {sending
          ? "처리 중"
          : placedOrderNumber
            ? "결제 다시 시도"
            : `${payableAmount.toLocaleString("ko-KR")}원 결제하기`}
      </button>
    </form>
  );
}

/** 결제 결과(`12-2`). 열거값은 대문자 스네이크다(`D5`) */
type PaymentResult = {
  orderNumber: string;
  status: "APPROVED" | "FAILED";
  amount: number;
  approvalNumber: string | null;
  cardIssuer: string | null;
  cardLast4: string | null;
  declineReason: string | null;
};

/**
 * 결제가 끝난 뒤.
 *
 * <p><b>거절도 여기로 온다.</b> 서버가 거절을 오류가 아니라 결과로 내리기 때문이다(`D11`).
 * 그 주문은 이미 닫혔고 재고도 돌아갔으므로 <b>같은 주문으로 다시 결제할 수 없다</b> —
 * 그래서 「다시 시도」가 아니라 「다시 주문」이라고 적는다.
 *
 * <p><b>거절이어도 주문 상세로 보낸다</b>(`15-3`). 그 주문은 취소된 채로 남고,
 * 왜 그렇게 됐는지는 거기 처리 내역에 있다 — 거래기록 열람이 그 화면이다(`D2` R6 제3항).
 */
function Done({ result }: { result: PaymentResult }) {
  const approved = result.status === "APPROVED";

  return (
    <section
      aria-labelledby="done-heading"
      className="grid justify-items-start gap-3 border-t border-border pt-6"
    >
      <h2 id="done-heading" className="text-lg font-semibold">
        {approved ? "결제가 완료되었습니다." : "결제가 거절되었습니다."}
      </h2>

      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
        <dt className="text-text-muted">주문번호</dt>
        <dd>{result.orderNumber}</dd>

        <dt className="text-text-muted">결제 금액</dt>
        <dd>{result.amount.toLocaleString("ko-KR")}원</dd>

        {approved ? (
          <>
            <dt className="text-text-muted">승인번호</dt>
            <dd>{result.approvalNumber}</dd>
            <dt className="text-text-muted">결제 수단</dt>
            <dd>
              {result.cardIssuer} ****{result.cardLast4}
            </dd>
          </>
        ) : (
          <>
            <dt className="text-text-muted">거절 사유</dt>
            <dd>{DECLINE_TEXT[result.declineReason ?? ""] ?? "카드사에서 거절했습니다."}</dd>
          </>
        )}
      </dl>

      {approved ? null : (
        <p className="text-sm text-text-muted">
          이 주문은 취소되었고 상품은 다시 판매됩니다. 다른 카드로 새로 주문해 주세요.
        </p>
      )}

      <div className="flex flex-wrap gap-4">
        <Link
          href={`/orders/${result.orderNumber}`}
          className="
            text-sm font-semibold text-accent-text underline underline-offset-4
            focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
          "
        >
          주문 상세 보기
        </Link>
        <Link
          href="/products"
          className="
            text-sm text-text-muted underline underline-offset-4 hover:text-accent-text
            focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
          "
        >
          상품 더 보기
        </Link>
      </div>
    </section>
  );
}

/** PG 가 준 코드를 사람이 읽는 말로. 모르는 코드는 뭉뚱그린다 — 새 코드가 늘어도 화면이 안 깨진다 */
const DECLINE_TEXT: Record<string, string> = {
  limit_exceeded: "카드 한도를 초과했습니다.",
};

function text(form: FormData, name: string): string {
  return String(form.get(name) ?? "").trim();
}

/** 빈 칸은 없는 것으로 보낸다 */
function optional(form: FormData, name: string): string | undefined {
  return text(form, name) || undefined;
}

/**
 * 실패를 사람이 읽는 말로.
 *
 * <p><b>`slug` 로 갈린다</b>(`D5`·`D20`). 상태 코드로 가르면 서버가 코드를 조정할 때
 * 화면이 같이 틀어지고, `detail` 을 그대로 쓰면 개발자 문체가 사용자에게 나간다.
 */
function messageFor(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return "요청을 보내지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }

  switch (error.slug) {
    case "out-of-stock":
      return "재고가 모자랍니다. 장바구니에서 개수를 줄여 주세요.";
    case "sku-not-buyable":
      return "지금은 구매할 수 없는 상품이 들어 있습니다.";
    case "order-empty":
      return "주문할 상품이 없습니다.";
    case "payment-gateway-unavailable":
      return "결제사가 응답하지 않습니다. 잠시 후 다시 시도해 주세요.";
    case "payment-card-required":
      return "카드번호를 입력해 주세요.";
    case "validation-failed":
      return "입력하신 내용을 다시 확인해 주세요.";
    case "order-transition-not-allowed":
      return "이미 결제가 끝난 주문입니다.";
    default:
      return "결제하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
}
