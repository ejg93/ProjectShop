"use client";

import { useState, useTransition } from "react";

import { api } from "@/lib/api";

/**
 * 대행을 끝내고 관리자로 돌아간다(`16b`).
 *
 * <p><b>대행 중에 열린 몇 안 되는 쓰기다</b> — 서버 필터가 이것과 로그아웃만 통과시킨다. 이 버튼이 없으면
 * 관리자가 빠져나올 길은 로그아웃뿐이다.
 */
export function EndImpersonationButton() {
  const [pending, startTransition] = useTransition();
  const [failed, setFailed] = useState(false);

  const end = () => {
    setFailed(false);
    startTransition(async () => {
      try {
        await api("/api/admin/impersonation/end", { method: "POST" });
        window.location.assign("/admin/roles");
      } catch {
        setFailed(true);
      }
    });
  };

  return (
    <>
      <button
        type="button"
        disabled={pending}
        onClick={end}
        className="rounded-ui border border-surface px-3 py-1 text-xs font-semibold disabled:opacity-50"
      >
        대행 끝내기
      </button>
      {failed ? (
        <span role="alert" className="text-xs">
          끝내지 못했습니다. 로그아웃하시면 대행도 끝납니다.
        </span>
      ) : null}
    </>
  );
}
