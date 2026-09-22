import type { Metadata } from "next";

import { apiSession } from "@/lib/api-session";
import { dateTimeText, priceText } from "@/lib/format";

import { RegisterCouponForm } from "./register-coupon-form";

export const metadata: Metadata = { title: "내 쿠폰 · ProjectShop" };

/**
 * 내가 받은 쿠폰 한 장. 서버의 `CouponQuery.Issued` 와 짝이다.
 *
 * <p><b>코드가 없다.</b> 받고 나면 코드로 할 일이 없고, 실으면 받은 사람이 그것을
 * 퍼뜨리는 경로가 된다 — 쿠폰이 사람에 붙어 있는 설계와 어긋난다(`49`).
 */
type MyCoupon = {
  couponIssueId: number;
  name: string;
  /** `amount` 면 원, `percent` 면 bp(1000 = 10.00%) */
  discountKind: string;
  discountValue: number;
  /** 정률의 상한. 정액에는 없다 */
  maxDiscountAmount: number | null;
  minOrderAmount: number;
  expiresAt: string;
  /** 썼으면 그 시각. 안 썼으면 null */
  usedAt: string | null;
};

type MyCouponPage = {
  items: MyCoupon[];
  page: number;
  size: number;
  total: number;
};

/**
 * 내 쿠폰함(`Q163`).
 *
 * <p><b>쓴 것과 지난 것도 같이 그린다.</b> 안 보여 주면 「분명히 받았는데 없다」에 답할
 * 자리가 없다 — 서버가 셋을 다 내려주고 화면이 갈라 그린다(`D20`).
 *
 * <p><b>받는 자리가 목록이 아니라 코드 칸이다</b>(사용자 결정, 2026-09-22). 받을 수 있는
 * 쿠폰을 뿌리면 아직 안 알린 코드가 통째로 새서, 사는 사람에게 정의 조회를 안 줬다(`V91`).
 */
export default async function MyCouponsPage() {
  const page = await apiSession<MyCouponPage>("/api/me/coupons?page=0&size=50");

  const usable = page.items.filter((coupon) => coupon.usedAt === null);
  const finished = page.items.filter((coupon) => coupon.usedAt !== null);

  return (
    <div className="mx-auto grid w-full max-w-3xl flex-1 content-start gap-8 px-4 py-16">
      <div className="grid gap-2">
        <h1 className="text-3xl font-semibold tracking-tight">내 쿠폰</h1>
        <p className="text-sm text-text-muted">
          받으신 쿠폰은 주문하실 때 한 장만 쓰실 수 있습니다.
        </p>
      </div>

      <RegisterCouponForm />

      <section aria-labelledby="usable-heading" className="grid gap-3 border-t border-border pt-6">
        <h2 id="usable-heading" className="text-lg font-semibold">
          쓰실 수 있는 쿠폰
        </h2>

        {usable.length > 0 ? (
          <ul className="grid gap-3">
            {usable.map((coupon) => (
              <CouponCard key={coupon.couponIssueId} coupon={coupon} />
            ))}
          </ul>
        ) : (
          <p className="text-sm text-text-muted">
            지금 쓰실 수 있는 쿠폰이 없습니다. 받으신 코드를 위에 입력해 주세요.
          </p>
        )}
      </section>

      {finished.length > 0 ? (
        <section
          aria-labelledby="finished-heading"
          className="grid gap-3 border-t border-border pt-6"
        >
          <h2 id="finished-heading" className="text-lg font-semibold">
            다 쓰신 쿠폰
          </h2>
          <ul className="grid gap-3">
            {finished.map((coupon) => (
              <CouponCard key={coupon.couponIssueId} coupon={coupon} />
            ))}
          </ul>
        </section>
      ) : null}
    </div>
  );
}

/**
 * 쿠폰 한 장. <b>할인 폭을 화면이 계산하지 않는다</b> — 정률의 실제 할인액은 장바구니가
 * 정해져야 나오고, 여기서 미리 셈하면 <b>주문 화면의 값과 갈린다</b>(`50`).
 */
function CouponCard({ coupon }: { coupon: MyCoupon }) {
  const used = coupon.usedAt !== null;

  return (
    <li
      className={`grid gap-1 rounded-ui border border-border px-4 py-3 ${
        used ? "bg-surface text-text-muted" : "bg-surface-raised"
      }`}
    >
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <span className="text-sm font-medium">{coupon.name}</span>
        <span className="text-base font-semibold">{discountText(coupon)}</span>
      </div>

      <p className="text-xs text-text-muted">
        {coupon.minOrderAmount > 0
          ? `${priceText(coupon.minOrderAmount)} 이상 주문하실 때`
          : "주문 금액 조건 없음"}
        {coupon.maxDiscountAmount !== null
          ? ` · 최대 ${priceText(coupon.maxDiscountAmount)}`
          : ""}
      </p>

      <p className="text-xs text-text-muted">
        {used ? `${dateTimeText(coupon.usedAt!)}에 사용` : `${dateTimeText(coupon.expiresAt)}까지`}
      </p>
    </li>
  );
}

/** 정률은 bp 라 100으로 나눈다. 뜻은 `discountKind` 가 정한다(`49`) */
function discountText(coupon: MyCoupon): string {
  return coupon.discountKind === "percent"
    ? `${coupon.discountValue / 100}%`
    : `${priceText(coupon.discountValue)}`;
}
