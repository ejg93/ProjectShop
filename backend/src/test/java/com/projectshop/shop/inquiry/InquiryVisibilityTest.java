package com.projectshop.shop.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.error.ShopException;

/**
 * 비공개 문의가 남에게 안 보이나(청크 59).
 *
 * <p><b>화면에서 숨기는 방식을 안 쓴다.</b> 그러면 API 로는 그대로 나간다 —
 * 여기서 보는 것은 <b>목록 쿼리가 애초에 안 뽑는지</b>다.
 *
 * <p><b>입구가 관객별로 갈려 있다</b>(사용자 선택). 그래서 각 테스트가 「이 관객의 입구가
 * 무엇을 고르나」를 묻는다 — 조건을 빠뜨렸을 때 결과가 더 나오는 것이 아니라 빈다.
 */
@DisplayName("문의 공개 여부")
class InquiryVisibilityTest extends PostgresTestBase {

    @Autowired
    private InquiryService inquiries;

    @Autowired
    private InquiryQuery query;

    @Autowired
    private JdbcClient jdbc;

    private AuthFixture fixture;
    private long askerId;
    private long strangerId;
    private long sellerOwnerId;
    private long adminId;
    private long sellerId;
    private long productId;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);

        askerId = fixture.insertUser("asker@test.local", "묻는사람");
        fixture.grantGlobal(askerId, "customer");

        strangerId = fixture.insertUser("stranger@test.local", "남");
        fixture.grantGlobal(strangerId, "customer");

        adminId = fixture.insertUser("inquiry-admin@test.local", "관리자");
        fixture.grantGlobal(adminId, "admin");

        sellerId = fixture.insertSeller("s-qna", "문의셀러");
        fixture.verifySeller(sellerId);
        sellerOwnerId = fixture.insertUser("qna-owner@test.local", "셀러대표");
        fixture.joinSeller(sellerId, sellerOwnerId);
        fixture.grantOrg(sellerOwnerId, "seller_owner", sellerId);

        productId = insertProduct();
    }

    @Nested
    @DisplayName("공개 목록은")
    class PublicListing {

        @Test
        @DisplayName("공개 문의를 담는다")
        void showsPublicQuestions() {
            ask(askerId, true);

            assertThat(query.findPublic(productId, 0, 20).items()).hasSize(1);
        }

        @Test
        @DisplayName("비공개 문의를 안 담는다")
        void hidesPrivateQuestions() {
            ask(askerId, false);

            assertThat(query.findPublic(productId, 0, 20).items())
                    .as("화면에서 숨기면 API 로는 보인다. 고르는 조건이어야 한다")
                    .isEmpty();
        }

        /**
         * 정보통신망법 제50조의7 이 요구하는 것은 <b>게시 중단</b>이다(`R34`).
         * 조건에서 빼지 않으면 화면에만 안 보이고 이 API 로는 그대로 나간다.
         */
        @Test
        @DisplayName("내려간 게시물을 안 담는다")
        void hidesBlockedPosts() {
            String number = ask(askerId, true);
            block(number);

            assertThat(query.findPublic(productId, 0, 20).items()).isEmpty();
        }

        @Test
        @DisplayName("낸 사람을 실을 칸이 없다")
        void hasNoRoomForTheAuthor() {
            ask(askerId, true);
            InquiryQuery.PublicEntry entry = query.findPublic(productId, 0, 20).items().get(0);

            assertThat(entry.getClass().getRecordComponents())
                    .as("마스킹으로 가리면 새 컬럼을 더할 때 그 규칙에 넣는 것을 빠뜨린다")
                    .noneMatch(component -> component.getName().toLowerCase().contains("user"));
        }
    }

    @Nested
    @DisplayName("내 문의는")
    class MyListing {

        @Test
        @DisplayName("내 비공개도 보인다")
        void showsMyPrivateQuestions() {
            ask(askerId, false);

            assertThat(query.findMine(askerId, 0, 20).items()).hasSize(1);
        }

        @Test
        @DisplayName("남의 것은 안 보인다")
        void hidesOtherPeopleQuestions() {
            ask(askerId, false);

            assertThat(query.findMine(strangerId, 0, 20).items())
                    .as("조건이 자기 것이라 남의 행은 애초에 안 뽑힌다")
                    .isEmpty();
        }

        /**
         * 자기 글이 왜 안 보이는지를 본인은 알아야 한다. 제50조의7 은 <b>게시 중단</b>을
         * 요구하지 작성자에게서 감추라고 하지 않는다.
         */
        @Test
        @DisplayName("내려간 내 글도 보인다")
        void showsMyBlockedPost() {
            String number = ask(askerId, true);
            block(number);

            assertThat(query.findMine(askerId, 0, 20).items()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("셀러 목록은")
    class SellerListing {

        @Test
        @DisplayName("자기 상품에 달린 비공개도 본다")
        void showsPrivateQuestionsOnOwnProducts() {
            ask(askerId, false);

            assertThat(query.findForSeller(sellerOwnerId, 0, 20).items()).hasSize(1);
        }

        /**
         * 처리정지·이의제기·분쟁은 <b>우리에게 온 것</b>이라 셀러가 볼 것이 아니다.
         * 조건이 상품의 셀러라 그 요구는 애초에 안 걸린다 — 규칙 하나로 성립한다.
         */
        @Test
        @DisplayName("계정에 붙는 요구는 안 나온다")
        void neverShowsAccountBoundRequests() {
            file(askerId, "processing_stop");

            assertThat(query.findForSeller(sellerOwnerId, 0, 20).items()).isEmpty();
        }

        @Test
        @DisplayName("셀러가 아니면 못 본다")
        void refusesSomeoneWithoutASeller() {
            assertThatThrownBy(() -> query.findForSeller(askerId, 0, 20))
                    .as("0건이 아니다 — 0건과 못 봄이 갈려야 개수로 정보가 안 샌다")
                    .isInstanceOf(ShopException.class);
        }
    }

    @Nested
    @DisplayName("계정에 붙는 요구는")
    class AccountBoundRequests {

        @Test
        @DisplayName("공개로 내도 비공개가 된다")
        void isForcedPrivate() {
            String number = file(askerId, "processing_stop");

            assertThat(isPublic(number))
                    .as("처리정지 요구가 상품 페이지에 뜨면 그 사람이 무슨 요구를 했는지가 남에게 보인다")
                    .isFalse();
        }

        @Test
        @DisplayName("제약이 뒤에서 여는 것도 막는다")
        void cannotBeOpenedByHand() {
            String number = file(askerId, "dispute");

            assertThatThrownBy(() -> jdbc.sql(
                            "update inquiry set is_public = true where inquiry_number = :number")
                    .param("number", number)
                    .update())
                    .as("앱 검증으로만 두면 새 입구가 빠뜨린다(`D23` 축 2)")
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
    }

    /**
     * <b>내리는 경로가 있나</b>(청크 59-2).
     *
     * <p>1차 마무리(2026-08-23)가 찾은 자리다 — `58` 이 자리를 만들고 `59` 가 조회에서
     * 빼는 것까지 했는데 <b>그 상태로 옮기는 코드가 없어서 `psql` 로만 내려졌다.</b>
     * 요건표에는 「내릴 수단이 생겼다」고 적혀 있었다.
     *
     * <p><b>여기서 보는 것은 「내린 글이 목록에서 빠지는 것」이 아니다.</b> 그것은
     * {@link PublicListing#hidesBlockedPosts} 가 이미 고정했고 <b>그래서 이 구멍이
     * 초록인 채로 남았다</b> — 그 테스트는 SQL 로 상태를 밀어 넣고 시작했다.
     */
    /**
     * 법정 요구에 <b>언제까지 답해야 하는지</b>가 박혀 있나(청크 58-1).
     *
     * <p>개인정보 보호법 제37조는 「지체 없이」라고만 하고 <b>일수가 조문에 없다</b> —
     * 시행령 제44조제2항이 「요구서를 받은 날부터 <b>10일 이내</b>」로 정한다.
     * <b>달력일이다</b>: 「영업일」이라고 안 적혀 있다.
     *
     * <p><b>「기한 안에 답」은 `check` 로 못 건다</b> — 막으면 늦은 답이 영영 안 나간다.
     * 강제는 「넘긴 것이 조회로 드러난다」가 천장이고 그 위는 사람이 본다(`R5` 와 같은 자리).
     */
    @Nested
    @DisplayName("처리 기한은")
    class LegalDue {

        @Test
        @DisplayName("처리정지 요구에 열흘로 박힌다")
        void isTenDaysForProcessingStop() {
            String number = file(askerId, "processing_stop");

            assertThat(dueDaysOf(number))
                    .as("개인정보 보호법 시행령 제44조제2항 — 받은 날부터 10일 이내")
                    .isEqualTo(10);
        }

        /**
         * <b>모르는 것을 지어서 박느니 비워 둔다.</b> 제38조제5항(이의제기)과 전자상거래법
         * 제20조(분쟁)는 <b>기한 조문을 못 찾았다</b> — 후자는 「신속히」라고만 한다.
         * 틀린 날짜가 박히면 그것이 지켜야 할 값이 된다.
         */
        @Test
        @DisplayName("기한을 못 찾은 종류에는 안 박힌다")
        void isAbsentWhereTheArticleGivesNoDeadline() {
            assertThat(dueIsNull(file(askerId, "access_objection"))).isTrue();
            assertThat(dueIsNull(file(askerId, "dispute"))).isTrue();
            assertThat(dueIsNull(ask(askerId, true))).isTrue();
        }

        @Test
        @DisplayName("제약이 빈 기한을 막는다")
        void isRequiredByTheConstraint() {
            String number = file(askerId, "processing_stop");

            assertThatThrownBy(() -> jdbc.sql(
                            "update inquiry set due_at = null where inquiry_number = :number")
                    .param("number", number)
                    .update())
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        }

        @Test
        @DisplayName("넘긴 것이 조회로 드러난다")
        void surfacesWhatIsOverdue() {
            String number = file(askerId, "processing_stop");
            jdbc.sql("""
                            update inquiry set due_at = now() - interval '1 day'
                             where inquiry_number = :number
                            """)
                    .param("number", number)
                    .update();

            assertThat(query.findMine(askerId, 0, 20).items())
                    .singleElement()
                    .extracting(InquiryQuery.Entry::overdue)
                    .as("「기한 안에 답」은 check 로 못 건다 — 막으면 늦은 답이 영영 안 나간다")
                    .isEqualTo(true);
        }
    }

    @Nested
    @DisplayName("게시 중단은")
    class Blocking {

        @Test
        @DisplayName("관리자가 내린다")
        void isDoneByAnAdmin() {
            String number = ask(askerId, true);

            assertThatCode(() -> inquiries.block(adminId, number, "advertisement"))
                    .doesNotThrowAnyException();
            assertThat(query.findPublic(productId, 0, 20).items())
                    .as("제50조의7 이 요구하는 것은 게시 중단이다")
                    .isEmpty();
        }

        /**
         * 조문의 의무자가 운영자라 그 판단도 운영자가 한다(사용자 선택).
         * 셀러에게 열면 <b>불리한 질문을 광고로 몰아 내리는 자리</b>가 같이 생기고,
         * 내린 근거가 {@code advertisement} 로만 남아서 사후에 갈라내기도 어렵다.
         */
        @Test
        @DisplayName("셀러는 자기 상품 것도 못 내린다")
        void refusesTheSellerEvenOnOwnProduct() {
            String number = ask(askerId, true);

            assertThatThrownBy(() -> inquiries.block(sellerOwnerId, number, "advertisement"))
                    .isInstanceOf(ShopException.class);
        }

        @Test
        @DisplayName("낸 사람은 못 내린다")
        void refusesTheAuthor() {
            String number = ask(askerId, true);

            assertThatThrownBy(() -> inquiries.block(askerId, number, "abuse"))
                    .isInstanceOf(ShopException.class);
        }

        @Test
        @DisplayName("답이 나간 글도 내린다")
        void blocksAnAnsweredPost() {
            String number = ask(askerId, true);
            inquiries.answer(sellerOwnerId, number, "내일 발송합니다");

            assertThatCode(() -> inquiries.block(adminId, number, "advertisement"))
                    .as("광고에 답을 달았다고 그 광고가 남을 이유가 없다")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("두 번은 못 내린다")
        void cannotBeBlockedTwice() {
            String number = ask(askerId, true);
            inquiries.block(adminId, number, "advertisement");

            assertThatThrownBy(() -> inquiries.block(adminId, number, "abuse"))
                    .as("조건부 UPDATE 라 상태가 곧 판정이다")
                    .isInstanceOf(ShopException.class);
        }

        @Test
        @DisplayName("낸 사람에게는 그대로 보인다")
        void staysVisibleToTheAuthor() {
            String number = ask(askerId, true);
            inquiries.block(adminId, number, "advertisement");

            assertThat(query.findMine(askerId, 0, 20).items())
                    .as("제50조의7 은 게시 중단을 요구하지 작성자에게서 감추라고 하지 않는다")
                    .hasSize(1);
        }
    }

    @Nested
    @DisplayName("답변은")
    class Answering {

        @Test
        @DisplayName("자기 상품이면 셀러가 한다")
        void isDoneBySellerOnOwnProduct() {
            String number = ask(askerId, true);

            assertThatCode(() -> inquiries.answer(sellerOwnerId, number, "내일 발송합니다"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("남의 상품이면 못 한다")
        void isRefusedOnAnotherSellerProduct() {
            long otherSeller = fixture.insertSeller("s-other", "다른셀러");
            long otherOwner = fixture.insertUser("other-owner@test.local", "다른대표");
            fixture.joinSeller(otherSeller, otherOwner);
            fixture.grantOrg(otherOwner, "seller_owner", otherSeller);

            String number = ask(askerId, true);

            assertThatThrownBy(() -> inquiries.answer(otherOwner, number, "제가 답합니다"))
                    .isInstanceOf(ShopException.class);
        }

        /**
         * 처리정지·이의제기는 개인정보처리자인 <b>우리</b>에게 온 것이다(`R28`).
         * 그 요구에는 셀러가 없어서 셀러 스코프가 자연히 안 걸린다.
         */
        @Test
        @DisplayName("법정 요구는 관리자가 한다")
        void isDoneByAdminForLegalRequests() {
            String number = file(askerId, "access_objection");

            assertThatThrownBy(() -> inquiries.answer(sellerOwnerId, number, "셀러가 답한다"))
                    .as("그 요구에는 셀러가 없다")
                    .isInstanceOf(ShopException.class);
            assertThatCode(() -> inquiries.answer(adminId, number, "처리했습니다"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("두 번은 못 한다")
        void cannotBeDoneTwice() {
            String number = ask(askerId, true);
            inquiries.answer(sellerOwnerId, number, "내일 발송합니다");

            assertThatThrownBy(() -> inquiries.answer(sellerOwnerId, number, "다시 답합니다"))
                    .as("조건부 UPDATE 라 둘이 동시에 와도 하나만 통과한다")
                    .isInstanceOf(ShopException.class);
        }
    }

    private String ask(long userId, boolean isPublic) {
        return inquiries.create(userId, new InquiryService.NewInquiry(
                "product", productId, "이 상품 언제 오나요", isPublic));
    }

    private String file(long userId, String kind) {
        return inquiries.create(userId, new InquiryService.NewInquiry(
                kind, null, "제 정보 처리를 멈춰 주세요", true));
    }

    private void block(String inquiryNumber) {
        jdbc.sql("""
                        update inquiry
                           set status = 'blocked', blocked_at = now(),
                               blocked_reason = 'advertisement'
                         where inquiry_number = :number
                        """)
                .param("number", inquiryNumber)
                .update();
    }

    /** 접수일과 기한 사이의 날수. 달력일이라 그대로 뺀다 */
    private int dueDaysOf(String inquiryNumber) {
        return jdbc.sql("""
                        select (due_at::date - created_at::date) from inquiry
                         where inquiry_number = :number
                        """)
                .param("number", inquiryNumber)
                .query(Integer.class)
                .single();
    }

    private boolean dueIsNull(String inquiryNumber) {
        return jdbc.sql("select due_at is null from inquiry where inquiry_number = :number")
                .param("number", inquiryNumber)
                .query(Boolean.class)
                .single();
    }

    private boolean isPublic(String inquiryNumber) {
        return jdbc.sql("select is_public from inquiry where inquiry_number = :number")
                .param("number", inquiryNumber)
                .query(Boolean.class)
                .single();
    }

    private long insertProduct() {
        return jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name)
                        values (:sellerId, :userId, '문의 테스트 상품')
                        returning product_id
                        """)
                .param("sellerId", sellerId)
                .param("userId", sellerOwnerId)
                .query(Long.class)
                .single();
    }
}
