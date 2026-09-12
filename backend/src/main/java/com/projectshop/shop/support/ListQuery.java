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

    /** 크기를 안 주면 이만큼(`D5` 「목록 조회」). 컨트롤러마다 적던 값을 한 곳으로 모았다. */
    public static final int DEFAULT_SIZE = 20;

    private ListQuery() {
    }

    /**
     * 페이지 번호와 크기. <b>성한 값이라는 것을 타입이 보증한다.</b>
     *
     * <p><b>보정이 생성자에 있다</b>(`Q23`). 전에는 {@code of()} 안에 있어서
     * <b>안 부르면 안 돌았다</b> — 목록 입구 열 곳이 각자 그것을 부르고 있었고,
     * 열한 번째가 빠뜨려도 컴파일도 테스트도 통과한다. 상한만 조용히 사라진다.
     * 지금은 이 타입이 존재한다는 것 자체가 「보정을 거쳤다」는 뜻이다.
     *
     * <p><b>{@code offset} 을 칸에서 뺐다.</b> 칸이면 {@code new Paging(3, 20, 0)} 처럼
     * <b>말이 안 되는 값</b>을 넣을 수 있는데, 계산해서 주면 그 실수가 성립하지 않는다.
     */
    public record Paging(int page, int size) {

        public Paging {
            page = Math.max(page, 0);
            size = Math.min(Math.max(size, 1), MAX_SIZE);
        }

        /**
         * 건너뛸 행 수.
         *
         * <p><b>{@code long} 이라야 한다.</b> 호출자가 {@code page * size} 를 {@code int} 로
         * 곱하면 한 번은 넘쳐서 <b>음수 offset</b> 이 SQL 로 나간다.
         */
        public long offset() {
            return (long) page * size;
        }
    }

    /**
     * 정렬 절을 만든다. <b>요청 문자열이 SQL 에 닿지 않는다.</b>
     *
     * <p>컬럼명은 값이 아니라 식별자라 바인딩이 안 된다({@code order by ?} 는 문법 오류다).
     * 결합이 강제되므로 <b>들어올 수 있는 값을 우리가 정한다</b>(`D14`).
     * SQL 에 닿는 것은 {@code sortable} 의 값뿐이고 요청은 키를 고르는 데만 쓰인다.
     *
     * <p><b>{@link OrderBy} 로 돌려준다</b>(`Q24`). {@code String} 이던 자리인데, 그러면
     * SQL 을 잇는 쪽에서 <b>검증을 거친 문자열과 요청 문자열이 같은 타입</b>이라
     * {@code " order by " + sort} 라고 써도 아무것도 안 걸린다.
     *
     * @param sort     {@code 필드,방향} 형태. null 이면 {@code defaultSort}
     * @param sortable API 이름 → 실제 컬럼식. 여기 없는 이름은 거부한다
     */
    public static OrderBy orderBy(String sort, String defaultSort, Map<String, String> sortable) {
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

        return new OrderBy(column + " " + direction);
    }

    /**
     * SQL 에 이어도 되는 정렬 절. <b>허용 목록을 거쳤다는 것을 타입이 뜻한다</b>(`Q24`).
     *
     * <p>{@link Paging} 과 같은 꼴인데 <b>막는 힘이 그만 못하다.</b> 페이지는 생 {@code int} 를
     * 받는 자리를 없애서 빠뜨릴 대상 자체가 사라졌지만, 정렬은 조립이 문자열이라
     * <b>{@code OrderBy} 를 아예 안 쓰고 {@code " order by " + sort} 라고 쓰면 그대로 통과한다.</b>
     * 그 구멍은 {@code ArchitectureTest} 의 규칙이 맡는다 — <b>둘이 서로의 구멍을 덮는다.</b>
     *
     * <p><b>생성자를 못 닫는다.</b> {@code public record} 의 canonical 생성자는 {@code public}
     * 이라야 해서, {@code new OrderBy(요청문자열)} 이라고 쓰는 길이 열려 있다 —
     * <b>이 타입은 「거쳤다」를 뜻하지 「거칠 수밖에 없다」를 뜻하지 않는다.</b>
     * 그래서 위 규칙이 짝으로 필요하다.
     *
     * <p><b>{@code toString} 을 안 고친다.</b> 고쳐서 절을 그대로 돌려주면
     * {@code " order by " + orderBy} 가 컴파일도 동작도 돼서, <b>이 타입이 결합 자리에서 주는
     * 신호가 사라진다</b> — {@code clause()} 를 쓸 때만 남게 된다.
     *
     * @param clause {@code 컬럼식 방향} 꼴. 컬럼식은 {@code sortable} 의 값이라 요청이 안 닿는다
     */
    public record OrderBy(String clause) {

        public OrderBy {
            if (clause == null || clause.isBlank()) {
                throw new IllegalArgumentException("정렬 절이 비어 있다");
            }
        }
    }
}
