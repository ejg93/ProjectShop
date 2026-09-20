"use client";

import { DEMO_GROUPS, DEMO_PASSWORD, type DemoAccount } from "./demo-accounts";

/**
 * 연습용 계정 안내(`Q131`). 접어 두고, 누르면 로그인 칸을 채운다.
 *
 * <p><b>접는다.</b> 아홉 줄을 펴 두면 로그인 칸이 아래로 밀려서, 자기 계정으로 들어오는
 * 사람이 할 일을 한 번 더 찾아야 한다(`D20` 「지나가는 화면」).
 *
 * <p><b>{@code <details>} 를 쓴다.</b> 직접 만든 접기는 키보드와 화면낭독기를 따로 챙겨야 하고,
 * 이 화면에는 그럴 만한 이유가 없다 — 브라우저가 이미 열림 상태를 읽어 준다.
 *
 * <p><b>경고를 접힌 자리 밖에 안 둔다.</b> 무엇이 공개됐는지는 계정을 볼 때 알면 되는 것이고,
 * 접기 바깥에 두면 자기 계정으로 들어오는 사람에게 상관없는 경고가 계속 보인다.
 */
export function DemoAccountsPanel({ onPick }: { onPick: (account: DemoAccount) => void }) {
  return (
    <details className="rounded-ui border border-border bg-surface-raised px-4 py-3">
      <summary
        className="
          cursor-pointer text-sm font-semibold
          focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
        "
      >
        계정 없이 둘러보기
      </summary>

      <div className="grid gap-4 pt-3">
        <p className="text-sm text-text-muted">
          연습용으로 만들어 둔 계정입니다. 아무거나 고르시면 아래 칸이 채워집니다.
          <br />
          비밀번호는 아홉 개가 모두 <code className="font-mono">{DEMO_PASSWORD}</code> 로 같습니다.
        </p>

        {DEMO_GROUPS.map((group) => (
          <div key={group.title} className="grid gap-2">
            <h2 className="text-sm font-semibold">{group.title}</h2>
            <p className="text-xs text-text-muted">{group.note}</p>

            <ul className="flex flex-wrap gap-2">
              {group.accounts.map((account) => (
                <li key={account.email}>
                  <button
                    type="button"
                    onClick={() => onPick(account)}
                    aria-label={`${account.name} 계정으로 칸 채우기`}
                    className="
                      rounded-ui border border-border px-3 py-1.5 text-sm
                      transition-colors duration-200 hover:bg-surface
                      focus-visible:outline-2 focus-visible:outline-offset-2
                      focus-visible:outline-accent-text
                    "
                  >
                    {account.name}
                  </button>
                </li>
              ))}
            </ul>
          </div>
        ))}

        {/*
          공개한 것이 무엇인지 적는다. 시스템관리자 계정을 누구나 쓴다는 뜻이고,
          그것이 이 화면을 만든 목적이라 숨기지 않는다(`D20` 「사실대로 적는다」).
        */}
        <p className="text-sm">
          여기 있는 계정과 자료는 모두 연습용입니다. 시스템관리자 계정도 함께 공개되어 있어
          누구나 관리자 화면을 여실 수 있습니다.
          <br />
          <strong>실제로 쓰시는 이름·연락처·주소는 넣지 말아 주시기 바랍니다.</strong>
        </p>
      </div>
    </details>
  );
}
