package com.projectshop.shop.audit;

import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * 누가 무슨 권한으로 무엇을 했는지 남긴다.
 *
 * <p>관측 로그(D16)와 다르다. 그쪽은 "이 요청이 어디서 느려졌나" 를 파일에 남기고 지워도 되지만,
 * 이쪽은 DB 에 남기고 보존 기간이 걸려 있다.
 *
 * <p><b>사건이 두 종류다.</b> {@link Kind} 를 호출자가 고르고, 그 값이 트랜잭션 전파를 정한다.
 * 종류를 안 고르면 컴파일이 안 되므로 새 사건을 추가할 때 판단을 건너뛸 수 없다.
 *
 * <p>이전에는 전부 업무 트랜잭션에 얹혀 갔고, 「권한 거부는 어차피 쓰기를 안 하므로 안 걸린다」는
 * 전제 위에 있었다. 그 전제가 깨져 있었다 — {@code @Transactional} 서비스 안에서 판정하고
 * 거부로 예외를 던지면 그 거부 기록까지 같이 롤백된다. 청크 {@code 35c} 가 실서버에서 확인했다.
 * 구매자가 남의 발송 경로를 두드렸는데 {@code audit_log} 에 한 줄도 안 남았다.
 * 청크 {@code 4b-2} 가 {@link Kind} 로 갈랐다.
 */
@Component
public class AuditLog {

    private final AuditLogWriter writer;

    AuditLog(AuditLogWriter writer) {
        this.writer = writer;
    }

    /**
     * 사건의 종류. <b>업무가 롤백될 때 이 기록도 같이 사라져야 하나</b>가 유일한 가름이다.
     *
     * <p>전부 {@link #ATTEMPT} 로 두면 안 일어난 일이 기록에 남고, 전부 {@link #OUTCOME} 으로 두면
     * 실패한 시도가 통째로 사라진다. 둘 다 감사가 성립 안 하는 방향이라 사건마다 고른다.
     */
    public enum Kind {

        /**
         * 시도. 업무가 롤백돼도 남는다.
         *
         * <p>거부·실패처럼 <b>업무가 안 일어난 것 자체가 기록할 사실</b>인 사건이다.
         * 업무와 운명을 같이하면 이 종류는 정의상 한 줄도 안 남는다.
         */
        ATTEMPT,

        /**
         * 결과. 업무와 운명을 같이한다.
         *
         * <p>역할 부여·상품 생성처럼 <b>업무가 성립해야 사실이 되는</b> 사건이다.
         * 따로 커밋하면 롤백된 뒤에도 "했다" 는 기록만 남아서 감사가 거짓말을 한다.
         */
        OUTCOME
    }

    /**
     * 사건 하나를 남긴다.
     *
     * @param kind 시도인가 결과인가. {@link Kind} 를 보고 고른다
     * @param eventType 점 표기. {@code permission.denied}, {@code role.granted} 같은 것
     * @param actorUserId 한 사람. 시스템이 한 일이면 null
     * @param target 대상 자원. 자원이 없는 사건이면 {@link Target#none()}
     * @param detail 사건마다 다른 값. 조회 조건이 될 값은 여기 두지 않는다
     */
    public void record(Kind kind, String eventType, Long actorUserId, Target target,
            Map<String, Object> detail) {

        switch (kind) {
            case ATTEMPT -> writer.detached(eventType, actorUserId, target, detail);
            case OUTCOME -> writer.joined(eventType, actorUserId, target, detail);
        }
    }

    /** 무엇에 대한 사건인가. 자원이 없는 사건도 있어서 둘 다 null 인 경우를 허용한다 */
    public record Target(String type, Long id) {

        public static Target none() {
            return new Target(null, null);
        }

        public static Target of(String type, long id) {
            return new Target(type, id);
        }

        /** 대상이 특정 행이 아니라 자원 종류 전체인 경우. 목록 조회 거부 같은 것 */
        public static Target ofType(String type) {
            return new Target(type, null);
        }
    }
}
