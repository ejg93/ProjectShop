-- 광고 게시물을 내리는 권한(청크 59-2).
--
-- 58 이 inquiry.status = 'blocked' 와 blocked_at·blocked_reason 을 세우고 요건표 R34 에
-- 「내릴 수단이 생겼다」고 적었는데, **그 상태로 옮기는 코드가 어디에도 없었다** —
-- 59 가 입구를 열면서 조회 조건에만 status <> 'blocked' 를 넣었고, 실제로는 psql 로만 내려졌다.
--
-- **정보통신망법 제50조의7 이 요구하는 것은 게시 중단이다**(D2 R34).
-- 스키마에 자리가 있는 것으로는 안 된다 — 58 이 접수에서 세운 논리(「방침 문구가 아니라
-- 자원으로 만든다」)를 차단 쪽에서 안 지킨 자리다.


-- 답이 나간 글도 내릴 수 있게 한다.
--
-- **58 이 답변 시각을 상태와 한 몸으로 묶어 놨다** — inquiry_answer_check 가
-- 「status = 'answered' 인 것과 answered_at 이 있는 것은 같다」로 돼 있어서,
-- 답이 나간 글을 blocked 로 옮기면 **그 등식이 깨져서 막힌다.**
--
-- 광고에 답을 달았다고 그 광고가 남을 이유가 없다. **answered_at 은 사건 시각이지
-- 상태 표현이 아니다** — 「답이 나갔다」는 사실은 그 뒤에 무슨 상태가 되든 남아야 한다.
--
-- 방향을 하나만 건다. 답변 상태면 답이 있어야 하지만, **답이 있다고 답변 상태인 것은 아니다.**
alter table inquiry drop constraint inquiry_answer_check;

alter table inquiry add constraint inquiry_answer_check
    check ((answered_at is null) = (answer is null)
           and (status <> 'answered' or answer is not null));


insert into permission (resource, action, description) values
    ('inquiry', 'block', '광고성 게시물의 게시를 중단한다');


-- 관리자만이다(사용자 선택).
--
-- **조문의 의무자가 운영자라 그 판단도 운영자가 한다.** 셀러에게 넘기면 우리 의무의
-- 이행 여부를 남이 정하게 되고(환불 승인을 관리자만에게 둔 V24 와 같은 논리),
-- 더 나쁜 것은 **불리한 질문을 광고로 몰아 내리는 자리가 같이 생기는 것**이다.
-- 내린 근거가 advertisement 로만 남아서 「광고였다」와 「불리해서 내렸다」가 같은 값이 된다.
--
-- 구매 전 문의라 그 질문을 못 보게 되는 사람은 **살까 말까 하는 사람**이다.
insert into role_permission (role_id, permission_id, scope, effect)
select r.role_id, p.permission_id, 'all', 'allow'
  from role r
  join permission p on p.resource = 'inquiry' and p.action = 'block'
 where r.code = 'admin';


-- 감사자. 읽기가 아니면 막는다.
--
-- V5 의 deny 도 그 시점의 permission 만 훑었다. V12 의 주석이 「새 권한을 넣는 마이그레이션이
-- 감사자 deny 도 같이 넣어야 한다」고 남긴 자리다.
insert into role_permission (role_id, permission_id, scope, effect)
select r.role_id, p.permission_id, 'all', 'deny'
  from role r
  join permission p on p.resource = 'inquiry' and p.action = 'block'
 where r.code = 'auditor';


-- 차단이 관리자 밖으로 안 나갔는지 확인한다.
--
-- 빠뜨리면 조용하다. 셀러에게 열리는 순간 위 대가가 그대로 실현되는데 오류도 로그도 안 남는다.
-- 일부러 여는 청크는 이 검사를 같이 고치면서 왜 여는지를 적게 된다(V24 와 같은 자리).
do $$
declare granted text;
begin
    select string_agg(r.code, ', ' order by r.code) into granted
      from role_permission rp
      join role r on r.role_id = rp.role_id
      join permission p on p.permission_id = rp.permission_id
     where p.resource = 'inquiry' and p.action = 'block'
       and rp.effect = 'allow' and r.code <> 'admin';

    if granted is not null then
        raise exception '제50조의7 의 의무자는 운영자다. 열린 역할: %', granted;
    end if;
end $$;
