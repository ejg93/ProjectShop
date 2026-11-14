-- 2차 인증 관리자 초기화 권한(`Q461`, 안전성 확보조치 기준 제6조② `D2` R45). 기기를 잃은 계정은 다른 관리자가 등록을 지워
-- 다음 로그인에서 등록부터 하게 한다 — 그전에는 운영자가 DB 에서 그 행을 지우는 것이 유일한 길이었다.
--
-- 자원 `user` 는 이미 목록에 있다 — `check` 를 다시 걸 일이 없다. `kind` 가 `write` 면 감사자 거부가 트리거로 붙는다(`V75`).
insert into permission (resource, action, kind, description) values
    ('user', 'reset_second_factor', 'write',
     '다른 사람의 2차 인증 등록을 지운다 — 기기를 잃은 계정이 다음 로그인에서 등록부터 한다(Q461, D2 R45)');

-- 관리자만(`V3` 의 관례). 남의 2차 인증을 지우는 것은 그 계정의 인증 수단 하나를 빼는 것이다.
insert into role_permission (role_id, permission_id, scope)
select r.role_id, p.permission_id, 'all'
  from permission p
  join role r on r.code = 'admin'
 where p.resource = 'user' and p.action = 'reset_second_factor';
