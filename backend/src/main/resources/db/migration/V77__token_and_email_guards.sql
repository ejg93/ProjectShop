-- 앱 검증에만 있던 둘을 DB 로 내린다(`Q62`, 마무리 15차 리뷰 지적 #3·#5).
--
-- **앱 검증은 3위라 새 입구가 생기면 빠뜨린다**(`D23` 축 2). 둘 다 「지금은 한 자리가 지킨다」인데,
-- 그 자리가 늘어나는 것을 아무도 안 막고 있었다.

-- ---------------------------------------------------------------------------
-- 1. 쓴 토큰은 못 되살린다
-- ---------------------------------------------------------------------------

-- 지금은 `update … where used_at is null` 의 영향행 수로 막는다 — 조건을 빠뜨린 `update` 가
-- 하나 생기면 **이미 쓴 토큰이 다시 살아난다.** 비밀번호 재설정 열쇠라 그것이 계정 탈취다.
--
-- **`null` 로 되돌리는 것도 막는다.** 「비우는 것은 고치는 것이 아니다」로 읽으면 같은 구멍이다.
create or replace function reject_token_reuse() returns trigger as $$
begin
    if old.used_at is not null then
        raise exception '이미 쓴 토큰은 못 고친다(Q62): %', tg_table_name;
    end if;
    return new;
end;
$$ language plpgsql;

create trigger password_reset_token_used_once
    before update of used_at on password_reset_token
    for each row
    when (old.used_at is not null and old.used_at is distinct from new.used_at)
    execute function reject_token_reuse();

create trigger email_change_request_used_once
    before update of used_at on email_change_request
    for each row
    when (old.used_at is not null and old.used_at is distinct from new.used_at)
    execute function reject_token_reuse();

-- ---------------------------------------------------------------------------
-- 2. 확인 전에는 이메일이 안 바뀐다
-- ---------------------------------------------------------------------------

-- 확인 토큰이 이메일 변경의 **성립 요건**이다(`5e-1`). 지금은 서비스 하나가 그 순서를 지키는데,
-- 관리자 화면이나 배치가 `app_user.email` 을 직접 고치면 **확인 없이 주소가 바뀐다** —
-- 그 주소로 비밀번호 재설정이 가므로 계정이 넘어간다.
--
-- **`move_stock` 과 같은 「길 하나」 모양**이다. 가입은 `insert` 라 안 걸리고,
-- 파기는 `new.email` 이 `null` 이라 지나간다(`D13` 이 비우는 것으로 정했다).
create or replace function assert_email_change_confirmed() returns trigger as $$
begin
    if new.email is null then
        return new;
    end if;
    if not exists (
        select 1 from email_change_request r
         where r.user_id = new.user_id
           and r.new_email = new.email
           and r.used_at is not null) then
        raise exception '확인된 이메일 변경 요청이 없다(Q62): user_id=%', new.user_id;
    end if;
    return new;
end;
$$ language plpgsql;

create trigger app_user_email_needs_confirmation
    before update of email on app_user
    for each row
    when (old.email is distinct from new.email)
    execute function assert_email_change_confirmed();
