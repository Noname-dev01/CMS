# 작업 계획 인덱스

코드베이스 검토(2026-07-12) 결과 확인된 미완성 기능·잔여 작업의 실행 계획 목록.
각 계획은 추가 질문 없이 그대로 실행 가능한 수준으로 작성되어 있다.

> 검토 근거: GitHub 열린 이슈/PR 없음. 소스 내 TODO 주석 없음(vendor 라이브러리 제외).
> 미완성 항목은 CLAUDE.md "핵심 도메인 모델"의 미구현 명시와 실제 코드·템플릿 대조로 도출.

## 목록 (권장 착수 순서)

| # | 계획 | 유형 | 선행 조건 | 착수 전 사용자 승인 |
|---|------|------|-----------|---------------------|
| 1 | [PLAN-password-reset.md](PLAN-password-reset.md) — 비밀번호 재설정 메일 발송·토큰 검증 | feat | 없음 | **필요** (공개 경로 4개 추가 = 인가 정책 변경) |
| 2 | ✅ **완료 (2026-07-14)** [PLAN-login-failure-lockout.md](PLAN-login-failure-lockout.md) — 로그인 연속 실패 시 LOCKED 자동 전이 (+30분 자동 해제) | feat | 없음 (1과 독립) | 승인 완료 (2026-07-14, 로그인 정책 변경) |
| 3 | ✅ **완료 (2026-07-18)** [PLAN-password-expiry.md](PLAN-password-expiry.md) — 비밀번호 90일 만료(PASSWORD_EXPIRED) 자동 전이 | feat | 1번 완료 필수 (2026-07-14 해소) | 승인 완료 (2026-07-17, 로그인 정책·스키마 변경) |
| 4 | ✅ **완료 (2026-07-19)** [PLAN-dashboard-demo-cleanup.md](PLAN-dashboard-demo-cleanup.md) — 대시보드 잔여 SB Admin 2 데모 위젯 정리 + 최근 7일 방문자 차트 | feat/chore | 없음 (언제든 가능) | 불필요 |
| 5 | ✅ **완료 (2026-07-20)** [PLAN-notice-board.md](PLAN-notice-board.md) — 첫 콘텐츠 도메인: 공지사항(notice) 관리 CRUD (로드맵 2026-07-20 Top 5 ①) | feat | 없음 | 승인 완료 (2026-07-20, ADMIN+MANAGER 인가·V9 멱등 메뉴 시드 — 인가 정책 변경) |

## 공통 규칙 (모든 계획에 적용)

- 브랜치: `feat/<kebab-case>` → PR → CI(`./gradlew test`) 통과 → Squash merge (`docs/branching.md`)
- Flyway 마이그레이션 번호는 **작성 시점에 `src/main/resources/db/migration/`의 최대 버전을 확인**하고 다음 번호를 쓴다. 계획 간 머지 순서에 따라 문서의 예시 번호(V4, V5)와 달라질 수 있다. 머지된 마이그레이션 파일은 수정 금지.
- 상태 변경 fetch 호출은 CSRF 헤더(`X-CSRF-TOKEN`) 필수.
- 한 계획 = 한 브랜치 = 한 PR. 계획 간 작업을 섞지 않는다.
- 비자명한 이슈를 해결하면 `docs/troubleshooting.md`에 기록한다.
- 계획 완료 후 이 인덱스의 해당 행을 삭제(또는 완료 표시)한다.
