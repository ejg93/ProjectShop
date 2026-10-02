-- 상품 검색 인덱스(`60`). 이름·설명에 3-gram GIN 을 걸어 `ilike '%검색어%'` 가 인덱스를 탄다.
--
-- **형태소 사전이 아니라 3-gram 이다**(2026-09-25 사용자 결정). 표준 Postgres 에 한국어 사전이 없어서
-- `to_tsvector` 는 조사가 붙은 낱말(「운동화를」)을 「운동화」로 못 찾는다. 3-gram 은 글자 조각으로 맞추니
-- 조사와 상관없이 맞는다. 순위는 `similarity` 로 매긴다(`ProductSearchQuery`). 외부 검색 시스템은 안 둔다.
--
-- **`pg_trgm` 은 PG13 부터 trusted 확장이다** — 슈퍼유저가 아니어도 DB 소유자가 켠다.
--
-- **칸에 바로 건다**(`coalesce` 식이 아니라). 식 인덱스는 조회 식이 글자까지 같아야 타는데,
-- `description` 이 null 이면 `ilike` 가 어차피 안 맞아서 식으로 감쌀 이유가 없다.
--
-- **3자 미만 검색어는 인덱스를 안 탄다** — 조각이 안 나와서 순차 스캔이 된다. 답은 맞고 느릴 뿐이다(`stack.md`).
--
-- **`fastupdate = off` 다.** 켜 두면(기본) 새 행의 조각이 대기 목록에 쌓이고, 플래너가 그 목록을 다 훑는 비용을 매겨
-- 상품을 한꺼번에 넣은 뒤에는 autovacuum 이 목록을 비울 때까지 검색이 순차 스캔이 된다(2026-10-02 실측 —
-- 5,000 줄을 넣자 순차 스캔, `gin_clean_pending_list` 뒤 3-gram 비트맵). 상품은 쓰기가 드물고 검색이 잦아서
-- 넣을 때 바로 트리에 넣는 값을 치른다.
create extension if not exists pg_trgm;

create index product_name_trgm_idx on product using gin (name gin_trgm_ops) with (fastupdate = off);
create index product_description_trgm_idx on product using gin (description gin_trgm_ops) with (fastupdate = off);
