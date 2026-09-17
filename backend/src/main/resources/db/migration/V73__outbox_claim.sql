-- 발행기가 편지를 집었다는 표시다(`33b`, `event-catalog.md` 「전송」).
--
-- **왜 컬럼이 필요한가.** 두 대가 같은 표를 보면 같은 편지를 둘 다 보낸다. 흔한 처방은
-- `select ... for update skip locked` 로 행을 잠그고 보내는 것인데, **그 잠금은 커밋까지 산다** —
-- 브로커 왕복이 트랜잭션 안으로 들어가서 `D11` 「트랜잭션 안에서 바깥을 안 부른다」를 어긴다
-- (`ArchitectureTest.트랜잭션_안에서_바깥을_안_부른다` 가 그 자리를 막는다).
--
-- 그래서 **잠금을 컬럼으로 내린다.** 집는 트랜잭션은 `skip locked` 로 짧게 끝내고 이 칸을 찍는다.
-- 보내는 것은 그 트랜잭션 **밖**이고, 도장(`published_at`)은 그다음 짧은 트랜잭션이다.
--
-- **집었는데 안 보낸 편지는 다시 집힌다.** 보내는 중에 죽으면 이 칸만 찍힌 행이 남는데,
-- 발행기가 `claimed_at` 이 오래된 행을 다시 집어서 그 편지가 영영 안 가는 자리를 없앤다.
-- 그 대가가 **중복 발행**이고, 그것이 「최소 한 번」의 근원이다(`event-catalog.md` 「전달」).
alter table outbox_event add column claimed_at timestamptz;

comment on column outbox_event.claimed_at is
    '발행기가 집은 시각. 오래되면 다시 집는다 (33b)';

-- 안 보낸 것을 집을 때 쓰는 인덱스다. 조건은 발행기의 `where` 와 같고,
-- 보낸 행이 쌓여도 안 커진다 — `outbox_event_unpublished_idx` 와 같은 이유다.
--
-- **`claimed_at` 을 정렬 키 뒤에 둔다.** 훑는 순서는 여전히 `outbox_event_id` 고,
-- `claimed_at` 은 그 안에서 거르는 값이다.
create index outbox_event_claimable_idx on outbox_event (outbox_event_id, claimed_at)
    where published_at is null;

-- **`V70` 의 불변 검사가 이 칸을 안 본다.** 그 함수가 견주는 목록이
-- `(type, source, subject, occurred_at, data, created_at)` 이라 `claimed_at` 은 자유롭게 바뀐다 —
-- 발행기가 매 회차 여기에 쓰기 때문에 그래야 한다.
--
-- **그래서 「고칠 수 있는 칸은 published_at 뿐」이라는 `V70` 의 예외 문구는 이 파일 뒤로 낡았다.**
-- 마이그레이션은 지나간 사실이라 안 고친다(`D23`). 고칠 수 있는 칸은 이제 둘이다 — `published_at` 과 여기.
