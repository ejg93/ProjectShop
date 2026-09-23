/**
 * 입력칸 하나. <b>라벨은 칸 위에 둔다.</b>
 *
 * <p>자리표시 문구를 라벨로 쓰지 않는다 — 값을 넣는 순간 무슨 칸이었는지가 사라지고,
 * 화면낭독기는 "편집창" 이라고만 말한다(`D20` 「레이블」).
 *
 * <p><b>로그인 폼에 있던 것을 옮겨 왔다.</b> 가입 화면이 두 번째 사용자고, 거기 두고 가져다 쓰면
 * 화면 하나가 다른 화면의 조각을 부르는 모양이 된다.
 *
 * @param invalid <b>그 칸의 값이 규칙에 안 맞을 때만</b> 참이다(`D20`). 어느 칸인지 모르는
 *                오류에는 안 넘긴다 — 기본값이 거짓인 이유가 그것이다.
 *                두 칸에 다 걸었더니 화면낭독기가 <b>맞은 이메일까지 「잘못된 입력」이라고 읽었다.</b>
 * @param hint 규칙을 미리 알린다. <b>오류로 알리기 전에 말하는 쪽이 낫다</b> —
 *             비밀번호 길이처럼 지키면 되는 것은 틀린 뒤에 알려 줄 이유가 없다
 * @param required 기본이 참이다. <b>거짓으로 두는 것은 서버도 선택으로 받는 칸뿐이다</b>(청크 15-2) —
 *                 상세 주소와 배송 요청사항이 그것이다. 화면만 풀면 서버에서 400 이 난다
 * @param maxLength 서버가 거는 길이와 같은 값을 준다. <b>여기서 막는 것이 목적이 아니라</b>
 *                  다 치고 나서 거절당하는 것을 줄이는 것이다 — 판정은 서버가 한다
 * @param error 서버가 이 칸을 지목하며 준 사유(`Q129`). <b>넘기면 {@code invalid} 가 참이 되고</b>
 *              칸 아래에 문구가 붙는다 — 둘을 따로 넘기면 한쪽만 준 화면이 생기고,
 *              그때 화면낭독기는 「잘못된 입력」이라고만 하고 이유를 안 말한다(WCAG 3.3.1)
 */
export function Field({
  name,
  type,
  label,
  autoComplete,
  invalid = false,
  error,
  defaultValue,
  hint,
  required = true,
  maxLength,
}: {
  name: string;
  type: "email" | "password" | "text" | "date";
  label: string;
  autoComplete: string;
  invalid?: boolean;
  error?: string;
  defaultValue?: string;
  hint?: string;
  required?: boolean;
  maxLength?: number;
}) {
  const hintId = hint ? `${name}-hint` : undefined;
  const errorId = error ? `${name}-error` : undefined;

  // **둘을 같이 묶는다.** 규칙과 사유가 둘 다 있으면 화면낭독기가 둘 다 읽어야 하고,
  // 하나만 걸면 나머지는 눈으로만 보인다.
  const describedBy = [hintId, errorId].filter(Boolean).join(" ") || undefined;

  return (
    <div className="grid gap-2">
      <label htmlFor={name} className="text-sm font-semibold">
        {label}
      </label>

      {hint ? (
        <p id={hintId} className="text-xs text-text-muted">
          {hint}
        </p>
      ) : null}

      <input
        id={name}
        name={name}
        type={type}
        required={required}
        maxLength={maxLength}
        autoComplete={autoComplete}
        aria-invalid={invalid || error !== undefined}
        // 힌트를 칸에 묶는다. 안 묶으면 화면낭독기가 라벨만 읽고 규칙을 안 말한다.
        aria-describedby={describedBy}
        defaultValue={defaultValue}
        className="
          rounded-ui border border-border bg-surface-raised px-3 py-2.5 text-base
          transition-colors duration-200
          focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
          aria-invalid:border-danger-text
        "
      />

      {/*
        칸 바로 아래다. 폼 맨 위에 모으면 칸이 열인 화면에서 **문구와 칸이 멀어지고**,
        고치러 내려가는 사이에 무엇이 틀렸는지를 잊는다(`D20` 「오류는 자리를 가려서 보여준다」).

        `role=alert` 를 안 쓴다. 제출 뒤에 여러 칸이 한꺼번에 뜨는 자리라 읽기가 서로를
        끊는다 — `aria-describedby` 로 칸에 묶여 있어서 초점이 가면 그때 읽힌다.
      */}
      {error ? (
        <p id={errorId} className="text-sm text-danger-text">
          {error}
        </p>
      ) : null}
    </div>
  );
}
