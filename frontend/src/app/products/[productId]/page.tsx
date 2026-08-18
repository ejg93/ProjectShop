import type { Metadata } from "next";
import Image from "next/image";
import { notFound } from "next/navigation";

import { ApiError, apiPublic } from "@/lib/api";
import {
  BrokerageNotice,
  SellerIdentityTable,
  type SellerIdentity,
} from "@/components/seller-identity";

import { PurchasePanel } from "./purchase-panel";
import type { OptionGroup, PublicSku } from "./purchase-panel";

export const metadata: Metadata = { title: "상품 상세 · ProjectShop" };

/** 공개 상세(`8b`). 재고 수량과 수수료율은 안 온다 */
type ProductDetail = {
  productId: number;
  sellerId: number;
  sellerName: string;
  name: string;
  description: string | null;
  withdrawalRestricted: boolean;
  withdrawalRestrictionReason: WithdrawalReason | null;
  options: OptionGroup[];
  skus: PublicSku[];
};

/** 법이 인정한 셋뿐이다(전자상거래법 제17조제2항, `D2` R4) */
type WithdrawalReason = "MADE_TO_ORDER" | "PERISHABLE" | "SEALED_COPYRIGHT";

/**
 * 청약철회를 제한하는 사유를 사람이 읽는 말로.
 *
 * <p><b>서버가 준 값을 그대로 안 쓴다</b>(`D20` 「서버 문구를 그대로 안 쓴다」).
 * 저장값은 열거값이라 화면 문구가 아니다.
 */
const WITHDRAWAL_REASON_TEXT: Record<WithdrawalReason, string> = {
  MADE_TO_ORDER: "주문을 받고 만드는 상품이라 청약철회가 제한됩니다.",
  PERISHABLE: "쉽게 상하는 상품이라 청약철회가 제한됩니다.",
  SEALED_COPYRIGHT: "포장을 뜯으면 복제가 가능한 상품이라 청약철회가 제한됩니다.",
};

/** 신고번호가 없는 이유. 빈 칸으로 두면 「아직 안 넣은 것」과 구분이 안 된다(`14a`) */
/**
 * 상품 상세(`14b`).
 *
 * <p><b>법이 요구하는 고지 셋이 이 화면에 다 걸린다</b>(점검 A).
 *
 * <table>
 *   <tr><td>`R1`</td><td>셀러 신원</td><td>전자상거래법 제10조·제13조</td></tr>
 *   <tr><td>`R2`</td><td>중개자 지위</td><td>전자상거래법 제20조</td></tr>
 *   <tr><td>`R4`</td><td>청약철회 제한 사유</td><td>제17조제2항</td></tr>
 * </table>
 *
 * <p>`R4` 가 특히 그렇다 — <b>표시가 제한의 성립 요건이다</b>(제17조제2항 단서).
 * 서버는 이미 반품을 막는데({@code requireWithdrawable}) 사람에게 보이는 자리가 없어서
 * <b>집행만 있고 성립 근거가 반쪽</b>이었다.
 */
export default async function ProductDetailPage({
  params,
}: {
  params: Promise<{ productId: string }>;
}) {
  const { productId } = await params;

  const product = await findProduct(productId);
  // 셀러 신원은 상품을 받은 뒤에야 부를 수 있다. 어느 셀러인지가 상품에 있다.
  const seller = await apiPublic<SellerIdentity>(`/api/sellers/${product.sellerId}`);

  return (
    <div className="mx-auto grid w-full max-w-6xl flex-1 content-start gap-12 px-4 py-16">
      <div className="grid gap-10 md:grid-cols-2">
        <Image
          // 자리표시라 상품과 무관한 사진이다. 읽어 주면 없는 정보를 있는 것처럼 말한다(`D20`).
          src={`https://picsum.photos/seed/${product.productId}/800/600`}
          alt=""
          width={800}
          height={600}
          priority
          className="aspect-[4/3] w-full rounded-ui object-cover"
        />

        <div className="grid content-start gap-6">
          <div className="grid gap-2">
            <p className="text-sm text-text-muted">{product.sellerName}</p>
            <h1 className="text-2xl font-semibold tracking-tight">{product.name}</h1>
            {product.description ? (
              <p className="text-sm leading-relaxed text-text-muted">{product.description}</p>
            ) : null}
          </div>

          <PurchasePanel options={product.options} skus={product.skus} />

          {product.withdrawalRestricted ? (
            <Withdrawal reason={product.withdrawalRestrictionReason} />
          ) : null}
        </div>
      </div>

      <BrokerageNotice />

      <section aria-labelledby="seller-heading" className="grid gap-3 border-t border-border pt-6">
        <h2 id="seller-heading" className="text-sm font-semibold">
          판매자 정보
        </h2>
        <SellerIdentityTable seller={seller} />
      </section>
    </div>
  );
}

/**
 * 청약철회 제한 고지(`D2` R4).
 *
 * <p><b>이 자리가 비면 제한 자체가 성립하지 않는다.</b> 그래서 사유를 모르는 경우에도
 * 안 그리지 않고, 제한이 있다는 사실만이라도 알린다 — 표시가 없으면 서버의 거부가 근거를 잃는다.
 */
function Withdrawal({ reason }: { reason: WithdrawalReason | null }) {
  return (
    <section
      aria-labelledby="withdrawal-heading"
      className="grid gap-1 rounded-ui border border-border bg-surface-raised p-4"
    >
      <h2 id="withdrawal-heading" className="text-sm font-semibold">
        청약철회 제한 안내
      </h2>
      <p className="text-sm text-text-muted">
        {reason
          ? WITHDRAWAL_REASON_TEXT[reason]
          : "이 상품은 청약철회가 제한됩니다."}
      </p>
    </section>
  );
}

/**
 * 없는 상품이면 404 로 답한다.
 *
 * <p>화면만 바꿔 그리고 200 으로 답하면 <b>없는 주소가 있는 것으로 기록된다</b> —
 * 검색 엔진과 로그가 그 값을 믿는다. 서버가 파는 중이 아닌 것과 없는 것을
 * 같은 404 로 주므로(`8b`) 여기서도 안 가른다.
 */
async function findProduct(rawId: string): Promise<ProductDetail> {
  // 숫자가 아니면 상품 주소가 아니다. 서버에 물으면 400 이 오는데,
  // 그건 <b>고칠 수 있는 요청</b>이라는 뜻이라 사람이 볼 답으로는 틀렸다.
  if (!/^\d+$/.test(rawId)) {
    notFound();
  }

  try {
    return await apiPublic<ProductDetail>(`/api/products/${rawId}`);
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      notFound();
    }
    throw error;
  }
}
