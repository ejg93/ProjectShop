-- V94 를 되돌린다. **공개 고지가 다시 「고객센터」를 가리킨다** — 계약내용 서면(`V27`)은 고객센터가 없다고
-- 적었으니 두 공개 문서가 다시 서로 다른 말을 한다. 「내 후기」 화면(`Q171`)을 같이 걷을 때만 쓴다.
--
-- **시행된 판은 못 지운다**(`policy_document_immutable`). 이 파일은 시행 전(`effective_at` 이 미래)에만 먹는다 —
-- 시행 뒤라면 고칠 것을 담은 제4판을 새로 넣는다.
--
-- 되돌리기 전에 `scripts/db-dump.sh` 를 돌린다(`64`).

delete from policy_document where code = 'review_policy' and version = 3;
