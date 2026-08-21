-- 배치 회차 이력. 「그날 그 배치가 성공했나」에 답한다(`D19`).
--
-- 쓸모가 셋이다.
--   1. 중복 실행 차단 — 재실행 안전성을 상태나 조건으로 못 얻는 배치가 있다.
--      정산 마감(청크 19)이 그것이다: 두 번 돌면 지급이 두 배가 되는데, 금액을 더하는 일이라
--      "이미 처리된 것" 이 대상에서 안 빠진다. 아래 부분 유니크가 그 자리다.
--   2. 체인 판정 — 선행 배치가 그날 성공했나를 후행이 본다(`D19` 3층).
--   3. 사후 조회 — 파기가 언제 돌았나에 답할 근거가 로그밖에 없으면 로그 보존에 묶인다.
--
-- **기준일이 있는 배치만 남긴다.** 결제 만료·환불 스위퍼는 5분 주기라 하루 288행이 쌓이고,
-- 위 셋 중 앞의 둘이 기준일 축으로만 성립한다. 그 배치들은 로그로 본다.
--
-- **행은 끝날 때 한 번 쓴다.** 시작에 `running` 을 넣지 않는다 —
-- 그러면 JVM 이 죽은 회차가 영영 `running` 으로 남고, 그 행을 치우는 배치가 또 필요해진다.
-- 죽으면 행이 없고, 다음 회차가 「성공 행이 없다」를 보고 다시 돈다. 파기는 재실행이 안전하다.

create table batch_run (
    batch_run_id bigint not null generated always as identity primary key,

    -- 카탈로그(`D19`)의 배치 하나를 가리킨다. 목록을 check 로 안 좁혔다 —
    -- 배치가 늘 때마다 마이그레이션을 하나 더 쓰게 되고, 오타는 아래 조회가 바로 드러낸다
    -- (없는 이름으로 남기면 그 배치의 성공 행이 영영 안 잡혀서 매 회차가 새 회차가 된다).
    batch_name text not null,

    -- 판단 기준일(KST). **회차를 세는 축이다** — 실행 시각이 아니라 이 값이 같으면 같은 회차다.
    -- 04:00 에 돌든 재시도로 04:30 에 돌든 같은 날의 같은 일이다(`D10`).
    baseline_date date not null,

    started_at  timestamptz not null,
    finished_at timestamptz not null,

    -- 고른 수와 실제로 처리한 수. 둘이 다르면 건너뛴 건이 있다는 뜻이다.
    -- 집합 delete 로 도는 배치는 고른 것이 곧 지운 것이라 둘이 같다.
    target_count    integer,
    processed_count integer,

    status text not null,

    -- 실패 이유. **개인정보를 안 넣는다**(`D16`) — 예외 메시지에 값이 실려 오므로
    -- 이 칸에는 종류만 적는다. 자세한 것은 로그에 있다.
    failure_reason text,

    created_at timestamptz not null default now(),

    constraint batch_run_batch_name_check
        check (batch_name ~ '^[a-z][a-z0-9_]*$'),

    constraint batch_run_status_check
        check (status in ('succeeded', 'failed', 'skipped')),

    -- 실패 행에만 이유가 있다. 양방향으로 건다 — 이유 없는 실패 행은 다음 사람이 로그를
    -- 뒤지게 만들고, 이유가 붙은 성공 행은 무엇을 뜻하는지가 표에서 안 읽힌다.
    constraint batch_run_failure_reason_check
        check ((failure_reason is not null) = (status = 'failed')),

    constraint batch_run_failure_reason_length_check
        check (failure_reason is null or length(failure_reason) between 1 and 500),

    -- 성공 행에만 수가 있다. 스킵은 고르기 전에 접은 것이라 셀 것이 없고,
    -- 실패는 어디까지 처리했는지를 못 센다 — 본체가 던진 뒤라 셀 자리가 없다.
    constraint batch_run_counts_check
        check ((status <> 'succeeded')
            or (target_count is not null and processed_count is not null)),

    constraint batch_run_count_range_check
        check ((target_count is null or target_count >= 0)
            and (processed_count is null or processed_count >= 0)
            and (processed_count is null or processed_count <= target_count)),

    constraint batch_run_finished_after_started_check
        check (finished_at >= started_at)
);

-- **중복 실행을 막는 자리다.** 성공한 회차는 (배치, 기준일) 당 하나뿐이라,
-- 정산 마감이 두 번 지급하는 것을 앱이 아니라 스키마가 거부한다.
-- 실패·스킵 행은 여러 개여도 된다 — 재시도가 정상이고, 시도마다 한 행이 남는다(`D19`).
create unique index batch_run_succeeded_unique
    on batch_run (batch_name, baseline_date) where status = 'succeeded';

-- 인덱스를 더 안 만든다. 기준일 있는 배치만 남기므로 하루에 몇 행이고,
-- 1년 보존이라 상한이 천 단위다. 그 크기에서 순차 스캔이 인덱스보다 싸다.

comment on table batch_run is '배치 회차 이력. 성공 회차는 (batch_name, baseline_date) 당 하나다';
