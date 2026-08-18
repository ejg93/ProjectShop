import Link from "next/link";

/**
 * 법이 표시를 요구하는 셀러 정보와 중개자 고지.
 *
 * <p><b>부르는 곳이 둘이라 컴포넌트로 뺐다</b>(청크 15-2) — 상품 상세(`14b`)와 주문서(`15-2`).
 * 사본을 두면 한쪽 문안만 다듬는 날이 오고, <b>그때 법 요건을 반쯤 지킨 화면</b>이 생긴다.
 *
 * <p>표시 시점이 둘인 것은 법이 그렇게 요구해서다. 제10조·제13조가 <b>사이버몰 표시</b>를,
 * 제20조제2항이 중개자에게 <b>청약 이전 제공</b>을 각각 요구한다 — 앞이 상품 상세고 뒤가 주문서다.
 */

/** 셀러 신원(`14a`). 법이 표시를 요구하는 값이라 공개로 나온다(`D2` R1) */
export type SellerIdentity = {
  sellerId: number;
  name: string;
  businessName: string;
  representativeName: string;
  businessRegNo: string;
  address: string;
  phone: string;
  email: string;
  mailOrderNo: string | null;
  mailOrderExemptReason: MailOrderExemption | null;
  defaultShippingFee: number;
};

/** 통신판매업 신고를 안 해도 되는 사유(공정거래위원회 고시, `D2` R1) */
export type MailOrderExemption =
  | "SIMPLIFIED_TAXPAYER"
  | "UNDER_50_TRANSACTIONS"
  | "NON_BUSINESS";

const EXEMPTION_TEXT: Record<MailOrderExemption, string> = {
  SIMPLIFIED_TAXPAYER: "간이과세자로 신고 면제",
  UNDER_50_TRANSACTIONS: "거래 횟수 기준 미만으로 신고 면제",
  NON_BUSINESS: "사업자가 아니어서 신고 면제",
};

/**
 * 셀러 신원 표시(`D2` R1, 전자상거래법 제10조·제13조).
 *
 * <p><b>표로 그린다.</b> 항목과 값이 짝지어진 것이라 문단으로 늘어놓으면
 * 보조기술이 어느 값이 어느 항목인지 못 잇는다.
 *
 * <p><b>제목을 안 그린다.</b> 부르는 화면마다 이 표가 놓이는 자리가 달라서다 —
 * 상품 상세에서는 절 하나고, 주문서에서는 셀러 묶음 안에 접혀 들어간다.
 * 제목을 여기서 그리면 <b>같은 `id` 가 한 쪽에 여럿</b>이 되거나 제목 층이 어긋난다.
 */
export function SellerIdentityTable({ seller }: { seller: SellerIdentity }) {
  const rows: [string, string][] = [
    ["상호", seller.businessName],
    ["대표자", seller.representativeName],
    ["사업자등록번호", seller.businessRegNo],
    [
      "통신판매업 신고번호",
      seller.mailOrderNo ??
        (seller.mailOrderExemptReason
          ? EXEMPTION_TEXT[seller.mailOrderExemptReason]
          : "확인 중"),
    ],
    ["사업장 주소", seller.address],
    ["연락처", seller.phone],
    ["전자우편", seller.email],
  ];

  return (
    // 표가 좁은 화면에서 넘칠 수 있다. 몸통만 가로로 굴리고 쪽 전체는 안 흔든다
    <div className="overflow-x-auto">
      <table className="w-full max-w-3xl border-collapse text-sm">
        <tbody>
          {rows.map(([label, value]) => (
            <tr key={label} className="border-b border-border last:border-b-0">
              <th
                scope="row"
                className="w-40 whitespace-nowrap py-2 pr-4 text-left font-normal text-text-muted"
              >
                {label}
              </th>
              <td className="py-2">{value}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/**
 * 중개자 지위 고지(`D2` R2, 전자상거래법 제20조).
 *
 * <p><b>문안이 두 벌이 된다는 것을 알고 둔다.</b> 같은 뜻이 약관 제2조(`V11`)에도 있다 —
 * 여기서 약관 본문을 잘라 오면 절 제목이 바뀔 때 고지가 조용히 사라지고, 전문을 붙이면
 * 화면마다 약관 한 벌이 딸려 나온다. 대신 전문으로 가는 길을 옆에 둔다(사용자 선택).
 *
 * @param headingId 한 쪽에 두 번 그리지 않지만, 쓰는 화면이 제목 층을 정할 수 있게 받는다
 */
export function BrokerageNotice({
  headingId = "brokerage-heading",
}: {
  headingId?: string;
}) {
  return (
    <section
      aria-labelledby={headingId}
      className="grid justify-items-start gap-2 border-t border-border pt-6"
    >
      <h2 id={headingId} className="text-sm font-semibold">
        통신판매중개자 고지
      </h2>
      <p className="max-w-3xl text-sm leading-relaxed text-text-muted">
        이 상품은 판매자가 등록하고 판매합니다.<br />
        ProjectShop 은 통신판매중개자로서 통신판매의 당사자가 아니며, 상품 정보와 거래에 대한
        책임은 판매자에게 있습니다.
      </p>
      <Link
        href="/terms"
        className="
          text-sm font-semibold text-accent-text underline underline-offset-4
          focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
        "
      >
        이용약관 보기
      </Link>
    </section>
  );
}
