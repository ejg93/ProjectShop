package com.projectshop.shop.inquiry;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectshop.shop.auth.PermissionEvaluator;
import com.projectshop.shop.auth.PermissionEvaluator.Target;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.support.ExposedNumber;

/**
 * 문의를 받고 답한다(청크 59).
 *
 * <h2>받는 자리가 곧 법정 창구다</h2>
 *
 * <p>세 요건이 여기로 온다 — 처리정지 요구(개인정보법 제37조)와 열람등요구 이의제기
 * (제38조제5항)가 `R28`, 불만·분쟁 접수(전자상거래법 제20조제3항)가 `R25` 다.
 * <b>방침이 창구를 가리키는 문구만 있으면 「어디로 받나」에 코드가 답을 못 한다.</b>
 *
 * <h2>공개 여부를 앱이 안 정한다</h2>
 *
 * <p>계정에 붙는 요구 셋은 언제나 비공개다. 그것을 앱에서 걸면 새 입구가 생길 때
 * 빠뜨리므로 {@code inquiry_visibility_check} 가 한 층 아래에서 막는다(`V54`, `D23` 축 2) —
 * 여기서 하는 것은 <b>제약이 받아 줄 값을 만들어 넘기는 것</b>뿐이다.
 */
@Service
public class InquiryService {

    /** 문의 노출 번호의 접두어(`D9`). 주문·묶음·환불과 형식이 같아서 이것이 종류를 가른다 */
    private static final String NUMBER_PREFIX = "Q-";

    /**
     * 처리정지 요구에 답할 기한. <b>개인정보 보호법 시행령 제44조제2항</b>(`D2` R28).
     *
     * <p>법 제37조는 「지체 없이」라고만 하고 <b>일수가 조문에 없다</b> — 시행령이
     * 「요구서를 받은 날부터 10일 이내」로 정한다. <b>달력일이다</b>: 「영업일」이라고
     * 안 적혀 있어서 {@code BusinessCalendar} 가 안 걸린다(`D10`).
     */
    private static final int PROCESSING_STOP_DUE_DAYS = 10;

    /** {@code inquiry.blocked_reason} 에 들어가는 값(`V53`) */
    static final String REASON_ADVERTISEMENT = "advertisement";
    static final String REASON_ABUSE = "abuse";

    private static final String RESOURCE = "inquiry";
    private static final String CREATE = "create";
    private static final String ANSWER = "answer";
    private static final String BLOCK = "block";
    private static final String READ = "read";

    private final JdbcClient jdbc;
    private final PermissionEvaluator evaluator;

    InquiryService(JdbcClient jdbc, PermissionEvaluator evaluator) {
        this.jdbc = jdbc;
        this.evaluator = evaluator;
    }

    /**
     * 낼 문의.
     *
     * @param productId 상품 문의에만 있다. 계정에 붙는 요구는 {@code null}
     * @param isPublic  상품 문의에만 뜻이 있다. 그 밖에는 무엇을 넣든 비공개가 된다
     */
    public record NewInquiry(String kind, Long productId, String sellerOrderNumber,
            String question, boolean isPublic) {}

    /**
     * 문의를 낸다.
     *
     * <p><b>판정 대상이 자기 자신이다.</b> 문의는 내는 순간 주인이 자기라서, 「이 사람이
     * 이것을 해도 되나」의 대상이 그 사람 자신이 된다 — {@code own} 스코프가 여기서 걸린다.
     *
     * <p>상품 문의는 <b>상품이 실재하는지</b>를 먼저 본다. 없는 상품에 묻는 것은 형식은 맞고
     * 대상이 없는 것이라 404 다.
     */
    @Transactional
    public String create(long userId, NewInquiry command) {
        if (!evaluator.decide(userId, RESOURCE, CREATE, Target.ownedBy(userId)).allowed()) {
            throw new ShopException(ErrorCode.INQUIRY_FORBIDDEN, "문의를 낼 권한이 없다");
        }

        if (InquiryKind.PRODUCT.code().equals(command.kind())) {
            requireProduct(command.productId());
        }

        // 주문 문의는 **자기 묶음에만** 낼 수 있다(청크 58-2).
        //
        // 없는 묶음과 남의 묶음을 **같은 404 로 답한다** — 갈라 주면 묶음 번호를 훑어서
        // 실재하는 주문의 지도를 그릴 수 있고, 그게 곧 셀러별 거래 건수다(`OrderActionService` 와 같은 판단).
        Long sellerOrderId = InquiryKind.ORDER.code().equals(command.kind())
                ? myBundle(userId, command.sellerOrderNumber())
                : null;

        // 번호가 부딪히면 다시 뽑는다. 재시도가 이 안에 있어서 부르는 쪽이 세지 않는다(`D9`).
        // 기한을 접수하는 순간 박제한다(`58-1`). 답한 날에서 세면 늦게 답할수록 기한이
        // 밀려서 「늦었다」가 성립을 안 한다 — `refund.due_at` 과 같은 판단이다.
        return ExposedNumber.insertWith(NUMBER_PREFIX, "문의번호", number -> jdbc.sql("""
                        insert into inquiry (inquiry_number, kind, product_id, seller_order_id,
                                             user_id, question, is_public, due_at)
                        values (:number, :kind, :productId, :sellerOrderId, :userId, :question,
                                cast(:kind as text) = 'product' and :isPublic,
                                case when cast(:kind as text) = 'processing_stop'
                                     then now() + make_interval(days => :dueDays) end)
                        returning inquiry_number
                        """)
                .param("dueDays", PROCESSING_STOP_DUE_DAYS)
                .param("number", number)
                .param("kind", command.kind())
                .param("productId", command.productId())
                .param("sellerOrderId", sellerOrderId)
                .param("userId", userId)
                .param("question", command.question())
                .param("isPublic", command.isPublic())
                .query(String.class)
                .single());
    }

    /**
     * 문의에 답한다.
     *
     * <p><b>판정 대상이 낸 사람과 그 상품의 셀러다.</b> 셀러는 자기 상품에 달린 것만 답하고
     * (`seller` 스코프) 관리자는 전부 답한다 — <b>법정 요구는 우리에게 온 것</b>이라
     * 셀러가 답할 것이 아니고, 그 요구에는 셀러가 없어서 스코프가 자연히 안 걸린다.
     *
     * <p><b>답과 상태와 시각이 같이 움직인다.</b> 셋을 따로 두면 「답변인데 답이 없는」 행이
     * 생기는데, {@code inquiry_answer_check} 가 그것을 한 층 아래에서 막는다(`V53`).
     */
    @Transactional
    public void answer(long userId, String inquiryNumber, String answer) {
        Row row = find(inquiryNumber);

        if (!evaluator.decide(userId, RESOURCE, ANSWER, row.target()).allowed()) {
            // 못 보는 것과 못 답하는 것을 갈라 준다. 여기까지 온 사람은 번호를 이미 아는데,
            // 그 번호를 아는 경로가 자기 문의이거나 공개 목록이라 실재가 이미 드러나 있다.
            throw new ShopException(ErrorCode.INQUIRY_FORBIDDEN, "이 문의에 답할 권한이 없다");
        }

        // 조건부 UPDATE 라 둘이 동시에 와도 하나만 통과한다 — 읽고 나서 쓰는 사이를
        // 우리가 못 잠그므로 갱신 자체가 판정이어야 한다(`RefundService.approve` 와 같은 모양).
        int updated = jdbc.sql("""
                        update inquiry
                           set status = :answered, answer = :answer, answered_at = now(),
                               updated_at = now()
                         where inquiry_number = :number and status = :received
                        """)
                .param("answered", InquiryStatus.ANSWERED.code())
                .param("answer", answer)
                .param("number", inquiryNumber)
                .param("received", InquiryStatus.RECEIVED.code())
                .update();

        if (updated == 0) {
            throw new ShopException(ErrorCode.INQUIRY_ALREADY_CLOSED,
                    "이미 " + row.status() + " 인 문의다");
        }
    }

    /**
     * 낸 사람이 문의를 거둔다(청크 59-1).
     *
     * <p>{@code 58} 이 상태 목록에 {@code withdrawn} 을 넣었는데 {@code 59} 가 입구를 열면서
     * <b>옮기는 코드를 안 만들었다</b> — 값이 {@code check} 에 있으면 다음 사람이
     * 그 상태가 도달 가능하다고 읽는다(1차 마무리가 잡았다).
     *
     * <p><b>낸 사람만 거둔다.</b> 판정 대상이 자기 자신이라 {@code own} 스코프가 걸리고,
     * 관리자는 {@code all} 이라 대신 거둘 수 있다 — 그래도 <b>내리는 것과는 다른 자리</b>다:
     * 거두기는 「안 물어본 것으로 한다」고 내리기는 「게시를 중단한다」(제50조의7)다.
     *
     * <p><b>답이 나간 것은 못 거둔다.</b> 셀러가 이미 답을 썼는데 질문이 사라지면
     * 그 답이 무엇에 대한 것인지가 없어진다.
     */
    @Transactional
    public void withdraw(long userId, String inquiryNumber) {
        Row row = find(inquiryNumber);

        // **대상이 낸 사람뿐이다.** `row.target()` 을 그대로 쓰면 셀러가 실려서
        // `seller` 스코프가 걸리고, 그 순간 **셀러가 남의 문의를 거둔다** —
        // 자기 상품에 달린 불리한 질문을 지우는 자리가 된다.
        //
        // 동작은 `read` 를 쓴다. 새 권한을 파지 않는 이유는 **거두기가 자기 것을 다루는
        // 일이라 볼 수 있는 범위와 정확히 같아서**다 — 관리자는 `all` 이라 대신 거둘 수 있다.
        if (!evaluator.decide(userId, RESOURCE, READ, Target.ownedBy(row.ownerUserId()))
                .allowed()) {
            throw new ShopException(ErrorCode.INQUIRY_NOT_FOUND,
                    "그런 문의가 없다: " + inquiryNumber);
        }

        int updated = jdbc.sql("""
                        update inquiry
                           set status = :withdrawn, updated_at = now()
                         where inquiry_number = :number and status = :received
                        """)
                .param("withdrawn", InquiryStatus.WITHDRAWN.code())
                .param("number", inquiryNumber)
                .param("received", InquiryStatus.RECEIVED.code())
                .update();

        if (updated == 0) {
            throw new ShopException(ErrorCode.INQUIRY_ALREADY_CLOSED,
                    "이미 " + row.status() + " 인 문의다");
        }
    }

    /**
     * 게시를 중단한다(청크 59-2).
     *
     * <p><b>정보통신망법 제50조의7 이 요구하는 것은 게시 중단이다</b>(`D2` R34).
     * {@code 58} 이 자리를 만들고 {@code 59} 가 조회에서 빼는 것까지 했는데,
     * <b>그 상태로 옮기는 코드가 없어서 `psql` 로만 내려졌다.</b> 여기가 그 자리다.
     *
     * <p><b>관리자만 부른다</b>(사용자 선택, `V58`). 조문의 의무자가 운영자라 그 판단도
     * 운영자가 한다 — 셀러에게 넘기면 우리 의무의 이행 여부를 남이 정하고,
     * 더 나쁜 것은 <b>불리한 질문을 광고로 몰아 내리는 자리</b>가 같이 생기는 것이다.
     *
     * <p><b>낸 사람에게는 그대로 보인다</b>({@code InquiryQuery.findMine}) — 제50조의7 은
     * 게시 중단을 요구하지 작성자에게서 감추라고 하지 않는다.
     *
     * @param reason {@code advertisement}(제50조의7) 또는 {@code abuse}(약관).
     *               <b>값으로 갈라 둔다</b> — 법이 근거인 것과 우리 판단이 코드에서 안 갈리면
     *               개정될 때 무엇을 고쳐야 하는지 모른다
     */
    @Transactional
    public void block(long userId, String inquiryNumber, String reason) {
        Row row = find(inquiryNumber);

        if (!evaluator.decide(userId, RESOURCE, BLOCK, row.target()).allowed()) {
            throw new ShopException(ErrorCode.INQUIRY_FORBIDDEN, "게시를 중단할 권한이 없다");
        }

        // 조건부 UPDATE 가 곧 판정이다 — 읽고 나서 쓰는 사이를 우리가 못 잠근다.
        // 이미 답이 나간 글도 내릴 수 있다: 광고에 답을 달았다고 그 광고가 남을 이유가 없다.
        int updated = jdbc.sql("""
                        update inquiry
                           set status = :blocked, blocked_at = now(), blocked_reason = :reason,
                               updated_at = now()
                         where inquiry_number = :number and status <> :blocked
                        """)
                .param("blocked", InquiryStatus.BLOCKED.code())
                .param("reason", reason)
                .param("number", inquiryNumber)
                .update();

        if (updated == 0) {
            throw new ShopException(ErrorCode.INQUIRY_ALREADY_CLOSED, "이미 내려간 게시물이다");
        }
    }

    /**
     * 판정에 쓸 대상. <b>낸 사람과 셀러 둘 다 실린다.</b>
     *
     * @param sellerId 계정에 붙는 요구에는 없다. 그 경우 셀러 스코프가 안 걸린다
     */
    private record Row(long ownerUserId, Long sellerId, String status) {

        Target target() {
            return sellerId == null
                    ? Target.ownedBy(ownerUserId)
                    : Target.of(ownerUserId, sellerId);
        }
    }

    private Row find(String inquiryNumber) {
        return jdbc.sql("""
                        select i.user_id,
                               coalesce(p.seller_id, so.seller_id) as seller_id,
                               i.status
                          from inquiry i
                          left join product p on p.product_id = i.product_id
                          left join seller_order so on so.seller_order_id = i.seller_order_id
                         where i.inquiry_number = :number
                        """)
                .param("number", inquiryNumber)
                .query((rs, rowNum) -> new Row(
                        rs.getLong("user_id"),
                        (Long) rs.getObject("seller_id"),
                        rs.getString("status")))
                .optional()
                .orElseThrow(() -> new ShopException(ErrorCode.INQUIRY_NOT_FOUND,
                        "그런 문의가 없다: " + inquiryNumber));
    }

    /**
     * 내 묶음인가(청크 58-2).
     *
     * <p><b>없는 묶음과 남의 묶음이 같은 404 다.</b> 갈라 주면 묶음 번호를 훑어서
     * 실재하는 주문의 지도를 그릴 수 있고, 그것이 곧 셀러별 거래 건수다.
     *
     * <p>주인을 <b>주문에서</b> 읽는다 — 묶음은 셀러가 가진 것이고 산 사람은 주문에 있다(`D7`).
     */
    private long myBundle(long userId, String sellerOrderNumber) {
        return jdbc.sql("""
                        select so.seller_order_id
                          from seller_order so
                          join shop_order o on o.order_id = so.order_id
                         where so.seller_order_number = :number and o.user_id = :userId
                        """)
                .param("number", sellerOrderNumber)
                .param("userId", userId)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new ShopException(ErrorCode.SELLER_ORDER_NOT_FOUND,
                        "그런 셀러 주문이 없다: " + sellerOrderNumber));
    }

    private void requireProduct(long productId) {
        Optional<Long> found = jdbc.sql("select product_id from product where product_id = :id")
                .param("id", productId)
                .query(Long.class)
                .optional();

        if (found.isEmpty()) {
            throw new ShopException(ErrorCode.PRODUCT_NOT_FOUND, "그런 상품이 없다: " + productId);
        }
    }
}
