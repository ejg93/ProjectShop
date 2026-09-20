"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";

import { SubmitButton } from "@/components/submit-button";
import { api } from "@/lib/api";
import { firstBadField, placeErrors } from "@/lib/field-errors";

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
/**
 * 이 폼이 그리는 칸. <b>순서가 곧 화면 순서</b>라 초점이 위에서부터 간다.
 *
 * <p><b>서버가 부르는 이름과 모양이 다르다</b>(`Q136`). 판매가는 `skus[0].price_incl_vat` 로,
 * 근거는 `substantiations[0].claim` 으로 온다 — 옵션 없는 상품이라 줄이 하나뿐이고
 * 화면은 그 하나를 펴서 그린다. {@link placeErrors} 의 사다리가 그 둘을 이어 준다.
 */
const FORM_FIELDS = [
  "name",
  "description",
  "priceInclVat",
  "stockCount",
  "claim",
  "evidence",
  "sourceUrl",
] as const;

type FormField = (typeof FORM_FIELDS)[number];

export function ProductForm({ sellerId }: { sellerId: number }) {
  const router = useRouter();
  const [failure, setFailure] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Partial<Record<FormField, string>>>({});
  const [unplaced, setUnplaced] = useState<string[]>([]);

  async function submit(form: FormData) {
    setFailure(null);
    setFieldErrors({});
    setUnplaced([]);

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
      const placed = placeErrors(e, FORM_FIELDS);
      setFieldErrors(placed.byField);
      setUnplaced(placed.rest);
      setFailure(e instanceof Error ? e.message : "등록하지 못했습니다.");

      // 첫 칸으로 보낸다. 안 보내면 어디가 빨간지 찾아 내려가야 한다(WCAG 3.3.1).
      const first = firstBadField(placed, FORM_FIELDS);
      if (first) {
        document.getElementById(first)?.focus();
      }
    }
  }

  return (
    <form action={submit} className="grid gap-6">
      <fieldset className="grid gap-4">
        <legend className="text-sm font-medium">상품</legend>

        {/* 100 은 서버의 `@Size` 와 같아야 한다(`Q22`). 갈리면 화면이 받은 것을 서버가 400 으로 막는다 */}
        <Field label="상품명" name="name" required maxLength={100} error={fieldErrors.name} />
        <Field label="설명" name="description" multiline maxLength={2000} error={fieldErrors.description} />
        <Field label="판매가 (부가세 포함)" name="priceInclVat" required type="number" min={0} error={fieldErrors.priceInclVat} />
        <Field label="재고" name="stockCount" required type="number" min={0} error={fieldErrors.stockCount} />
      </fieldset>

      <fieldset className="grid gap-4">
        <legend className="text-sm font-medium">표시·광고 근거 (선택)</legend>
        <p className="text-sm text-text-muted">
          「국내 1위」처럼 사실을 말하는 문구를 쓰셨다면 그 근거를 적어 주세요.
          <br />
          공정거래위원회가 요청하면 15일 안에 제출해야 하는 자료입니다.
        </p>

        <Field label="문구" name="claim" maxLength={200} error={fieldErrors.claim} />
        <Field label="근거" name="evidence" multiline maxLength={2000} error={fieldErrors.evidence} />
        <Field label="출처 주소" name="sourceUrl" type="url" maxLength={500} error={fieldErrors.sourceUrl} />
      </fieldset>

      {failure ? (
        <p role="alert" className="text-sm text-danger-text">
          {failure}
        </p>
      ) : null}

      {/*
        **어느 칸인지 모르는 사유**다(`13h`). 버리면 사용자는 위 한 줄만 보고 무엇을
        확인할지 모른다 — 칸 아래에 못 붙였다고 안 보여 줄 이유는 없다.
      */}
      {unplaced.length > 0 ? (
        <ul className="grid gap-1 text-sm text-danger-text">
          {unplaced.map((message) => (
            <li key={message}>{message}</li>
          ))}
        </ul>
      ) : null}

      <SubmitButton label="등록" pendingLabel="등록하는 중…" className="justify-self-start" />
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
 *
 * <p><b>{@code components/field.tsx} 를 안 쓴다.</b> 그쪽은 {@code type} 이 셋으로 닫혀 있고
 * 여러 줄·숫자 하한을 모른다 — 이 폼이 그 셋을 다 쓴다. 합치는 것은 그 자체로 한 청크고,
 * <b>같은 규약을 두 군데서 지키는 것이 지금 값</b>이라 오류 표기 모양을 저쪽에 맞췄다.
 *
 * @param error 서버가 이 칸을 지목하며 준 사유(`Q136`). <b>넘기면 {@code aria-invalid} 가 서고</b>
 *              칸 아래에 문구가 붙는다 — 하나만 하면 화면낭독기가 「잘못된 입력」이라고만 하고
 *              이유를 안 말한다(WCAG 3.3.1)
 */
function Field({
  label,
  name,
  required,
  multiline,
  type = "text",
  maxLength,
  min,
  error,
}: {
  label: string;
  name: string;
  required?: boolean;
  multiline?: boolean;
  type?: string;
  maxLength?: number;
  min?: number;
  error?: string;
}) {
  const className = `
    rounded-md border border-border bg-surface px-3 py-2 text-sm
    transition-colors duration-200
    focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-text
    aria-invalid:border-danger-text
  `;

  const errorId = error ? `${name}-error` : undefined;

  // **{@code id} 가 있어야 초점이 간다.** 여기 있던 `label` 감싸기만으로는 마우스가 닿을 뿐이고,
  // 제출 뒤에 첫 틀린 칸을 부르는 `getElementById` 가 못 찾는다(`Q136`).
  const shared = {
    id: name,
    name,
    maxLength,
    className,
    "aria-invalid": error !== undefined,
    "aria-describedby": errorId,
  };

  return (
    <div className="grid gap-1">
      <label htmlFor={name} className="text-sm">
        {label}
        {required ? <span className="text-danger-text"> *</span> : null}
      </label>

      {multiline ? (
        <textarea {...shared} rows={4} />
      ) : (
        <input {...shared} type={type} required={required} min={min} />
      )}

      {/*
        칸 바로 아래다. 폼 맨 위에 모으면 칸이 열인 화면에서 문구와 칸이 멀어진다
        (`D20` 「오류는 자리를 가려서 보여준다」). `role=alert` 는 안 쓴다 —
        여러 칸이 한꺼번에 뜨면 읽기가 서로를 끊고, `aria-describedby` 로 묶여 있어서
        초점이 가면 그때 읽힌다.
      */}
      {error ? (
        <p id={errorId} className="text-sm text-danger-text">
          {error}
        </p>
      ) : null}
    </div>
  );
}
