package com.projectshop.shop.payment;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.support.Retries;

/**
 * 환불 요청이 없는 채로 닫힌 묶음을 찾아 요청을 만든다.
 *
 * <h2>왜 있나</h2>
 *
 * <p><b>법이 청약철회만으로 환급 의무를 발생시킨다</b>(`D2` R5, 전자상거래법 제18조제2항).
 * 별도 요청을 요구할 근거가 없다. 그런데 {@code 12a-1} 은 환불을 요청·승인 워크플로로 만들었고
 * 요청을 사람만 낼 수 있었다 — <b>취소·반품을 했는데 아무도 요청을 안 낸 구간</b>에서
 * 3영업일이 그냥 흐르고 지연배상금이 연 15%로 붙는다(시행령 제21조의3).
 *
 * <p><b>요청을 누가 낼 수 있나와 요청이 자동으로 나야 하나는 다른 물음</b>이다.
 * 앞은 권한 설계(`V24`)고 뒤가 이 클래스다.
 *
 * <h2>왜 전이 쪽이 아니라 배치인가</h2>
 *
 * <p>{@code OrderStatusService} 가 취소·반품을 처리할 때 같이 만드는 편이 지연이 0 이다.
 * 그런데 그쪽은 {@code order} 패키지고 이쪽은 {@code payment} 라 <b>{@code order → payment}
 * 의존이 생겨 순환</b>이 된다({@code PaymentService} 가 이미 {@code order} 를 쓴다).
 * `D23` 「의존」이 허용해 둔 순환은 {@code auth ↔ audit} 하나뿐이고 그건 "설계상 필연" 이라
 * 적혀 있다 — 이건 배치로 피할 수 있어서 필연이 아니다.
 *
 * <p><b>몇 분 늦는 것은 3영업일 기한에 무의미하다.</b> 그리고 기한은 이 요청이 만들어진 시각이
 * 아니라 <b>사건이 일어난 날</b>에서 세므로({@link RefundService} 의 기산점 표) 배치가 늦어도
 * 기한이 밀리지 않는다.
 *
 * <h2>여러 번 돌아도 결과가 같다</h2>
 *
 * <p>고르는 조건이 「환불 요청이 없는 묶음」이다. 만들고 나면 다음 회차의 대상에서 빠진다.
 * 도중에 죽어도 만들어진 것까지는 커밋돼 있고, 남은 것은 다음 회차가 집는다.
 */
@Component
public class RefundSweeper {

    private static final Logger log = LoggerFactory.getLogger(RefundSweeper.class);

    private final JdbcClient jdbc;
    private final RefundService refunds;

    RefundSweeper(JdbcClient jdbc, RefundService refunds) {
        this.jdbc = jdbc;
        this.refunds = refunds;
    }

    /**
     * {@code fixedDelay} 다. 앞 회차가 끝난 뒤부터 5분을 세므로 느려져도 겹치지 않는다
     * ({@code OrderStatusBatch.expireUnpaidOrders} 와 같은 이유).
     *
     * <p><b>04:00 규칙에서 빠진다</b>(`D10`). 하루 한 번이면 3영업일 중 하루를 배치 대기로 쓴다 —
     * 기한이 짧아서 그 하루가 크다.
     */
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT2M")
    public void sweep() {
        sweepClosedBundles();
    }

    /**
     * 닫혔는데 환불 요청이 없는 묶음.
     *
     * @param sellerOrderNumber 요청을 만들 대상
     * @param actorType         묶음을 닫은 사람의 종류. 사유가 여기서 갈린다
     */
    private record Target(String sellerOrderNumber, String actorType) {
    }

    /** @return 만든 요청 수 */
    public int sweepClosedBundles() {
        List<Target> targets = findTargets();

        if (targets.isEmpty()) {
            // 대상이 없는 회차를 INFO 로 남기면 5분마다 한 줄씩 하루 288줄이 쌓여서
            // 진짜 처리가 그 사이에 묻힌다(`D16`).
            log.debug("환불 요청 스위퍼 — 대상 없음");
            return 0;
        }
        log.info("환불 요청 스위퍼 시작 대상={}건", targets.size());

        int created = 0;
        for (Target target : targets) {
            if (create(target)) {
                created++;
            }
        }
        log.info("환불 요청 스위퍼 끝 생성={}건", created);
        return created;
    }

    /**
     * 대상을 고른다.
     *
     * <p>넷을 본다.
     * <ol>
     *   <li><b>닫혔고 취소·반품이다</b> — {@code confirmed} 도 {@code closed_at} 을 채우지만
     *       구매확정은 환불 대상이 아니다. {@code seller_order_closed_refundable_idx} 의
     *       부분 인덱스 조건이 이 조건과 같다(`V25`)</li>
     *   <li><b>결제 승인이 있다</b> — 낸 적 없는 돈은 돌려줄 것이 없다. 결제 만료로 자동 취소된
     *       묶음이 여기서 걸러진다</li>
     *   <li><b>반려 아닌 환불이 하나도 없다</b> — 사람이 이미 냈으면 만들지 않는다.
     *       반려된 것만 있으면 다시 만든다(그 돈은 여전히 안 나갔다)</li>
     *   <li><b>누가 닫았나</b> — 사유와 기산점이 여기서 갈린다</li>
     * </ol>
     *
     * <p><b>이력의 마지막 줄을 읽는다.</b> 같은 묶음이 여러 번 전이하므로 종점으로 옮긴 줄이
     * 어느 것인지를 시각으로 고른다.
     */
    private List<Target> findTargets() {
        return jdbc.sql("""
                        select so.seller_order_number,
                               (select h.actor_type
                                  from order_status_history h
                                 where h.seller_order_id = so.seller_order_id
                                   and h.to_status = so.status
                                 order by h.occurred_at desc, h.order_status_history_id desc
                                 limit 1) as actor_type
                          from seller_order so
                         where so.closed_at is not null
                           and so.status in ('cancelled', 'returned')
                           and exists (select 1 from payment p
                                        where p.order_id = so.order_id and p.status = 'approved')
                           and not exists (select 1 from refund r
                                            where r.seller_order_id = so.seller_order_id
                                              and r.status <> 'rejected')
                         order by so.closed_at, so.seller_order_id
                        """)
                .query((rs, rowNum) -> new Target(
                        rs.getString("seller_order_number"),
                        rs.getString("actor_type")))
                .list();
    }

    /**
     * 하나가 실패해도 나머지를 계속한다.
     *
     * <p>레벨을 가른다. <b>{@code ShopException} 은 `WARN`</b> 이다 — 고르고 나서 만들기까지
     * 사이에 사람이 요청을 냈거나 묶음 상태가 바뀐 것이라 사람이 할 일이 없다.
     * 그 밖의 실패는 `ERROR` 다 — 배치가 못 도는 것이라 누가 봐야 한다(`D16`).
     *
     * <p><b>개인정보를 안 남긴다</b>(`D16`). 로그에 나가는 것은 노출 번호뿐이다.
     */
    private boolean create(Target target) {
        try {
            Retries.onConflict(() -> {
                RefundService.Bundle bundle =
                        refunds.findRefundableBundle(target.sellerOrderNumber());

                return refunds.requestBySystem(bundle, new RefundService.RequestCommand(
                        target.sellerOrderNumber(), reasonOf(bundle, target.actorType()),
                        List.of(), null));
            });
            return true;
        } catch (ShopException e) {
            log.warn("스위퍼가 건너뛴다 seller_order_number={} 이유={}",
                    target.sellerOrderNumber(), e.code());
            return false;
        } catch (RuntimeException e) {
            log.error("스위퍼가 실패했다 seller_order_number={}", target.sellerOrderNumber(), e);
            return false;
        }
    }

    /**
     * 사유를 정한다. <b>기산점이 여기서 갈린다</b>(`D2` R5).
     *
     * <p>반품은 누가 눌렀든 청약철회에 따른 환급이다 — 셀러가 반품완료를 누르는 것은
     * <b>재화를 반환받았다는 사실</b>이지 셀러가 일으킨 사건이 아니다(제18조제2항 1호).
     *
     * <p>취소는 갈린다.
     * <ul>
     *   <li><b>고객</b> — 청약철회다. 제18조제2항 3호로 청약철회한 날에서 센다</li>
     *   <li><b>셀러</b> — 공급 불능이다. <b>제15조제2항</b>이라 대금을 지급한 날에서 센다</li>
     *   <li><b>그 밖</b> — 관리자거나 이력을 못 찾은 것이다. 사유가 자유 텍스트라 조문을
     *       못 고르므로 <b>이른 쪽</b>으로 떨어뜨린다. 늦게 잡으면 위반이고 일찍 잡으면
     *       우리가 손해를 볼 뿐이다</li>
     * </ul>
     */
    private static String reasonOf(RefundService.Bundle bundle, String actorType) {
        if ("returned".equals(bundle.status())) {
            return RefundService.REASON_WITHDRAWAL;
        }
        if ("customer".equals(actorType)) {
            return RefundService.REASON_CANCELLED;
        }
        if ("seller".equals(actorType)) {
            return RefundService.REASON_SUPPLY_FAILED;
        }
        return RefundService.REASON_ADMIN_CANCELLED;
    }
}
