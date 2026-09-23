package com.projectshop.shop.support;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.Callable;

import javax.sql.DataSource;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * 한 일에서 나간 SQL 문장 수를 센다(`Q204`). 속도가 아니라 <b>모양</b>을 재는 도구다 — 목록이 쪽 크기에 따라, 상세가 묶음 수에 따라
 * 문장이 늘면 행마다 질의가 나가는 것이다(N+1).
 *
 * <p><b>라이브러리 없이 연결을 감싼다.</b> {@code DataSource} 를 {@link DelegatingDataSource} 로 감싸고 거기서 준 연결을
 * 동적 프록시로 감싸 문장을 만드는 호출({@code prepareStatement}·{@code prepareCall}·{@code createStatement})을 센다.
 * {@code JdbcClient} 는 질의 하나에 문장 하나를 만든다.
 *
 * <p><b>센 스레드 것만 센다.</b> 스위퍼·배치가 다른 스레드에서 도는 동안 재도 그 문장은 안 섞인다.
 */
public final class QueryCounter {

    private static final ThreadLocal<int[]> COUNT = new ThreadLocal<>();

    private static final Set<String> STATEMENT_FACTORIES = Set.of("prepareStatement", "prepareCall", "createStatement");

    private QueryCounter() {
    }

    /** 이 일에서 나간 문장 수 */
    public static int count(Callable<?> work) {
        COUNT.set(new int[1]);
        try {
            work.call();
            return COUNT.get()[0];
        } catch (Exception e) {
            throw new IllegalStateException("세던 일이 실패했다", e);
        } finally {
            COUNT.remove();
        }
    }

    /** 시험 컨텍스트의 {@code DataSource} 를 감싼다. 시험 구성이 빈으로 올린다 */
    public static BeanPostProcessor wrappingDataSource() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                return bean instanceof DataSource dataSource ? counting(dataSource) : bean;
            }
        };
    }

    private static DataSource counting(DataSource target) {
        return new DelegatingDataSource(target) {
            @Override
            public Connection getConnection() throws SQLException {
                return counted(super.getConnection());
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                return counted(super.getConnection(username, password));
            }
        };
    }

    private static Connection counted(Connection target) {
        return (Connection) Proxy.newProxyInstance(QueryCounter.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    int[] count = COUNT.get();
                    if (count != null && STATEMENT_FACTORIES.contains(method.getName())) {
                        count[0]++;
                    }
                    try {
                        return method.invoke(target, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}
