-- 사진 표시 이름에 제어문자·양방향 서식 문자를 막는다(`Q305`, `D14` 입력 검증).
--
-- 앱이 먼저 400 으로 거부한다(`ImagePipeline.Incoming`). 이 제약은 그 타입을 안 지나는 입구가 생겼을 때의 바닥이다.
-- 목록은 앱과 같다 — 제어문자(`[:cntrl:]`)와 U+061C · U+200E · U+200F · U+202A–U+202E · U+2066–U+2069.
-- U+202E 같은 문자는 `gpj.exe` 를 `exe.jpg` 처럼 보이게 한다.
--
-- **`not valid` 로 건다** — 이미 들어간 행은 안 본다. 운영에 그런 이름이 하나라도 있으면 검사하는 제약은
-- 배포 때 마이그레이션을 멈춘다. 새로 쓰는 행과 고치는 행만 본다.

alter table product_image
    add constraint product_image_original_name_chars_check
        check (original_name !~ '[[:cntrl:]؜‎‏‪-‮⁦-⁩]') not valid;

alter table review_image
    add constraint review_image_original_name_chars_check
        check (original_name !~ '[[:cntrl:]؜‎‏‪-‮⁦-⁩]') not valid;
