-- 멱등키. 같은 요청이 두 번 도착해도 자원이 하나만 생기게 한다.
--
-- 네트워크가 끊겨서 클라이언트가 응답을 못 받은 경우, 요청은 서버에 닿아 처리됐을 수 있다.
-- 클라이언트는 실패로 보고 재전송하고, 막지 않으면 주문이 둘 생기고 재고가 두 번 빠진다.
--
-- 키는 클라이언트가 만든다(D11). 서버가 주면 "그 키를 받는 요청" 이 또 필요해서 멱등이 성립 안 한다.

create table idempotency_key (
    idempotency_key_id bigint not null generated always as identity primary key,

    -- 계정별로 유일하다(D11). 남의 키와 겹쳐도 상관없다 — 키는 요청을 가르는 값이지 식별자가 아니다.
    -- cascade 인 것은 이 행이 24시간짜리라서다. 5년 보존 대상이 아니므로 계정을 따라 사라져도 된다.
    user_id bigint not null references app_user (user_id) on delete cascade,

    key_value text not null,

    -- 요청 본문의 SHA-256. 본문 전체를 보관하지 않는다(D11).
    -- 같은 키로 다른 본문이 오면 키를 재사용한 것이라 422 로 떨어뜨린다.
    request_hash text not null,

    -- 성공 응답. 재전송이 이것을 그대로 받는다.
    --
    -- 실패는 안 담는다. 처리와 기록이 한 트랜잭션이라 실패하면 이 행도 같이 롤백되고,
    -- 실패는 자원이 안 생기므로 막을 중복이 없다. D11 의 「서버가 하는 일」에 근거를 적었다.
    response_body jsonb,

    created_at timestamptz not null default now(),

    constraint idempotency_key_unique unique (user_id, key_value),

    -- D9 가 최대 255자로 정했다. 빈 키는 헤더를 안 보낸 것과 같다.
    constraint idempotency_key_length_check check (length(key_value) between 1 and 255),

    constraint idempotency_key_hash_check check (length(request_hash) = 64)
);

comment on table idempotency_key is '멱등키. 24시간 뒤 AccountPurgeService 가 지운다(D11)';

-- 파기 배치가 훑는다. 지우고 나면 인덱스에서 빠져서 두 번 돌아도 훑을 것이 없다.
create index idempotency_key_created_idx on idempotency_key (created_at);


-- 응답이 안 채워진 채로 커밋되면 재전송이 빈 답을 받는다.
--
-- 선점(insert)과 응답 저장(update)이 한 트랜잭션 안에서 순서대로 일어나므로,
-- 커밋 시점에는 반드시 채워져 있어야 한다. 안 채워졌으면 저장하는 코드를 빠뜨린 것이다.
--
-- check 로 못 거는 이유는 insert 시점에는 응답을 아직 모르기 때문이다.
-- Postgres 의 check 는 지연시킬 수 없어서(deferrable 을 못 붙인다) 트리거로 간다.
--
-- NEW 를 보지 않고 행을 다시 읽는다. NEW 는 이 트리거를 걸어 준 문장 시점의 값이라
-- insert 로 걸린 것은 언제나 response_body 가 null 이다 — 뒤에 update 로 채워도 그 예약분은
-- null 을 들고 커밋 시점에 터진다. 즉 NEW 를 믿으면 이 트리거는 절대 통과할 수 없다.
--
-- 청크 35c 가 HTTP 층에서 잡았다. 그때까지 통합 테스트가 전부 롤백해서 이 트리거가 한 번도
-- 안 돌았고, 그래서 POST /api/orders 가 실서버에서 언제나 500 이었다.
--
-- V16 의 assert_order_amounts 가 같은 이유로 같은 모양이다 — 지연 트리거는 NEW 를 안 믿고
-- 커밋 시점의 값을 다시 조회한다.
create or replace function check_idempotency_response() returns trigger
language plpgsql as $$
declare v_body jsonb;
begin
    select response_body into v_body
      from idempotency_key
     where idempotency_key_id = new.idempotency_key_id;

    -- 같은 트랜잭션에서 지워졌다. 검사할 것이 없다.
    if not found then
        return null;
    end if;

    if v_body is null then
        raise exception '멱등키에 응답이 안 붙었다 (key=%)', new.key_value;
    end if;
    return null;
end;
$$;

create constraint trigger idempotency_key_response_check
    after insert or update on idempotency_key
    deferrable initially deferred
    for each row execute function check_idempotency_response();
