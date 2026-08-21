import { SellerIdentityTable, type SellerIdentity } from "@/components/seller-identity";
import { priceText } from "@/lib/format";

import type { OrderLine } from "./checkout-form";

/**
 * 청약 전에 보여야 하는 것(`15-5`, `D2` R19).
 *
 * <p><b>주문서에서 이 조각만 따로 빼 둔 이유가 강제 지점이다.</b> 제7조·제14조제2항이
 * 청약 전 확인·정정 절차를 요구하고 제13조제2항이 <b>배송료를 포함한</b> 가격 표시를 요구하는데,
 * 그 표시가 페이지 안에 섞여 있으면 <b>지워도 아무것도 안 깨진다</b>(`15-4` 가 남긴 자리).
 * 여기 있으면 {@code summary.test.tsx} 가 항목·수량·금액·배송비·총액을 못박는다.
 *
 * <p>화면 문구는 존댓말이다(`D20`). 개발자가 읽는 글과 규칙이 다르다.
 */

/** 주문서가 그리는 장바구니 한 줄 */
export type CartItem = {
  cartItemId: number;
  productName: string;
  optionLabel: string | null;
  sellerId: number;
  sellerName: string;
  priceInclVat: number;
  quantity: number;
  available: boolean;
  withdrawalRestrictionReason: string | null;
  /** 공급시기 약정 날수(영업일). `null` 이면 약정이 없고 법정 3영업일이 걸린다(`14c`) */
  supplyLeadDays: number | null;
};

/** 셀러 하나로 묶은 줄들 */
export type SellerGrouping = { sellerId: number; sellerName: string; items: CartItem[] };

/**
 * 셀러 하나와 그 묶음.
 *
 * <p><b>신원을 접어 둔다.</b> 일곱 칸을 셀러마다 펼쳐 두면 주문서가 길어져서 정작
 * 금액과 배송지가 화면 밖으로 밀린다. `details` 는 접혀 있어도 <b>문서 안에 있는 내용</b>이라
 * 보조기술이 찾고 브라우저 검색에도 걸린다 — 눌러야 서버에서 받아 오는 방식과 다르다.
 */
export function SellerGroup({
  group,
  seller,
  line,
}: {
  group: SellerGrouping;
  seller: SellerIdentity;
  line: OrderLine;
}) {
  return (
    <section
      aria-labelledby={`checkout-seller-${group.sellerId}`}
      className="grid gap-3 rounded-ui border border-border bg-surface-raised p-4"
    >
      <h2 id={`checkout-seller-${group.sellerId}`} className="text-sm font-semibold">
        {group.sellerName}
      </h2>

      <ul className="grid gap-2">
        {group.items.map((item) => (
          <li key={item.cartItemId} className="flex flex-wrap justify-between gap-2 text-sm">
            <span>
              {item.productName}
              {item.optionLabel ? (
                <span className="text-text-muted"> · {item.optionLabel}</span>
              ) : null}
              <span className="text-text-muted"> × {item.quantity}개</span>
            </span>
            <span>{priceText(item.priceInclVat * item.quantity)}</span>
          </li>
        ))}
      </ul>

      <p className="flex justify-between border-t border-border pt-2 text-sm text-text-muted">
        <span>배송비</span>
        <span>{priceText(line.shippingFee)}</span>
      </p>

      {/*
        공급시기 고지(`14c`, `D2` R21). **청약 전에 알아야 할 거래조건이다**(제13조제2항 4호) —
        상품 상세에서 봤더라도 여기서 다시 말한다. 담고 나서 결제까지 오는 동안
        무엇을 언제 받는지가 바뀌지 않았다는 것을 이 화면이 확인해 준다.

        **묶음 단위로 적는다.** 한 셀러 묶음은 한 번에 나가므로 가장 늦은 항목이
        그 묶음의 발송 시점을 정한다(`V26`).
      */}
      <p className="border-t border-border pt-2 text-xs text-text-muted">
        {bundleLeadDays(group.items) === null ? (
          <>결제하신 날부터 3영업일 이내에 발송됩니다.</>
        ) : (
          <>
            결제하신 날부터 <strong className="text-text">{bundleLeadDays(group.items)}영업일</strong>{" "}
            이내에 발송하기로 약정된 상품이 들어 있습니다.
          </>
        )}
      </p>

      <details className="border-t border-border pt-2">
        <summary
          className="
            cursor-pointer text-sm font-semibold
            focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
          "
        >
          판매자 정보
        </summary>
        <div className="pt-2">
          <SellerIdentityTable seller={seller} />
        </div>
      </details>
    </section>
  );
}

/**
 * 결제하실 금액.
 *
 * <p><b>상품 금액과 배송비를 갈라서 적는다.</b> 합계만 적으면 배송비가 얼마인지가 안 보이고,
 * 그건 제13조제2항이 요구하는 표시가 아니다.
 */
export function Amounts({
  itemsTotal,
  shippingTotal,
}: {
  itemsTotal: number;
  shippingTotal: number;
}) {
  return (
    <section aria-labelledby="amounts-heading" className="grid gap-2 border-t border-border pt-6">
      <h2 id="amounts-heading" className="text-sm font-semibold">
        결제 금액
      </h2>

      <p className="flex justify-between text-sm">
        <span className="text-text-muted">상품 금액</span>
        <span>{priceText(itemsTotal)}</span>
      </p>
      <p className="flex justify-between text-sm">
        <span className="text-text-muted">배송비</span>
        <span>{priceText(shippingTotal)}</span>
      </p>
      <p className="flex justify-between border-t border-border pt-2 text-base font-semibold">
        <span>결제하실 금액</span>
        <span>{priceText(itemsTotal + shippingTotal)}</span>
      </p>
    </section>
  );
}

/**
 * 이 묶음의 약정 날수(`14c`).
 *
 * <p><b>가장 긴 것을 쓴다.</b> 한 셀러 묶음은 한 번에 나가므로 가장 늦게 준비되는 항목이
 * 그 묶음의 발송 시점을 정한다 — 서버가 `seller_order.supply_lead_days` 를 굳힐 때와 같은 규칙이다(`V26`).
 *
 * <p><b>약정이 하나도 없으면 `null` 이다.</b> 그것이 「법정 3영업일이 걸린다」는 사실이고,
 * 0(당일 발송)과 섞이면 안 된다.
 */
export function bundleLeadDays(items: CartItem[]): number | null {
  const agreed = items
    .map((item) => item.supplyLeadDays)
    .filter((days): days is number => days !== null);

  return agreed.length === 0 ? null : Math.max(...agreed);
}
