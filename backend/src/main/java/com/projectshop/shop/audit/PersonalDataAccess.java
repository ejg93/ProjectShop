package com.projectshop.shop.audit;

import com.projectshop.shop.auth.PermissionEvaluator.Decision;
import com.projectshop.shop.auth.PersonalFieldGroup;

/**
 * 개인정보 그룹 열람 증서(`Q282a`). 건 하나의 개인정보 그룹은 이것으로만 연다.
 *
 * <p><b>생성자가 이 패키지 안에 있다</b> — 만드는 자리는 {@link AuditLog#open} 하나고, 그 자리가 열람 기록을 남긴 뒤
 * 증서를 돌려준다. 그래서 증서를 쥔 코드는 기록을 이미 남긴 코드다(안전성 확보조치 기준 제8조①, `D2` R44).
 *
 * <p>응답이 칸을 싣는 판정과 기록이 보는 판정이 같다 — 증서가 그 판정을 쥐고 있다.
 */
public final class PersonalDataAccess {

    private final Decision decision;

    PersonalDataAccess(Decision decision) {
        this.decision = decision;
    }

    /** 이 개인정보 그룹을 볼 수 있나. 제한이 없으면 무엇이든 볼 수 있다 */
    public boolean opened(PersonalFieldGroup group) {
        return decision.allowed() && decision.visibleFieldGroups().covers(group.code());
    }
}
