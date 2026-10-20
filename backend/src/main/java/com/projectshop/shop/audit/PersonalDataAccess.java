package com.projectshop.shop.audit;

import java.util.Set;

import com.projectshop.shop.auth.PermissionEvaluator.Decision;
import com.projectshop.shop.auth.PersonalFieldGroup;

/**
 * 개인정보 그룹 열람 증서(`Q282a`). 건 하나의 개인정보 그룹은 이것으로만 연다.
 *
 * <p><b>생성자가 이 패키지 안에 있다</b> — 만드는 자리는 {@link AuditLog#open} 하나고, 그 자리가 열람 기록을 남긴 뒤
 * 증서를 돌려준다. 그래서 증서를 쥔 코드는 기록을 이미 남긴 코드다(안전성 확보조치 기준 제8조①, `D2` R44).
 *
 * <p><b>증서를 열 때 넘긴 그룹만 묻는다</b>(마무리 65차 독립 리뷰). 넘기지 않은 그룹은 기록이 안 됐으므로
 * 물으면 예외다 — 판정이 허용해도 연다고 답하면 기록 없이 열린다.
 *
 * <p>응답이 칸을 싣는 판정과 기록이 보는 판정이 같다 — 증서가 그 판정을 쥐고 있다.
 */
public final class PersonalDataAccess {

    private final Decision decision;
    private final Set<PersonalFieldGroup> recorded;

    PersonalDataAccess(Decision decision, Set<PersonalFieldGroup> recorded) {
        this.decision = decision;
        this.recorded = Set.copyOf(recorded);
    }

    /**
     * 이 개인정보 그룹을 볼 수 있나. 제한이 없으면 무엇이든 볼 수 있다.
     *
     * @throws IllegalStateException 증서를 열 때 넘기지 않은 그룹이다 — 그 그룹은 기록이 안 됐다
     */
    public boolean opened(PersonalFieldGroup group) {
        if (!recorded.contains(group)) {
            throw new IllegalStateException("증서를 열 때 넘기지 않은 개인정보 그룹이다: " + group.code());
        }
        return allows(decision, group);
    }

    /** 판정이 이 그룹을 여나. 기록할 그룹을 고를 때도 같은 물음을 쓴다 */
    static boolean allows(Decision decision, PersonalFieldGroup group) {
        return decision.allowed() && decision.visibleFieldGroups().covers(group.code());
    }
}
