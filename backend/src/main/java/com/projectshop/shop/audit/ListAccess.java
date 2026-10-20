package com.projectshop.shop.audit;

import java.util.List;
import java.util.Set;

import com.projectshop.shop.auth.PermissionEvaluator.Decision;
import com.projectshop.shop.auth.PersonalFieldGroup;

/**
 * 목록 한 쪽의 개인정보 그룹 열람 증서(`Q282b`). 목록의 개인정보 그룹은 이것으로만 연다.
 *
 * <p><b>생성자가 이 패키지 안에 있다</b> — 만드는 자리는 {@link AuditLog#openList} 하나다. 쪽은
 * {@link Unrecorded#record} 가 이 증서를 받아야 꺼내지고, 그 자리가 열람 기록을 남긴다
 * (안전성 확보조치 기준 제8조①, `D2` R44).
 *
 * <p><b>증서를 열 때 넘긴 그룹만 묻는다</b> — {@link PersonalDataAccess} 와 같은 이유다.
 * <b>기록할지는 {@link AuditLog#openList} 가 정해서 넘긴다</b> — 부르는 쪽이 정하면 새 입구가 「안 남긴다」를 넘길 수 있다.
 */
public final class ListAccess {

    private final Decision decision;
    private final AuditLog auditLog;
    private final long viewerId;
    private final String resource;
    private final boolean recordsView;
    private final Set<PersonalFieldGroup> recorded;
    private final List<String> openedGroups;

    ListAccess(Decision decision, AuditLog auditLog, long viewerId, String resource, boolean recordsView,
            Set<PersonalFieldGroup> recorded) {
        this.decision = decision;
        this.auditLog = auditLog;
        this.viewerId = viewerId;
        this.resource = resource;
        this.recordsView = recordsView;
        this.recorded = Set.copyOf(recorded);
        this.openedGroups = recorded.stream()
                .filter(group -> PersonalDataAccess.allows(decision, group))
                .map(PersonalFieldGroup::code)
                .sorted()
                .toList();
    }

    /**
     * 이 개인정보 그룹을 볼 수 있나. 제한이 없으면 무엇이든 볼 수 있다.
     *
     * @throws IllegalStateException 증서를 열 때 넘기지 않은 그룹이다 — 그 그룹은 기록되지 않는다
     */
    public boolean opened(PersonalFieldGroup group) {
        if (!recorded.contains(group)) {
            throw new IllegalStateException("증서를 열 때 넘기지 않은 개인정보 그룹이다: " + group.code());
        }
        return PersonalDataAccess.allows(decision, group);
    }

    /**
     * 쪽의 번호로 열람 기록을 남긴다. <b>자기 것만 보는 목록이면 안 남긴다</b> — 자기 글을 보는 사람은 취급자가 아니다.
     * 열린 그룹·번호가 비면 {@link AuditLog#personalDataListed} 가 거른다.
     */
    void record(List<String> numbers) {
        if (!recordsView) {
            return;
        }
        auditLog.personalDataListed(viewerId, resource, numbers, openedGroups);
    }
}
