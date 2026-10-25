-- 사진 표시 이름에 제어문자·양방향 서식 문자를 막는다(`Q305`, `D14` 입력 검증).
--
-- 앱이 먼저 400 으로 거부한다(`ImagePipeline.Incoming`). 이 제약은 그 타입을 안 지나는 입구가 생겼을 때의 바닥이다.
-- 목록은 앱과 같다 — 제어문자(Cc: U+0001–U+001F · U+007F–U+009F, NUL 은 `text` 가 애초에 못 든다)와
-- U+061C · U+200E · U+200F · U+202A–U+202E · U+2066–U+2069. U+202E 같은 문자는 `gpj.exe` 를 `exe.jpg` 처럼 보이게 한다.
--
-- **범위를 글자 번호로 적는다** — `[[:cntrl:]]` 은 DB 의 ctype 을 따라서 `en_US.utf8` 이면 U+2028·U+2029 까지,
-- C 면 C1 을 빼고 잡아 앱(Java `Character.CONTROL`)과 갈린다(마무리 70차 독립 리뷰). 갈리면 앱을 지난 이름이
-- 400 이 아니라 제약 위반으로 끝난다. **글자 자체를 파일에 안 넣는다** — `\uXXXX` 이스케이프만 쓴다(ARE 가 푼다).
--
-- **`not valid` 로 건다** — 이미 들어간 행은 안 본다. 운영에 그런 이름이 하나라도 있으면 검사하는 제약은
-- 배포 때 마이그레이션을 멈춘다. 새로 쓰는 행과 고치는 행만 본다.

alter table product_image
    add constraint product_image_original_name_chars_check
        check (original_name !~ '[\u0001-\u001f\u007f-\u009f\u061c\u200e\u200f\u202a-\u202e\u2066-\u2069]') not valid;

alter table review_image
    add constraint review_image_original_name_chars_check
        check (original_name !~ '[\u0001-\u001f\u007f-\u009f\u061c\u200e\u200f\u202a-\u202e\u2066-\u2069]') not valid;
