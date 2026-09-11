package com.projectshop.shop.support;

import java.util.Arrays;

/**
 * 어떤 일을 <b>누구의 행동으로 적을 것인가</b>. 자원 여럿이 같이 쓰는 목록이다.
 *
 * <table>
 *   <caption>이 목록에 매달린 자리</caption>
 *   <tr><th>표</th><th>컬럼</th><th>제약</th></tr>
 *   <tr><td>{@code order_status_history}</td><td>{@code actor_type}</td>
 *       <td>{@code order_status_history_actor_type_check} (`V18`)</td></tr>
 *   <tr><td>{@code refund}</td><td>{@code requested_by_type}</td>
 *       <td>{@code refund_requested_by_type_check} (`V25`)</td></tr>
 * </table>
 *
 * <p><b>{@code support} 에 있는 이유는 소비자가 여럿이라서다</b>(`43a-17`). 처음에는
 * {@code order} 안에 있었는데, {@code V25} 가 환불 표를 세우며 <b>「{@code order_status_history} 가
 * 같은 문제를 이미 풀었다, 같은 모양을 쓴다」</b>고 적어 뒀다 — 그 타입은 처음부터
 * {@code order} 전용이 아니었고 <b>문서가 코드보다 앞서 있었다.</b>
 *
 * <p><b>{@code order} 에 두고 열지 않았다</b>(사용자 선택). 그러면 이 값을 쓰는 자원이 늘 때마다
 * {@code X → order} 의존이 생기고 {@code ArchitectureTest} 의 순환 예외가 자란다 —
 * 그 규칙이 <b>「넷째가 생기면 예외로 넣기 전에 멈춘다」</b>고 적어 뒀고 지금 셋이다.
 * {@code refund} 를 읽는 패키지가 이미 넷이라({@code notification}·{@code order}·{@code payment}·{@code settlement})
 * 셋째 소비자가 가깝다.
 *
 * <p><b>역할 코드가 아니다.</b> {@code role.code} 는 {@code seller_owner}·{@code auditor} 까지
 * 있는 권한 축의 목록이고, 이쪽은 <b>한 줄이 누구의 행동인가</b>를 넷으로 뭉갠 것이다.
 * 셀러 대표가 하든 직원이 하든 {@code seller} 로 남는다 — 누구인지는 옆 컬럼이 답한다.
 *
 * <p><b>{@link #SYSTEM} 만 사람이 없다.</b> {@code V18} 이
 * {@code (actor_type = 'system') = (actor_user_id is null)} 로, {@code V25} 가
 * {@code refund_requested_by_user_check} 로 그 짝을 각각 강제한다.
 *
 * <p>열거형으로 두는 근거는 `D23` 「가르는 물음」이다 — <b>값이 하나 늘면 코드를 고쳐야 한다.</b>
 * {@code admin} 이면 사유가 필수고({@code order_status_history_admin_reason_check}),
 * 강제 전이 판정이 그 값으로 갈린다. 표에 행만 넣으면 도달 불가능한 값이 된다.
 */
public enum ActorType {

    /** 산 사람이 스스로 했다. 구매확정·반품 접수·취소·환불 요청 */
    CUSTOMER,

    /** 셀러가 했다. 발송·배송완료·반품 입고. <b>누가</b>인지는 옆 컬럼이다 */
    SELLER,

    /** 운영이 했다. <b>사유가 필수다</b>(`D7`) — 표 밖으로 옮기는 경로라 근거가 남아야 한다 */
    ADMIN,

    /** 배치·결제 모듈이 했다. 지목할 사람이 없어 사용자 칸이 비어 있다 */
    SYSTEM;

    /** 저장값. DB 는 소문자고 응답은 대문자다(`D5` 「형식」) */
    public String code() {
        return name().toLowerCase();
    }

    /**
     * 저장값을 주체로 되돌린다.
     *
     * <p><b>모르는 값이면 터진다.</b> {@code check} 가 이미 막고 있으므로 여기 오는 모르는 값은
     * <b>마이그레이션과 이 enum 이 어긋났다</b>는 뜻이다. 조용히 통과시키면 그 값이 그대로
     * 응답에 실려 나가고 화면이 처음 보는 주체를 받는다.
     */
    public static ActorType of(String code) {
        return Arrays.stream(values())
                .filter(type -> type.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("모르는 주체다: " + code));
    }
}
