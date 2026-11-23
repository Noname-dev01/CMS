#!/usr/bin/env node
// PreToolUse 훅(Bash·PowerShell): 세션 scratchpad 밖의 시스템 임시 폴더·드라이브 루트에 쓰는 명령을 막는다.
//
// 막는 것: 리다이렉트(>, >>), tee/touch/mkdir/cp/mv/rm, curl·javac류의 -o, PowerShell 쓰기 cmdlet의 대상이
//          /tmp, $TMPDIR·$TEMP·$TMP, AppData/Local/Temp, 드라이브 루트 바로 아래 파일인 경우.
//          디스크에 쓰는 조사 명령 git fsck --lost-found도 막는다.
// 허용:    AppData/Local/Temp/claude/ 아래(세션 scratchpad), /dev/null, 읽기.
// 한계:    변수에 담은 경로·스크립트 파일·프로그램 내부의 쓰기는 알아보지 못한다. 문자열 안의 텍스트를 오탐할 수 있다.
// 훅 자체 오류는 명령을 막지 않는다(fail-open).

const TEMP = String.raw`(?:/tmp(?=[/"'\s;|&)]|$)|\$\{?(?:TMPDIR|TEMP|TMP)\b\}?|\$env:(?:TEMP|TMP)\b|(?:/[a-z]|[a-z]:)[\x5c/]+Users[\x5c/]+[^\x5c/"'\n]+[\x5c/]+AppData[\x5c/]+Local[\x5c/]+Temp(?=[\x5c/"'\s;|&)]|$)(?![\x5c/]+claude[\x5c/](?![^\s"']*\.\.)))`;
const ROOT_FILE = String.raw`(?:/(?:[a-z]/)?[\w.-]+(?=["'\s;|&)]|$)|[a-z]:[\x5c/]+[\w.-]+(?=["'\s;|&)]|$))`;
const TARGET = `(?:${TEMP}|${ROOT_FILE})`;
const ARGS = String.raw`[^;|&\n]*?\s["']?`;
const PATTERNS = [
  new RegExp(String.raw`(?:^|[^<=-])>{1,2}\|?\s*["']?${TEMP}`, 'im'),
  // 루트 파일은 `<div>/g` 같은 치환식과 헷갈리지 않게 앞에 공백이 있는 리다이렉트만 본다.
  new RegExp(String.raw`(?:^|\s)[\d&*]?>{1,2}\|?\s*["']?${ROOT_FILE}`, 'im'),
  new RegExp(String.raw`(?:^|[\s;|&(])(?:tee|touch|mkdir|mv|rm|rmdir)\b${ARGS}${TARGET}`, 'im'),
  // 복사는 원본을 읽기만 하므로 마지막 인자(목적지)와 -t·-Destination만 본다.
  new RegExp(String.raw`(?:^|[\s;|&(])(?:cp|Copy-Item)\b[^;|&\n]*?\s(?:"${TARGET}[^"\n]*"|'${TARGET}[^'\n]*'|${TARGET}[^\s;|&]*)(?:\s+-\w+)*\s*(?=\d?>|[;|&)\n]|$)`, 'im'),
  new RegExp(String.raw`(?:^|[\s;|&(])(?:cp|Copy-Item)\b[^;|&\n]*?\s-(?:t|Destination)\s*["']?${TARGET}`, 'im'),
  new RegExp(String.raw`(?:^|[\s;|&(])(?:(?:curl|wget|gcc)\b[^;|&\n]*?\s-(?:\w*o|-output(?:-document)?=?)|(?:javac|unzip)\b[^;|&\n]*?\s-d|tar\s+-?\w*x\w*\b[^;|&\n]*?\s-C)\s*["']?${TARGET}`, 'im'),
  new RegExp(String.raw`(?:Set-Content|Add-Content|Out-File|New-Item|Remove-Item|Move-Item)\b${ARGS}${TARGET}`, 'im'),
  // .git/lost-found에 개체를 복사하는 옵션이라 조사 명령으로 쓰지 않는다.
  /\bgit\b[^;|&\n]*\bfsck\b[^;|&\n]*--lost-found\b/i,
];

// 따옴표 heredoc 본문은 셸이 해석하지 않는 데이터라 검사하지 않는다.
const stripHeredocs = (c) => c.replace(/(<<-?\s*(['"])(\w+)\2[^\n]*\n)[\s\S]*?\n\s*\3(?=\n|$)/g, '$1$3');

let raw = '';
process.stdin.on('data', (chunk) => { raw += chunk; });
process.stdin.on('end', () => {
  try {
    const command = stripHeredocs(JSON.parse(raw).tool_input?.command || '');
    if (!PATTERNS.some((p) => p.test(command))) return;
    process.stdout.write(JSON.stringify({
      hookSpecificOutput: {
        hookEventName: 'PreToolUse',
        permissionDecision: 'deny',
        permissionDecisionReason: '세션 scratchpad 밖(임시 폴더·드라이브 루트·.git/lost-found)에 쓰는 명령이라 막았다. '
          + '읽기만 하면 되면 리다이렉트를 없애고, 파일이 필요하면 시스템 프롬프트의 scratchpad 절대 경로에 쓴다. '
          + '다른 방법으로 우회하지 않으며, 꼭 필요하면 사용자에게 먼저 묻는다.',
      },
    }));
  } catch {
    // fail-open
  }
});
