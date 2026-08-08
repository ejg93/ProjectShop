package com.projectshop.shop.seller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;

/**
 * 셀러 신원정보 제약(`D2` R1).
 *
 * <p>전자상거래법 제20조② 가 중개자에게 <b>청약 전까지 신원정보 확인</b>을 요구하고
 * 제20조의2 가 안 했을 때 연대책임을 붙인다. 그 요건을 앱이 아니라 DB 가 막는지 본다 —
 * 앱 검증은 새 입구가 생기면 빠뜨리고, 빠뜨린 결과가 연대책임이다.
 */
@DisplayName("셀러 신원정보")
class SellerSchemaTest extends PostgresTestBase {

    @Autowired
    private JdbcClient jdbc;

    @Nested
    @DisplayName("확인 전에는")
    class BeforeVerification {

        @Test
        @DisplayName("정보 없이도 셀러를 만들 수 있다 — pending 이다")
        void createsWithoutIdentity() {
            long sellerId = insertSeller("s-pending");

            assertThat(status(sellerId))
                    .as("등록과 확인은 다른 시점이다. 정보를 다 받아야 행이 생기면 등록 흐름을 못 만든다")
                    .isEqualTo("pending");
        }

        @Test
        @DisplayName("정보 없이 판매를 시작할 수 없다")
        void cannotActivateWithoutIdentity() {
            long sellerId = insertSeller("s-no-identity");

            assertThatThrownBy(() -> activate(sellerId))
                    .as("확인 없이 중개를 시작하면 제20조의2 의 연대책임이 붙는다")
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("한 칸만 비어도 판매를 시작할 수 없다")
        void cannotActivateWithPartialIdentity() {
            long sellerId = insertSeller("s-partial");
            fillIdentity(sellerId);
            jdbc.sql("update seller set phone = null where seller_id = :id")
                    .param("id", sellerId)
                    .update();

            assertThatThrownBy(() -> activate(sellerId))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Nested
    @DisplayName("통신판매업 신고번호는")
    class MailOrderNumber {

        @Test
        @DisplayName("번호가 있으면 판매를 시작한다")
        void activatesWithNumber() {
            long sellerId = insertSeller("s-with-no");
            fillIdentity(sellerId);
            jdbc.sql("update seller set mail_order_no = '2026-서울강남-01234' where seller_id = :id")
                    .param("id", sellerId)
                    .update();

            activate(sellerId);

            assertThat(status(sellerId)).isEqualTo("active");
        }

        @Test
        @DisplayName("면제 사유만 있어도 판매를 시작한다")
        void activatesWithExemption() {
            long sellerId = insertSeller("s-exempt");
            fillIdentity(sellerId);
            jdbc.sql("""
                            update seller set mail_order_exempt_reason = 'simplified_taxpayer'
                             where seller_id = :id
                            """)
                    .param("id", sellerId)
                    .update();

            activate(sellerId);

            assertThat(status(sellerId))
                    .as("거래 50회 미만·간이과세자는 신고 면제다. 번호를 요구하면 합법적인 셀러가 막힌다")
                    .isEqualTo("active");
        }

        @Test
        @DisplayName("둘 다 없으면 판매를 시작할 수 없다")
        void cannotActivateWithNeither() {
            long sellerId = insertSeller("s-neither");
            fillIdentity(sellerId);

            assertThatThrownBy(() -> activate(sellerId))
                    .as("안 넣은 것과 면제라 없는 것이 갈려야 한다")
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("번호와 면제 사유를 같이 가질 수 없다")
        void rejectsBoth() {
            long sellerId = insertSeller("s-both");

            assertThatThrownBy(() -> jdbc.sql("""
                            update seller
                               set mail_order_no = '2026-서울강남-01234',
                                   mail_order_exempt_reason = 'simplified_taxpayer'
                             where seller_id = :id
                            """).param("id", sellerId).update())
                    .as("둘 다 있으면 어느 쪽이 사실인지 알 수 없다")
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("모르는 면제 사유는 안 들어간다")
        void rejectsUnknownExemption() {
            long sellerId = insertSeller("s-bad-exempt");

            assertThatThrownBy(() -> jdbc.sql("""
                            update seller set mail_order_exempt_reason = '그냥'
                             where seller_id = :id
                            """).param("id", sellerId).update())
                    .as("고시가 정한 둘뿐이다. 열어 두면 근거 없는 면제가 들어온다")
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Nested
    @DisplayName("사업자등록번호는")
    class BusinessRegistrationNumber {

        // 제약 위반은 트랜잭션을 죽인다. 한 테스트에 두 번 던지면 두 번째가
        // 25P02(current transaction is aborted)로 바뀌어서 무엇을 검증한 것인지 흐려진다.

        @Test
        @DisplayName("하이픈이 들어가면 안 들어간다")
        void rejectsHyphens() {
            assertThatThrownBy(() -> setRegNo(insertSeller("s-reg-hyphen"), "123-45-6789"))
                    .as("하이픈 없이 담는다. 표시할 때 넣는 것이라 저장 형태가 하나여야 한다")
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("자릿수가 모자라면 안 들어간다")
        void rejectsShortNumber() {
            assertThatThrownBy(() -> setRegNo(insertSeller("s-reg-short"), "12345"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    private long insertSeller(String code) {
        return jdbc.sql("insert into seller (code, name) values (:code, :code) returning seller_id")
                .param("code", code)
                .query(Long.class)
                .single();
    }

    /** 신고번호를 뺀 나머지를 채운다. 그 하나만 갈아 끼우며 보려는 것이다. */
    private void fillIdentity(long sellerId) {
        jdbc.sql("""
                        update seller
                           set business_name = '주식회사 테스트',
                               representative_name = '홍길동',
                               business_reg_no = '1234567890',
                               address = '서울시 강남구 테헤란로 1',
                               phone = '02-0000-0000',
                               email = 'seller@test.local'
                         where seller_id = :id
                        """)
                .param("id", sellerId)
                .update();
    }

    private void activate(long sellerId) {
        jdbc.sql("update seller set status = 'active' where seller_id = :id")
                .param("id", sellerId)
                .update();
    }

    private void setRegNo(long sellerId, String regNo) {
        jdbc.sql("update seller set business_reg_no = :no where seller_id = :id")
                .param("no", regNo)
                .param("id", sellerId)
                .update();
    }

    private String status(long sellerId) {
        return jdbc.sql("select status from seller where seller_id = :id")
                .param("id", sellerId)
                .query(String.class)
                .single();
    }
}
