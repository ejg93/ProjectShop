"use client";

import { useState } from "react";

import { api } from "@/lib/api";

/**
 * 로그아웃(`13b`).
 *
 * <p><b>링크가 아니라 버튼이다.</b> 서버 상태를 바꾸는 조작이라 `POST` 로 가야 하고,
 * 링크로 두면 브라우저나 확장이 미리 열어 보는 것만으로 로그아웃된다.
 *
 * <p><b>확인을 안 받는다</b>(`D20` 「되돌릴 수 없는 조작」). 다시 로그인하면 되는 조작이고,
 * 확인이 흔해지면 사용자가 읽지 않고 누른다.
 *
 * <p><b>끝나면 통째로 다시 그린다.</b> `router.refresh()` 로는 셸의 머리만 바뀌고
 * 지금 보던 화면이 로그인해야 보는 것이면 그대로 남는다 — 그 화면은 다음 요청에서 401 이다.
 */
export function LogoutButton() {
  const [sending, setSending] = useState(false);

  async function logout() {
    setSending(true);
    try {
      await api("/api/auth/logout", { method: "POST" });
      window.location.replace("/products");
    } catch {
      // 실패해도 갈 곳은 같다. 세션이 이미 끊겨서 401 이면 그것도 로그아웃된 상태다.
      window.location.replace("/products");
    }
  }

  return (
    <button
      type="button"
      onClick={logout}
      disabled={sending}
      className="
        rounded-ui text-sm text-text-muted
        transition-colors duration-200
        hover:text-text
        focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-accent-text
        disabled:opacity-60
      "
    >
      {sending ? "나가는 중" : "로그아웃"}
    </button>
  );
}
