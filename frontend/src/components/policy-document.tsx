import type { Components } from "react-markdown";
import Markdown from "react-markdown";
import remarkGfm from "remark-gfm";

/**
 * 정책 문서 한 장. <b>이용약관·개인정보처리방침·청약철회 안내가 같은 모양으로 그려진다</b>(`13a-2`).
 *
 * <p>본문이 마크다운인 이유는 <b>법이 강조를 요구해서다</b> — 약관규제법 제3조제1항이 중요한 내용을
 * 부호·색채·굵고 큰 문자 <b>등</b>으로 명확하게 표시하라고 한다. 통짜 텍스트로 뿌리면 그 의무를 못 지킨다.
 *
 * <p><b>굵게와 색을 같이 쓴다.</b> 색만으로 강조하면 색을 못 보는 사람에게 아무 일도 안 일어난다
 * (`D20` 「색만으로 알리지 않는다」). 법이 "등" 이라고 열거한 것도 하나로는 부족하다는 뜻으로 읽는다.
 *
 * @param title 화면의 `h1`. 본문 안의 `##` 이 `h2` 라 제목 단계가 안 건너뛴다(`D20`)
 * @param body 마크다운 원문. <b>DB 에서 온다</b> — 개정판을 배포 없이 갈아 끼우려는 설계다
 * @param effectiveAt 이 판의 시행 시각. 없으면 안 그린다
 * @param version 개정판 번호. <b>어느 판을 읽고 있는지가 보여야 한다</b> — 개정 고지가 그 값을 가리킨다
 */
export function PolicyDocument({
  title,
  body,
  version,
  effectiveAt,
}: {
  title: string;
  body: string;
  version?: number;
  effectiveAt?: string;
}) {
  return (
    <article className="mx-auto grid w-full max-w-3xl flex-1 content-start gap-6 px-4 py-16">
      <header className="grid gap-2 border-b border-border pb-6">
        <h1 className="text-2xl font-semibold tracking-tight">{title}</h1>
        {version === undefined && effectiveAt === undefined ? null : (
          <p className="text-sm text-text-muted">
            {version === undefined ? null : `제${version}판`}
            {version !== undefined && effectiveAt !== undefined ? " · " : null}
            {effectiveAt === undefined ? null : `${dateText(effectiveAt)} 시행`}
          </p>
        )}
      </header>

      <PolicyBody>{body}</PolicyBody>
    </article>
  );
}

/**
 * 본문만 그린다. <b>셸이 없어서 다른 화면 안에 넣을 수 있다</b> —
 * 가입 화면이 약관 전문을 `details` 안에 펼칠 때 쓴다(`13d-2`).
 *
 * <p><b>원시 HTML 을 안 살린다.</b> `rehype-raw` 를 안 붙였으므로 본문에 태그가 들어와도
 * 글자로 나간다 — 지금 본문은 우리가 쓰지만, 이것이 셀러 글을 그리게 되는 날 그 결정이 방벽이 된다.
 *
 * @param children 마크다운 원문
 */
export function PolicyBody({ children }: { children: string }) {
  return (
    <div className="grid gap-4">
      <Markdown remarkPlugins={[remarkGfm]} components={MARKDOWN}>
        {children}
      </Markdown>
    </div>
  );
}

/**
 * 마크다운 요소를 우리 토큰으로 그린다.
 *
 * <p>기본 스타일에 안 맡긴다. Tailwind 는 브라우저 기본 서식을 지워 두므로
 * <b>손대지 않으면 제목과 본문이 같은 크기로 나온다</b> — 강조가 사라지는 것과 같다.
 */
const MARKDOWN: Components = {
  // **받은 props 를 펼치지 않는다.** react-markdown 은 파싱한 AST 노드를 `node` 로 같이 넘기는데,
  // 그대로 펼치면 DOM 에 `node="[object Object]"` 가 요소마다 붙는다.
  // 쓰는 것만 이름으로 받으면 그 실수가 성립하지 않는다 — 무엇이 필요한지도 같이 드러난다.
  h2: ({ children }) => (
    <h2 className="mt-6 text-lg font-semibold tracking-tight first:mt-0">{children}</h2>
  ),
  h3: ({ children }) => <h3 className="mt-4 text-base font-semibold">{children}</h3>,
  p: ({ children }) => <p className="text-sm leading-relaxed text-text-muted">{children}</p>,

  // 굵게와 색을 같이 준다. 하나만으로는 법이 말한 "명확하게" 에 안 닿는다.
  strong: ({ children }) => <strong className="font-semibold text-text">{children}</strong>,

  ul: ({ children }) => (
    <ul className="grid list-disc gap-1 pl-5 text-sm leading-relaxed text-text-muted">
      {children}
    </ul>
  ),
  ol: ({ children }) => (
    <ol className="grid list-decimal gap-1 pl-5 text-sm leading-relaxed text-text-muted">
      {children}
    </ol>
  ),

  a: ({ href, children }) => (
    <a
      href={href}
      className="
        font-semibold text-accent-text underline underline-offset-4
        focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
      "
    >
      {children}
    </a>
  ),

  // 표가 좁은 화면에서 넘친다. 몸통만 가로로 굴리고 쪽 전체는 안 흔든다.
  table: ({ children }) => (
    <div className="overflow-x-auto">
      <table className="w-full border-collapse text-sm">{children}</table>
    </div>
  ),

  // `style` 을 받는 이유는 GFM 이 표의 정렬을 그것으로 넘기기 때문이다. 버리면 정렬이 사라진다.
  th: ({ children, style }) => (
    <th
      scope="col"
      style={style}
      className="border-b border-border py-2 pr-4 text-left font-semibold last:pr-0"
    >
      {children}
    </th>
  ),
  td: ({ children, style }) => (
    <td style={style} className="border-b border-border py-2 pr-4 text-text-muted last:pr-0">
      {children}
    </td>
  ),
};

/** 시행일은 날짜까지만 보인다. 시각은 읽는 사람에게 뜻이 없다 */
function dateText(iso: string): string {
  return new Date(iso).toLocaleDateString("ko-KR", {
    year: "numeric",
    month: "long",
    day: "numeric",
    timeZone: "Asia/Seoul",
  });
}
