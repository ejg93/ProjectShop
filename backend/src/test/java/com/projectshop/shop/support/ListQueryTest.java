package com.projectshop.shop.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.support.ListQuery.Paging;

/**
 * 목록 조회의 보정과 허용 목록이 실제로 도는지 본다.
 *
 * <p><b>걸려 있는 것과 도는 것은 다르다</b>(`점검 L`). {@link ListQuery#MAX_SIZE} 는
 * 「목록 하나로 전체를 긁어 가지 못하게 막는다」고 `D5` 가 이유까지 적어 뒀는데
 * <b>그 100 이 실제로 자르는지 재는 자리가 하나도 없었다.</b> 목록 입구 열 곳이 전부
 * 이 클래스를 지나가므로 여기가 뚫리면 열 곳이 같이 뚫린다.
 *
 * <p>{@link ListQuery#orderBy} 는 <b>요청 문자열이 SQL 에 닿는 유일한 자리</b>다(`D14`).
 * 컬럼명은 바인딩이 안 돼서 결합이 강제되고, 그래서 허용 목록이 유일한 방벽이다.
 */
class ListQueryTest {

    private static final Map<String, String> SORTABLE =
            Map.of("createdAt", "i.created_at", "name", "p.name");

    /**
     * <b>보정이 생성자에 있다</b>(`Q23`). 그래서 <b>어느 경로로 만들어도</b> 성한 값이다 —
     * 전에는 {@code of()} 안에 있어서 그것을 안 부르는 길이 열려 있었다.
     */
    @Test
    @DisplayName("size 는 만드는 순간 상한에서 잘린다")
    void sizeIsCappedAtMax() {
        assertThat(new Paging(0, ListQuery.MAX_SIZE + 1).size()).isEqualTo(ListQuery.MAX_SIZE);
        assertThat(new Paging(0, Integer.MAX_VALUE).size()).isEqualTo(ListQuery.MAX_SIZE);
    }

    @Test
    @DisplayName("0 이나 음수 size 는 1 이 되고 음수 page 는 0 이 된다")
    void nonPositiveInputsBecomeSane() {
        assertThat(new Paging(0, 0).size()).isEqualTo(1);
        assertThat(new Paging(0, -5).size()).isEqualTo(1);
        assertThat(new Paging(-3, 20).page()).isZero();
    }

    /**
     * {@code offset} 은 {@code long} 이라야 한다.
     *
     * <p>호출자가 {@code page * size} 를 직접 곱하던 자리인데, {@code int} 끼리 곱하면
     * <b>한 번은 넘쳐서 음수가 된다</b> — 그러면 {@code offset} 이 음수인 SQL 이 나간다.
     *
     * <p><b>칸이 아니라 계산이다</b>(`Q23`). 칸이면 {@code new Paging(3, 20, 0)} 처럼
     * 페이지와 안 맞는 값을 넣을 수 있는데, 계산해서 주면 그 실수가 성립하지 않는다.
     */
    @Test
    @DisplayName("offset 은 int 로 넘치지 않는다")
    void offsetDoesNotOverflow() {
        Paging paging = new Paging(Integer.MAX_VALUE, ListQuery.MAX_SIZE);

        assertThat(paging.offset())
                .isEqualTo((long) Integer.MAX_VALUE * ListQuery.MAX_SIZE)
                .isPositive();
    }

    @Test
    @DisplayName("허용 목록에 없는 정렬 필드는 거부한다")
    void unlistedSortFieldIsRejected() {
        assertThatThrownBy(() -> ListQuery.orderBy("price", "createdAt", SORTABLE))
                .isInstanceOf(ShopException.class)
                .hasMessageContaining("price");
    }

    /**
     * <b>요청 문자열이 SQL 에 안 닿는다.</b> 들어온 이름은 허용 목록의 키를 고르는 데만 쓰이고,
     * SQL 로 가는 것은 우리가 적어 둔 값이다.
     */
    @Test
    @DisplayName("정렬 절에는 허용 목록의 값만 실린다")
    void onlyMappedColumnReachesSql() {
        assertThat(ListQuery.orderBy("name", "createdAt", SORTABLE)).isEqualTo("p.name desc");
        assertThat(ListQuery.orderBy("name,asc", "createdAt", SORTABLE)).isEqualTo("p.name asc");
        assertThat(ListQuery.orderBy(null, "createdAt", SORTABLE)).isEqualTo("i.created_at desc");
    }

    /** 방향은 {@code asc} 가 아니면 전부 {@code desc} 다 — 오타로 SQL 이 깨지지 않게 한다. */
    @Test
    @DisplayName("방향에 아무 값이나 넣어도 SQL 이 안 깨진다")
    void unknownDirectionFallsBackToDesc() {
        assertThat(ListQuery.orderBy("name,; drop table product", "createdAt", SORTABLE))
                .isEqualTo("p.name desc");
    }
}
