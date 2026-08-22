"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";

import { api } from "@/lib/api";

/**
 * 상품 등록 폼(`13f-1`).
 *
 * <p><b>옵션 없는 상품까지다.</b> 서버는 옵션과 SKU 를 한 덩어리로 받는데(`D4`),
 * 옵션 조합을 화면에서 만드는 것은 그 자체로 한 청크다 — 여기서는 <b>SKU 하나짜리</b>를 세우고
 * 옵션이 있는 상품은 뒤에 붙인다. 눌러도 아무 일이 안 나는 폼보다 <b>좁게 도는 폼</b>이 낫다.
 *
 * <p><b>실증자료를 같이 받는다</b>(`R32`, 표시광고법 제5조). 사실과 관련한 문구는
 * 실증할 수 있어야 하고 요청이 오면 <b>15일 안에</b> 내야 한다 — 등록 시점에 안 받으면
 * 그 문구를 쓴 사람이 떠난 뒤에 근거를 찾게 된다.
 *
 * <p><b>선택이다.</b> 사실 주장이 없는 상품도 있어서 비어 있는 것 자체는 잘못이 아니다.
 * 있어야 하는데 없는 것은 검수가 본다 — 문구에서 사실 주장을 뽑아내는 것은 사람의 판단이다.
 */
export function ProductForm({ sellerId }: { sellerId: number }) {
  const router = useRouter();
  const [sending, setSending] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  async function submit(form: FormData) {
    setSending(true);
    setFailure(null);

    try {
      await api<{ productId: number }>("/api/products", {
        method: "POST",
        body: {
          sellerId,
          name: text(form, "name"),
          description: optional(form, "description"),
          // 옵션이 없는 상품이라 조합이 하나뿐이다. 서버는 목록을 요구하므로 빈 목록을 보낸다.
          options: [],
          skus: [
            {
              optionValues: [],
              priceInclVat: Number(text(form, "priceInclVat")),
              stockCount: Number(text(form, "stockCount")),
            },
          ],
          substantiations: substantiationsOf(form),
        },
      });

      router.push("/seller/products");
      router.refresh();
    } catch (e) {
      // 무엇이 틀렸는지는 서버가 말한다(`D5`). 화면이 다시 판정하면 둘이 갈린다.
      setFailure(e instanceof Error ? e.message : "등록하지 못했습니다.");
    } finally {
      setSending(false);
    }
  }

  return (
    <form action={submit} className="grid gap-6">
      <fieldset className="grid gap-4">
        <legend className="text-sm font-medium">상품</legend>

        <Field label="상품명" name="name" required maxLength={200} />
        <Field label="설명" name="description" multiline />
        <Field label="판매가 (부가세 포함)" name="priceInclVat" required type="number" min={0} />
        <Field label="재고" name="stockCount" required type="number" min={0} />
      </fieldset>

      <fieldset className="grid gap-4">
        <legend className="text-sm font-medium">표시·광고 근거 (선택)</legend>
        <p className="text-sm text-text-muted">
          「국내 1위」처럼 사실을 말하는 문구를 쓰셨다면 그 근거를 적어 주세요.
          <br />
          공정거래위원회가 요청하면 15일 안에 제출해야 하는 자료입니다.
        </p>

        <Field label="문구" name="claim" maxLength={200} />
        <Field label="근거" name="evidence" multiline maxLength={2000} />
        <Field label="출처 주소" name="sourceUrl" type="url" maxLength={500} />
      </fieldset>

      {failure ? (
        <p role="alert" className="text-sm text-danger-text">
          {failure}
        </p>
      ) : null}

      <button
        type="submit"
        disabled={sending}
        className="
          justify-self-start rounded-md bg-accent px-4 py-2 text-sm font-medium text-accent-foreground
          disabled:opacity-60
          focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
        "
      >
        {sending ? "등록하는 중…" : "등록"}
      </button>
    </form>
  );
}

/**
 * 근거를 목록으로 만든다.
 *
 * <p>지금은 한 벌만 받는다. <b>서버는 여러 개를 받으므로</b> 화면이 늘 때
 * 계약을 안 고쳐도 된다 — 주장 하나에 근거 하나가 붙는 모양이라 여러 벌이 자연스럽다.
 */
function substantiationsOf(form: FormData) {
  const claim = optional(form, "claim");
  const evidence = optional(form, "evidence");

  if (!claim || !evidence) {
    return [];
  }
  return [{ claim, evidence, sourceUrl: optional(form, "sourceUrl") }];
}

function text(form: FormData, name: string): string {
  return String(form.get(name) ?? "").trim();
}

/** 빈 문자열 대신 없는 것으로 보낸다. 「안 적음」과 「빈칸을 적음」이 데이터에서 갈려야 한다 */
function optional(form: FormData, name: string): string | undefined {
  const value = text(form, name);
  return value === "" ? undefined : value;
}

/**
 * 이름표와 칸을 붙여 그린다.
 *
 * <p><b>{@code label} 로 묶는다</b>(WCAG 1.3.1·3.3.2). 자리표시 글자만 두면
 * 값을 넣는 순간 무엇을 넣는 칸인지가 사라지고, 보조기술은 처음부터 못 읽는다.
 */
function Field({
  label,
  name,
  required,
  multiline,
  type = "text",
  maxLength,
  min,
}: {
  label: string;
  name: string;
  required?: boolean;
  multiline?: boolean;
  type?: string;
  maxLength?: number;
  min?: number;
}) {
  const className = `
    rounded-md border border-border bg-surface px-3 py-2 text-sm
    focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
  `;

  return (
    <label className="grid gap-1">
      <span className="text-sm">
        {label}
        {required ? <span className="text-danger-text"> *</span> : null}
      </span>
      {multiline ? (
        <textarea name={name} rows={4} maxLength={maxLength} className={className} />
      ) : (
        <input
          name={name}
          type={type}
          required={required}
          maxLength={maxLength}
          min={min}
          className={className}
        />
      )}
    </label>
  );
}
