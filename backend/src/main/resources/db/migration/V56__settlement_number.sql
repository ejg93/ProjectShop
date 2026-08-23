-- 정산서에 노출 번호를 붙이고 조회 권한을 연다(청크 20).
--
-- **D9 가 이미 settlement_number 를 요구하고 있었다.** 「셀러별 정산 건수가 새면 매출 추정에
-- 쓰인다」가 그 이유고, 청크 17 이 표를 세우면서 이 컬럼을 빠뜨렸다 —
-- 그때는 부르는 곳이 없어서 안 드러났고 조회 입구를 여는 지금 걸렸다.
--
-- 접두어는 T- 다. P- 는 D9 가 결제(payment_number)에 예약해 뒀고,
-- 두 글자 접두어는 나머지 넷(빈 문자열·S-·R-·Q-)과 형식이 어긋난다.


alter table settlement add column settlement_number text;

-- 지금 있는 행을 채운다.
--
-- **운영 데이터가 없다** — 정산 표는 청크 17 이 오늘 세웠고 들어간 행은 테스트가 만든 것뿐이다.
-- 그래도 update 를 두는 이유는, 없어도 된다는 판단을 **여기 적어 두지 않으면 다음 사람이
-- 「빠뜨렸나」를 묻게 되기** 때문이다(V25 가 남긴 문장과 같은 자리).
--
-- 난수 집합은 주문번호와 같다 — 0·O·1·I 를 뺀 32자(D9).
update settlement
   set settlement_number = 'T-'
       || to_char(created_at at time zone 'Asia/Seoul', 'YYYYMMDD') || '-'
       || (select string_agg(substr('23456789ABCDEFGHJKLMNPQRSTUVWXYZ',
                                    (random() * 31)::int + 1, 1), '')
             from generate_series(1, 6))
 where settlement_number is null;

alter table settlement alter column settlement_number set not null;

alter table settlement add constraint settlement_number_unique unique (settlement_number);

-- T-20260801-K3M9P7.
alter table settlement add constraint settlement_number_format_check
    check (settlement_number ~ '^T-[0-9]{8}-[2-9A-HJ-NP-Z]{6}$');

comment on column settlement.settlement_number is
    '정산서 노출 번호. 내부 ID 를 URL 에 쓰면 전체 정산 건수가 샌다(D9, 청크 20)';


-- 조회 권한.
--
-- **고객에게 안 준다.** 정산은 우리와 셀러 사이의 계산이고 사는 사람이 볼 것이 아니다 —
-- 셀러 신원 응답에서 수수료율을 뺀 것과 같은 판단이다(14a).
insert into permission (resource, action, description) values
    ('settlement', 'read', '정산서와 그 항목을 조회한다');


-- 판매자(대표). 자기 셀러 것만 본다.
insert into role_permission (role_id, permission_id, scope, effect)
select r.role_id, p.permission_id, 'seller', 'allow'
  from role r
  join permission p on p.resource = 'settlement' and p.action = 'read'
 where r.code = 'seller_owner';


-- 관리자. V3 의 admin 부여는 그 시점의 permission 만 훑었으므로 새 권한은 여기서 넣는다.
insert into role_permission (role_id, permission_id, scope, effect)
select r.role_id, p.permission_id, 'all', 'allow'
  from role r
  join permission p on p.resource = 'settlement' and p.action = 'read'
 where r.code = 'admin';


-- 감사자. 읽기라 연다.
--
-- **돈이 어디로 갔나를 못 보면 감사가 성립을 안 한다.** V5 의 deny 도 그 시점의 permission 만
-- 훑었으므로 새 권한은 여기서 정한다 — 읽기는 열고 쓰기는 애초에 없다.
insert into role_permission (role_id, permission_id, scope, effect)
select r.role_id, p.permission_id, 'all', 'allow'
  from role r
  join permission p on p.resource = 'settlement' and p.action = 'read'
 where r.code = 'auditor';


-- 고객에게 안 열렸는지 확인한다.
--
-- 빠뜨렸을 때 아무 일도 안 일어난다 — 고객이 남의 셀러 정산서를 여는데 오류도 로그도 안 남는다.
-- 나중에 「셀러가 자기 매출을 고객에게 공개」 같은 것이 생기더라도 그건 다른 자원이지
-- 이 권한을 여는 일이 아니다.
do $$
declare granted text;
begin
    select string_agg(r.code, ', ' order by r.code) into granted
      from role_permission rp
      join role r on r.role_id = rp.role_id
      join permission p on p.permission_id = rp.permission_id
     where p.resource = 'settlement' and rp.effect = 'allow'
       and r.code not in ('admin', 'auditor', 'seller_owner');

    if granted is not null then
        raise exception '정산은 우리와 셀러 사이의 계산이다. 열린 역할: %', granted;
    end if;
end $$;


-- 셀러가 seller 범위로만 열렸는지 확인한다.
--
-- all 로 들어가면 셀러가 남의 매출을 본다. 정산액은 곧 그 셀러의 월 거래액이다.
do $$
declare wide text;
begin
    select string_agg(rp.scope, ', ' order by rp.scope) into wide
      from role_permission rp
      join role r on r.role_id = rp.role_id
      join permission p on p.permission_id = rp.permission_id
     where p.resource = 'settlement' and r.code = 'seller_owner'
       and rp.effect = 'allow' and rp.scope <> 'seller';

    if wide is not null then
        raise exception '셀러에게 seller 아닌 범위로 열린 정산 조회가 있다: %', wide;
    end if;
end $$;
