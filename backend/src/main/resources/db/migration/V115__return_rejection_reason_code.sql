-- 반품 거절 사유를 닫힌 목록으로 가르고, 훼손 거절에는 검수 소견을 요구한다(`Q212`, 마무리 47차 독립 리뷰).
--
-- 거절 사유가 자유 글(`return_note.decision_reason`)이라 시스템은 훼손 때문인지 몰랐고, 검수 소견은 선택(`43a-5`)이라
-- **입고·소견 없이 훼손을 이유로 거절**할 수 있었다. 전자상거래법 제17조제5항은 훼손 책임의 다툼을 통신판매업자가
-- 입증하라고 한다(`D2` R37). 기간 경과·제한 사유 거절은 물건을 받기 전에도 정당해서(`V63` 「거절에는 안 건다」) 훼손만 건다.
--
-- **제약을 셋으로 가른다.** 값 목록 제약에 다른 따옴표 값(`'rejected'`)이 섞이면 `EnumConstraintTest` 가 그 값까지
-- 열거값으로 읽는다. 표 하나로 판정되는 것은 즉시 제약, 두 표(`return_note`)에 걸친 것만 지연 트리거다.

alter table return_request add column rejection_reason_code text;

comment on column return_request.rejection_reason_code is
    '거절 사유(Q212) — damaged·period_expired·restricted·other. 거절일 때만 있다. damaged 는 검수(inspected_at)를 거쳐야 한다';

-- 이미 거절된 행은 사유를 가를 근거가 없어 「그 밖」이다. 시드에는 반품 행이 0 이고 운영 행 수는 배포 로그로 본다.
update return_request set rejection_reason_code = 'other' where status = 'rejected';

alter table return_request
    add constraint return_request_rejection_reason_code_check
        check (rejection_reason_code in ('damaged', 'period_expired', 'restricted', 'other')),
    add constraint return_request_rejection_code_presence_check
        check ((status = 'rejected') = (rejection_reason_code is not null)),
    add constraint return_request_damaged_inspected_check
        check (rejection_reason_code is distinct from 'damaged' or inspected_at is not null);

-- 두 표에 걸친 규칙 — 훼손 거절이면 검수 소견 글이 있어야 한다. 검수 시각만 있고 소견이 비는 행을 막는다.
-- 트리거가 **코드만 바뀌는 update 도** 잡게 `update of status, rejection_reason_code` 로 다시 만든다 —
-- `V63` 의 것은 `update of status` 뿐이었다.
create or replace function assert_return_rejection_reason() returns trigger as $$
begin
    if new.status = 'rejected'
       and not exists (select 1 from return_note n
                        where n.return_request_id = new.return_request_id
                          and n.decision_reason is not null) then
        raise exception '반품 거절에는 사유가 필요하다 (return_request_id=%)',
            new.return_request_id
            using errcode = 'check_violation';
    end if;
    if new.rejection_reason_code = 'damaged'
       and not exists (select 1 from return_note n
                        where n.return_request_id = new.return_request_id
                          and n.inspection_note is not null) then
        raise exception '훼손 거절에는 검수 소견이 필요하다 (return_request_id=%)',
            new.return_request_id
            using errcode = 'check_violation';
    end if;
    return null;
end;
$$ language plpgsql;

drop trigger return_requires_rejection_reason on return_request;

create constraint trigger return_requires_rejection_reason
    after insert or update of status, rejection_reason_code on return_request
    deferrable initially deferred
    for each row execute function assert_return_rejection_reason();
