"use client";

import { useFormStatus } from "react-dom";

/**
 * 폼 제출 버튼. <b>제출 중인지를 폼에서 직접 읽는다</b>(`Q20-1`).
 *
 * <p><b>폼이 pending 을 손으로 들면 안 된다.</b> React 19 의 `<form action={fn}>` 은
 * `fn` 을 전환 안에서 돌리고, <b>`fn` 이 끝날 때까지 그 안의 상태 갱신을 커밋하지 않는다.</b>
 * 그래서 액션 첫 줄의 `setPending(true)` 가 화면에 못 닿고, 버튼이 안 잠긴다 —
 * 비행 중에 세 번 누르면 <b>세 번 다 나간다</b>(실측, `Q20-1`).
 *
 * <p>`useFormStatus` 는 그 값을 React 가 직접 준다. <b>폼의 자식이어야 읽힌다</b> —
 * 같은 컴포넌트 안에서 부르면 언제나 `false` 라, 이 버튼을 따로 두는 것이 조건이다.
 *
 * <p>`13-2a` 가 이 결함의 반대편을 실물로 밟았다 — 성공 경로가 `pending` 을 안 되돌려서
 * 액션이 끝난 뒤 갱신이 커밋되며 버튼이 「확인하는 중」에 <b>멈춰</b> 있었다.
 */
export function SubmitButton({
  label,
  pendingLabel,
  busy = false,
  blocked = false,
  tone = "accent",
  className = "",
}: {
  /** 평소 문구 */
  label: string;
  /** 보내는 동안 문구. 잠긴 이유를 말 안 하면 고장으로 본다(`D20`) */
  pendingLabel: string;
  /** 제출 말고도 잠가야 할 것이 있을 때. 이어지는 `useTransition` 같은 것 */
  busy?: boolean;
  /**
   * 보내는 것과 무관하게 못 누르는 것. 필수 동의 미체크 같은 것.
   *
   * <p><b>`busy` 와 갈라 둔다</b> — 이쪽은 문구가 안 바뀐다. 「가입하는 중」으로 바뀌면
   * 안 보낸 것을 보내는 중이라고 말하는 셈이다.
   */
  blocked?: boolean;
  /**
   * 색. 되돌릴 수 없는 조작은 `danger` 다(`D20`) — 탈퇴가 그 자리다.
   *
   * <p>색을 칸으로 두는 것이 `className` 으로 덮게 두는 것보다 낫다.
   * 덮게 두면 어느 화면이 무슨 색인지 이 파일이 모르게 된다.
   */
  tone?: "accent" | "danger";
  /** 자리잡기만 더한다. 색·모서리는 이 파일이 정한다 */
  className?: string;
}) {
  const { pending } = useFormStatus();
  const sending = pending || busy;
  const disabled = sending || blocked;

  // 두 색이 배경·글자·강조 테두리를 같이 바꾼다. 한 군데서 정해야 새 버튼이 색을 섞지 않는다.
  const palette =
    tone === "danger"
      ? "bg-danger-text text-surface focus-visible:outline-danger-text"
      : "bg-accent text-accent-on hover:bg-accent-hover focus-visible:outline-accent-text";

  return (
    <button
      type="submit"
      disabled={disabled}
      className={`
        rounded-ui px-4 py-2.5 text-sm font-semibold
        transition-[background-color,transform,opacity] duration-200
        motion-safe:active:translate-y-px
        focus-visible:outline-2 focus-visible:outline-offset-2
        disabled:cursor-not-allowed disabled:opacity-60
        ${palette}
        ${className}
      `}
    >
      {sending ? pendingLabel : label}
    </button>
  );
}
