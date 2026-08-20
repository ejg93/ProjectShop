"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { ApiError, api } from "@/lib/api";
import { dateText } from "@/lib/format";

/**
 * 동의 항목 하나의 지금 상태(`5f`).
 *
 * @param actedAt 마지막으로 켜거나 끈 시각. <b>건드린 적 없으면 null</b> 이다
 * @param dependsOn 이 항목을 켜려면 먼저 켜야 하는 항목의 코드. 없으면 null
 */
export type ConsentView = {
  code: string;
  title: string;
  isRequired: boolean;
  granted: boolean;
  actedAt: string | null;
  dependsOn: string | null;
};

/**
 * 무엇에 동의했고 무엇을 더 켤 수 있나.
 *
 * <p><b>건드린 적 없는 항목도 그린다</b>(`5f`). 동의한 것만 보여주면 무엇을 더 켤 수 있는지
 * 알 방법이 없고, 그건 열람이 아니라 일부 공개다.
 *
 * <p><b>확인을 안 받는다</b>(`D20` 「되돌릴 수 없는 조작」). 철회는 다시 켤 수 있어서
 * 되돌릴 수 있는 조작이다 — 확인이 흔해지면 사용자가 읽지 않고 누른다.
 *
 * <p><b>필수 항목에는 버튼을 안 그린다.</b> 서버가 거부하는 조작이라(`5f`) 그리면
 * 눌러야 422 가 나고, 화면은 「그럼 어떻게 하나」를 대신 말해야 한다 — 그 답이 탈퇴다.
 *
 * <p><b>부모를 끄면 종속도 같이 꺼진다</b>(`D2` R14). 서버가 한 번에 거두므로
 * 화면은 상태를 손으로 안 고치고 {@code router.refresh()} 로 다시 받는다.
 */
export function ConsentList({ items }: { items: ConsentView[] }) {
  const router = useRouter();
  const [sending, setSending] = useState<string | null>(null);
  const [refreshing, startRefresh] = useTransition();
  const [notice, setNotice] = useState<string | null>(null);
  const [failure, setFailure] = useState<string | null>(null);

  const granted = new Set(items.filter((item) => item.granted).map((item) => item.code));

  async function change(item: ConsentView, next: boolean) {
    setSending(item.code);
    setNotice(null);
    setFailure(null);

    try {
      await api(`/api/me/consents/${item.code}/${next ? "grant" : "revoke"}`, { method: "POST" });
      setNotice(`${item.title} 동의를 ${next ? "다시 받았습니다" : "철회했습니다"}.`);
      startRefresh(() => router.refresh());
    } catch (thrown) {
      setFailure(messageOf(thrown));
    } finally {
      setSending(null);
    }
  }

  return (
    <div className="grid gap-3">
      {items.map((item) => {
        const blockedBy =
          item.dependsOn && !granted.has(item.dependsOn)
            ? items.find((parent) => parent.code === item.dependsOn)?.title
            : undefined;

        return (
          <div
            key={item.code}
            className="grid gap-2 rounded-ui border border-border bg-surface-raised p-4"
          >
            <div className="flex flex-wrap items-baseline justify-between gap-3">
              <p className="text-sm font-medium">
                {item.title}{" "}
                {/* 필수 여부를 색이 아니라 글로 말한다(`D20` 「색만으로 알리지 않는다」) */}
                <span className={item.isRequired ? "font-semibold" : "text-text-muted"}>
                  ({item.isRequired ? "필수" : "선택"})
                </span>
              </p>

              <p className="text-xs text-text-muted">
                {item.granted ? "동의함" : "동의하지 않음"}
                {item.actedAt ? ` · ${dateText(item.actedAt)}` : null}
              </p>
            </div>

            {item.isRequired ? (
              <p className="text-xs text-text-muted">
                서비스를 드리는 데 반드시 필요한 항목이라 철회하실 수 없습니다.
                <br />
                처리를 멈추길 원하시면{" "}
                <Link
                  href="/me/withdraw"
                  className="
                    font-semibold text-accent-text underline underline-offset-4
                    focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
                  "
                >
                  탈퇴
                </Link>
                해 주시기 바랍니다.
              </p>
            ) : (
              <ChangeButton
                item={item}
                blockedBy={blockedBy}
                busy={sending !== null || refreshing}
                sending={sending === item.code}
                onChange={change}
              />
            )}
          </div>
        );
      })}

      <p role="status" className="text-xs text-text-muted">
        {notice}
      </p>
      <p role="alert" className="text-xs text-danger-text">
        {failure}
      </p>
    </div>
  );
}

/**
 * 켜거나 끄는 버튼 하나.
 *
 * @param blockedBy 부모에 동의하기 전이라 켤 수 없을 때 그 부모의 이름.
 *                  <b>버튼을 감추지 않고 이유를 적는다</b> — 감추면 무엇을 더 켤 수 있는지
 *                  알 방법이 없다(가입 화면이 같은 판단을 했다)
 */
function ChangeButton({
  item,
  blockedBy,
  busy,
  sending,
  onChange,
}: {
  item: ConsentView;
  blockedBy?: string;
  busy: boolean;
  sending: boolean;
  onChange: (item: ConsentView, next: boolean) => void;
}) {
  const reasonId = `${item.code}-reason`;

  return (
    <div className="grid gap-2">
      <button
        type="button"
        onClick={() => onChange(item, !item.granted)}
        disabled={busy || blockedBy !== undefined}
        aria-describedby={blockedBy ? reasonId : undefined}
        className="
          justify-self-start rounded-ui border border-border px-3 py-1.5 text-sm
          transition-colors duration-200
          hover:border-accent-text
          focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
          disabled:cursor-not-allowed disabled:opacity-60
        "
      >
        {sending ? "처리 중" : item.granted ? "동의 철회" : "동의하기"}
      </button>

      {blockedBy ? (
        <p id={reasonId} className="text-xs text-text-muted">
          {blockedBy}에 먼저 동의하셔야 받으실 수 있습니다.
        </p>
      ) : null}
    </div>
  );
}

/** 서버가 준 오류를 화면 문구로. <b>`slug` 로 갈린다</b>(`D5`·`D20`) */
function messageOf(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return "서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }

  switch (error.slug) {
    case "required-consent-revoke":
      return "필수 항목이라 철회하실 수 없습니다. 탈퇴로 처리해 드립니다.";
    case "consent-dependency":
      return "먼저 동의하셔야 하는 항목이 있습니다. 위 안내를 확인해 주세요.";
    case "consent-forbidden":
      return "동의 내역을 바꾸실 권한이 없습니다.";
    case "consent-item-not-found":
      return "지금은 없는 동의 항목입니다. 새로고침해 주세요.";
    default:
      return "바꾸지 못했습니다. 잠시 후 다시 시도해 주세요.";
  }
}
