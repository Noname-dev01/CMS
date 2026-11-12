#!/usr/bin/env node
// PreToolUse 훅: 머지된 Flyway 마이그레이션과 .env 파일의 편집을 막는다.
//
// - 마이그레이션: master(원격 우선)에 이미 있는 파일은 수정 금지(체크섬 불일치로 기동 실패 —
//   docs/migration-guide.md). 현재 브랜치에서 새로 만든 파일은 머지 전이라 허용한다.
//   예외인 "실배포 직전 마지막 합치기" 때는 /hooks에서 이 훅을 잠시 끈다.
// - .env.dev/.env.prod 등: 비밀 값 파일이라 편집하지 않는다(.env.example은 허용).
//
// 한계: Bash로 직접 쓰는 경로(sed -i, 리다이렉트)는 막지 못한다.
// 훅 자체 오류는 편집을 막지 않는다(fail-open) — 판정 불가로 작업이 멈추는 것을 피한다.

const { execFileSync } = require('child_process');
const path = require('path');

const MIGRATION_DIRS = ['src/main/resources/db/migration/', 'src/main/java/db/migration/'];

function deny(reason) {
  process.stdout.write(JSON.stringify({
    hookSpecificOutput: {
      hookEventName: 'PreToolUse',
      permissionDecision: 'deny',
      permissionDecisionReason: reason,
    },
  }));
  process.exit(0);
}

function existsOn(projectDir, ref, relPath) {
  try {
    execFileSync('git', ['cat-file', '-e', `${ref}:${relPath}`], { cwd: projectDir, stdio: 'ignore' });
    return true;
  } catch {
    return false;
  }
}

function refExists(projectDir, ref) {
  try {
    execFileSync('git', ['rev-parse', '--verify', '--quiet', ref], { cwd: projectDir, stdio: 'ignore' });
    return true;
  } catch {
    return false;
  }
}

let raw = '';
process.stdin.on('data', (chunk) => { raw += chunk; });
process.stdin.on('end', () => {
  try {
    const input = JSON.parse(raw);
    const filePath = input.tool_input && input.tool_input.file_path;
    if (!filePath) return;

    const projectDir = process.env.CLAUDE_PROJECT_DIR || input.cwd || process.cwd();
    const rel = path.relative(projectDir, path.resolve(projectDir, filePath)).split(path.sep).join('/');
    if (rel.startsWith('../')) return; // 프로젝트 밖은 관여하지 않는다

    const base = path.basename(rel);
    if (/^\.env(\..+)?$/.test(base) && base !== '.env.example') {
      deny(`${rel}은(는) 비밀 값 파일이라 편집하지 않는다(.gitignore 대상, 값은 사용자가 직접 관리). 필요한 키는 .env.example에 추가한다.`);
    }

    if (MIGRATION_DIRS.some((dir) => rel.startsWith(dir))) {
      const ref = ['origin/master', 'master'].find((r) => refExists(projectDir, r));
      if (ref && existsOn(projectDir, ref, rel)) {
        deny(`${rel}은(는) ${ref}에 머지된 마이그레이션이라 수정할 수 없다(체크섬 불일치로 기동 실패). `
          + '새 버전 파일을 추가한다 — docs/migration-guide.md. '
          + '실배포 직전 마지막 합치기라면 사용자가 /hooks에서 이 훅을 잠시 끈다.');
      }
    }
  } catch {
    // fail-open
  }
});
