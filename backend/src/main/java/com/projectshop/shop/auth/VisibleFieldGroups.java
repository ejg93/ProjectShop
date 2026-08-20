package com.projectshop.shop.auth;

import java.util.List;
import java.util.TreeSet;

import com.projectshop.shop.auth.PermissionEvaluator.Decision;

/**
 * 판정 결과를 응답의 {@code _visible_field_groups} 로 옮긴다(`13b`).
 *
 * <p><b>빈 배열의 뜻을 하나로 만드는 것이 이 클래스의 전부다.</b>
 *
 * <p>판정 엔진 안에서는 안 모호하다 — {@link Allowed} 가 「전부」와 「이 목록만」을
 * <b>타입으로</b> 가른다({@code Everything} 과 {@code Only}). 그런데 직렬화하면서
 * {@code values()} 만 꺼내 쓰면 그 구분이 뭉개진다. 제한이 없을 때 값이 비는데,
 * 그 빈 배열이 <b>「제한 없음」인지 「아무것도 못 봄」인지</b> 응답만 봐서는 안 갈린다.
 *
 * <p>그래서 <b>제한이 없으면 그 자원의 그룹 이름을 전부 싣는다.</b> 그러면 빈 배열은
 * 그때부터 「아무것도 못 본다」 하나만 뜻한다 — 「빈 값에 뜻을 싣지 않는다」(`D23`)를
 * 지키는 유일한 안이었다(사용자 선택).
 *
 * <p><b>목록의 출처가 enum 이다.</b> {@code permission_field_group} 표를 읽는 방법도 있지만,
 * 그 표와 enum 이 갈리면 응답이 코드가 모르는 이름을 싣게 된다 —
 * {@code canSee} 가 보는 것이 enum 이므로 <b>같은 것을 두 곳에서 안 정한다</b>.
 * 표와 enum 이 어긋나는 것은 스키마 테스트가 따로 막는다(`4g`).
 */
public final class VisibleFieldGroups {

    private VisibleFieldGroups() {
    }

    /**
     * @param decision 이 자원에 대한 판정
     * @param all      그 자원의 필드 그룹 전부. 보통 {@code SomeFields.values()} 다
     * @return 정렬된 그룹 이름. <b>비어 있으면 아무것도 못 본다는 뜻이다</b>
     */
    public static List<String> of(Decision decision, FieldGroup[] all) {
        if (!decision.allowed()) {
            return List.of();
        }

        if (!decision.fieldRestricted()) {
            return List.copyOf(new TreeSet<>(
                    java.util.Arrays.stream(all).map(FieldGroup::code).toList()));
        }

        return List.copyOf(new TreeSet<>(decision.visibleFieldGroups().values()));
    }
}
