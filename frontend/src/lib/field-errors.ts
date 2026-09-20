import { ApiError } from "./api";

/**
 * 서버가 지목한 칸을 폼의 칸에 붙인다(`Q129`).
 *
 * <p><b>이름 표기가 둘이다.</b> 서버는 요청에 쓴 이름으로 말하고(snake_case, 중첩이면
 * {@code shipping.postal_code} 처럼 점 표기) 폼의 칸 이름은 camelCase 다. 마지막 마디만
 * 쓰는 이유는 <b>폼이 중첩을 한 단계로 펴 놓기</b> 때문이다 — 주문서의 배송지가 그 모양이다.
 *
 * <p><b>아는 칸 목록을 받는다.</b> 이름을 계산만 하고 붙이면 {@code cart_item_ids} 처럼
 * 화면에 칸이 없는 것까지 붙이려 들고, 그 문구는 아무 데도 안 뜨고 사라진다 —
 * `13h` 의 「모르는 칸을 지목하지 않는다」가 막는 자리다.
 *
 * <p><b>못 붙인 것을 버리지 않는다.</b> {@link Placed#rest} 로 돌려주고 폼이 전체 오류 자리에
 * 같이 그린다. 버리면 사용자는 「다시 확인해 주세요」만 보고 무엇을 확인할지 모른다.
 *
 * <p><b>폼마다 다시 안 쓴다.</b> 주문서와 가입이 같은 모양을 쓰고, 두 벌이 되면
 * 한쪽만 고치는 날이 온다({@code lib/format} 이 `13e` 에서 같은 판단을 했다).
 */
export type Placed<Field extends string> = {
  byField: Partial<Record<Field, string>>;
  rest: string[];
};

export function placeErrors<Field extends string>(
  error: unknown,
  fields: readonly Field[],
): Placed<Field> {
  if (!(error instanceof ApiError)) {
    return { byField: {}, rest: [] };
  }

  const byField: Partial<Record<Field, string>> = {};
  const rest: string[] = [];

  for (const { field, message } of error.errors) {
    const name = camelOf(field.slice(field.lastIndexOf(".") + 1));
    const known = fields.find((candidate) => candidate === name);

    if (known) {
      // 같은 칸에 여럿이 오면 첫 것만 쓴다. 두 줄을 겹쳐 보여 주면 칸 높이가 들쭉날쭉해지고,
      // 하나를 고치면 보통 나머지도 같이 풀린다.
      byField[known] ??= message;
    } else {
      rest.push(message);
    }
  }

  return { byField, rest };
}

/** 첫 번째로 틀린 칸. 목록 순서가 곧 화면 순서라 <b>위에 있는 칸부터</b> 나온다 */
export function firstBadField<Field extends string>(
  placed: Placed<Field>,
  fields: readonly Field[],
): Field | undefined {
  return fields.find((field) => placed.byField[field] !== undefined);
}

function camelOf(snake: string): string {
  return snake.replace(/_([a-z0-9])/g, (_, char: string) => char.toUpperCase());
}
