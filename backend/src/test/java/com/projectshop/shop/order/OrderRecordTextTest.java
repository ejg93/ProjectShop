package com.projectshop.shop.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 소비자가 내려받는 거래기록이 그 거래의 전부를 담는가(`D2` R6, 제6조제1항 후단).
 *
 * <p>DB 를 안 쓴다. 입력이 이미 판정을 지난 {@code OrderQuery.Detail} 이라
 * <b>여기서 볼 것은 「무엇을 적는가」뿐</b>이고, 누가 볼 수 있나는 조회 쪽 테스트가 진다.
 */
@DisplayName("거래기록 파일")
class OrderRecordTextTest {

    private static final OffsetDateTime KST_NOON =
            OffsetDateTime.of(2026, 8, 21, 12, 0, 0, 0, ZoneOffset.ofHours(9));

    private static OrderQuery.Detail detail() {
        OrderQuery.Item item = new OrderQuery.Item("데모 티셔츠", "검정 / M", 2, 29_000L, 58_000L);
        OrderQuery.SellerOrder sellerOrder = new OrderQuery.SellerOrder(
                "20260821-7QX4P4-1", "데모셀러", "DELIVERED", 3_000L,
                KST_NOON, KST_NOON, KST_NOON, KST_NOON, KST_NOON, false,
                List.of(item), List.of("CONFIRM"));

        return new OrderQuery.Detail("20260821-7QX4P4", "PAID", 58_000L, 3_000L, 61_000L,
                KST_NOON, List.of(sellerOrder),
                List.of(new OrderQuery.HistoryEntry("데모셀러", "PREPARING", "SHIPPING",
                        "seller", KST_NOON)),
                new OrderQuery.Shipping("홍길동", "010-0000-0000", "06236",
                        "서울 강남구 테헤란로 1", "101동 1001호", "문 앞에 놔 주세요"),
                new OrderQuery.Payment("APPROVED", "CARD", "M12345678", "비자", "4242", null, KST_NOON),
                List.of(new OrderQuery.Refund("R-20260821-ABC123", "20260821-7QX4P4-1", "REQUESTED",
                        "withdrawal", 61_000L, KST_NOON, false, KST_NOON)),
                List.of(new OrderQuery.ContractDocument("terms", "terms_of_service", "이용약관", 3,
                        KST_NOON)),
                List.of());
    }

    @Nested
    @DisplayName("담는 것")
    class Contents {

        @Test
        @DisplayName("주문·금액·결제·배송지·처리 내역·계약 문서가 다 들어간다")
        void holdsTheWholeTransaction() {
            String record = OrderRecordText.of(detail(), KST_NOON);

            assertThat(record)
                    .describedAs("우리 화면 밖으로 나가는 사본이라 한 파일이 거래의 전부를 담아야 한다")
                    .contains("주문번호: 20260821-7QX4P4")
                    .contains("[ 주문 상품 ]")
                    .contains("데모 티셔츠 / 검정 / M")
                    .contains("[ 결제 금액 ]")
                    .contains("[ 결제 ]")
                    .contains("[ 배송지 ]")
                    .contains("[ 환불 ]")
                    .contains("[ 처리 내역 ]")
                    .contains("[ 계약 시점 문서 ]");
        }

        @Test
        @DisplayName("금액이 사람이 읽는 모양이다")
        void writesMoneyForPeople() {
            String record = OrderRecordText.of(detail(), KST_NOON);

            assertThat(record)
                    .describedAs("자릿점이 없으면 611000 과 61000 을 눈으로 못 가른다")
                    .contains("결제 금액: 61,000원")
                    .contains("단가 29,000원");
        }

        @Test
        @DisplayName("상태가 코드가 아니라 말이다")
        void writesStatusInWords() {
            String record = OrderRecordText.of(detail(), KST_NOON);

            assertThat(record)
                    .describedAs("소비자가 읽는 문서라 `PAID` 로는 뜻이 안 선다(`D20`)")
                    .contains("주문상태: 결제 완료")
                    .contains("배송 준비 중 → 배송 중")
                    .doesNotContain("PAYMENT_PENDING");
        }

        @Test
        @DisplayName("시각이 KST 로 적힌다")
        void writesTimeInKst() {
            String record = OrderRecordText.of(detail(), KST_NOON);

            assertThat(record)
                    .describedAs("파일을 여는 사람은 우리 서버 시간대를 모른다(`D10`)")
                    .contains("2026-08-21 12:00 KST");
        }

        @Test
        @DisplayName("카드 정보가 파기된 뒤에도 결제 기록은 적힌다")
        void survivesPurgedCardInfo() {
            OrderQuery.Detail source = detail();
            OrderQuery.Payment purged = new OrderQuery.Payment("APPROVED", "CARD",
                    source.payment().approvalNumber(), null, null, null, KST_NOON);
            OrderQuery.Detail withoutCard = new OrderQuery.Detail(source.orderNumber(),
                    source.status(), source.totalAmount(), source.shippingFeeTotal(),
                    source.payableAmount(), source.createdAt(), source.sellerOrders(),
                    source.history(), source.shipping(), purged, source.refunds(),
                    source.contractDocuments(), source.visibleFieldGroups());

            String record = OrderRecordText.of(withoutCard, KST_NOON);

            assertThat(record)
                    .describedAs("카드 정보는 여섯 달 뒤에 사라지고 대금결제 기록은 5년을 산다(`5i-1`)")
                    .contains("수단: 신용·체크카드")
                    .contains("승인번호: M12345678")
                    .doesNotContain("****");
        }
    }

    @Nested
    @DisplayName("상태 문구는")
    class StatusLabels {

        private static final Path SCREEN_TEXT = Path.of("..", "frontend", "src", "lib", "order-text.ts");

        /**
         * 화면과 문서가 각자 표를 든다. <b>글자가 같아야 하는 것은 아니지만 덮는 코드는 같아야 한다</b> —
         * 새 상태를 한쪽에만 더하면 다른 쪽에서 그 상태가 이름을 잃고 코드가 그대로 노출된다.
         */
        @Test
        @DisplayName("화면 표와 같은 코드를 덮는다")
        void coversTheSameCodesAsScreens() throws IOException {
            String source = Files.readString(SCREEN_TEXT, StandardCharsets.UTF_8);

            assertThat(codesIn(source, "PAYMENT_STATUS"))
                    .describedAs("결제 상태를 한쪽에만 더하면 다른 쪽이 코드를 그대로 그린다")
                    .isEqualTo(new TreeSet<>(OrderRecordText.PAYMENT_STATUS.keySet()));
            assertThat(codesIn(source, "SHIPMENT_STATUS"))
                    .describedAs("배송 상태도 같다")
                    .isEqualTo(new TreeSet<>(OrderRecordText.SHIPMENT_STATUS.keySet()));
        }

        /** {@code const 이름: Record<string, string> = { CODE: "…", … }} 에서 코드만 뽑는다 */
        private Set<String> codesIn(String source, String table) {
            int start = source.indexOf("const " + table);
            int end = source.indexOf("};", start);
            String body = source.substring(start, end);

            Set<String> codes = new TreeSet<>();
            Matcher matcher = Pattern.compile("(?m)^\\s{2}([A-Z_]+):").matcher(body);
            while (matcher.find()) {
                codes.add(matcher.group(1));
            }
            return codes;
        }
    }
}
