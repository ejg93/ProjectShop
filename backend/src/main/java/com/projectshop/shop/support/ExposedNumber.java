package com.projectshop.shop.support;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;

import org.springframework.dao.DuplicateKeyException;

import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

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
 * <p>충돌 재시도까지 여기 있다. 번호를 뽑는 쪽과 부딪혔을 때 다시 뽑는 쪽이 갈리면
 * 한쪽만 고치는 날이 온다.
 */
public final class ExposedNumber {

    /** 날짜 부분의 기준 시간대. 번호에 찍히는 "며칠" 은 업무 기준이라 KST 다(`D10`) */
    private static final java.time.ZoneId KST = java.time.ZoneId.of("Asia/Seoul");

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 난수 부분. {@code 0}·{@code O}·{@code 1}·{@code I} 를 뺀 32자다(`D9`) */
    private static final char[] ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final int RANDOM_LENGTH = 6;

    /** 번호가 부딪히면 다시 뽑는다. 3회에도 안 되면 오류다(`D9`) */
    private static final int RETRIES = 3;

    /**
     * 예측 가능한 번호는 순번만큼은 아니어도 정보가 샌다(`D9`).
     * 비용 차이가 없으므로 안전한 쪽을 쓴다.
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    private ExposedNumber() {}

    /**
     * 번호를 뽑아 넣는다. 유일 제약에 부딪히면 다시 뽑는다.
     *
     * <p><b>재시도가 여기 있는 이유는 부딪혔다는 사실이 DB 에서만 오기 때문이다.</b>
     * 미리 조회해서 비었는지 보는 방식은 그 사이에 남이 같은 번호를 넣으면 그대로 통과한다 —
     * 넣어 보고 걸리면 다시 뽑는 것이 유일하게 맞는 순서다.
     *
     * @param prefix 종류를 가르는 접두어. 주문번호처럼 없으면 빈 문자열
     * @param label  다 실패했을 때 오류에 적을 이름
     * @param insert 번호를 받아 실제로 넣는 것. 부딪히면 {@link DuplicateKeyException} 이 나야 한다
     */
    public static <T> T insertWith(String prefix, String label, Function<String, T> insert) {
        for (int attempt = 1; attempt <= RETRIES; attempt++) {
            try {
                return insert.apply(next(prefix));
            } catch (DuplicateKeyException e) {
                if (attempt == RETRIES) {
                    throw new ShopException(ErrorCode.INTERNAL, label + "를 못 뽑았다");
                }
            }
        }
        throw new IllegalStateException("여기 올 수 없다");
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
