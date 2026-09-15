package com.projectshop.shop.support;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;

import org.springframework.dao.DuplicateKeyException;


/**
 * 밖에 내보이는 번호를 뽑는다(`D9`).
 *
 * <p><b>{@code 접두어 + yyyyMMdd + '-' + 난수 6자}</b> 한 가지 형식이다. 접두어가 종류를 가른다 —
 * 주문은 없고, 셀러 묶음은 {@code S-}, 환불은 {@code R-} 다. 전화로 번호를 받는 자리에서
 * 형식이 같으면 어느 쪽인지 못 가려서 접두어 하나가 그것을 가른다.
 *
 * <p><b>여기 있는 이유는 사본이 셋이 되기 때문이다.</b> 주문번호와 셀러 주문번호가
 * {@code OrderService} 안에서 같은 코드를 두 벌 들고 있었고, 환불이 세 번째였다 —
 * 그 상태를 지나면 다음 사람이 어느 사본을 베낄지 고르는 자리가 생긴다(CLAUDE.md 「청크 규칙」).
 *
 * <p><b>다시 뽑는 것은 여기가 아니다</b>(`Q49`). 전에는 이 안에서 세 번까지 다시 넣었는데
 * <b>그 자리에서는 한 번도 못 돌았다</b> — 호출 다섯이 전부 {@code @Transactional} 안이고,
 * Postgres 는 오류가 한 번 나면 그 트랜잭션을 죽인다. 두 번째 {@code insert} 는
 * {@code DuplicateKeyException} 이 아니라 {@code 25P02}(죽은 트랜잭션) 로 와서 잡히지도 않았다.
 * 재시도는 트랜잭션 <b>밖</b>의 {@link Retries#onConflict} 가 받는다.
 */
public final class ExposedNumber {

    /** 날짜 부분의 기준 시간대. 번호에 찍히는 "며칠" 은 업무 기준이라 KST 다(`D10`) */
    private static final java.time.ZoneId KST = java.time.ZoneId.of("Asia/Seoul");

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 난수 부분. {@code 0}·{@code O}·{@code 1}·{@code I} 를 뺀 32자다(`D9`) */
    private static final char[] ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final int RANDOM_LENGTH = 6;

    /**
     * 예측 가능한 번호는 순번만큼은 아니어도 정보가 샌다(`D9`).
     * 비용 차이가 없으므로 안전한 쪽을 쓴다.
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    private ExposedNumber() {}

    /**
     * 번호를 뽑아 한 번 넣는다. 그 번호가 부딪히면 {@link Conflict} 로 바꿔 올린다.
     *
     * <p><b>부딪혔다는 사실은 DB 에서만 온다.</b> 미리 조회해서 비었는지 보는 방식은
     * 그 사이에 남이 같은 번호를 넣으면 그대로 통과한다 — 넣어 보고 걸리는 것이
     * 유일하게 맞는 순서다. <b>다시 뽑는 것은 트랜잭션 밖</b>이라 여기서 안 한다.
     *
     * <p><b>제약 이름을 받는 이유는 같은 {@code insert} 가 다른 유일 제약도 어길 수 있어서다.</b>
     * 이름이 안 맞으면 {@link Conflict} 로 안 바꾸고 그대로 올린다 — 다시 뽑아도 같은 실패라
     * 재시도가 네 배 느리기만 하다. {@link Retries} 의 「다시 해서 결과가 달라질 수 있는 것만」과 같은 기준이다.
     *
     * <p><b>결제사가 준 번호는 이 길을 안 지난다.</b> {@code payment_approval_number_unique} 와
     * {@code refund_gateway_refund_number_unique} 도 이름이 {@code _number_unique} 로 끝나지만
     * 값을 바깥에서 받으므로 다시 뽑을 것이 없다 — <b>꼬리로 가르면 그 둘이 같이 걸린다.</b>
     * 이 자리를 지나는 다섯만 재시도 대상이고, 그 다섯은 {@code ExposedNumberConstraintTest} 가 센다.
     *
     * @param prefix     종류를 가르는 접두어. 주문번호처럼 없으면 빈 문자열
     * @param constraint 그 번호를 지키는 유일 제약의 이름. 마이그레이션에 적힌 그대로
     * @param insert     번호를 받아 실제로 넣는 것. 부딪히면 {@link DuplicateKeyException} 이 나야 한다
     */
    public static <T> T insert(String prefix, String constraint, Function<String, T> insert) {
        try {
            return insert.apply(next(prefix));
        } catch (DuplicateKeyException e) {
            if (mentions(e, constraint)) {
                throw new Conflict(constraint, e);
            }
            throw e;
        }
    }

    /**
     * 이 예외가 그 제약을 짚고 있나.
     *
     * <p><b>메시지를 본다.</b> 제약 이름을 타입으로 읽으려면 {@code PSQLException} 이 필요한데
     * 드라이버가 {@code runtimeOnly} 라 본코드가 컴파일로 못 붙는다(`stack.md`).
     * Postgres 는 이름을 <b>큰따옴표로 감싸서</b> 메시지에 넣으므로 그것만 찾는다 —
     * 문구가 번역돼도 따옴표 안은 안 바뀐다. 실물 메시지는 {@code ExposedNumberConflictTest} 가 고정한다.
     */
    private static boolean mentions(Throwable thrown, String constraint) {
        String quoted = "\"" + constraint + "\"";
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains(quoted)) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    /**
     * 뽑은 번호가 이미 있었다. <b>다시 뽑으면 결과가 달라지는 유일한 충돌</b>이라
     * {@link Retries#onConflict} 가 이것만 보고 다시 돈다.
     *
     * <p><b>타입이 곧 판정이다.</b> 제약 이름 목록을 재시도 쪽에 두면 새 노출 번호가 생긴 날
     * 거기 적는 것을 빠뜨리고, 빠뜨린 것은 충돌이 나야만 드러난다 — 10억 분의 1 이라 안 드러난다.
     * 이 예외는 {@link #insert} 만 만들 수 있어서 <b>목록이 갈릴 자리가 없다</b>.
     */
    public static final class Conflict extends RuntimeException {

        private final transient String constraint;

        Conflict(String constraint, Throwable cause) {
            super("노출 번호가 부딪혔다: " + constraint, cause);
            this.constraint = constraint;
        }

        /** 어느 제약이 걸렸나. 재시도 로그가 이것을 적는다 */
        public String constraint() {
            return constraint;
        }
    }

    /** {@code R-20260819-7QX4M2}. 날짜는 CS 용이고 뒤는 순번을 가린다(`D9`) */
    static String next(String prefix) {
        return prefix + LocalDate.now(KST).format(DATE) + "-" + randomPart();
    }

    private static String randomPart() {
        StringBuilder random = new StringBuilder(RANDOM_LENGTH);
        for (int i = 0; i < RANDOM_LENGTH; i++) {
            random.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return random.toString();
    }
}
