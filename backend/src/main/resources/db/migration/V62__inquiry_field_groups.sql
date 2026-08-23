-- 문의 본문을 필드 그룹으로 묶는다(청크 25).
--
-- inquiry.question 은 자유 텍스트 2000자고 사람이 직접 쓴다 — **글 안에 연락처가 섞여 들어온다**
-- (V24 가 환불 요청 사유에서 같은 판단을 했고, 5i-2 가 그 글을 5년 표에서 뺀 이유도 이것이다).
--
-- 59 가 입구를 열면서 축에 D16 을 걸어 뒀는데 **본문이 누구에게 나가는지를 안 봤다**(점검 G).


-- 그룹 둘로 가른다.
--
-- **메타와 글을 가르는 것이 이 청크의 전부다.** 하나로 두면 「무슨 문의가 몇 건인가」를 보려는
-- 사람에게 글까지 같이 나가고, 그 글에 무엇이 들어 있는지는 우리가 모른다.
insert into permission_field_group (resource, code, description) values
    ('inquiry', 'basic', '문의 번호, 종류, 상태, 대상, 일시'),
    ('inquiry', 'body',  '고객이 쓴 질문과 답변 글');


-- 감사자에게 basic 만 연다.
--
-- **감사는 「누가 언제 무엇을 처리했나」지 고객이 쓴 글이 아니다.** 처리 이력은 상태·시각·행위자가
-- 답하고, 본문은 그 답에 필요가 없다 — 개인정보보호법 제3조제1항의 최소처리다.
--
-- V6 가 감사자에게서 payment 그룹을 닫은 것과 같은 논리다. 거기 근거가
-- 「결제 수단까지 볼 이유는 없다」였고, 여기는 「고객이 쓴 글까지 볼 이유는 없다」다.
--
-- **연결이 없으면 제한이 없다**(V6). 그래서 감사자에게 basic 을 연결하는 것 자체가
-- body 를 닫는 행위다 — 나머지 역할은 연결을 안 만들어서 전부 본다.
--
--   고객      자기 글이다
--   셀러      답하려면 질문을 읽어야 한다. 답을 못 쓰면 문의가 성립을 안 한다
--   관리자    법정 요구에 답하는 자리다(R25·R28)
insert into role_permission_field (role_id, permission_id, effect, permission_field_group_id)
select rp.role_id, rp.permission_id, rp.effect, g.permission_field_group_id
  from role_permission rp
  join role r on r.role_id = rp.role_id and r.code = 'auditor'
  join permission p on p.permission_id = rp.permission_id
                   and p.resource = 'inquiry' and p.action = 'read'
  join permission_field_group g on g.resource = 'inquiry' and g.code = 'basic'
 where rp.effect = 'allow';


-- 감사자에게 본문이 안 열렸는지 확인한다.
--
-- 빠뜨렸을 때 아무 일도 안 일어난다 — 감사자가 고객의 글을 그대로 읽는데 오류도 로그도 안 남는다.
-- 나중에 일부러 여는 청크는 이 검사를 같이 고치면서 왜 여는지를 적게 된다(V24 와 같은 자리).
do $$
declare opened int;
begin
    select count(*) into opened
      from role_permission_field f
      join role r on r.role_id = f.role_id and r.code = 'auditor'
      join permission_field_group g
           on g.permission_field_group_id = f.permission_field_group_id
          and g.resource = 'inquiry' and g.code = 'body'
     where f.effect = 'allow';

    if opened > 0 then
        raise exception '감사자에게 문의 본문이 열려 있다. 감사는 처리 이력이지 고객의 글이 아니다';
    end if;
end $$;


-- 감사자에게 연결이 실제로 생겼는지도 확인한다.
--
-- **연결이 하나도 없으면 제한이 없다**(V6). 위 insert 가 0행을 넣으면 감사자는 여전히 전부 보는데,
-- 그 상태와 「의도적으로 다 열어 둔 것」이 데이터에서 구분되지 않는다.
do $$
declare linked int;
begin
    select count(*) into linked
      from role_permission_field f
      join role r on r.role_id = f.role_id and r.code = 'auditor'
      join permission p on p.permission_id = f.permission_id
                       and p.resource = 'inquiry' and p.action = 'read'
     where f.effect = 'allow';

    if linked = 0 then
        raise exception '감사자의 문의 조회에 필드 연결이 안 생겼다. 그러면 제한이 없는 것이 된다';
    end if;
end $$;
