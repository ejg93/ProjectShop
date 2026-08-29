package com.projectshop.shop.settlement;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectshop.shop.auth.PermissionEvaluator;
import com.projectshop.shop.auth.PermissionEvaluator.Target;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

/**
 * 확정된 정산을 지급 상태로 옮긴다(청크 21).
 *
 * <h2>마감과 지급은 다른 사건이다</h2>
 *
 * <p>{@code 19} 가 지급액을 확정하는 데까지 왔고 그 뒤가 비어 있었다 — <b>마감된 정산서와
 * 실제로 돈이 나간 정산서가 데이터로 안 갈렸다.</b> 두 번 지급해도 표가 아무 말을 안 한다.
 *
 * <h2>요청과 승인을 가른다</h2>
 *
 * <p>{@link com.projectshop.shop.payment.RefundService} 가 같은 것을 이미 했고 이유도 같다 —
 * 돈이 나가는 결정을 한 사람이 혼자 끝내지 않는다. <b>둘 다 관리자다</b>:
 * 셀러가 올리게 하면 <b>셀러가 안 눌러서 지급이 밀리는 구조</b>가 되는데,
 * 그건 {@code 12a-5} 가 오늘 환불에서 고친 함정이다.
 *
 * <h2>PG 를 안 부른다</h2>
 *
 * <p>셀러 계좌로 보내는 것은 은행 이체라 모의 결제 게이트웨이의 일이 아니다.
 * 여기서 하는 것은 <b>「보냈다」를 기록하는 것</b>까지고, 실제 이체 연동은 계획에 없다
 * (`business-model.md` 가 모의 결제라고 못박았다).
 */
@Service
public class SettlementPayoutService {

    private static final String RESOURCE = "settlement";
    private static final String REQUEST = "request_payout";
    private static final String DECIDE = "payout";

    private final JdbcClient jdbc;
    private final PermissionEvaluator evaluator;

    SettlementPayoutService(JdbcClient jdbc, PermissionEvaluator evaluator) {
        this.jdbc = jdbc;
        this.evaluator = evaluator;
    }

    /**
     * 지급을 올린다. <b>반려된 것도 여기로 다시 온다.</b>
     *
     * <p>정산서는 {@code (셀러, 주기)} 당 하나라 환불처럼 새 행을 만들 수가 없다.
     * 그래서 다시 올릴 때 <b>처리 칸을 비운다</b> — 반려했다는 사실은 감사 로그가 든다.
     *
     * <p><b>줄 돈이 없으면 못 올린다.</b> 지급액이 0 이하인 정산서는 이월로 넘어가지
     * 지급 대상이 아니고({@code business-model.md}), {@code settlement_payout_amount_check} 가
     * 한 층 아래에서 막는다.
     */
    @Transactional
    public void request(long userId, String settlementNumber) {
        Row row = find(userId, settlementNumber, REQUEST);

        if (row.payoutAmount() <= 0) {
            throw new ShopException(ErrorCode.SETTLEMENT_NOTHING_TO_PAY,
                    "지급액이 " + row.payoutAmount() + " 원이라 올릴 것이 없다");
        }

        // 조건부 UPDATE 가 곧 판정이다. 읽고 나서 쓰는 사이를 우리가 못 잠근다.
        int updated = jdbc.sql("""
                        update settlement
                           set payout_status = :requested,
                               payout_requested_by_user_id = :userId,
                               payout_requested_at = now(),
                               payout_decided_by_user_id = null,
                               payout_decided_at = null,
                               updated_at = now()
                         where settlement_number = :number
                           and payout_status in (:pending, :rejected)
                        """)
                .param("requested", PayoutStatus.REQUESTED.code())
                .param("userId", userId)
                .param("number", settlementNumber)
                .param("pending", PayoutStatus.PENDING.code())
                .param("rejected", PayoutStatus.REJECTED.code())
                .update();

        if (updated == 0) {
            throw new ShopException(ErrorCode.SETTLEMENT_ALREADY_DECIDED,
                    "이미 " + row.payoutStatus() + " 인 정산서다");
        }
    }

    /**
     * 지급을 승인한다. <b>여기서 돈이 나간 것으로 친다.</b>
     *
     * <p>자기가 올린 것은 자기가 승인 못 한다. {@code settlement_payout_self_approval_check} 가
     * 같은 것을 한 층 아래에서 막고, 여기 있는 것은 <b>이유를 담은 403 을 주기 위해서</b>다 —
     * 앱만 있으면 새 입구가 빠뜨리고 제약만 있으면 사용자가 받는 것이 500 이다(`D23` 축 2).
     */
    @Transactional
    public void approve(long userId, String settlementNumber) {
        decide(userId, settlementNumber, PayoutStatus.PAID.code());
    }

    /** 지급을 반려한다. 돈이 안 나가므로 다시 올릴 수 있다 */
    @Transactional
    public void reject(long userId, String settlementNumber) {
        decide(userId, settlementNumber, PayoutStatus.REJECTED.code());
    }

    private void decide(long userId, String settlementNumber, String decision) {
        Row row = find(userId, settlementNumber, DECIDE);

        if (row.requestedByUserId() != null && row.requestedByUserId() == userId) {
            throw new ShopException(ErrorCode.SETTLEMENT_SELF_APPROVAL);
        }

        int updated = jdbc.sql("""
                        update settlement
                           set payout_status = :decision,
                               payout_decided_by_user_id = :userId,
                               payout_decided_at = now(),
                               updated_at = now()
                         where settlement_number = :number and payout_status = :requested
                        """)
                .param("decision", decision)
                .param("userId", userId)
                .param("number", settlementNumber)
                .param("requested", PayoutStatus.REQUESTED.code())
                .update();

        if (updated == 0) {
            throw new ShopException(ErrorCode.SETTLEMENT_ALREADY_DECIDED,
                    "올라온 지급이 아니다: " + row.payoutStatus());
        }
    }

    private record Row(long sellerId, long payoutAmount, String payoutStatus,
            Long requestedByUserId) {}

    /**
     * 다룰 수 있는 정산서인가.
     *
     * <p><b>판정이 상태 검사보다 앞이다.</b> 순서를 바꾸면 남의 정산서에 지급을 시도한 사람이
     * 「이미 처리됐다」(409)를 받는데, 그것만으로 <b>그 번호가 실재한다는 것</b>이 드러난다
     * ({@code RefundService.findPending} 와 같은 판단).
     */
    private Row find(long userId, String settlementNumber, String action) {
        Row row = jdbc.sql("""
                        select seller_id, payout_amount, payout_status,
                               payout_requested_by_user_id
                          from settlement where settlement_number = :number
                        """)
                .param("number", settlementNumber)
                .query((rs, rowNum) -> new Row(
                        rs.getLong("seller_id"),
                        rs.getLong("payout_amount"),
                        rs.getString("payout_status"),
                        (Long) rs.getObject("payout_requested_by_user_id")))
                .optional()
                .orElseThrow(() -> notFound(settlementNumber));

        if (!evaluator.decide(userId, RESOURCE, action, Target.ofSeller(row.sellerId()))
                .allowed()) {
            throw notFound(settlementNumber);
        }
        return row;
    }

    private static ShopException notFound(String settlementNumber) {
        return new ShopException(ErrorCode.SETTLEMENT_NOT_FOUND,
                "그런 정산서가 없다: " + settlementNumber);
    }
}
