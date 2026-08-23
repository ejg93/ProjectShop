-- 공개 목록의 조건을 「뺄 상태」에서 「보일 상태」로 뒤집는다(청크 59-1).
--
-- V54 가 부분 인덱스를 `is_public and status <> 'blocked'` 로 만들었다.
-- **거둔 문의(withdrawn)가 그 조건을 통과한다** — 낸 사람이 거뒀는데 남에게는 그대로 보인다.
--
-- 그때는 안 드러났다. `withdrawn` 으로 옮기는 코드가 없어서 그 상태의 행이 설 수가 없었고,
-- **59-1 이 거두기를 만드는 순간 드러났다.**
--
-- **조건을 뒤집는다.** 「이 상태만 뺀다」로 쓰면 상태가 늘 때마다 이 자리를 고쳐야 하고,
-- 빠뜨리면 **안 보여야 할 것이 보이는 쪽으로 샌다.** 「이 상태만 보인다」로 쓰면
-- 새 상태의 기본이 **안 보이는 쪽**이다 — V54 가 is_public 의 기본값을 비공개로 둔 것과 같은 판단:
-- 아래층은 안전한 쪽으로 떨어뜨린다.

drop index inquiry_public_idx;

create index inquiry_public_idx on inquiry (product_id, created_at desc)
 where is_public and status in ('received', 'answered') and product_id is not null;
