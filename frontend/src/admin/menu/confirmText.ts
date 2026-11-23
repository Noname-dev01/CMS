/**
 * 영구삭제 확인 문자열. 메뉴명에 개행 같은 제어문자가 있으면 단일행 입력창에 그대로 입력할 수 없으므로(브라우저가 CR/LF를 제거한다)
 * 가역 이스케이프한 문자열을 보여 주고 그것을 입력하게 한다 — 서버 로그용 이스케이프와 같은 규칙이다. 평범한 이름은 원문 그대로다.
 * 정규식 리터럴 대신 코드 값으로 판정한다(줄 구분자 U+2028·U+2029가 리터럴 안에 원문으로 들어가면 구문 오류다).
 */
export function escapeForConfirm(value: string): string {
  let escaped = ''
  for (const ch of value) {
    const code = ch.codePointAt(0) ?? 0
    if (ch === '\\') {
      escaped += '\\\\'
    } else if (ch === '\r') {
      escaped += '\\r'
    } else if (ch === '\n') {
      escaped += '\\n'
    } else if (ch === '\t') {
      escaped += '\\t'
    } else if (code <= 0x1f || (code >= 0x7f && code <= 0x9f) || code === 0x2028 || code === 0x2029 || (ch !== ' ' && /\s/.test(ch))) {
      // 일반 공백 외의 공백류(NBSP·U+FEFF 등)도 이스케이프한다 — 눈에 보이지 않으면 이름을 입력하지 않고도 확인이 통과할 수 있다
      escaped += '\\u' + code.toString(16).padStart(4, '0')
    } else {
      escaped += ch
    }
  }
  return escaped
}
