package com.projectshop.shop.support;

import java.util.Arrays;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * `check` 제약이 허용하는 값을 정의에서 뽑는다. <b>enum 과 대조하는 테스트가 쓴다</b>
 * (`D23` 「목록이 둘로 갈리는 것을 테스트가 막는다」).
 *
 * <p>제약 정의를 문자열로 읽는 것이 거칠지만, 대안이 <b>목록을 테스트에 손으로 또 적는 것</b>이라
 * 그러면 세 번째 사본이 생긴다 — 대조 테스트가 막으려는 것이 정확히 그거다.
 *
 * <p><b>이 클래스가 있는 이유도 같다.</b> 뽑는 코드가 테스트마다 한 벌씩 생기면
 * 정규식이 갈린다 — 실제로 갈려 있었다. 한쪽은 소문자만 훑어서 {@code under_50_transactions}
 * 처럼 숫자가 낀 값을 조용히 빠뜨렸고, <b>빠진 채로 테스트가 통과했다</b>(`35e`).
 */
public final class ConstraintValues {

    private ConstraintValues() {
    }

    /**
     * 제약이 허용하는 값 전체를 뽑는다.
     *
     * @param jdbc           제약 정의를 읽을 연결
     * @param constraintName `pg_constraint.conname` 에 있는 이름
     */
    public static List<String> of(JdbcClient jdbc, String constraintName) {
        return valuesIn(definitionOf(jdbc, constraintName));
    }

    /**
     * 제약 정의를 원문 그대로 준다. <b>정의 안이 여러 갈래인 제약이 쓴다</b> —
     * {@code order_status_history_status_check} 는 {@code case} 로 층마다 다른 목록을 적어서,
     * 통째로 뽑으면 두 목록이 한 덩어리가 되고 <b>어느 값이 어느 갈래인지가 사라진다.</b>
     *
     * @param jdbc           제약 정의를 읽을 연결
     * @param constraintName `pg_constraint.conname` 에 있는 이름
     */
    public static String definitionOf(JdbcClient jdbc, String constraintName) {
        return jdbc.sql("""
                        select pg_get_constraintdef(oid) from pg_constraint
                         where conname = :name
                        """)
                .param("name", constraintName)
                .query(String.class)
                .single();
    }

    /**
     * 정의 조각에서 따옴표 안의 값을 뽑는다. <b>같은 값이 여러 번 나와도 한 번만 준다</b> —
     * 한 제약이 {@code to_status} 와 {@code from_status} 에 같은 목록을 두 번 적는다.
     *
     * <p>숫자를 같이 받는다. {@code under_50_transactions} 에 {@code 50} 이 들어 있어서
     * 소문자만 훑으면 그 값이 통째로 빠진다.
     *
     * @param definitionFragment 제약 정의 또는 그 일부
     */
    public static List<String> valuesIn(String definitionFragment) {
        return Arrays.stream(definitionFragment.split("'"))
                .filter(part -> part.matches("[a-z0-9_]+"))
                .distinct()
                .toList();
    }
}
