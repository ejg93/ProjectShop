package com.projectshop.shop.order;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 주문 상세를 소비자가 내려받아 보관할 글로 옮긴다. 파일 하나가 그 거래의 전부를 담는다.
 *
 * <p><b>전자상거래법 제6조제1항 후단의 「보존」이 이 자리다</b>(`D2` R6) — 앞엣것(열람)은
 * 주문 상세 화면이 채웠고(`15-3`), 우리 화면 밖으로 나가는 사본은 없었다.
 * 제5조제5항은 소비자가 요청하면 확인·증명을 <b>전자문서로</b> 주라고 한다.
 *
 * <p><b>판정을 다시 안 한다.</b> 입력이 {@code OrderQuery.Detail} 이라 그 사람에게 안 보이는 것은
 * 여기 오기 전에 이미 빠져 있다(`4d` 필드 그룹). 파일을 만들며 표를 다시 읽으면
 * 판정을 지나치는 두 번째 경로가 생긴다.
 *
 * <p><b>상태 문구가 화면과 두 벌이 됐다.</b> 화면 라벨은 {@code lib/order-text.ts} 에 있고
 * 서버는 지금까지 코드만 내렸다(`D5`). 문서는 서버가 만드는 것이라 여기에도 표가 필요한데,
 * 두 표의 <b>코드 집합</b>이 갈리면 새 상태가 한쪽에서만 이름을 잃는다 —
 * {@code OrderRecordTextTest} 가 프론트 파일을 읽어 그 집합을 대조한다.
 *
 * <p>글은 사람이 읽는 것이라 존댓말이다(`D20`). 개발자가 읽는 글과 규칙이 다르다.
 */
final class OrderRecordText {

    /** 업무 시각은 KST 다(`D10`). 파일을 여는 사람은 우리 서버 시간대를 모른다 */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final String LINE = "=".repeat(60);

    /** 주문 층의 상태. 화면의 `PAYMENT_STATUS` 와 같은 코드를 덮는다 */
    static final Map<String, String> PAYMENT_STATUS = Map.of(
            "PAYMENT_PENDING", "결제 대기",
            "PAID", "결제 완료",
            "PAYMENT_EXPIRED", "결제 시간 만료",
            "PAYMENT_FAILED", "결제 실패");

    /** 셀러 묶음의 상태. 화면의 `SHIPMENT_STATUS` 와 같은 코드를 덮는다 */
    static final Map<String, String> SHIPMENT_STATUS = Map.of(
            "PREPARING", "배송 준비 중",
            "SHIPPING", "배송 중",
            "DELIVERED", "배송 완료",
            "CONFIRMED", "구매 확정",
            "CANCELLED", "취소됨",
            "RETURN_REQUESTED", "반품 접수",
            "RETURNED", "반품 완료");

    private static final Map<String, String> PAYMENT_RESULT = Map.of(
            "APPROVED", "승인",
            "FAILED", "거절");

    private static final Map<String, String> PAYMENT_METHOD = Map.of(
            "CARD", "신용·체크카드",
            "TRANSFER", "계좌이체");

    private static final Map<String, String> REFUND_STATUS = Map.of(
            "REQUESTED", "접수",
            "APPROVED", "환급 완료",
            "REJECTED", "반려");

    private OrderRecordText() {
    }

    /**
     * 거래기록 한 벌을 만든다.
     *
     * @param detail   그 사람이 볼 수 있는 것만 담긴 주문 상세
     * @param issuedAt 발급 시각. 언제 뽑은 사본인지가 없으면 여러 벌을 구분할 수 없다
     */
    static String of(OrderQuery.Detail detail, OffsetDateTime issuedAt) {
        StringBuilder out = new StringBuilder();

        out.append("거래기록\n").append(LINE).append('\n');
        row(out, "주문번호", detail.orderNumber());
        row(out, "주문일시", stamp(detail.createdAt()));
        row(out, "주문상태", label(PAYMENT_STATUS, detail.status()));
        out.append('\n');

        appendSellerOrders(out, detail.sellerOrders());
        appendAmounts(out, detail);
        appendPayment(out, detail.payment());
        appendShipping(out, detail.shipping());
        appendRefunds(out, detail.refunds());
        appendHistory(out, detail.history());
        appendContracts(out, detail.contractDocuments());

        out.append(LINE).append('\n');
        out.append("이 파일은 전자상거래법 제6조제1항에 따라 제공되는 거래기록입니다.\n");
        out.append("발급일시: ").append(stamp(issuedAt)).append('\n');
        return out.toString();
    }

    private static void appendSellerOrders(StringBuilder out, List<OrderQuery.SellerOrder> sellerOrders) {
        if (sellerOrders == null || sellerOrders.isEmpty()) {
            return;
        }
        out.append("[ 주문 상품 ]\n");
        for (OrderQuery.SellerOrder sellerOrder : sellerOrders) {
            out.append("· 판매자 ").append(sellerOrder.sellerName())
                    .append(" · 묶음번호 ").append(sellerOrder.sellerOrderNumber())
                    .append(" · ").append(label(SHIPMENT_STATUS, sellerOrder.status())).append('\n');
            for (OrderQuery.Item item : sellerOrder.items()) {
                out.append("  - ").append(item.productName());
                if (notBlank(item.optionLabel())) {
                    out.append(" / ").append(item.optionLabel());
                }
                out.append('\n').append("    수량 ").append(item.quantity())
                        .append(" · 단가 ").append(money(item.unitPriceInclVat()))
                        .append(" · 금액 ").append(money(item.lineAmount())).append('\n');
            }
            out.append("  배송비 ").append(money(sellerOrder.shippingFee())).append('\n');
        }
        out.append('\n');
    }

    private static void appendAmounts(StringBuilder out, OrderQuery.Detail detail) {
        out.append("[ 결제 금액 ]\n");
        row(out, "상품 합계", money(detail.totalAmount()));
        row(out, "배송비 합계", money(detail.shippingFeeTotal()));
        row(out, "결제 금액", money(detail.payableAmount()));
        out.append('\n');
    }

    private static void appendPayment(StringBuilder out, OrderQuery.Payment payment) {
        if (payment == null) {
            return;
        }
        out.append("[ 결제 ]\n");
        row(out, "결과", label(PAYMENT_RESULT, payment.status()));

        String method = label(PAYMENT_METHOD, payment.method());
        // 카드 정보는 거래가 끝나고 여섯 달 뒤에 사라진다(`5i-1`). 그 뒤에는 수단만 남는다.
        if (notBlank(payment.cardIssuer())) {
            method = method + " (" + payment.cardIssuer() + " ****" + payment.cardLast4() + ")";
        }
        row(out, "수단", method);

        if (notBlank(payment.approvalNumber())) {
            row(out, "승인번호", payment.approvalNumber());
        }
        if (payment.paidAt() != null) {
            row(out, "결제일시", stamp(payment.paidAt()));
        }
        out.append('\n');
    }

    private static void appendShipping(StringBuilder out, OrderQuery.Shipping shipping) {
        if (shipping == null) {
            return;
        }
        out.append("[ 배송지 ]\n");
        row(out, "받는 분", shipping.receiverName());
        row(out, "연락처", shipping.receiverPhone());

        String address = "(" + shipping.postalCode() + ") " + shipping.address1();
        if (notBlank(shipping.address2())) {
            address = address + " " + shipping.address2();
        }
        row(out, "주소", address);

        if (notBlank(shipping.deliveryMemo())) {
            row(out, "배송 요청", shipping.deliveryMemo());
        }
        out.append('\n');
    }

    private static void appendRefunds(StringBuilder out, List<OrderQuery.Refund> refunds) {
        if (refunds == null || refunds.isEmpty()) {
            return;
        }
        out.append("[ 환불 ]\n");
        for (OrderQuery.Refund refund : refunds) {
            out.append("· ").append(refund.refundNumber())
                    .append(" · ").append(money(refund.amount()))
                    .append(" · ").append(label(REFUND_STATUS, refund.status()))
                    .append(" · 요청일 ").append(stamp(refund.createdAt())).append('\n');
        }
        out.append('\n');
    }

    private static void appendHistory(StringBuilder out, List<OrderQuery.HistoryEntry> history) {
        if (history == null || history.isEmpty()) {
            return;
        }
        // 제6조제3항이 요구하는 열람이 이 대목이다 — 「언제 무엇이 있었나」에 답한다.
        out.append("[ 처리 내역 ]\n");
        for (OrderQuery.HistoryEntry entry : history) {
            out.append("· ").append(stamp(entry.occurredAt())).append("  ")
                    .append(status(entry.fromStatus())).append(" → ").append(status(entry.toStatus()));
            if (notBlank(entry.sellerName())) {
                out.append(" (").append(entry.sellerName()).append(')');
            }
            out.append('\n');
        }
        out.append('\n');
    }

    private static void appendContracts(StringBuilder out, List<OrderQuery.ContractDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        // 계약 시점의 판이다(`15-4`). 지금 판을 적으면 지나간 계약의 조건이 바뀐 것처럼 보인다.
        out.append("[ 계약 시점 문서 ]\n");
        for (OrderQuery.ContractDocument document : documents) {
            out.append("· ").append(document.title())
                    .append(" 제").append(document.version()).append("판");
            if (document.effectiveAt() != null) {
                out.append(" (").append(stamp(document.effectiveAt())).append(" 시행)");
            }
            out.append('\n');
        }
        out.append('\n');
    }

    /** 주문 층과 묶음 층이 한 줄에 섞여 오는 자리가 처리 내역이다 */
    private static String status(String code) {
        if (code == null) {
            return "-";
        }
        String label = PAYMENT_STATUS.get(code);
        return label != null ? label : label(SHIPMENT_STATUS, code);
    }

    /** 모르는 코드는 코드 그대로 적는다. 문서에 빈칸을 남기면 무엇이 빠졌는지가 안 보인다 */
    private static String label(Map<String, String> table, String code) {
        if (code == null) {
            return "-";
        }
        String label = table.get(code);
        return label != null ? label : code;
    }

    private static void row(StringBuilder out, String label, String value) {
        out.append(label).append(": ").append(notBlank(value) ? value : "-").append('\n');
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String money(long amount) {
        return String.format("%,d원", amount);
    }

    private static String stamp(OffsetDateTime at) {
        return at == null ? "-" : STAMP.format(at.atZoneSameInstant(KST)) + " KST";
    }
}
