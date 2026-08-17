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
 */
export function Field({
  name,
  type,
  label,
  autoComplete,
  invalid = false,
  defaultValue,
  hint,
}: {
  name: string;
  type: "email" | "password" | "text";
  label: string;
  autoComplete: string;
  invalid?: boolean;
  defaultValue?: string;
  hint?: string;
}) {
  const hintId = hint ? `${name}-hint` : undefined;

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
        required
        autoComplete={autoComplete}
        aria-invalid={invalid}
        // 힌트를 칸에 묶는다. 안 묶으면 화면낭독기가 라벨만 읽고 규칙을 안 말한다.
        aria-describedby={hintId}
        defaultValue={defaultValue}
        className="
          rounded-ui border border-border bg-surface-raised px-3 py-2.5 text-base
          transition-colors duration-200
          focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
          aria-invalid:border-danger-text
        "
      />
    </div>
  );
}
