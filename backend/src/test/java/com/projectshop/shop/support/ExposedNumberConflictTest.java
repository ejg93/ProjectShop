package com.projectshop.shop.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.projectshop.shop.PostgresTestBase;

/**
 * <b>재시도를 왜 트랜잭션 밖으로 옮겼나</b>를 진짜 DB 로 고정한다(`Q49`).
 *
 * <p>여기서 잡는 사실이 둘이다. 하나는 <b>유일 충돌이 난 트랜잭션은 죽어 있다</b>는 것이고,
 * 다른 하나는 <b>Postgres 가 제약 이름을 큰따옴표로 감싸서 메시지에 넣는다</b>는 것이다.
 * 앞의 것이 옮긴 이유고 뒤의 것이 {@link ExposedNumber} 의 판정 방식이 서 있는 바닥이다.
 *
 * <p><b>{@code RetriesTest} 는 이 둘을 못 본다.</b> 그쪽은 예외를 손으로 조립해서 던지므로
 * 메시지 꼴이 맞는지도, 두 번째 문장이 정말 죽는지도 확인할 방법이 없다 —
 * 조립한 것으로 조립한 것을 재는 셈이다.
 *
 * <p><b>트랜잭션을 새로 연다</b>({@code REQUIRES_NEW}). 이 바탕이 {@code @Transactional} 이라
 * 그냥 쓰면 충돌이 <b>테스트 자신의 트랜잭션</b>을 죽이고, 그 뒤로는 뒷정리조차 {@code 25P02} 로 죽는다 —
 * 이 청크가 고치고 있는 바로 그 함정이라 처음 쓸 때 그대로 밟았다.
 */
class ExposedNumberConflictTest extends PostgresTestBase {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void createProbeTable() {
        tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.execute(status -> jdbc.sql("""
                create table exposed_number_probe (
                    id bigint generated always as identity primary key,
                    number text not null,
                    constraint exposed_number_probe_number_unique unique (number))
                """).update());
    }

    @AfterEach
    void dropProbeTable() {
        tx.execute(status -> jdbc.sql("drop table exposed_number_probe").update());
    }

    @Test
    @DisplayName("유일 충돌이 난 트랜잭션에서는 다음 문장도 못 돈다")
    void transactionIsDeadAfterUniqueViolation() {
        insert("AAA");

        // **이것이 옮긴 이유다.** 전에는 이 자리에서 다시 넣었는데, 두 번째 문장은
        // DuplicateKeyException 이 아니라 25P02 로 와서 catch 에 안 걸렸다 — 500 이다.
        String afterConflict = tx.execute(status -> {
            assertThatThrownBy(() -> insertHere("AAA"))
                    .as("첫 충돌은 제대로 온다")
                    .isInstanceOf(DuplicateKeyException.class);

            try {
                jdbc.sql("select 1").query(Integer.class).single();
                return "다음 문장이 돌았다";
            } catch (RuntimeException e) {
                status.setRollbackOnly();
                return e.getMessage();
            }
        });

        assertThat(afterConflict)
                .as("Postgres 가 abort 된 트랜잭션에 주는 상태가 25P02 다. "
                        + "같은 트랜잭션 안의 재시도는 여기서 죽으므로 성공할 수가 없다")
                .contains("25P02");
    }

    @Test
    @DisplayName("Postgres 는 제약 이름을 큰따옴표로 감싸서 메시지에 넣는다")
    void messageCarriesConstraintNameInQuotes() {
        insert("BBB");

        assertThatThrownBy(() -> insert("BBB"))
                .as("`ExposedNumber` 가 이 꼴을 보고 판정한다. "
                        + "드라이버가 runtimeOnly 라 PSQLException 에 컴파일로 못 붙는다")
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("\"exposed_number_probe_number_unique\"");
    }

    @Test
    @DisplayName("기대한 제약이면 Conflict 로 바뀌고 다른 제약이면 그대로 올라간다")
    void wrapsOnlyTheNamedConstraint() {
        insert("CCC");

        assertThatThrownBy(() -> insertThrough("exposed_number_probe_number_unique"))
                .as("기대한 제약이 걸렸으니 번호를 다시 뽑을 값이 있다")
                .isInstanceOf(ExposedNumber.Conflict.class);

        assertThatThrownBy(() -> insertThrough("some_other_unique"))
                .as("다른 제약이면 번호를 다시 뽑아도 같은 실패라 재시도할 값이 없다")
                .isInstanceOf(DuplicateKeyException.class)
                .isNotInstanceOf(ExposedNumber.Conflict.class);
    }

    /** {@link ExposedNumber#insert} 를 지나서 넣는다. 값이 부딪히게 {@code CCC} 를 고정으로 쓴다 */
    private void insertThrough(String constraint) {
        tx.execute(status -> {
            try {
                return ExposedNumber.insert("", constraint, number -> insertHere("CCC"));
            } finally {
                status.setRollbackOnly();
            }
        });
    }

    private void insert(String number) {
        tx.execute(status -> insertHere(number));
    }

    private int insertHere(String number) {
        return jdbc.sql("insert into exposed_number_probe (number) values (:number)")
                .param("number", number)
                .update();
    }
}
