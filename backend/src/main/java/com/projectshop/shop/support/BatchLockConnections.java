package com.projectshop.shop.support;

import java.sql.Connection;
import java.sql.SQLException;

import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

/**
 * 배치 잠금이 쓰는 연결을 요청용 웅덩이 <b>밖에서</b> 준다(`Q50`).
 *
 * <p><b>가르는 것이 요점이다.</b> {@link BatchRuns} 가 회차 하나마다 연결을 하나
 * <b>본체가 끝날 때까지 붙들고</b> 있는데, 배치가 열이고 요청용 웅덩이 기본 크기도 열이다 —
 * 같은 웅덩이를 쓰면 배치가 몰린 새벽에 <b>손님 요청이 연결을 못 받는다.</b>
 * 요청용 웅덩이를 키우는 쪽은 <b>설정값이 경계</b>라 배치나 요청이 늘 때마다 다시 맞춰야 한다.
 *
 * <h2>웅덩이가 아니라 새 연결이다</h2>
 *
 * <p>회차마다 연결을 새로 연다. <b>advisory lock 이 세션에 붙어서</b> 연결을 재활용하면
 * 앞 회차의 잠금을 쥔 세션이 돌아올 수 있고, 같은 세션은 이미 쥔 잠금을 <b>또 잡는다</b>(재진입).
 * {@code Q50} 이 그것을 실측했다 — 잠금을 일부러 안 풀게 고쳐도 테스트가 초록으로 지나갔다.
 * 새 연결이면 닫는 순간 반드시 풀리므로 그 경로 자체가 없어진다.
 *
 * <p>여는 값은 회차마다 한 번이고 제일 잦은 배치가 5분 주기라 셈에 안 들어온다.
 *
 * <h2>수는 스케줄러가 묶는다</h2>
 *
 * <p>동시에 열리는 연결이 <b>동시에 도는 배치 수</b>를 못 넘고, 그것은
 * {@code spring.task.scheduling.pool.size} 가 정한다. 여기에 따로 상한을 두면
 * <b>두 수가 갈리는 자리</b>가 생기고 작은 쪽이 배치를 조용히 굶긴다.
 *
 * <h2>{@code DataSource} 빈으로 안 내놓는다</h2>
 *
 * <p>Boot 의 {@code DataSourceAutoConfiguration} 은 컨텍스트에 {@code DataSource} 가 하나라도
 * 있으면 통째로 물러난다. 그 타입으로 내놓으면 기본 웅덩이·Flyway·트랜잭션 관리자를 손으로
 * 다시 엮게 되고 이 청크가 건드릴 것이 아니다. 대신 {@link JdbcConnectionDetails} 를 받아
 * 안에서 만든다 — 그 빈은 테스트가 이미 갈아 끼우고 있어서({@code PostgresTestBase.forkDatabase})
 * fork 마다 나뉜 DB 를 이쪽도 그대로 따라간다.
 */
@Component
class BatchLockConnections {

    private final DriverManagerDataSource source;

    BatchLockConnections(JdbcConnectionDetails connection) {
        // 웅덩이가 아니라 「부를 때마다 새로 연다」. 잠금을 쥔 세션이 돌아올 자리가 없다.
        this.source = new DriverManagerDataSource(
                connection.getJdbcUrl(), connection.getUsername(), connection.getPassword());
    }

    /** 잠금을 걸고 풀 때까지 붙들 연결. <b>부르는 쪽이 닫고, 닫으면 잠금도 같이 풀린다</b> */
    Connection open() throws SQLException {
        return source.getConnection();
    }
}
