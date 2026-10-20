package com.projectshop.shop.audit;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.projectshop.shop.auth.ImpersonationToken;
import com.projectshop.shop.auth.PermissionEvaluator.Decision;
import com.projectshop.shop.auth.PersonalFieldGroup;

/**
 * 누가 무슨 권한으로 무엇을 했는지 남긴다.
 *
 * <p>관측 로그(D16)와 다르다. 그쪽은 "이 요청이 어디서 느려졌나" 를 파일에 남기고 지워도 되지만,
 * 이쪽은 DB 에 남기고 보존 기간이 걸려 있다.
 *
 * <p><b>사건이 두 종류다.</b> {@link Kind} 를 호출자가 고르고, 그 값이 트랜잭션 전파를 정한다.
 * 종류를 안 고르면 컴파일이 안 되므로 새 사건을 추가할 때 판단을 건너뛸 수 없다.
 *
 * <p>이전에는 전부 업무 트랜잭션에 얹혀 갔고, 「권한 거부는 어차피 쓰기를 안 하므로 안 걸린다」는
 * 전제 위에 있었다. 그 전제가 깨져 있었다 — {@code @Transactional} 서비스 안에서 판정하고
 * 거부로 예외를 던지면 그 거부 기록까지 같이 롤백된다. 청크 {@code 35c} 가 실서버에서 확인했다.
 * 구매자가 남의 발송 경로를 두드렸는데 {@code audit_log} 에 한 줄도 안 남았다.
 * 청크 {@code 4b-2} 가 {@link Kind} 로 갈랐다.
 */
@Component
public class AuditLog {

    private final AuditLogWriter writer;

    AuditLog(AuditLogWriter writer) {
        this.writer = writer;
    }

    /**
     * 사건의 종류. <b>업무가 롤백될 때 이 기록도 같이 사라져야 하나</b>가 유일한 가름이다.
     *
     * <p>전부 {@link #ATTEMPT} 로 두면 안 일어난 일이 기록에 남고, 전부 {@link #OUTCOME} 으로 두면
     * 실패한 시도가 통째로 사라진다. 둘 다 감사가 성립 안 하는 방향이라 사건마다 고른다.
     */
    public enum Kind {

        /**
         * 시도. 업무가 롤백돼도 남는다.
         *
         * <p>거부·실패처럼 <b>업무가 안 일어난 것 자체가 기록할 사실</b>인 사건이다.
         * 업무와 운명을 같이하면 이 종류는 정의상 한 줄도 안 남는다.
         */
        ATTEMPT,

        /**
         * 결과. 업무와 운명을 같이한다.
         *
         * <p>역할 부여·상품 생성처럼 <b>업무가 성립해야 사실이 되는</b> 사건이다.
         * 따로 커밋하면 롤백된 뒤에도 "했다" 는 기록만 남아서 감사가 거짓말을 한다.
         */
        OUTCOME
    }

    /**
     * 사건 하나를 남긴다.
     *
     * @param kind 시도인가 결과인가. {@link Kind} 를 보고 고른다
     * @param eventType 점 표기. {@code permission.denied}, {@code role.granted} 같은 것
     * @param actorUserId 한 사람. 시스템이 한 일이면 null
     * @param target 대상 자원. 자원이 없는 사건이면 {@link Target#none()}
     * @param detail 사건마다 다른 값. 조회 조건이 될 값은 여기 두지 않는다
     */
    public void record(Kind kind, String eventType, Long actorUserId, Target target,
            Map<String, Object> detail) {

        switch (kind) {
            case ATTEMPT -> writer.detached(eventType, actorUserId, target, detail);
            case OUTCOME -> writer.joined(eventType, actorUserId, target, detail);
        }
    }

    /**
     * 남의 개인정보를 열람한 사건의 이름(`Q276`). 열람 기록은 이 이름 하나로 모은다 — 감사 화면이 이 이름으로 거른다
     */
    public static final String PERSONAL_DATA_VIEWED = "personal_data.viewed";

    /**
     * 취급자가 남의 개인정보 한 건을 열람했다(`Q276`, `D2` R44 — 개인정보의 안전성 확보조치 기준 제8조제1항).
     *
     * <p><b>본인 열람은 안 남긴다</b> — 접속기록은 취급자가 정보주체의 정보를 처리한 기록이고, 자기 것을 보는 사람은
     * 취급자가 아니다. <b>대행 중이면 본인이어도 남긴다</b> — 요청의 주인은 그 사용자지만 실제로 본 사람은 시킨 관리자다
     * ({@code impersonator_user_id} 가 같이 남는다, `V101`).
     *
     * <p><b>열린 개인정보 그룹이 없으면 안 남긴다</b> — 번호·상태만 본 것은 개인정보 열람이 아니다.
     * 접속지는 {@link AuditLogWriter} 가 요청 문맥에서 채운다(`Q273`).
     *
     * @param subjectUserId 정보주체. 대상({@code target})이 된다
     * @param resource      어느 자원인가({@code order}·{@code seller_order}·{@code user})
     * @param number        그 자원의 노출 번호. 계정이면 비어 있다
     * @param openedGroups  이번 응답에 실제로 실은 개인정보 그룹 코드
     */
    private void personalDataViewed(long viewerId, long subjectUserId, String resource, String number,
            List<String> openedGroups) {
        if (openedGroups.isEmpty()) {
            return;
        }
        if (viewerId == subjectUserId && ImpersonationToken.currentImpersonatorId() == null) {
            return;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("resource", resource);
        if (number != null) {
            detail.put("number", number);
        }
        detail.put("groups", openedGroups);
        record(Kind.OUTCOME, PERSONAL_DATA_VIEWED, viewerId, Target.of("user", subjectUserId), detail);
    }

    /**
     * 건 하나의 개인정보 그룹을 열고 열람 증서를 돌려준다(`Q282a`). 증서는 여기서만 만든다 — 그래서 증서를 쥔 코드는
     * 기록을 이미 남긴 코드다.
     *
     * <p>판정이 연 그룹을 세어 {@link #personalDataViewed} 를 부른다. 본인·빈 그룹은 그쪽이 거른다.
     *
     * @param decision      응답이 칸을 싣는 그 판정
     * @param subjectUserId 정보주체
     * @param resource      어느 자원인가({@code order}·{@code seller_order}·{@code user})
     * @param number        그 자원의 노출 번호. 계정이면 비어 있다
     * @param personal      이 응답이 실을 수 있는 개인정보 그룹
     */
    public PersonalDataAccess open(Decision decision, long viewerId, long subjectUserId, String resource,
            String number, PersonalFieldGroup... personal) {
        PersonalDataAccess access = new PersonalDataAccess(decision);
        List<String> opened = Arrays.stream(personal)
                .filter(access::opened)
                .map(PersonalFieldGroup::code)
                .toList();
        personalDataViewed(viewerId, subjectUserId, resource, number, opened);
        return access;
    }

    /**
     * 취급자가 남의 개인정보가 섞인 목록 한 쪽을 열람했다(`Q276`). <b>쪽마다 한 줄</b>이다 — 행마다 남기면 한 쪽에
     * 스무 줄이고, 정보주체가 여럿이라 대상은 비우고 번호 목록을 {@code detail} 에 둔다.
     *
     * <p>목록은 남의 것을 훑는 자리라(관리자 전체 목록·셀러가 받은 목록) 본인 판정을 안 한다.
     */
    public void personalDataListed(long viewerId, String resource, List<String> numbers, List<String> openedGroups) {
        if (openedGroups.isEmpty() || numbers.isEmpty()) {
            return;
        }
        record(Kind.OUTCOME, PERSONAL_DATA_VIEWED, viewerId, Target.none(),
                Map.of("resource", resource, "numbers", numbers, "groups", openedGroups));
    }

    /** 무엇에 대한 사건인가. 자원이 없는 사건도 있어서 둘 다 null 인 경우를 허용한다 */
    public record Target(String type, Long id) {

        public static Target none() {
            return new Target(null, null);
        }

        public static Target of(String type, long id) {
            return new Target(type, id);
        }

        /** 대상이 특정 행이 아니라 자원 종류 전체인 경우. 목록 조회 거부 같은 것 */
        public static Target ofType(String type) {
            return new Target(type, null);
        }
    }
}
