import { describe, expect, it } from "vitest";

import { totpCode } from "./totp";

/**
 * 화면의 TOTP 계산이 RFC 6238 과 같은 코드를 내나(`Q454`).
 *
 * <p><b>서버(`Totp`)와 같은 벡터로 묶는다.</b> 연습 계정 코드를 화면이 채우는데, 계산이 갈리면 버튼이 낸 코드를
 * 서버가 「틀렸다」고 받고 사람은 원인을 모른다. 벡터는 RFC 6238 부록 B 의 SHA1 열이고(끝 여섯 자리),
 * 비밀은 ASCII `12345678901234567890` 의 Base32 다.
 */
const RFC_SECRET_BASE32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

describe("TOTP 계산", () => {
  it.each([
    [59, "287082"],
    [1111111109, "081804"],
    [1111111111, "050471"],
    [1234567890, "005924"],
    [2000000000, "279037"],
    [20000000000, "353130"],
  ])("RFC 6238 — %i초는 %s", async (seconds, expected) => {
    expect(await totpCode(RFC_SECRET_BASE32, new Date(seconds * 1000))).toBe(expected);
  });

  it("소문자·패딩이 섞인 키도 같은 코드다", async () => {
    const at = new Date(59 * 1000);
    expect(await totpCode(`${RFC_SECRET_BASE32.toLowerCase()}====`, at)).toBe("287082");
  });
});
