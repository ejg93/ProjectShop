-- 실패를 두 종류로 가른다(청크 36a, `D19` 2층).
--
-- **무조건 재시도하지 않는다.** 연결이 끊긴 것과 데이터가 틀린 것은 다음에 할 일이 다르다 —
-- 앞엣것은 10분 뒤에 다시 해 보면 되고, 뒤엣것은 다시 해도 같은 자리에서 또 죽는다.
-- 그 판단을 실패한 순간에 내려서 여기 적는다. 나중에 이력만 보고 다시 가르려면
-- 예외 종류 문자열을 파싱하게 되고, 그건 판단을 두 번 하는 것이다.
--
-- **`transient` 만 재시도 대상이다.** 스위퍼가 이 값을 보고 고른다.

alter table batch_run add column failure_kind text;

-- 실패 행에만 있다. 성공·스킵 행에 붙어 있으면 무엇을 뜻하는지가 표에서 안 읽힌다.
alter table batch_run add constraint batch_run_failure_kind_check
    check ((failure_kind is not null) = (status = 'failed'));

alter table batch_run add constraint batch_run_failure_kind_values_check
    check (failure_kind is null or failure_kind in ('transient', 'permanent'));

comment on column batch_run.failure_kind is '일시적(재시도 대상)인가 결정적(즉시 포기)인가. D19 2층';
