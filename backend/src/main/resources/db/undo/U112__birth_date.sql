-- V112 를 되돌린다. 생년월일 칸과 트리거는 걷는다.
--
-- **새 판 둘은 시행 여부로 갈린다**(`U94` 와 같은 판단). 수집·이용 고지는 넣는 순간 시행돼서
-- `consent_item_immutable` 이 못 지우게 막는다 — 되돌리려면 옛 문안으로 새 판을 쌓는다.
-- 처리방침은 7일 뒤 시행이라 그 전이면 지울 수 있다.
drop trigger app_user_adult_only on app_user;
drop function app_user_adult_only();
drop function age_in_years(date, date);
delete from permission_field_group where resource = 'user' and code = 'birth';
alter table app_user drop column birth_date;

delete from policy_document
 where code = 'privacy_policy'
   and effective_at > now()
   and body like '%비밀번호, 생년월일 | 회원 가입 |%';
