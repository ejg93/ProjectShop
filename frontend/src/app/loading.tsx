/**
 * 서버 컴포넌트가 데이터를 기다리는 동안 그리는 뼈대(`D20` 「기다리는 동안」).
 *
 * <p><b>이 파일이 없으면 브라우저가 이전 화면에 머문다.</b> 사용자에게는 누른 것이 안 먹은 것으로
 * 보이고, 그래서 같은 링크를 다시 누른다. `D20` 이 「첫 진입 → 뼈대」라고 정해 뒀는데
 * 그릴 자리가 없었다(`Q8`).
 *
 * <p><b>뿌리에 하나만 둔다.</b> 화면마다 모양이 다른 뼈대를 두면 화면이 늘 때마다 파일이 늘고,
 * 그중 하나를 빠뜨리면 그 화면만 멈춘 것처럼 보인다. 여기 있는 것은 <b>어느 화면에서나 참인 것</b>
 * — 머리와 발은 셸이 이미 그렸고, 바뀌는 것은 가운데뿐이다.
 *
 * <p><b>글자를 안 쓴다.</b> 「불러오는 중」 같은 문구는 화면마다 무엇을 부르는지가 달라서
 * 정확할 수가 없고, 낭독기에는 아래 {@code role="status"} 가 대신 말한다.
 */
export default function Loading() {
  return (
    <div className="mx-auto grid w-full max-w-4xl flex-1 content-start gap-6 px-4 py-16">
      <span role="status" className="sr-only">
        불러오는 중입니다.
      </span>

      {/*
        모양만 있는 블록이다. `aria-hidden` 으로 낭독기에서 가린다 —
        읽어 봐야 뜻이 없는 네모고, 위의 한 줄이 이미 상태를 말했다.
      */}
      <div aria-hidden className="grid gap-6">
        <div className="h-9 w-2/5 rounded-ui bg-surface-raised motion-safe:animate-pulse" />

        <div className="grid gap-3">
          {[0, 1, 2].map((row) => (
            <div
              key={row}
              className="h-24 rounded-ui border border-border bg-surface-raised motion-safe:animate-pulse"
            />
          ))}
        </div>
      </div>
    </div>
  );
}
