package com.projectshop.shop.support;

import java.util.List;
import java.util.Map;

import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

/**
 * 목록 조회에서 두 번 이상 나오는 것을 모은다(`D5` 「목록 조회」).
 *
 * <p>페이지 보정과 정렬 허용 목록이다. 목록 API 가 하나뿐일 때는 각자 두는 편이 읽기 쉬웠지만,
 * 두 번째가 생기면서 <b>같은 규칙이 두 벌</b>이 됐다 — 상한을 한쪽만 올리는 날이 온다.
 */
public final class ListQuery {

    /** 목록 하나로 전체를 긁어 가지 못하게 막는다(`D5`). */
    public static final int MAX_SIZE = 100;

    private ListQuery() {
    }

    /**
     * 페이지 번호와 크기. 요청이 무엇을 보내든 여기서 성한 값이 된다.
     *
     * @param offset 계산해 둔다. 호출자가 매번 곱하면 한 번은 int 로 넘쳐서 음수가 된다
     */
    public record Paging(int page, int size, long offset) {

        public static Paging of(int page, int size) {
            int safeSize = Math.min(Math.max(size, 1), MAX_SIZE);
            int safePage = Math.max(page, 0);
            return new Paging(safePage, safeSize, (long) safePage * safeSize);
        }
    }

    /**
     * 정렬 절을 만든다. <b>요청 문자열이 SQL 에 닿지 않는다.</b>
     *
     * <p>컬럼명은 값이 아니라 식별자라 바인딩이 안 된다({@code order by ?} 는 문법 오류다).
     * 결합이 강제되므로 <b>들어올 수 있는 값을 우리가 정한다</b>(`D14`).
     * SQL 에 닿는 것은 {@code sortable} 의 값뿐이고 요청은 키를 고르는 데만 쓰인다.
     *
     * @param sort     {@code 필드,방향} 형태. null 이면 {@code defaultSort}
     * @param sortable API 이름 → 실제 컬럼식. 여기 없는 이름은 거부한다
     */
    public static String orderBy(String sort, String defaultSort, Map<String, String> sortable) {
        String[] parts = (sort == null || sort.isBlank() ? defaultSort : sort).split(",");

        String column = sortable.get(parts[0].trim());
        if (column == null) {
            throw new ShopException(ErrorCode.SORT_NOT_ALLOWED,
                    "정렬할 수 없는 필드다: " + parts[0] + ". 쓸 수 있는 것: "
                            + List.copyOf(sortable.keySet()));
        }

        // 방향도 목록이다. asc 가 아니면 전부 desc 로 본다 — 오타로 SQL 이 깨지지 않게 한다.
        String direction = parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim())
                ? "asc" : "desc";

        return column + " " + direction;
    }
}
