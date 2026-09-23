-- 살아 있는 셀러에는 살아 있는 대표가 하나 이상 있다(`Q169`).
--
-- **`Q165` 가 이 불변식을 앱 검사(사다리 3위)에만 뒀다**(마무리 44차 독립 리뷰). 그 검사가 잠금 없는
-- `exists` 라 **대표 둘이 동시에 서로를 내보내면 둘 다 통과**해서 대표가 0이 된다. 대표가 0인 셀러는
-- 멤버를 부를 수도 뺄 수도 없고, 푸는 길이 그 청크가 안 만들겠다고 한 관리자 직접 개입뿐이다.
--
-- **탈퇴도 같은 길이다.** 탈퇴는 `user_role` 을 안 지우고 `app_user.deleted_at` 만 채운다 —
-- 역할 표에만 걸면 마지막 대표가 탈퇴하는 길이 그대로 열린다. **대표를 넘기기 전에는 탈퇴를 막는다**(사용자 선택).
-- 그래서 두 표에 건다. 폐업한 셀러(`seller.deleted_at`)는 대표가 없어도 된다.
--
-- **왜 부분 유니크가 아닌가.** 「0이 아니다」는 한 행이 아니라 행의 개수를 말한다. 제약은 행 하나를 보고,
-- 개수를 세려면 트리거다(`D23` 「불변식」).
--
-- **왜 지연인가.** 대표를 넘기는 것은 「새 대표를 넣고 옛 대표를 뺀다」 두 걸음이고, 순서에 따라
-- 중간에 0이 된다. 커밋 시점에 센다.
--
-- **셀러 행을 잠그고 센다.** 지연 트리거는 커밋 직전에 돌아서, 두 트랜잭션이 동시에 커밋하면
-- **서로의 삭제를 못 보고** 둘 다 통과한다 — 잠금이 없으면 이 트리거도 앱 검사와 같은 구멍이다.
-- 잠금을 기다린 쪽은 앞 트랜잭션이 커밋한 뒤 **새 스냅숏으로 다시 센다**(READ COMMITTED 에서
-- plpgsql 의 문장마다 스냅숏이 새로 잡힌다, `D11`). `REPEATABLE READ` 로 올리면 이 가정이 깨진다.

create or replace function assert_seller_keeps_owner(p_seller_id bigint)
returns void
language plpgsql as $$
begin
    -- 폐업했거나 지워진 셀러는 대표가 없어도 된다. 있으면 잠근다.
    perform 1 from seller
     where seller_id = p_seller_id and deleted_at is null
       for update;

    if not found then
        return;
    end if;

    if not exists (
            select 1
              from user_role ur
              join role r on r.role_id = ur.role_id
              join app_user u on u.user_id = ur.user_id
             where ur.seller_id = p_seller_id
               and r.code = 'seller_owner'
               and u.deleted_at is null) then
        raise exception '살아 있는 셀러의 마지막 대표를 없앨 수 없다 (seller_id=%)', p_seller_id;
    end if;
end;
$$;

-- 역할 쪽. **지운(또는 바꾸기 전) 행이 살아 있는 사람의 대표 역할이었을 때만** 센다.
-- 그 사람이 이미 없으면(탈퇴했거나 행째 지워졌으면) 이 삭제가 대표 수를 줄인 것이 아니다 —
-- 탈퇴는 아래 사람 쪽 트리거가 보고, 정리 삭제(파기 배치·시험 정리)는 셀러와 사람이 같이 사라진다.
create or replace function check_user_role_keeps_owner()
returns trigger
language plpgsql as $$
begin
    if old.seller_id is null then
        return null;
    end if;

    if tg_op = 'UPDATE'
       and new.role_id = old.role_id
       and new.user_id = old.user_id
       and new.seller_id is not distinct from old.seller_id then
        return null;
    end if;

    if not exists (select 1 from role where role_id = old.role_id and code = 'seller_owner') then
        return null;
    end if;

    if not exists (select 1 from app_user where user_id = old.user_id and deleted_at is null) then
        return null;
    end if;

    perform assert_seller_keeps_owner(old.seller_id);
    return null;
end;
$$;

create constraint trigger user_role_keeps_seller_owner
    after delete or update on user_role
    deferrable initially deferred
    for each row execute function check_user_role_keeps_owner();

-- 사람 쪽. 탈퇴하는 사람이 대표로 있는 살아 있는 셀러를 전부 센다.
-- **셀러 번호 순으로 잠근다** — 둘이 같은 셀러 둘을 다른 순서로 잠그면 교착이 난다.
create or replace function check_withdrawal_keeps_owner()
returns trigger
language plpgsql as $$
declare
    v_seller_id bigint;
begin
    for v_seller_id in
        select ur.seller_id
          from user_role ur
          join role r on r.role_id = ur.role_id
         where ur.user_id = new.user_id
           and ur.seller_id is not null
           and r.code = 'seller_owner'
         order by ur.seller_id
    loop
        perform assert_seller_keeps_owner(v_seller_id);
    end loop;
    return null;
end;
$$;

create constraint trigger app_user_withdrawal_keeps_seller_owner
    after update of deleted_at on app_user
    deferrable initially deferred
    for each row
    when (old.deleted_at is null and new.deleted_at is not null)
    execute function check_withdrawal_keeps_owner();
