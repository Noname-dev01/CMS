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
| 6 | ✅ **완료 (2026-07-27)** [PLAN-testcontainers.md](PLAN-testcontainers.md) — Testcontainers 전환: 테스트 DB 격리 (로드맵 2026-07-20 재선정 Top 5 ④) | test/infra | 없음 (언제든 가능) | 승인 완료 (2026-07-27, 신규 의존성 2개 + CI service container 제거) |
| 7 | ✅ **완료 (2026-07-22)** [PLAN-notice-attachment.md](PLAN-notice-attachment.md) — 파일 스토리지 추상화 + 공지 첨부파일 (로드맵 2026-07-20 재선정 Top 5 ②) | feat | 5번 완료 필수 | 승인 완료 (신규 의존성 없음, 스키마 변경) |
| 8 | ✅ **완료 (2026-07-28)** [PLAN-public-notice.md](PLAN-public-notice.md) — 공개 공지 페이지: 첫 비관리자 화면 (로드맵 2026-07-20 재선정 Top 5 ③) | feat | 5번 완료 필수 | 승인 완료 (2026-07-28, `/notices` GET/HEAD `permitAll`+나머지 `denyAll` 명시 — 인가 정책 변경) |
| 9 | ✅ **완료 (2026-07-30)** [PLAN-prod-profile.md](PLAN-prod-profile.md) — prod 프로파일 부활 + 배포 준비 (로드맵 2026-07-29 선정 Top 3 ①) | infra/security | 8번 완료 권장(배포할 콘텐츠 확보) | 승인 완료 (2026-07-29, `/actuator/health` permitAll + `/actuator/**` denyAll 명시 — 인가 정책 변경) |
| 10 | ✅ **완료 (2026-08-03)** [PLAN-public-notice-attachment.md](PLAN-public-notice-attachment.md) — 공개 공지 상세 첨부파일 다운로드 (로드맵 2026-07-29 선정 Top 3 ②) | feat | 8번 완료 필수 | 승인 완료(신규 의존성·스키마 변경·인가 정책 변경 없음) |
| 11 | ✅ **완료 (2026-08-06)** [PLAN-not-found-handling.md](PLAN-not-found-handling.md) — 핸들러 없는 경로의 404 응답 정정 (로드맵 "후속 과제 — ① prod 프로파일 완료 시 발견") | fix | 없음 | 불필요(스키마·인가 정책 변경 없음, `SecurityConfig.java` 등은 매처 상수 소유권 이동만) |
| 12 | ✅ **완료 (2026-08-10)** [PLAN-profile-image-storage.md](PLAN-profile-image-storage.md) — 프로필 이미지 Base64-in-DB → FileStorage 이관 (로드맵 2026-07-29 선정 Top 3 ③) | feat/refactor | 없음(FileStorage 도입으로 선행 조건 해소됨) | 승인 완료(신규 의존성 없음, 스키마 변경(V11), 인가 정책 변경 없음 — SecurityConfig 무수정) |
| 13 | ✅ **완료 (2026-09-22)** [PLAN-password-policy-unification.md](PLAN-password-policy-unification.md) — 관리자 비밀번호 검증 정책 통일 + 이메일 정규화·길이 정합 (로드맵 2026-09-05 선정 Top 5 ①, 외부 기술 감사 H-01·M-07) | security/fix | 없음 | 승인 완료(신규 의존성 없음, 스키마·인가 정책 변경 없음 — 비밀번호 최소 길이(15코드포인트)·72바이트 처리 방식·부트스트랩 이메일 정규화 포함 여부 3건 사용자 협의) |
| 15 | ✅ **구현 완료 (2026-09-29, 커밋·PR 전)** [PLAN-default-deny-authorization.md](PLAN-default-deny-authorization.md) — SecurityConfig 기본 거부 전환 (로드맵 "우선순위에서 밀린 감사 항목" M-08) | security | 없음 | 승인 완료 (2026-09-29, `anyRequest().permitAll()` → `denyAll()` + ERROR 디스패치·정적 리소스 명시 공개 — 인가 정책 변경, 스키마·의존성 변경 없음) |
| 16 | ✅ **완료 (2026-09-29, #47 · 필수 체크 `prod-smoke` 등록까지 확인)** [PLAN-ci-prod-gates.md](PLAN-ci-prod-gates.md) — CI 배포 게이트 확장: prod 기동 스모크·백업복구 왕복·이미지 스캔·digest 고정 (로드맵 "우선순위에서 밀린 감사 항목" M-06) | infra/ci | 취약 의존성 상향 PR #46 머지(2026-09-29 해소) | 승인 완료 (2026-09-29, 스키마·인가 정책·앱 코드 변경 없음, 백업/복구 스크립트는 이미지 참조 리터럴만 치환, CI 도구(Trivy 액션·Dependabot) 추가) |
| 17 | ✅ **구현 완료 (2026-09-29, 커밋·PR 전)** [PLAN-public-notice-attachment.md](PLAN-public-notice-attachment.md) "후속 작업 — 스트리밍 전환" — 공개 첨부 다운로드 byte[] 전량 로딩 → 스트리밍 (로드맵 "후속 과제 — ② 공개 첨부 다운로드 완료 시 기록"의 자원 고갈 위험) | refactor/infra | 10번 완료 필수 | 승인 완료(스키마·인가 정책·신규 의존성 변경 없음; 전역 `open-in-view=false` 설정 1줄은 스파이크 결과에 따라 사용자 결정) |
| 18 | ✅ **완료 (2026-09-30, #64)** [PLAN-clock-unification.md](PLAN-clock-unification.md) — 시각 원천 KST Clock 단일화, `LocalDateTime.now()` 직접 호출 제거 (로드맵 "우선순위에서 밀린 감사 항목" M-05) | refactor | 없음 | 승인 완료(스키마·인가 정책·신규 의존성 변경 없음; 운영 동작 무변화 — 테스트 JVM 시각 원천 정합성 방어) |
| 19 | ✅ **완료 (2026-09-30 · `45c9771` #66)** [PLAN-mail-executor-bound.md](PLAN-mail-executor-bound.md) — 재설정 메일 발송 executor 큐·동시 실행 상한 (로드맵 2026-09-05 선정 Top 5 ④ 잔여) | fix/ops | 없음 | 승인 완료(스키마·인가 정책·신규 의존성 변경 없음, 설정 3줄 + 테스트) |
| 20 | ✅ **완료 (2026-09-30 · `c45fae3` #67)** [PLAN-member-list-sort-tiebreak.md](PLAN-member-list-sort-tiebreak.md) — 회원 목록 정렬 id 보조 정렬(tie-breaker) 추가 (로드맵 "우선순위에서 밀린 감사 항목" L-01) | fix | 없음 | 불필요(스키마·인가 정책·신규 의존성 변경 없음, 쿼리·테스트만 변경) |
| 21 | ✅ **완료 (2026-09-30 · `41554b9` #68)** [PLAN-menu-reorder.md](PLAN-menu-reorder.md) — 메뉴 형제 순서 재조정 API(`PUT /admin/api/menus/order`) + 트리 드래그 앤 드롭 (원본 `menu-management-plan.md` "알려진 한계 — 순서(ord) 재조정") | feat | 없음 | 불필요(스키마·인가 정책·신규 의존성 변경 없음; URI가 원안 `PATCH .../reorder`에서 컨벤션상 `PUT .../order`로 변경됨 — 승인 완료) |
| 22 | ✅ **완료 (2026-09-30 · `2e03503` #69)** [PLAN-menu-move.md](PLAN-menu-move.md) — 메뉴 부모 이동 API(`PATCH /admin/api/menus/{id}/parent`) + "상위 변경" 폼 (원본 `menu-management-plan.md` "알려진 한계 — 부모 이동", PR #68 후속) | feat | 21번 완료 필수 | 승인 완료(스키마·인가 정책·신규 의존성 변경 없음; 결과는 항상 2단·자식 있는 메뉴 이동 거부, 교착은 409로 허용하는 계약 수용 등 사용자 결정 사항 10건 기본값대로 확정) |
| 23 | ✅ **완료 (2026-10-01 · #71·#73·#74·#75)** [PLAN-menu-structure-apply.md](PLAN-menu-structure-apply.md) — 메뉴 3단 확장 + 드래그 초안·[반영] 버튼 일괄 구조 반영(`PUT /admin/api/menus/structure`), 기존 `PUT /order`·`PATCH /{id}/parent` 대체 | feat/refactor | 21·22번 완료 | 승인 완료 (2026-10-01, 스키마·인가 정책·신규 의존성 변경 없음; 정책·PR 3분할(A 사이드바 3단 → B 서버 API → C 화면 + 기존 API 제거)은 이 계획이 한 계획 = 여러 PR의 예외) |
| 24 | ✅ **완료 (2026-10-02 · #78 `ab80e04` · #79 `642b926`)** [PLAN-menu-permanent-delete.md](PLAN-menu-permanent-delete.md) — 메뉴 영구삭제(하드 삭제) + 비활성화는 `PATCH useYn=false`로 일원화, 삭제 이름·URL 감사 기록 | feat | 23번 완료 | 승인 완료 (2026-10-01, `admin_action_log` 스키마 변경(V12)·`DELETE /admin/api/menus/{id}` 의미 변경·`PUT /structure` 낡은 초안 400→409; 인가 정책·신규 의존성 변경 없음; 계획 하나가 두 PR(A 감사 로그 확장 → B 영구삭제·화면)로 나뉘는 예외) |
| 25 | ✅ **완료 (2026-10-02 · #81 `090dd45` · #82 `9c651b8` · #83 `3ecdfb5` · #84 `8f5baf7`)** [PLAN-menu-permission-management.md](PLAN-menu-permission-management.md) (PR ③ 구현 계획: [PLAN-permission-management-pr3.md](PLAN-permission-management-pr3.md)) — 권한관리: ADMIN이 운영 중에 MANAGER 위임 기능(공지)의 조회·생성·수정·삭제 권한을 바꾸고 `menu.access_role`을 대체 | feat/security | 24번 완료 | 승인 완료 (2026-10-02, 인가 정책 변경·스키마 변경(V13~V16: 권한 테이블 2개 추가 → `access_role` 매핑 제거 → DROP)·신규 의존성 없음; 사용자 결정 D1~D6·U1~U8 확정, 적대적 리뷰 3라운드 ship; 계획 하나가 PR 4개로 나뉘는 예외) |
| 26 | ✅ **PR A 완료 (2026-10-03 · #86 `1b6b196`)**, PR B(V20 DROP) 대기 [PLAN-member-permission.md](PLAN-member-permission.md) — 권한관리를 역할별에서 사용자별(MANAGER 개별)로 전환 (v5, 적대적 리뷰 5라운드 ship, PR A 머지 완료 — PR B(V20 DROP)는 PR A 운영 안정 뒤) | feat/security | 25번 완료 | **필요** (인가 정책 변경·스키마 변경(V17~V19, PR B V20 DROP); 사용자 결정 D1~D5 확정 2026-10-03, Q1~Q4 기본값 대기) |
| 14 | [PLAN-audit-log-integrity.md](PLAN-audit-log-integrity.md) — 감사 로그 신뢰성 강화: IP 위조 차단 + 커밋 순서 보장 (로드맵 2026-09-05 선정 Top 5 ③, 외부 기술 감사 H-03·M-01) | security/fix | 없음 | 불필요(신규 의존성 없음, 스키마·인가 정책 변경 없음) |

## 공통 규칙 (모든 계획에 적용)

- 브랜치: `feat/<kebab-case>` → PR → CI(`./gradlew test`) 통과 → Squash merge (`docs/branching.md`)
- Flyway 마이그레이션 번호는 **작성 시점에 `src/main/resources/db/migration/`의 최대 버전을 확인**하고 다음 번호를 쓴다. 계획 간 머지 순서에 따라 문서의 예시 번호(V4, V5)와 달라질 수 있다. 머지된 마이그레이션 파일은 수정 금지.
- 상태 변경 fetch 호출은 CSRF 헤더(`X-CSRF-TOKEN`) 필수.
- 한 계획 = 한 브랜치 = 한 PR. 계획 간 작업을 섞지 않는다.
- 비자명한 이슈를 해결하면 `docs/troubleshooting.md`에 기록한다.
- 계획 완료 후 이 인덱스의 해당 행을 삭제(또는 완료 표시)한다.
