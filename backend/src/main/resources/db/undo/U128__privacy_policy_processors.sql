-- V128 을 되돌린다. 시행 전인 판만 지운다 — 시행된 판은 `policy_document_immutable` 이 막고, 그때는 되돌리는 것이 아니라 새 판을 낸다.
delete from policy_document
 where code = 'privacy_policy'
   and effective_at > now()
   and body like '%## 5-1. 개인정보의 국외 이전%';
