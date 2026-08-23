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
import com.projectshop.shop.auth.PermissionEvaluator;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.order.OrderFixture;

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

    @Autowired
    private PermissionEvaluator evaluator;

    private AuthFixture fixture;
    private long askerId;
    private long strangerId;
    private long sellerOwnerId;
    private long adminId;
    private long auditorId;
    private long sellerId;
    private long productId;
    private String bundleNumber;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);

        askerId = fixture.insertUser("asker@test.local", "묻는사람");
        fixture.grantGlobal(askerId, "customer");

        strangerId = fixture.insertUser("stranger@test.local", "남");
        fixture.grantGlobal(strangerId, "customer");

        adminId = fixture.insertUser("inquiry-admin@test.local", "관리자");
        fixture.grantGlobal(adminId, "admin");

        auditorId = fixture.insertUser("inquiry-auditor@test.local", "감사자");
        fixture.grantGlobal(auditorId, "auditor");

        sellerId = fixture.insertSeller("s-qna", "문의셀러");
        fixture.verifySeller(sellerId);
        sellerOwnerId = fixture.insertUser("qna-owner@test.local", "셀러대표");
        fixture.joinSeller(sellerId, sellerOwnerId);
        fixture.grantOrg(sellerOwnerId, "seller_owner", sellerId);

        productId = insertProduct();
        bundleNumber = makeBundle();
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

    /**
     * 낸 사람이 거둘 수 있나(청크 59-1).
     *
     * <p>{@code 58} 이 상태 목록에 {@code withdrawn} 을 넣었는데 {@code 59} 가 입구를 열면서
     * <b>옮기는 코드를 안 만들었다</b> — 값이 {@code check} 에 있으면 다음 사람이
     * 그 상태가 도달 가능하다고 읽는다(1차 마무리가 잡았다).
     */
    /**
     * 주문에 붙는 문의(청크 58-2).
     *
     * <p>없어진 {@code 22} 행이 「대상 자원(<b>주문</b>·상품)」을 적어 뒀는데 {@code 58} 이
     * 상품과 계정만 담아서 <b>「이 주문 언제 와요?」를 담을 값이 없었다</b>(점검 G 가 찾았다).
     *
     * <p><b>{@code dispute} 로 대신하면 안 된다.</b> 그 표는 전자상거래법 시행령 제6조 4호의
     * <b>불만·분쟁처리 기록</b>(3년)이라, 단순 문의까지 거기 쌓이면
     * 「분쟁이 몇 건이었나」에 답을 못 한다.
     */
    /**
     * 감사자가 <b>고객이 쓴 글</b>까지 보나(청크 25).
     *
     * <p>{@code 59} 가 입구를 열면서 축에 `D16` 을 걸어 뒀는데 <b>본문이 누구에게 나가는지를
     * 안 봤다</b>(점검 G 가 찾았다). {@code inquiry.question} 은 자유 텍스트 2000자고
     * 사람이 직접 쓴다 — <b>글 안에 연락처가 섞여 들어온다.</b>
     *
     * <p><b>감사는 「누가 언제 무엇을 처리했나」지 고객이 쓴 글이 아니다</b> —
     * 개인정보보호법 제3조제1항의 최소처리다. {@code V6} 가 감사자에게서 {@code payment} 그룹을
     * 닫은 것과 같은 논리다.
     */
    @Nested
    @DisplayName("본문은")
    class Body {

        /**
         * <b>마무리(2026-08-23)가 이 테스트를 두 번 고쳤다.</b>
         *
         * <p>처음에는 {@code findMine(auditorId)} 가 빈 목록을 주는 것으로 통과했는데,
         * 그건 <b>감사자에게 자기 문의가 없어서</b>지 본문이 가려져서가 아니었다 —
         * <b>마스킹 코드가 아예 없는데도 초록이었다.</b>
         *
         * <p>그다음 감사자를 셀러에 넣어 봤더니 이번엔 본문이 <b>그대로 나왔다.</b>
         * 역할을 겹쳐 주면 {@code seller_owner} 의 <b>연결 없는 규칙</b>이 「제한 없음」이라
         * 전부 열어 버린다(`V6`) — 판정이 허용 규칙을 다 모으기 때문이다(`4d`).
         *
         * <p><b>감사자만으로 남의 문의를 보는 경로가 아직 없다.</b> 관리자·감사자용 목록이
         * 서는 청크가 그 경로를 만들고, 마스킹은 <b>그때 처음 실제로 걸린다.</b>
         * 지금 고정할 수 있는 것은 <b>판정이 그 그룹을 안 열어 준다</b>는 사실이고,
         * 그것이 이 규칙의 유일한 출처다.
         */
        @Test
        @DisplayName("판정이 감사자에게 글을 안 연다")
        void isNotGrantedToTheAuditorByTheEvaluator() {
            var decision = evaluator.decide(auditorId, "inquiry", "read",
                    PermissionEvaluator.Target.ownedBy(askerId));

            assertThat(decision.allowed())
                    .as("감사자는 문의를 본다 — 가리는 것은 글뿐이다")
                    .isTrue();
            assertThat(decision.canSee(InquiryFields.BODY))
                    .as("감사는 처리 이력이지 고객이 쓴 글이 아니다(제3조제1항 최소처리)")
                    .isFalse();
            assertThat(decision.canSee(InquiryFields.BASIC))
                    .as("메타는 열려 있다")
                    .isTrue();
        }

        @Test
        @DisplayName("셀러는 답하려고 읽는다")
        void staysVisibleToTheSellerWhoAnswers() {
            ask(askerId, false);

            assertThat(query.findForSeller(sellerOwnerId, 0, 20).items())
                    .singleElement()
                    .extracting(InquiryQuery.Entry::question)
                    .as("답을 못 쓰면 문의가 성립을 안 한다")
                    .isNotNull();
        }

        @Test
        @DisplayName("낸 사람은 자기 글을 본다")
        void staysVisibleToTheAuthor() {
            ask(askerId, false);

            assertThat(query.findMine(askerId, 0, 20).items())
                    .singleElement()
                    .extracting(InquiryQuery.Entry::question)
                    .isNotNull();
        }

        @Test
        @DisplayName("공개 Q&A 는 그대로 보인다")
        void staysVisibleOnThePublicListing() {
            ask(askerId, true);

            assertThat(query.findPublic(productId, 0, 20).items())
                    .singleElement()
                    .extracting(InquiryQuery.PublicEntry::question)
                    .as("공개로 낸 글이라 가릴 것이 없다")
                    .isNotNull();
        }
    }

    @Nested
    @DisplayName("주문 문의는")
    class OrderInquiry {

        @Test
        @DisplayName("자기 묶음에 낸다")
        void isFiledOnOwnBundle() {
            String number = askAboutOrder(askerId, bundleNumber);

            assertThat(kindOf(number)).isEqualTo("order");
        }

        /**
         * 없는 묶음과 남의 묶음이 <b>같은 404 다</b> — 갈라 주면 묶음 번호를 훑어서
         * 실재하는 주문의 지도를 그릴 수 있고, 그것이 곧 셀러별 거래 건수다.
         */
        @Test
        @DisplayName("남의 묶음에는 못 낸다")
        void refusesAnotherPersonBundle() {
            assertThatThrownBy(() ->
                    askAboutOrder(strangerId, bundleNumber))
                    .isInstanceOf(ShopException.class);
        }

        @Test
        @DisplayName("없는 묶음에도 못 낸다")
        void refusesAMissingBundle() {
            assertThatThrownBy(() -> askAboutOrder(askerId, "S-20260101-AAAAAB"))
                    .isInstanceOf(ShopException.class);
        }

        @Test
        @DisplayName("공개가 성립하지 않는다")
        void isNeverPublic() {
            String number = askAboutOrder(askerId, bundleNumber);

            assertThat(isPublic(number))
                    .as("남의 주문을 남이 보면 안 된다 — 무엇을 언제 샀는지가 질문에 들어 있다")
                    .isFalse();
        }

        @Test
        @DisplayName("상품 화면에 안 나온다")
        void neverAppearsOnTheProductPage() {
            askAboutOrder(askerId, bundleNumber);

            assertThat(query.findPublic(productId, 0, 20).items()).isEmpty();
        }

        /**
         * <b>셀러가 상품이 아니라 묶음에서 나온다.</b> 목록 조건이 상품의 셀러만 보면
         * 주문 문의가 통째로 안 잡히고, 셀러는 <b>자기 주문에 온 질문을 못 본다</b>.
         */
        @Test
        @DisplayName("그 묶음의 셀러가 본다")
        void isVisibleToTheBundleSeller() {
            askAboutOrder(askerId, bundleNumber);

            assertThat(query.findForSeller(sellerOwnerId, 0, 20).items())
                    .singleElement()
                    .extracting(InquiryQuery.Entry::kind)
                    .isEqualTo("ORDER");
        }
    }

    @Nested
    @DisplayName("거두기는")
    class Withdrawing {

        @Test
        @DisplayName("낸 사람이 거둔다")
        void isDoneByTheAuthor() {
            String number = ask(askerId, true);

            assertThatCode(() -> inquiries.withdraw(askerId, number)).doesNotThrowAnyException();
            assertThat(query.findPublic(productId, 0, 20).items()).isEmpty();
        }

        /**
         * 판정 대상을 문의 그대로 쓰면 셀러가 실려서 {@code seller} 스코프가 걸리고,
         * 그 순간 <b>셀러가 자기 상품에 달린 불리한 질문을 지우는 자리</b>가 된다.
         * 대상을 낸 사람으로 좁힌 이유다.
         */
        @Test
        @DisplayName("셀러는 남의 문의를 못 거둔다")
        void refusesTheSeller() {
            String number = ask(askerId, true);

            assertThatThrownBy(() -> inquiries.withdraw(sellerOwnerId, number))
                    .isInstanceOf(ShopException.class);
        }

        @Test
        @DisplayName("남은 못 거둔다")
        void refusesAStranger() {
            String number = ask(askerId, true);

            assertThatThrownBy(() -> inquiries.withdraw(strangerId, number))
                    .isInstanceOf(ShopException.class);
        }

        @Test
        @DisplayName("답이 나간 것은 못 거둔다")
        void refusesAnAnsweredInquiry() {
            String number = ask(askerId, true);
            inquiries.answer(sellerOwnerId, number, "내일 발송합니다");

            assertThatThrownBy(() -> inquiries.withdraw(askerId, number))
                    .as("질문이 사라지면 그 답이 무엇에 대한 것인지가 없어진다")
                    .isInstanceOf(ShopException.class);
        }
    }

    /**
     * <b>내리는 경로가 있나</b>(청크 59-2).
     *
     * <p>1차 마무리(2026-08-23)가 찾은 자리다 — `58` 이 자리를 만들고 `59` 가 조회에서
     * 빼는 것까지 했는데 <b>그 상태로 옮기는 코드가 없어서 `psql` 로만 내려졌다.</b>
     *
     * <p><b>여기서 보는 것은 「내린 글이 목록에서 빠지는 것」이 아니다.</b> 그것은
     * {@link PublicListing#hidesBlockedPosts} 가 이미 고정했고 <b>그래서 이 구멍이
     * 초록인 채로 남았다</b> — 그 테스트는 SQL 로 상태를 밀어 넣고 시작했다.
     */
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
                "product", productId, null, "이 상품 언제 오나요", isPublic));
    }

    private String file(long userId, String kind) {
        return inquiries.create(userId, new InquiryService.NewInquiry(
                kind, null, null, "제 정보 처리를 멈춰 주세요", true));
    }

    /** 주문 문의. <b>공개로 내도 비공개가 된다</b> — 종류가 그것을 정한다 */
    private String askAboutOrder(long userId, String sellerOrderNumber) {
        return inquiries.create(userId, new InquiryService.NewInquiry(
                "order", null, sellerOrderNumber, "이 주문 언제 오나요", true));
    }

    private String kindOf(String inquiryNumber) {
        return jdbc.sql("select kind from inquiry where inquiry_number = :number")
                .param("number", inquiryNumber)
                .query(String.class)
                .single();
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

    /**
     * 주문 문의가 가리킬 실제 묶음. <b>산 사람은 주문에, 셀러는 묶음에 있다</b>(`D7`).
     *
     * <p>결제까지 안 간다 — 주문 문의에 필요한 것은 <b>「내 묶음인가」</b>뿐이고
     * 그 판정은 주문의 주인만 본다.
     */
    private String makeBundle() {
        long orderId = jdbc.sql("""
                        insert into shop_order (order_number, user_id, total_amount,
                                                commission_total, shipping_fee_total,
                                                payable_amount)
                        values (:number, :userId, 0, 0, 0, 0)
                        returning order_id
                        """)
                .param("number", OrderFixture.sellerOrderNumber().substring(2))
                .param("userId", askerId)
                .query(Long.class)
                .single();

        OrderFixture.attachContractDocuments(jdbc, orderId);

        String bundleNumber = OrderFixture.sellerOrderNumber();
        jdbc.sql("""
                        insert into seller_order (seller_order_number, order_id, seller_id)
                        values (:number, :orderId, :sellerId)
                        """)
                .param("number", bundleNumber)
                .param("orderId", orderId)
                .param("sellerId", sellerId)
                .update();

        return bundleNumber;
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
