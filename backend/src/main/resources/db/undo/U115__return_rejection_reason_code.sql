-- V115 를 되돌린다. 트리거를 `V63` 모양(`update of status`, 사유 글만 본다)으로 돌리고 제약 셋과 칸을 걷는다.

drop trigger return_requires_rejection_reason on return_request;

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
    return null;
end;
$$ language plpgsql;

create constraint trigger return_requires_rejection_reason
    after insert or update of status on return_request
    deferrable initially deferred
    for each row execute function assert_return_rejection_reason();

alter table return_request
    drop constraint return_request_damaged_inspected_check,
    drop constraint return_request_rejection_code_presence_check,
    drop constraint return_request_rejection_reason_code_check,
    drop column rejection_reason_code;
