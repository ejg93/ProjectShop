package com.projectshop.shop.audit;

import java.util.Arrays;
import java.util.List;

import com.projectshop.shop.auth.FieldGroup;
import com.projectshop.shop.auth.PermissionEvaluator.Decision;

/**
 * 판정이 이번 응답에 연 개인정보 그룹(`Q276`). 열람 기록({@link AuditLog#personalDataViewed})의 입력이다.
 *
 * <p><b>응답이 칸을 싣는 그 판정에서 뽑는다.</b> 응답과 기록이 다른 판정을 보면 「실었는데 안 남김」이 생긴다 —
 * 그래서 조회 입구가 쥔 {@link Decision} 을 그대로 받는다.
 *
 * <p><b>어느 그룹이 개인정보인지는 부르는 쪽이 적는다</b>(배송지·결제·생년월일·연락처·문의 본문). 그룹 열거형에 표시를 두면
 * 열거형마다 같은 판단을 다시 적게 되고, 부르는 자리는 넷뿐이다 — 새 입구가 빠뜨리는 것은 {@code PersonalDataViewTest} 가 잰다.
 */
public final class PersonalDataGroups {

    private PersonalDataGroups() {}

    public static List<String> opened(Decision decision, FieldGroup... personal) {
        return Arrays.stream(personal)
                .filter(decision::canSee)
                .map(FieldGroup::code)
                .toList();
    }
}
