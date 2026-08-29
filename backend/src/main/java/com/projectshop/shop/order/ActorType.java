package com.projectshop.shop.order;

import java.util.Arrays;

/**
 * 상태를 옮긴 주체. 저장값은 {@code order_status_history.actor_type} 이고
 * 목록은 {@code order_status_history_actor_type_check} 다(`V18`).
 *
 * <p><b>역할 코드가 아니다.</b> {@code role.code} 는 {@code seller_owner}·{@code auditor} 까지
 * 있는 권한 축의 목록이고, 이쪽은 <b>이력 한 줄이 누구의 행동인가</b>를 넷으로 뭉갠 것이다.
 * 셀러 대표가 옮기든 직원이 옮기든 이력에는 {@code seller} 로 남는다 — 누구인지는
 * {@code actor_user_id} 가 답한다.
 *
 * <p><b>{@link #SYSTEM} 만 사람이 없다.</b> {@code V18} 이
 * {@code (actor_type = 'system') = (actor_user_id is null)} 로 그 짝을 강제한다.
 *
 * <p>열거형으로 두는 근거는 `D23` 「가르는 물음」이다 — <b>값이 하나 늘면 코드를 고쳐야 한다.</b>
 * {@code admin} 이면 사유가 필수고({@code order_status_history_admin_reason_check}),
 * 강제 전이 판정이 {@code "admin"} 을 문자열로 비교한다. 표에 행만 넣으면 도달 불가능한 값이 된다.
 */
enum ActorType {

    /** 산 사람이 스스로 옮겼다. 구매확정·반품 접수·취소 */
    CUSTOMER,

    /** 셀러가 옮겼다. 발송·배송완료·반품 입고. <b>누가</b>인지는 {@code actor_user_id} 다 */
    SELLER,

    /** 운영이 옮겼다. <b>사유가 필수다</b>(`D7`) — 표 밖으로 옮기는 경로라 근거가 남아야 한다 */
    ADMIN,

    /** 배치·결제 모듈이 옮겼다. 지목할 사람이 없어 {@code actor_user_id} 가 비어 있다 */
    SYSTEM;

    /** 저장값. DB 는 소문자고 응답은 대문자다(`D5` 「형식」) */
    String code() {
        return name().toLowerCase();
    }

    /**
     * 저장값을 주체로 되돌린다.
     *
     * <p><b>모르는 값이면 터진다.</b> {@code check} 가 이미 막고 있으므로 여기 오는 모르는 값은
     * <b>마이그레이션과 이 enum 이 어긋났다</b>는 뜻이다. 조용히 통과시키면 그 값이 그대로
     * 응답에 실려 나가고 화면이 처음 보는 주체를 받는다.
     */
    static ActorType of(String code) {
        return Arrays.stream(values())
                .filter(type -> type.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("모르는 전이 주체다: " + code));
    }
}
