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
| 26 | ✅ **PR A 완료 (2026-10-03 · #86 `1b6b196`)**, **PR B 완료 (2026-10-06 · #101 `4572733`)** [PLAN-member-permission.md](PLAN-member-permission.md) — 권한관리를 역할별에서 사용자별(MANAGER 개별)로 전환 (PR A v5 적대적 리뷰 5라운드 ship / PR B v7 적대적 리뷰 2라운드 ship — V22로 `role_permission`·`permission_role` DROP, 이전 앱 기동 실기 확인) | feat/security | 25번 완료 | **필요** (인가 정책 변경·스키마 변경(V17~V19, PR B V22 이상 DROP); 사용자 결정 D1~D5 확정 2026-10-03, Q1~Q4 기본값 대기) |
| 27 | ✅ **완료 (2026-10-05 · #92 `a5a2b2f`)** [PLAN-admin-notification.md](PLAN-admin-notification.md) — 상단바 알림(벨) E1~E4, V20 (v6, 적대적 리뷰 5라운드 ship) | feat | 없음 | 승인 완료(스키마 변경 V20, 사용자 결정 D11) |
| 28 | ✅ **완료 (2026-10-06 · #94 `63c4e33`)** [PLAN-admin-message.md](PLAN-admin-message.md) — 관리자 1:1 쪽지(상단바 봉투) + 쪽지함 페이지, V21 (v9, 적대적 리뷰 7라운드 ship) | feat | 없음 | 승인 완료(스키마 변경 V21·`/admin/member/messages` 상시 허용 경로 추가 D3, 2026-10-05) |
| 29 | ✅ **완료 (2026-10-06 · #97 `1505c43`)** [PLAN-spring-boot-4.md](PLAN-spring-boot-4.md) — Spring Boot 3.5.16 → 4.0.8 전환으로 spring-webmvc CVE-2026-47884 해소 (v3, 적대적 리뷰 3라운드 ship, classic 스타터 단계 전환·Tomcat 11.0.26·Jackson 3.1.7/2.21.7 오버라이드) | security | 없음 | 승인 완료(의존성 메이저 상향, 사용자 결정: 후행 토큰 400·로그인 Location 상대 URI·`timestamp` ISO 통일, 2026-10-06) |
| 30 | ✅ **완료 (2026-10-06 · #99 `b3566c4`)** [PLAN-modular-starters.md](PLAN-modular-starters.md) — classic 스타터 → 모듈식 스타터 전환(`starter-webmvc`·`starter-aspectj`, 테스트 `webmvc-test`·`data-jpa-test`·`security-test`) — 자동 구성 표면 축소, 동작 계약 무변경 (v4, 적대적 리뷰 4라운드 ship) | refactor | 29번 완료 | 불필요(스키마·인가 정책 변경 없음) |
| 31 | ✅ **완료 (2026-10-06 · #104 `70d6f43`)** [PLAN-doc-consistency-l02.md](PLAN-doc-consistency-l02.md) — 문서-코드 정합(감사 L-02): README 배지(Boot 4.0·Security 7·Hibernate 7)·주요 기능 보강·`.env.dev` 작성 안내·Swagger 접근 조건, `deployment.md`·`deployment-edge.md` IP 소스 서술(#44 이후), `migration-guide.md` 표 재구성·baseline 절차, `branching.md`·`dependabot.md` 정정 (v9, 적대적 리뷰 7라운드 + 사용자 결정 U3로 형식 규칙 축소) | docs | 없음 | 불필요(문서만 — 스키마·인가 정책·코드·의존성 변경 없음; 범위 U1·U2 사용자 결정 2026-10-06) |
| 32 | ✅ **완료 (2026-10-07 · #106 `b143f5a`)** [PLAN-java-21.md](PLAN-java-21.md) — Java 17 → 21 전환: toolchain·`Dockerfile` temurin `21-jdk`/`21-jre`(index digest)·CI `setup-java` 21, README·AGENTS·CLAUDE 정합. Unicode 15 대소문자 매핑 변화 점검 SQL 포함 (v3, 적대적 리뷰 3라운드 ship) | chore | 없음 | 승인 완료(2026-10-07 — 스키마·인가 정책·의존성 변경 없음) |
| 33 | ✅ **완료 (2026-10-07 · #108 `626764b`)** [PLAN-storage-load-nofollow.md](PLAN-storage-load-nofollow.md) — `LocalDiskFileStorage` 심볼릭 링크 탈출 차단: `load()`도 `openChannel()`(`NOFOLLOW_LINKS`)로 최종 파일 링크 거부, 경로 검증 기준을 "설정 루트 실경로 + 네임스페이스 이름"으로 바꿔 `root/profile` 디렉터리 링크 차단 (로드맵 "후속 과제 — ②"의 `load()` 잔여 한계, v3 적대적 리뷰 3라운드 ship) | security | 없음 | 승인 완료(2026-10-07 — 스키마·인가 정책·의존성 변경 없음; 범위 확장(네임스페이스 링크)은 리뷰 중 사용자 결정) |
| 34 | ✅ **완료 (2026-10-07 · #109 `c01ff79`)** [PLAN-extension-locale-root.md](PLAN-extension-locale-root.md) — 첨부 확장자 소문자화 `Locale.ROOT` 고정(`NoticeAttachmentService`·`LocalDiskFileStorage`): tr/az 기본 로케일에서 대문자 `.GIF`·`.ZIP` 거부되던 잠재 결함 (`PLAN-java-21.md` §8 후속, v2 적대적 리뷰 2라운드 ship) | fix | 없음 | 승인 완료(2026-10-07 — 스키마·인가 정책·의존성 변경 없음, 현재 런타임(en_US·ko_KR) 동작 변화 없음) |
| 35 | ✅ **완료 (2026-10-07 · #110 `c762fe4`)** [PLAN-public-notice-search.md](PLAN-public-notice-search.md) — 공개 공지 목록 제목 검색(`/notices?keyword=`): 공개 전용 QueryDSL `searchPublishedByTitle`(목록·COUNT 동일 조건), 100 코드 유닛 상한, LIKE 와일드카드 문자 그대로, 검색어 유지 페이지 링크, 1만·5만 행 비용 실측 (로드맵 "선정에서 탈락한 후보", v2 적대적 리뷰 2라운드 ship) | feat | 없음 | 승인 완료(2026-10-07 — 스키마·인가 정책·레이트리밋·의존성 변경 없음, 검색 대상 제목만 사용자 확정) |
| 36 | ✅ **구현·검증 완료 (2026-10-07, 커밋·PR 전)** [PLAN-html-editor.md](PLAN-html-editor.md) — HTML 편집기(Quill 2.0.3) + 허용 목록 sanitizer(jsoup 1.23.2, 저장·출력 이중) + 본문 이미지 업로드(`/content-images/{id}`, 참조 테이블 공개 판정·카운터 행 잠금 상한) — 첫 적용 공지(V23 MEDIUMTEXT·V24 이미지 테이블·V25 평문→HTML 변환), 공용 이미지 검증기(APNG·GIF 논리 화면 차단) (로드맵 Top 8 ⓪, v7 적대적 리뷰 7라운드 ship) | feat/security | 없음 | 승인 완료(2026-10-07 — 새 의존성 jsoup·Quill, 스키마 V23~V25(본문 일괄 변환), `GET/HEAD /content-images/*` 공개 — 인가 정책 변경) |
| 14 | [PLAN-audit-log-integrity.md](PLAN-audit-log-integrity.md) — 감사 로그 신뢰성 강화: IP 위조 차단 + 커밋 순서 보장 (로드맵 2026-09-05 선정 Top 5 ③, 외부 기술 감사 H-03·M-01) | security/fix | 없음 | 불필요(신규 의존성 없음, 스키마·인가 정책 변경 없음) |

## 공통 규칙 (모든 계획에 적용)

- 브랜치: `feat/<kebab-case>` → PR → CI(`./gradlew test`) 통과 → Squash merge (`docs/branching.md`)
- Flyway 마이그레이션 번호는 **작성 시점에 `src/main/resources/db/migration/`의 최대 버전을 확인**하고 다음 번호를 쓴다. 계획 간 머지 순서에 따라 문서의 예시 번호(V4, V5)와 달라질 수 있다. 머지된 마이그레이션 파일은 수정 금지.
- 상태 변경 fetch 호출은 CSRF 헤더(`X-CSRF-TOKEN`) 필수.
- 한 계획 = 한 브랜치 = 한 PR. 계획 간 작업을 섞지 않는다.
- 비자명한 이슈를 해결하면 `docs/troubleshooting.md`에 기록한다.
- 계획 완료 후 이 인덱스의 해당 행을 삭제(또는 완료 표시)한다.
