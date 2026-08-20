import type { Metadata } from "next";
import Link from "next/link";

import {
  BrokerageNotice,
  SellerIdentityTable,
  type SellerIdentity,
} from "@/components/seller-identity";
import { apiPublic } from "@/lib/api";
import { apiSession } from "@/lib/api-session";

import { CheckoutForm } from "./checkout-form";
import type { OrderLine } from "./checkout-form";

export const metadata: Metadata = { title: "주문서 · ProjectShop" };

type CartItem = {
  cartItemId: number;
  productName: string;
  optionLabel: string | null;
  sellerId: number;
  sellerName: string;
  priceInclVat: number;
  quantity: number;
  available: boolean;
  withdrawalRestrictionReason: string | null;
};

type Cart = { items: CartItem[]; total: number };

/**
 * 주문서(`15-2`).
 *
 * <p><b>법이 이 화면을 지목한다.</b> 전자상거래법 제20조제2항이 중개자에게
 * <b>청약이 이루어지기 전까지</b> 셀러 신원을 제공하라고 하고(`D2` R1), 제20조가 중개자 지위
 * 고지를 요구한다(`R2`). 청약은 주문을 만드는 순간이므로 <b>여기가 마지막 자리</b>다.
 *
 * <p><b>총액을 여기서 확정해 보여준다.</b> 제13조제2항이 재화의 가격에 <b>배송료를 포함</b>해
 * 표시하라고 해서다. 그래서 `15-2` 가 공개 셀러 응답에 배송비를 더했다 — 주문 생성 응답으로
 * 대신하면 금액이 청약 뒤에 나온다.
 *
 * <p><b>서버 컴포넌트다</b>(`D24`). 장바구니는 세션을 실어 읽고(`apiSession`),
 * 셀러 신원은 누구에게나 같은 값이라 공개 입구로 읽는다(`apiPublic`).
 */
export default async function CheckoutPage() {
  // 로그인해야 들어오는 화면이다(`D20` 화면 지도). 장바구니는 비로그인도 열려서
  // 그것만으로는 못 가른다 — 여기서 안 막으면 <b>주문서를 다 그린 뒤 결제 버튼에서 401</b> 이 난다.
  // 401 을 만나면 이 입구가 로그인 화면으로 보낸다.
  await apiSession("/api/me");

  const cart = await apiSession<Cart>("/api/cart");
  const buyable = cart.items.filter((item) => item.available);

  if (buyable.length === 0) {
    return <Empty hasItems={cart.items.length > 0} />;
  }

  const groups = groupBySeller(buyable);

  // 셀러 수만큼 부른다. 한 주문의 셀러는 많아야 몇이고, 묶어서 받는 경로를 새로 내면
  // 상품 상세가 쓰는 것과 계약이 두 벌이 된다(`SellerQuery` 주석).
  const identities = await Promise.all(
    groups.map((group) => apiPublic<SellerIdentity>(`/api/sellers/${group.sellerId}`)),
  );

  const lines: OrderLine[] = groups.map((group, index) => ({
    sellerId: group.sellerId,
    itemsAmount: group.items.reduce(
      (sum, item) => sum + item.priceInclVat * item.quantity,
      0,
    ),
    shippingFee: identities[index].defaultShippingFee,
  }));

  const itemsTotal = lines.reduce((sum, line) => sum + line.itemsAmount, 0);
  const shippingTotal = lines.reduce((sum, line) => sum + line.shippingFee, 0);

  return (
    <div className="mx-auto grid w-full max-w-4xl flex-1 content-start gap-8 px-4 py-16">
      <div className="grid gap-2">
        <h1 className="text-3xl font-semibold tracking-tight">주문서</h1>
        <p className="text-sm text-text-muted">
          주문하시기 전에 판매자 정보와 결제하실 금액을 확인해 주세요.
        </p>
      </div>

      {cart.items.length > buyable.length ? (
        <p role="status" className="text-sm text-text-muted">
          지금 구매할 수 없는 상품은 주문에서 빠집니다.{" "}
          <Link href="/cart" className="font-semibold text-accent-text underline underline-offset-4">
            장바구니에서 확인하기
          </Link>
        </p>
      ) : null}

      <div className="grid gap-4">
        {groups.map((group, index) => (
          <SellerGroup
            key={group.sellerId}
            group={group}
            seller={identities[index]}
            line={lines[index]}
          />
        ))}
      </div>

      <Amounts itemsTotal={itemsTotal} shippingTotal={shippingTotal} />

      <BrokerageNotice />

      <CheckoutForm
        cartItemIds={buyable.map((item) => item.cartItemId)}
        payableAmount={itemsTotal + shippingTotal}
        madeToOrderNames={buyable
          .filter((item) => item.withdrawalRestrictionReason === "made_to_order")
          .map((item) => item.productName)}
      />
    </div>
  );
}

/**
 * 셀러 하나와 그 묶음.
 *
 * <p><b>신원을 접어 둔다.</b> 일곱 칸을 셀러마다 펼쳐 두면 주문서가 길어져서 정작
 * 금액과 배송지가 화면 밖으로 밀린다. `details` 는 접혀 있어도 <b>문서 안에 있는 내용</b>이라
 * 보조기술이 찾고 브라우저 검색에도 걸린다 — 눌러야 서버에서 받아 오는 방식과 다르다.
 */
function SellerGroup({
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
function Amounts({
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
 * 주문할 것이 없을 때.
 *
 * <p><b>둘을 가른다</b>(`D20` 「빈 상태」). 아무것도 안 담은 것과, 담긴 것이 전부 품절이라
 * 주문할 수 없는 것은 사용자가 할 일이 다르다.
 */
function Empty({ hasItems }: { hasItems: boolean }) {
  return (
    <div className="mx-auto grid w-full max-w-4xl flex-1 content-center justify-items-start gap-3 px-4 py-16">
      <h1 className="text-2xl font-semibold tracking-tight">주문서</h1>
      <p className="text-sm text-text-muted">
        {hasItems
          ? "담아 두신 상품을 지금은 구매할 수 없습니다."
          : "주문하실 상품이 없습니다."}
      </p>
      <Link
        href={hasItems ? "/cart" : "/products"}
        className="
          text-sm font-semibold text-accent-text underline underline-offset-4
          focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
        "
      >
        {hasItems ? "장바구니로 가기" : "상품 둘러보기"}
      </Link>
    </div>
  );
}

type SellerGrouping = { sellerId: number; sellerName: string; items: CartItem[] };

/** 담은 순서를 안에서 지킨다. 서버가 담은 시각 순으로 내려주므로 다시 정렬하지 않는다 */
function groupBySeller(items: CartItem[]): SellerGrouping[] {
  const groups = new Map<number, SellerGrouping>();

  for (const item of items) {
    const group = groups.get(item.sellerId);
    if (group) {
      group.items.push(item);
    } else {
      groups.set(item.sellerId, {
        sellerId: item.sellerId,
        sellerName: item.sellerName,
        items: [item],
      });
    }
  }
  return [...groups.values()];
}

/** 부가세가 이미 포함된 값이다(`D8`). 화면이 다시 더하지 않는다 */
function priceText(amount: number): string {
  return `${amount.toLocaleString("ko-KR")}원`;
}
