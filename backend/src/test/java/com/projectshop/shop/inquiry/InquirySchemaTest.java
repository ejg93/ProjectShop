package com.projectshop.shop.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.order.TransactionPurgeService;

/**
 * 문의 표가 법 요건 셋을 실제로 받을 수 있나(청크 58).
 *
 * <table>
 *   <caption>이 표가 닫는 요건</caption>
 *   <tr><th>요건</th><th>조문</th><th>무엇이 없었나</th></tr>
 *   <tr><td>`R28`</td><td>개인정보법 제37조·제38조제5항</td><td>처리정지·이의제기를 <b>받을 자리</b></td></tr>
 *   <tr><td>`R25`</td><td>전자상거래법 제20조제3항</td><td>불만·분쟁을 <b>받을 자리</b></td></tr>
 *   <tr><td>`R34`</td><td>정보통신망법 제50조의7</td><td>광고 게시물을 <b>내릴 수단</b></td></tr>
 * </table>
 *
 * <p><b>앞의 둘은 받을 자리가 있어야 성립한다.</b> 방침이 창구를 가리키는 문구만 있으면
 * 「어디로 받나」에 코드가 답을 못 한다 — 문구는 자원이 아니다.
 */
@DisplayName("문의 스키마")
class InquirySchemaTest extends PostgresTestBase {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private TransactionPurgeService purgeService;

    private AuthFixture fixture;
    private long userId;
    private long productId;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);
        userId = fixture.insertUser("inquiry-user@test.local", "묻는사람");
        productId = insertProduct(fixture.insertSeller("s-inquiry", "문의셀러"));
    }

    @Nested
    @DisplayName("종류가")
    class Kinds {

        @Test
        @DisplayName("상품 문의는 상품에 붙는다")
        void bindsAProductQuestionToItsProduct() {
            assertThatCode(() -> insert("product", productId))
                    .doesNotThrowAnyException();
        }

        /**
         * <b>처리정지는 「내 개인정보를 그만 쓰라」는 요구라 대상이 사람이다.</b>
         * 상품 번호를 요구하면 상품을 안 산 사람은 낼 수가 없고, 그러면 제37조제1항이
         * 인정한 권리를 우리가 스키마로 좁히는 것이 된다.
         */
        @Test
        @DisplayName("계정에 붙는 요구에는 상품이 없다")
        void bindsAPrivacyRequestToTheAccountOnly() {
            assertThatCode(() -> insert("processing_stop", null))
                    .doesNotThrowAnyException();
            assertThatCode(() -> insert("access_objection", null))
                    .doesNotThrowAnyException();
            assertThatCode(() -> insert("dispute", null))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("상품 문의에 상품이 없으면 안 들어간다")
        void rejectsAProductQuestionWithoutAProduct() {
            assertThatThrownBy(() -> insert("product", null))
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("계정 요구에 상품이 붙으면 안 들어간다")
        void rejectsAPrivacyRequestWithAProduct() {
            assertThatThrownBy(() -> insert("processing_stop", productId))
                    .as("그 행은 상품 화면의 목록에 섞여 나간다")
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("모르는 종류는 안 들어간다")
        void rejectsAnUnknownKind() {
            assertThatThrownBy(() -> insert("whatever", null))
                    .isInstanceOf(DataAccessException.class);
        }
    }

    @Nested
    @DisplayName("게시 중단은")
    class Blocking {

        @Test
        @DisplayName("광고를 사유로 내릴 수 있다")
        void blocksAnAdvertisement() {
            long inquiryId = insert("product", productId);

            assertThatCode(() -> block(inquiryId, "advertisement"))
                    .as("우리는 보내는 쪽이 아니라 운영자 쪽이다(제50조의7)")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("사유가 없으면 못 내린다")
        void requiresAReason() {
            long inquiryId = insert("product", productId);

            assertThatThrownBy(() -> jdbc.sql("""
                            update inquiry set status = 'blocked', blocked_at = now()
                             where inquiry_id = :id
                            """)
                    .param("id", inquiryId)
                    .update())
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("법이 안 정한 사유는 안 들어간다")
        void rejectsAnUnlistedReason() {
            long inquiryId = insert("product", productId);

            assertThatThrownBy(() -> block(inquiryId, "그냥 마음에 안 든다"))
                    .as("자유 텍스트로 두면 법이 인정하지 않는 사유가 화면에 나간다")
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("안 내린 문의에는 사유가 없다")
        void keepsTheReasonEmptyWhileVisible() {
            long inquiryId = insert("product", productId);

            assertThatThrownBy(() -> jdbc.sql("""
                            update inquiry set blocked_reason = 'advertisement'
                             where inquiry_id = :id
                            """)
                    .param("id", inquiryId)
                    .update())
                    .isInstanceOf(DataAccessException.class);
        }
    }

    @Nested
    @DisplayName("답변은")
    class Answers {

        @Test
        @DisplayName("답과 시각이 같이 찬다")
        void comesWithItsTime() {
            long inquiryId = insert("product", productId);

            assertThatCode(() -> jdbc.sql("""
                            update inquiry
                               set status = 'answered', answer = '답입니다', answered_at = now()
                             where inquiry_id = :id
                            """)
                    .param("id", inquiryId)
                    .update())
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("답 없이 답변 상태가 될 수 없다")
        void cannotBeMarkedWithoutText() {
            long inquiryId = insert("product", productId);

            assertThatThrownBy(() -> jdbc.sql("""
                            update inquiry set status = 'answered', answered_at = now()
                             where inquiry_id = :id
                            """)
                    .param("id", inquiryId)
                    .update())
                    .as("「답변인데 답이 없는」 행이 생긴다")
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("상태를 안 옮기고 답만 써 둘 수 없다")
        void cannotBeWrittenWithoutMovingTheStatus() {
            long inquiryId = insert("product", productId);

            assertThatThrownBy(() -> jdbc.sql("""
                            update inquiry set answer = '답입니다' where inquiry_id = :id
                            """)
                    .param("id", inquiryId)
                    .update())
                    .as("그 답은 아무 화면에도 안 나간다")
                    .isInstanceOf(DataAccessException.class);
        }
    }

    /**
     * <b>수집하는 코드가 파기하는 코드보다 먼저 나오면 그 사이가 위반 구간이다</b>(`D23`).
     *
     * <p>기간은 전자상거래법 시행령 제6조의 「소비자의 불만 또는 분쟁처리에 관한 기록」
     * 3년이다(`D2` R6).
     */
    @Nested
    @DisplayName("파기는")
    class Purge {

        @Test
        @DisplayName("3년이 지난 문의를 지운다")
        void deletesInquiriesPastThreeYears() {
            long inquiryId = insert("product", productId);
            backdate(inquiryId, 37);

            assertThat(purgeService.purge().inquiries()).isEqualTo(1);
            assertThat(exists(inquiryId)).isFalse();
        }

        @Test
        @DisplayName("3년이 안 된 문의는 남긴다")
        void keepsInquiriesWithinThreeYears() {
            long inquiryId = insert("product", productId);
            backdate(inquiryId, 35);

            purgeService.purge();
            assertThat(exists(inquiryId)).isTrue();
        }

        @Test
        @DisplayName("답이 안 나간 문의도 낸 날로 센다")
        void countsFromTheDayItWasFiled() {
            long inquiryId = insert("processing_stop", null);
            backdate(inquiryId, 37);

            assertThat(purgeService.purge().inquiries())
                    .as("답변일로 잡으면 답이 안 나간 문의가 영영 안 지워진다")
                    .isEqualTo(1);
        }
    }

    private long insert(String kind, Long productKey) {
        return jdbc.sql("""
                        insert into inquiry (inquiry_number, kind, product_id, user_id, question)
                        values (:number, :kind, :productId, :userId, '이 상품 언제 오나요')
                        returning inquiry_id
                        """)
                .param("number", inquiryNumber())
                .param("kind", kind)
                .param("productId", productKey)
                .param("userId", userId)
                .query(Long.class)
                .single();
    }

    private void block(long inquiryId, String reason) {
        jdbc.sql("""
                        update inquiry
                           set status = 'blocked', blocked_at = now(), blocked_reason = :reason
                         where inquiry_id = :id
                        """)
                .param("reason", reason)
                .param("id", inquiryId)
                .update();
    }

    private void backdate(long inquiryId, int months) {
        jdbc.sql("update inquiry set created_at = :moment where inquiry_id = :id")
                .param("moment", OffsetDateTime.now().minusMonths(months))
                .param("id", inquiryId)
                .update();
    }

    private boolean exists(long inquiryId) {
        return jdbc.sql("select exists (select 1 from inquiry where inquiry_id = :id)")
                .param("id", inquiryId)
                .query(Boolean.class)
                .single();
    }

    /** {@code Q-20260823-K3M9P7} 꼴. 부를 때마다 다른 값이 나온다 */
    private static String inquiryNumber() {
        char[] alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
        int sequence = NUMBERS++;

        StringBuilder tail = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            tail.append(alphabet[(sequence >> (i * 5)) & 0x1F]);
        }
        return "Q-20260823-" + tail;
    }

    private static int NUMBERS = 1;

    private long insertProduct(long sellerId) {
        return jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name)
                        values (:sellerId, :userId, '문의 테스트 상품')
                        returning product_id
                        """)
                .param("sellerId", sellerId)
                .param("userId", userId)
                .query(Long.class)
                .single();
    }
}
