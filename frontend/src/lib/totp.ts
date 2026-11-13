/**
 * Base32 비밀과 시각으로 TOTP 코드를 Web Crypto 의 HMAC-SHA-1 로 계산한다(`Q454`, RFC 6238).
 *
 * <p><b>값은 서버(`Totp`)와 같다</b> — HMAC-SHA1 · 6자리 · 30초. 연습 계정 코드 채우기와 e2e 가 같이 쓴다.
 * 운영 계정의 비밀은 화면에 안 온다 — 여기 들어오는 것은 공개된 연습 계정 비밀뿐이다.
 */

const ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
const STEP_SECONDS = 30;
const DIGITS = 6;

/** Base32(RFC 4648 §6)를 푼다. 소문자·패딩·공백을 받는다 */
function fromBase32(text: string): Uint8Array<ArrayBuffer> {
  const bytes: number[] = [];
  let buffer = 0;
  let bits = 0;
  for (const char of text.toUpperCase().replace(/[=\s]/g, "")) {
    const value = ALPHABET.indexOf(char);
    if (value < 0) {
      throw new Error(`Base32 밖 글자: ${char}`);
    }
    buffer = (buffer << 5) | value;
    bits += 5;
    if (bits >= 8) {
      bytes.push((buffer >> (bits - 8)) & 0xff);
      bits -= 8;
    }
  }
  return new Uint8Array(bytes);
}

export async function totpCode(secretBase32: string, at: Date): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw", fromBase32(secretBase32), { name: "HMAC", hash: "SHA-1" }, false, ["sign"],
  );
  const step = Math.floor(at.getTime() / 1000 / STEP_SECONDS);
  // 카운터는 8바이트 big-endian 이다(RFC 4226 §5.1). 스텝이 2^32 를 넘을 수 있어 둘로 나눠 쓴다.
  const counter = new DataView(new ArrayBuffer(8));
  counter.setUint32(0, Math.floor(step / 2 ** 32));
  counter.setUint32(4, step >>> 0);
  const hash = new Uint8Array(await crypto.subtle.sign("HMAC", key, counter.buffer));
  // 동적 절단(RFC 4226 §5.3)
  const offset = hash[hash.length - 1] & 0x0f;
  const binary = ((hash[offset] & 0x7f) << 24)
    | (hash[offset + 1] << 16)
    | (hash[offset + 2] << 8)
    | hash[offset + 3];
  return String(binary % 10 ** DIGITS).padStart(DIGITS, "0");
}
