package com.projectshop.shop.auth;

import java.util.Arrays;
import java.util.Locale;

/**
 * 권한이 읽기냐 쓰기냐. 저장값은 {@code permission.kind} 고 목록은 {@code permission_kind_check} 다(`Q59`).
 *
 * <p><b>이름으로 안 가른다.</b> {@code action = 'read'} 로 가르면 조회에 다른 이름을 붙이는 날
 * 조용히 틀린다 — 그래서 컬럼을 두고 <b>넣는 사람이 매번 정하게</b> 한다(기본값이 없다).
 *
 * <p><b>이 값이 거부를 만든다.</b> 읽기 전용 역할({@code role.is_read_only})에 붙는 거부 행을
 * 트리거가 이 종류를 보고 만든다 — 사람이 마이그레이션마다 손으로 넣던 것을 내린 자리다
 * (`D6` 「알려진 구멍 1·2」).
 *
 * <p><b>Java 는 이 값으로 분기하지 않는다.</b> 판정은 여전히 거부 행이 하고, 이 열거형이 있는 이유는
 * {@code EnumConstraintTest} 가 SQL 의 닫힌 목록과 대조할 상대가 필요해서다.
 */
enum PermissionKind {

    /** 조회다. 읽기 전용 역할도 지나간다 */
    READ,

    /** 값을 바꾼다. 읽기 전용 역할에는 거부가 붙는다 */
    WRITE;

    /** 저장값. 소문자다 */
    String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * 저장값을 종류로 되돌린다.
     *
     * <p><b>모르는 값이면 터진다.</b> {@code permission_kind_check} 가 이미 막고 있으므로
     * 여기 오는 모르는 값은 <b>마이그레이션과 이 enum 이 어긋났다</b>는 뜻이다.
     */
    static PermissionKind of(String code) {
        return Arrays.stream(values())
                .filter(kind -> kind.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("모르는 권한 종류다: " + code));
    }
}
