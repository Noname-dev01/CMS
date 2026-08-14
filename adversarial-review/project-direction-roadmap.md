# 프로젝트 방향성 리포트 — 분석 및 3단계 로드맵

> 작성일: 2026-07-10
> 기준 커밋: `03680cd` (기능: 메뉴 데이터 기반 사이드바 동적 렌더링 #6)
> 최근 갱신: 2026-08-12 — "후속 과제 — ① prod 프로파일 완료 시 발견"의 "DB 백업 전략" 완료 반영 (`95f264f` #30, PR MERGED·완료 기준 21개 전 항목 실기 검증 사실확인) — 상세는 해당 섹션 참조. 검증 중 계획 문서가 스스로 명시한 "오프사이트 백업 미포함" 잔여 위험은 신규 후속 과제로 별도 기록.
> 이전 갱신: 2026-08-12 — `ProfileImageMigrationRunnerIntegrationTest`(Testcontainers) 추가로 "후속 과제 — ③" 2번 항목 완료 반영 (`ca6446f` #29, PR MERGED·CI test pass 사실확인) — 상세는 하단 "후속 과제 — ③ 프로필 이미지 이관 완료 시 발견" 참조. 남은 미해소는 1번 항목(실 레거시 데이터 이관 Playwright 골든 패스)뿐이다.
> 이전 갱신: 2026-08-11 — ③ 프로필 이미지 Base64-in-DB → FileStorage 이관 완료 반영 (`e3175a9` #28, PR MERGED 사실확인). **Top 3(2026-07-29 선정) 전 항목 완료.** 완료 기준 5개 중 3개 완전 충족, 2개는 부분 충족(실 레거시 데이터 이관 골든 패스·마이그레이션 러너 Testcontainers 통합 테스트 미작성) — 사용자 협의 후 완료 처리, 잔여 항목은 하단 "후속 과제 — ③ 프로필 이미지 이관 완료 시 발견" 참조.
> 이전 갱신: 2026-08-07 — "후속 과제 — ① prod 프로파일 완료 시 발견" 중 "핸들러 없는 경로가 404 대신 500 반환" 완료 반영 (`7c64307` #26, PR MERGED·CI success 사실확인). Top 3 항목 자체는 변동 없음 — 남은 미완료는 여전히 ③(프로필 이미지 이관) 하나.
> 이전 갱신: 2026-08-06 — ② 공개 공지 상세 첨부파일 다운로드 완료 반영 (`10c28ff` #25, PR MERGED·CI test pass 사실확인). 남은 미완료는 ③(프로필 이미지 이관) 하나.
> 이전 갱신: 2026-07-30 — ① prod 프로파일 부활 완료 반영 (`a51d29d` #23, `AdminBootstrapLoader`·`ProfileGuardEnvironmentPostProcessor`·actuator 이중 방어·Docker 실기 검증 사실확인, `/code-review-loop` 3라운드 거침) — 3단계(운영 경험) 개시. 검증 중 발견된 범위 밖 항목은 하단 "후속 과제" 참조.
> 이전 갱신: 2026-07-29 — 전면 재분석(`/createRoadmap`) 후 Top 3 신규 선정 (기준 커밋 `b2a6b0f` #22). 열린 이슈/PR 0건, 소스 TODO 0건(vendor 제외) 재확인. 신규 선정 근거는 하단 "실행 로드맵 — Top 3 (2026-07-29 선정)" 참조.
> 이전 갱신: 2026-07-28 — ③ 공개 공지 페이지 완료 반영 (`7ab80a5` #21, `com.cms.publicweb` 신규 패키지·`/notices` GET/HEAD permitAll+denyAll·Playwright 실기 검증·PR #21 CI success 사실확인)
> 이전 갱신: 2026-07-27 — ④ Testcontainers 전환 완료 반영 (`04b8121` #20, `MariaDbContainerSupport`·CI service container 제거·PR #20 CI success 사실확인)
> 이전 갱신: 2026-07-27 — ② 파일 스토리지 추상화 + 공지 첨부파일 완료 반영 (`174e925` #18, 첨부 CRUD·경로 탈출 방지·확장자 검증·감사 로그 사실확인) + `./gradlew test` 전체 통과 재확인
> 이전 갱신: 2026-07-21 — ① 공지사항(notice) 관리 완료 표기 정정 (`feat/notice-board` → `6c5ca4c` #16, 모순되는 구 문구 삭제) + `./gradlew test` 전체 통과 재확인

## 현재 상태 진단

**한 줄 요약: "CMS"라는 이름과 달리, 지금 이 프로젝트는 콘텐츠가 없는 잘 만든 관리자 백오피스 골격이다.** 이 간극을 어떻게 메울지가 방향성의 핵심이다.

### 강점 (계속 유지할 것)

- **공학적 규율이 좋다.** 계층 분리, DTO 경계, 생성자 주입, QueryDSL 동적 쿼리, 전역 예외 처리까지 일관성이 있고, `AdminPageAnnotationConventionTest`처럼 컨벤션 자체를 테스트로 강제하고 있다.
- **횡단 관심사가 이미 성숙하다.** AOP 감사 로깅(REQUIRES_NEW 독립 트랜잭션 + 예외 격리), CSRF 전면 활성화, API 경로 JSON 401/403 분기(CSRF 필터 순서로 인한 401/403 오분류까지 처리 — `SecurityConfig.java:69-72`), ADMIN/MANAGER 역할 세분화 완료.
- **기록 문화**: 트러블슈팅 24건, 마이그레이션 SQL 기록, CI + GitHub Flow. 이 습관이 프로젝트의 가장 큰 자산이다.

### 공백 (방향성을 정하는 제약)

1. **콘텐츠 도메인 부재.** 도메인이 member(관리자 계정)·menu·log·visit·dashboard뿐 — 전부 "관리를 위한 관리" 기능이고, 관리할 대상(게시글, 페이지, 미디어)이 없다.
2. ~~**스키마 관리가 임계점.** `ddl-auto: update` + `docs/migration/` 수동 SQL 3개. 이 방식은 도메인이 하나만 더 늘어도 깨진다.~~ → **해소됨** (`020b203`, #7): Flyway 도입 완료, `ddl-auto: validate` 전환.
3. ~~**prod 프로파일이 의도적으로 제거된 상태**(#3 커밋)라 배포 목표가 없다. Dockerfile과 nginx 설정은 남아 있다.~~ → **해소됨** (`a51d29d`, #23): prod 프로파일 부활 완료 — `AdminBootstrapLoader`(초기 관리자 부트스트랩)·`ProfileGuardEnvironmentPostProcessor`(프로파일 가드)·actuator 이중 방어·`docker-compose.prod.yml` 배포 가능 상태 검증까지 완료. 실제 인터넷 배포(리버스 프록시·TLS·호스팅)는 여전히 범위 밖(하단 "후속 과제" 참조).
4. **미완성 기능이 반쯤 열려 있다.** ~~`resetToken`·SMTP 설정은 있으나 비밀번호 재설정 발송/사용 로직 없음.~~ → **해소됨** (`99359d3`, #10): 비밀번호 재설정(메일 링크 발급 + 토큰 검증) 구현 완료. ~~`MemberStatus`의 `LOCKED` 상태 전이 로직(로그인 실패 잠금) 없음.~~ → **해소됨** (`f0ecc15`, #12): 로그인 연속 5회 실패 시 자동 잠금(30분 lazy 해제) 구현 완료. ~~`PASSWORD_EXPIRED` 상태 전이 로직(비번 만료)은 여전히 미구현.~~ → **해소됨** (`c889a3c`, #14): 비밀번호 90일 만료 자동 전이 구현 완료. ~~타 관리자 계정 수정/상태 변경 API(`PATCH /admin/api/members/{id}`) 없음 → 관리자 CRUD 미완.~~ → **해소됨** (`163367e`, #9): 타 관리자 수정/상태 변경 API + 대상자 세션 강제 만료 구현 완료.
5. ~~**버전 부채**: Spring Boot 3.4.x는 OSS 지원이 종료된 라인(2026-07 기준). 최소 3.5.x로 올리고,~~ → **해소됨** (`76bba41`, #8): Boot 3.5.16 업그레이드 완료. 중기적으로 Java 21 + Boot 4.x 검토 시점은 유효.

## 3단계 로드맵

### 1단계 — 기반 마감 (새 기능보다 먼저)

- ✅ **Flyway 도입 (완료, 2026-07-10 · `020b203` #7).** 스키마 baseline + access_role 백필 + 메뉴 시드를 Flyway 마이그레이션(V1~V3)으로 이관하고 `ddl-auto`를 `validate`로 전환 완료.
- **계정 라이프사이클 마감** (진행 중): 필드·상태값이 이미 있어 마감 비용이 낮고, 감사 로깅 인프라와 시너지가 크다.
    - ✅ 관리자에 의한 타 계정 수정/잠금 해제 API (완료, 2026-07-10 · `163367e` #9). `PATCH /admin/api/members/{id}` — 부분 수정, 최후 활성 ADMIN 가드(비관적 락), 상태·권한 변경 시 대상자 세션 강제 만료(AFTER_COMMIT, best-effort)까지 포함.
    - ✅ 로그인 실패 N회 → `LOCKED` 자동 전이 (완료, 2026-07-14 · `f0ecc15` #12). 연속 5회 실패(`BadCredentialsException`만 카운트) 시 자동 잠금 + 30분 lazy 자동 해제, 성공 핸들러 fail-closed 재확인, 잠금 전이 감사 로그·세션 만료, 최후 ADMIN 복구 절차 troubleshooting 기록까지 포함.
    - ✅ 비밀번호 재설정 메일 발송·토큰 검증 (완료, 2026-07-14 · `99359d3` #10). 메일 링크 발급(토큰 SHA-256 해시 저장·30분 TTL·일회용·60초 쿨다운·계정 열거 방지) + 토큰 검증 재설정, 재설정 성공 시 기존 세션 만료·`PASSWORD_EXPIRED`→`ACTIVE` 복귀까지 포함.
    - ✅ 비밀번호 90일 만료(`PASSWORD_EXPIRED`) 전이 (완료, 2026-07-18 · `c889a3c` #14). 로그인 시점 조건부 벌크 UPDATE 전이(+성공 직전 재판정 TOCTOU 차단), 모든 비밀번호 변경 경로의 `ACTIVE` 복귀 관문 중앙화, V5~V7 마이그레이션까지 포함 — **1단계 계정 라이프사이클 마감**.
- ✅ **Boot 3.5.x 업그레이드 (완료, 2026-07-10 · `76bba41` #8).** Spring Boot 3.4.3 → 3.5.16 마이너 업그레이드 완료.

### 2단계 — 정체성 확보: 첫 콘텐츠 도메인 (프로젝트의 본론)

"CMS"가 되려면 관리 대상이 필요하다. **공지사항/게시판 도메인 하나를 끝까지** 만드는 것을 추천.

- Board/Post + 첨부파일 → 기존 패턴(Controller→Service→Repository, QueryDSL 검색, AOP 감사 로깅, 메뉴 등록, `@AdminPage` 화면)을 그대로 재사용하는 첫 실전 검증. 지금까지 만든 골격이 "새 도메인을 얼마나 싸게 추가할 수 있는가"로 증명된다.
- **파일 스토리지 추상화**가 강제로 필요해진다(첨부파일을 Base64로 DB에 넣을 수는 없으므로). 로컬 디스크 구현으로 시작해 인터페이스만 잡아두면, 프로필 이미지의 Base64-in-DB 방식도 자연스럽게 이관 가능.
- 콘텐츠 소비자 결정: 공개 프론트 페이지를 Thymeleaf로 소박하게 붙이는 방안(진짜 CMS 완성형) vs 공개 조회 API만 제공하는 headless 방안. **전자 추천** — 현재 스택과 일관되고 배포 데모가 명확하다.

### 3단계 — 운영 경험 (포트폴리오 가치의 완성)

- ✅ **prod 프로파일 부활** (완료, 2026-07-30 · `a51d29d` #23): Swagger 비활성, actuator `health`만 공개(설정+Security 이중 방어), `ddl-auto: validate`(공통 정책 유지), 시크릿 전부 환경변수 외부화 + 초기 관리자 환경변수 부트스트랩까지 완료. Docker Compose 기반 배포 가능 상태 검증 완료(실배포 아님).
- 저비용 VPS나 홈서버에 실배포 + 최소 모니터링(actuator + 로그). "배포 가능한 master"라는 GitHub Flow 원칙은 실제 배포가 있어야 의미를 갖는다. (리버스 프록시·TLS·호스팅 확정 필요 — 하단 "후속 과제" 참조)

### 지속 항목 (급하지 않지만 방향은 정해둘 것)

- ~~**Testcontainers 검토**: 현재 테스트가 로컬 MariaDB 기동에 의존 — Testcontainers로 바꾸면 CI/로컬 환경 차이가 사라진다.~~ → **해소됨** (`04b8121`, #20): Testcontainers 전환 완료.
- **QueryDSL 원본(5.1.0)은 사실상 개발 중단 상태.** 당장 문제는 없지만 Boot 4.x 이행 시 OpenFeign 포크(`io.github.openfeign.querydsl`) 전환을 염두에 둘 것.
- 메뉴 3단계 확장, 메뉴-권한 실제 연동(`accessRole`을 노출뿐 아니라 인가에 반영)은 콘텐츠 도메인이 생겨 메뉴가 실제로 늘어난 뒤 판단해도 늦지 않다.

## 실행 로드맵 — Top 5 (2026-07-12 선정)

> 코드베이스 전수 검토(열린 이슈/PR 0건, 소스 TODO 0건 — vendor 제외) + 기존 로드맵 미완료 항목 대조로 선정.
> ①~④는 `adversarial-review/plan/`에 실행 계획서가 이미 있으므로 **해당 문서를 그대로 따른다** (아래는 요약).
> 공통 규칙(브랜치·Flyway 번호 확인·CSRF·1계획=1PR)은 [`plan/README.md`](plan/README.md) 참조.

### ① 비밀번호 재설정 메일 발송·토큰 검증 — ✅ 완료 (2026-07-14 · `99359d3` #10)
- **실행 원본**: [`plan/PLAN-password-reset.md`](plan/PLAN-password-reset.md)
- **목표**: 로그인 불가 관리자가 이메일 링크로 스스로 비밀번호를 재설정. `resetToken`·SMTP 설정 등 절반 준비된 기능의 마감. ③의 유일한 복구 경로라 **선행 조건 해소** 레버리지가 가장 크다.
- **파일·순서·완료 기준**: 계획서에 명시 (평문 토큰 저장 금지 — SHA-256 해시, 사용/만료 토큰 400, 재설정 후 기존 세션 만료까지).
- **착수 게이트**: 공개 경로 4개 추가 = **인가 정책 변경 → 사용자 승인 필수**. (2026-07-13 승인 완료)

### ② 로그인 연속 실패 시 LOCKED 자동 전이 — ✅ 완료 (2026-07-14 · `f0ecc15` #12)
- **실행 원본**: [`plan/PLAN-login-failure-lockout.md`](plan/PLAN-login-failure-lockout.md)
- **목표**: 연속 5회 실패 시 자동 잠금으로 무차별 대입 차단. ①과 독립적으로 착수 가능.
- **파일·순서·완료 기준**: 계획서에 명시 (`failed_login_count` 컬럼 + 벌크 UPDATE 원자적 증가, 실패 핸들러, 성공 시 리셋, 최후 ADMIN 복구 절차 troubleshooting 기록까지) — 완료 기준 체크리스트 전 항목 충족 확인.
- **착수 게이트**: 로그인 정책 변경 → 사용자 승인 필수. (2026-07-14 승인 완료)

### ③ 비밀번호 90일 만료(PASSWORD_EXPIRED) 자동 전이 — ✅ 완료 (2026-07-18 · `c889a3c` #14)
- **실행 원본**: [`plan/PLAN-password-expiry.md`](plan/PLAN-password-expiry.md) (v8 — 적대적 리뷰 7라운드 ship, 구현·검증 결과 기록됨)
- **목표**: 계정 라이프사이클 마감 — `MemberStatus` 5종 중 마지막 미구현 전이 제거.
- **파일·순서·완료 기준**: 계획서에 명시 (`password_changed_at` 컬럼 + 백필 마이그레이션(V5~V7), 로그인 시점 검사 + 성공 직전 재판정, 재설정·내 비밀번호 변경 흐름으로 복귀) — 완료 기준 체크리스트 전 항목 충족 확인.
- **착수 게이트**: **① 완료 필수** (2026-07-14 해소) + 만료 정책 사용자 승인. (2026-07-17 승인 완료)

### ④ 대시보드 데모 위젯 정리 + 실데이터 차트 — ✅ 완료 (2026-07-19 · `3e652e6` #15)
- **실행 원본**: [`plan/PLAN-dashboard-demo-cleanup.md`](plan/PLAN-dashboard-demo-cleanup.md) (v6 — 적대적 리뷰 5라운드 ship, 구현·검증 결과 기록됨)
- **목표**: SB Admin 2 더미 콘텐츠 제거, 최근 7일 방문자 실데이터 차트 1개로 대체.
- **파일·순서·완료 기준**: 계획서에 명시 (chart-*-demo.js 제거, 집계 실패 시 500 없는 폴백, 저장·집계 KST `Clock` 단일 시간원 통일 포함) — 완료 기준 체크리스트 전 항목 충족 확인.

### ⑤ 첫 콘텐츠 도메인 — 공지사항(notice) 관리
- 2026-07-20 재선정 Top 5의 **①번으로 승계** — 아래 신규 섹션 참조.

## 실행 로드맵 — Top 5 (2026-07-20 재선정)

> 재선정 근거: 열린 이슈/PR 0건, 소스 TODO 0건(vendor 제외). 2026-07-12 Top 5 중 ①~④ 전부 완료, ⑤만 미착수 승계.
> 리팩토링 전수 점검 결과 비대 클래스·레이어 위반 없음(최대 `AdminMemberService` 344줄) — 리팩토링 항목은 발굴하지 않음.
> 공통 규칙(브랜치·Flyway 번호 확인·CSRF·1계획=1PR)은 [`plan/README.md`](plan/README.md) 참조.

### ① 첫 콘텐츠 도메인 — 공지사항(notice) 관리 — ✅ 완료 (2026-07-20 · `6c5ca4c` #16)
- **유형**: 기능 추가 (2단계 개시) / **선정 이유**: ②③의 선행 조건이자 "CMS에 관리할 대상이 없다"는 최대 공백을 메우는 최고 레버리지 작업.
- **실행 원본**: [`plan/PLAN-notice-board.md`](plan/PLAN-notice-board.md) (v7 — 적대적 리뷰 5라운드, 4라운드 needs-attention 반복 후 사용자 결정으로 ship — 구현·검증 결과 기록됨)
- **목표**: "관리할 대상"이 없는 CMS에 첫 콘텐츠 도메인을 추가해 2단계(정체성 확보)를 개시한다. 기존 골격(계층 분리·QueryDSL 검색·AOP 감사 로깅·메뉴·`@AdminPage` 화면)을 새 도메인에 그대로 재사용하는 첫 실전 검증.
- **1차 범위 (확정)**: 제목·내용·사용여부(노출)·작성자·작성/수정일의 관리 화면 CRUD. **첨부파일 제외** (→ ②), **공개 프론트 제외** (→ ③).
- **수정해야 할 정확한 파일** (member 도메인 패키지 패턴 미러 — 실측 기준):
    - 신규: `src/main/java/com/cms/admin/notice/` 하위 `domain/Notice.java`, `repository/NoticeRepository.java`, `repository/NoticeRepositoryImpl.java`(QueryDSL 검색), `service/NoticeService.java`, `controller/NoticeController.java`(REST), `controller/NoticePageController.java`(`@AdminPage` 필수), `dto/request/NoticeCreateRequest.java`·`NoticeUpdateRequest.java`, `dto/response/NoticeResponse.java`
    - 신규: `src/main/resources/templates/admin/notice/manage.html` (`templates/admin/menu/manage.html` 구조 미러, CSRF 헤더 필수)
    - 신규: `src/main/resources/db/migration/V8__create_notice.sql` — 현재 최대 버전 V7 실측(작성 시점 재확인), 컬럼 규약(감사 컬럼 등)은 `V1__init_schema.sql`의 member·menu 정의를 그대로 따름
    - 신규 테스트: `NoticeServiceTest`, `NoticeControllerTest` (MockMvc + spring-security-test)
    - 수정 없음이 원칙 (사이드바 메뉴는 코드가 아니라 데이터)
- **단계별 작업 순서**: `/plan-review-loop`로 계획 확정 → `feat/notice-board` 브랜치 → Flyway → 엔티티 → Repository(+Impl) → Service(`@AdminActionLogged` 부착) → DTO → REST Controller(`GET/POST /admin/api/notices`, `GET/PATCH/DELETE /admin/api/notices/{id}`, DELETE는 소프트 삭제 204) → PageController + manage.html → 메뉴 등록(`POST /admin/api/menus`, `accessRole=ALL` — V3 시드는 빈 테이블 전용이므로 마이그레이션 아님) → 테스트 → playwright 검증
- **완료 기준**: `./gradlew test` 통과 / 빈 DB `bootRun` 기동(`validate` 통과) / ADMIN·MANAGER 각각 로그인해 CRUD 골든 패스 + 목록 검색·페이징 playwright 확인 / 소프트 삭제 후 목록 미노출 / 감사 로그(`AdminActionLog`)에 생성·수정·삭제 기록 확인 / 사이드바에 메뉴 노출
- **착수 게이트**: 스키마 변경 수반 — 계획서 승인 시 함께 고지.

### ② 파일 스토리지 추상화 + 공지 첨부파일 — ✅ 완료 (2026-07-27 · `174e925` #18)
- **유형**: 기능 추가 / **선정 이유**: 첨부파일이 강제하는 스토리지 추상화는 프로필 이미지 Base64-in-DB 방식의 탈출구도 함께 열어주는 구조적 투자.
- **완료 근거**: `com.cms.common.storage`(`FileStorage`·`LocalDiskFileStorage`·`FileStorageProperties`·`StorageFileNotFoundException`) + `com.cms.admin.notice`의 `NoticeAttachment`·`NoticeAttachmentService`·`NoticeAttachmentController` 구현 확인, `V10__create_notice_attachment.sql` 마이그레이션 확인. `LocalDiskFileStorageTest`(경로 탈출 `../` 차단 포함)·`NoticeAttachmentServiceTest`(확장자/Content-Type/10MB 초과/빈 파일 400)·`NoticeAttachmentControllerTest`·`NoticeAttachmentTransactionIntegrationTest`(업로드 롤백 시 파일 정리, 삭제는 커밋 후 파일 제거) 전부 통과 확인. `NoticeAttachmentService`에 업로드·삭제 `@AdminActionLogged` 부착 확인. `./gradlew test`(dev DB 기동 상태) 전체 통과 재확인.
- **실행 원본**: 없음 — 착수 시 `/plan-review-loop`로 계획서 작성·검증.
- **목표**: 로컬 디스크 구현의 파일 스토리지 인터페이스를 도입하고, 공지사항에 첨부파일 업로드·다운로드·삭제를 추가한다. 완료 시 "DB에 Base64로 넣을 수 없는" 실파일을 다루는 첫 경로가 생기고, 이후 프로필 이미지 이관(별도 후속)도 같은 인터페이스를 재사용할 수 있다.
- **수정해야 할 정확한 파일** (실측 기준):
    - 신규: `src/main/java/com/cms/common/storage/` — `FileStorage.java`(인터페이스), `LocalDiskFileStorage.java`(구현), 저장 경로 설정 프로퍼티 클래스
    - 신규: `src/main/java/com/cms/admin/notice/` 하위 첨부 엔티티·Repository·DTO·첨부 API(①에서 생성되는 패키지에 추가 — ① 완료 선행)
    - 수정: `src/main/resources/application.yml`·`application-dev.yml` (저장 루트 경로 프로퍼티), `src/main/resources/templates/admin/notice/manage.html` (①에서 신규 생성 — 업로드 UI 추가)
    - 신규: `src/main/resources/db/migration/V<N>__create_notice_attachment.sql` (번호는 작성 시점 최대 버전+1)
    - 신규 테스트: `LocalDiskFileStorage` 단위 테스트, 첨부 API MockMvc 테스트
- **단계별 작업 순서**: `/plan-review-loop` 계획 확정 → Flyway → `FileStorage` 인터페이스 + 로컬 구현(경로 탈출 방지·확장자/크기 검증 포함) → 첨부 엔티티·Repository → Service(업로드/삭제 `@AdminActionLogged`) → REST API(multipart 업로드는 CSRF 헤더 필수) → 화면 → 테스트 → playwright 검증
- **완료 기준**: `./gradlew test` 통과 / 업로드→목록 표시→다운로드→삭제 골든 패스 playwright 확인 / 허용 외 확장자·크기 초과 400 응답 확인 / 경로 조작(`../`) 차단 테스트 통과 / 감사 로그에 업로드·삭제 기록
- **착수 게이트**: **① 완료 필수** + 스키마 변경 수반. 파일 검증 정책(허용 확장자·최대 크기)은 계획서에서 사용자 확인.

### ③ 공개 공지 페이지 (첫 비관리자 화면) — ✅ 완료 (2026-07-28 · `7ab80a5` #21)
- **유형**: 기능 추가 / **선정 이유**: "관리 화면만 있는 CMS"에서 "콘텐츠를 내보내는 CMS"로 — 2단계 정체성의 완성이자 3단계(⑤ 실배포)의 배포 대상을 만든다.
- **완료 근거**: `com.cms.publicweb.notice`(`PublicNoticeController`·`PublicNoticeService`·`PublicNoticeSummary`/`PublicNoticeDetail`) + `com.cms.publicweb.support.PublicWebExceptionAdvice` origin/master에 실존 확인. `SecurityConfig.java`에 `/notices`·`/notices/**` GET/HEAD `permitAll` + 나머지 `denyAll` 명시 확인(diff 실측). `NoticeRepository`에 `findByDeletedFalseAndUseYnTrue`·`findByIdAndDeletedFalseAndUseYnTrue` 파생 쿼리 확인. `PublicNoticeControllerTest`·`PublicNoticeServiceTest`·`PublicNoticeTemplateConventionTest`·`SecurityConfigTest`/`NoticeRepositoryDataJpaTest` 추가분 존재 확인. Playwright 실기 검증 기록: 비로그인 목록·상세 열람, 미노출·삭제·비숫자·없는 ID 전부 404(JSON 아님), `page` malformed 값 0 보정, XSS payload(`<script>`/`onerror`) 텍스트로만 렌더링(실행 안 됨), 404 홈 링크 정상화, 관리 화면(대시보드·공지 관리·회원 관리) 회귀 없음 확인. `./gradlew test` 로컬 전체 통과 + PR #21 GitHub Actions CI(`test`) `pass`(1m15s, run 30354208868) 확인. 적대적 리뷰 4라운드(ship) 거침. 스키마 변경 없음(Flyway 최대 버전 V10 유지).
- **실행 원본**: [`plan/PLAN-public-notice.md`](plan/PLAN-public-notice.md) (4라운드 적대적 리뷰 후 ship, 구현·검증 결과 기록됨)
- **목표**: 비로그인 사용자가 노출(`useYn`) 상태의 공지 목록·상세를 볼 수 있는 공개 Thymeleaf 페이지를 추가한다. 기존 로드맵의 "공개 프론트 vs headless" 갈림길에서 **공개 프론트(Thymeleaf)** 방안을 실행하는 것.
- **수정해야 할 정확한 파일** (실측 기준):
    - 신규: `src/main/java/com/cms/publicweb/` (신규 패키지 — 관리자 `admin` 패키지와 분리) 공개 페이지 컨트롤러 (`@AdminPage` 부착 금지 — `AdminPageAnnotationConventionTest`·`AdminSidebarAdvice` 범위는 admin 패키지 한정)
    - 신규: `src/main/resources/templates/public/` 하위 공지 목록·상세 템플릿 (admin 프래그먼트 재사용 불가 — 공개용 최소 레이아웃 신규)
    - 수정: `src/main/java/com/cms/config/SecurityConfig.java` — 공개 경로 추가 (**인가 정책 변경 → 사용자 승인 필수**; 현재 `anyRequest().permitAll()`이라 실제로는 `/admin/**` 밖 경로 추가 시 코드 변경이 불필요할 수 있음 — 계획서에서 실측 확정)
    - 신규 테스트: 공개 컨트롤러 MockMvc 테스트 (비인증 접근 200, 미노출 공지 404)
- **단계별 작업 순서**: `/plan-review-loop` 계획 확정(공개 URL 구조·인가 정책 승인 포함) → 공개 조회 Service 메서드(노출 필터 강제) → 페이지 컨트롤러 → 템플릿 → 테스트 → playwright 검증
- **완료 기준**: `./gradlew test` 통과 / 비로그인 브라우저로 공지 목록·상세 열람 playwright 확인 / 미노출·소프트 삭제 공지 404 / 관리 화면 회귀 없음 / XSS 방어(본문 이스케이프 정책) 테스트 통과
- **착수 게이트**: **① 완료 필수** + 공개 경로 신설 = **인가 정책 협의 필수** (CLAUDE.md 보안 규칙).

### ④ Testcontainers 전환 — 테스트 DB 격리 — ✅ 완료 (2026-07-27 · `04b8121` #20)
- **유형**: 테스트·인프라 / **선정 이유**: 테스트 파일이 43개(동시성·통합 테스트 다수)로 늘어 "⑤ 이후가 적기"라던 기존 유보 사유가 이미 해소됨. 로컬 MariaDB(3307) 기동 의존과 CI service container 이중 구성을 단일화한다.
- **완료 근거**: `src/test/java/com/cms/support/MariaDbContainerSupport.java`(`@ServiceConnection` + static 싱글턴) 신규 확인, DB 접속 테스트 14개 전부 `extends MariaDbContainerSupport` 전환 확인. `build.gradle`에 `spring-boot-testcontainers`·`org.testcontainers:mariadb` 의존성 + `maxParallelForks=1`·`forkEvery=0`·`testLogging.showStandardStreams=true` 설정 확인. `.github/workflows/ci.yml`에서 MariaDB service container + DB/MAIL env 전부 제거 확인(diff 실측). `adversarial-review/plan/PLAN-testcontainers.md` 구현·검증 결과 기록(로컬 DB 내린 상태 연속 2회 통과·서로 다른 컨테이너 ID·Ryuk 30초 이내 정리·지연 시작(단위 테스트 단독 실행 시 컨테이너 미기동) 확인, 성능 중앙값 37초→50초(1.35배, 2배 이내), 474개 테스트 누락 없음, `bootRun` 무회귀). PR #20 GitHub Actions CI 실행(`run 30267775992`) `success`(1m38s) 확인 — 계획서 후속 항목이던 "CI 실제 통과"까지 사실확인 완료.
- **실행 원본**: [`plan/PLAN-testcontainers.md`](plan/PLAN-testcontainers.md) (5라운드 적대적 리뷰 후 ship, 구현·검증 결과 기록됨)
- **목표**: 테스트가 로컬 MariaDB 기동·환경변수 주입 없이 `./gradlew test` 단독으로 도는 상태. CI/로컬 환경 차이(포트 점유·시드 오염 함정 포함)가 사라진다.
- **수정해야 할 정확한 파일** (실측 기준):
    - 수정: `build.gradle` (`org.testcontainers:mariadb`·`junit-jupiter` 테스트 의존성 추가 — 신규 의존성이므로 착수 시 제안·승인)
    - 신규: `src/test/resources/` (현재 디렉터리 자체가 없음 — 테스트 전용 설정) + `src/main/java/com/cms/support/` 아님 → 실측 위치는 `src/test/java/com/cms/support/`에 Testcontainers 설정 클래스 추가 (`CmsTestApplication.java`가 이미 있는 패키지)
    - 수정: `.github/workflows/ci.yml` (MariaDB service container 제거 또는 유지 여부 — 계획서에서 결정)
    - 수정: `CLAUDE.md`·`docs/development-workflow.md` (테스트 실행 전제 변경 반영)
- **단계별 작업 순서**: `/plan-review-loop` 계획 확정 → 의존성 추가 승인 → 컨테이너 설정(재사용 전략 포함) → 기존 통합·동시성 테스트 전 클래스 전환 → CI 워크플로 정리 → 문서 갱신
- **완료 기준**: 로컬 MariaDB를 **내린 상태**에서 `./gradlew test` 전체 통과 / CI 통과 / 테스트 소요 시간이 기존 대비 과도하게(예: 2배 이상) 늘지 않음
- **착수 게이트**: 신규 의존성 추가 → 사용자 승인 (CLAUDE.md 주의사항). ①~③과 독립이라 언제든 끼워 넣기 가능.

### ⑤ prod 프로파일 부활 + 배포 준비 (3단계 개시) — ✅ 완료 (2026-07-30 · `a51d29d` #23)
- **유형**: 인프라·보안 / **선정 이유**: ①·③으로 "배포할 대상"이 생기는 시점에 맞춰 준비 — "배포 가능한 master"라는 GitHub Flow 원칙을 실배포로 증명하는 마지막 조각.
- **완료 근거**: 2026-07-29 재선정 Top 3의 ①번으로 승계되어 실행됨 — 완료 근거는 하단 "실행 로드맵 — Top 3 (2026-07-29 선정)" ①번 참조.
- **실행 원본**: 없음 — 착수 시 `/plan-review-loop`로 계획서 작성·검증.
- **목표**: `prod` 프로파일로 기동하면 Swagger 비활성·시크릿 전부 환경변수 주입·안전한 로깅 수준이 보장되는 상태. 실배포(호스트 선정·도메인)는 별도 사용자 결정 사안으로 분리한다.
- **수정해야 할 정확한 파일** (실측 기준):
    - 신규: `src/main/resources/application-prod.yml` (a8ffb9a #3에서 의도적으로 제거됐던 것의 재설계 — springdoc 비활성화, Flyway·`validate` 유지, 시크릿 placeholder)
    - 수정: `src/main/resources/application.yml` (프로파일 공통/분리 재정리 필요 시), `Dockerfile` (실측 존재 — JDK 17 기반, prod 기동 인자 점검), `Makefile`·`docker-compose.dev.yml` (prod용 compose 신규 여부는 계획서에서 결정)
    - 수정: `docs/development-workflow.md` 또는 신규 배포 문서
- **단계별 작업 순서**: `/plan-review-loop` 계획 확정(prod에서 잠글 항목 목록 승인) → application-prod.yml 작성 → Docker 빌드·기동 검증 → `/deploy-check` 스킬로 전수 점검 → 문서화
- **완료 기준**: `SPRING_PROFILES_ACTIVE=prod`로 Docker 기동 성공 / Swagger UI 404 확인 / 시크릿 하드코딩 0건(코드·yml 검사) / `/actuator/health` 공개·그 외 actuator 비노출 확인 / `/deploy-check` ship 판정
- **착수 게이트**: **①(최소) 완료 후 권장** — 배포할 콘텐츠가 있어야 의미. prod 보안 잠금 항목은 인가 정책에 준해 사용자 협의.

### 선정에서 탈락한 후보 (다음 갱신 때 재평가)

- **비밀번호 재설정 공개 API의 IP 기반 rate limit**: 계정별 60초 쿨다운·열거 방지는 이미 있음 — 실배포(⑤) 이후 노출 환경이 생기면 재평가.
- **메뉴 3단계·accessRole 인가 연동 / QueryDSL 포크(OpenFeign) 전환 / Java 21 + Boot 4.x**: 기존 "지속 항목" 판단 유지 — 콘텐츠 도메인 확장·Boot 4.x 이행 시점에 재평가.
- **docs/migration/ 레거시 수동 SQL 3개 정리**: Flyway 이관 완료로 역할 종료 — 이력 가치가 있어 보존, 별도 작업 불필요 판단.
- **공개 공지 목록 검색**: `PLAN-public-notice.md`에서 의도적으로 범위 제외(목록+페이징+상세만). 관리 화면 대비 사용 빈도가 낮을 것으로 추정되는 공개 화면 기능이라 이번 갱신에서는 후순위 — 다음 갱신 때 재평가.

## 실행 로드맵 — Top 3 (2026-07-29 선정)

> 선정 근거: `/createRoadmap` 전면 재분석. 열린 이슈/PR 0건(`gh issue list --state all`, `gh pr list`), 소스 TODO/FIXME 0건(vendor 제외), 비대 클래스 없음(최대 `AdminMemberService` 344줄, 임계 아님), 테스트 51개 클래스로 커버리지 공백 없음.
> 2026-07-20 Top 5 중 ①~④ 전부 완료, **⑤(prod 프로파일 부활)만 미착수 승계** — 아래 Top 3의 ①번으로 재편.
> 신규 발굴 2건: ②(공개 첨부파일 노출)은 `PLAN-public-notice.md`가 의도적으로 범위 제외했던 gap, ③(프로필 이미지 이관)은 기존 로드맵에서 "②(FileStorage) 완료 후 재평가"로 미뤄뒀던 후보가 선행 조건 해소로 승격된 것.
> 공통 규칙(브랜치·Flyway 번호 확인·CSRF·1계획=1PR)은 [`plan/README.md`](plan/README.md) 참조.

### ① prod 프로파일 부활 + 배포 준비 (3단계 개시) — ✅ 완료 (2026-07-30 · `a51d29d` #23)
- **유형**: 인프라·보안 / **선정 이유**: 2026-07-20 Top 5 ⑤ 승계. ①②③(콘텐츠 도메인·공개 페이지)이 모두 완료되어 "배포할 대상"이 이미 확보된 상태 — "배포 가능한 master"라는 GitHub Flow 원칙을 실배포로 증명하는 마지막 조각이며 3단계의 유일한 개시 조건.
- **완료 근거**: `AdminBootstrapLoader`/`AdminBootstrapCredentials`(초기 관리자 환경변수 부트스트랩, `uk_member_user_id` 유니크 제약으로 동시성 직렬화) + `ProfileGuardEnvironmentPostProcessor`(dev+prod 동시 활성화·활성 프로파일 0개 컨텍스트 생성 전 차단, `META-INF/spring.factories` 등록) + `SecurityConfig`/`application.yml`(`/actuator/health`만 무인증 공개, 설정+Security 이중 차단) + `application-prod.yml`(springdoc 비활성) 전부 코드 열람으로 실존 확인. `spring.profiles.active` 기본값 제거로 프로파일 미지정 기동 fail-fast 실측. 신규 테스트 `AdminBootstrapLoaderTest`·`AdminBootstrapConcurrencyIntegrationTest`(실 DB 동시성 경합 재현)·`ProfileGuardEnvironmentPostProcessorTest`·`ActuatorExposureTest`(Testcontainers 기반 실제 등록 엔드포인트 `{health}` 단일 확인) 전부 통과. `docker-compose.prod.yml` + `scripts/prod-up.sh`(health 폴링 + RestartCount 안정성 재확인)로 Docker 실기 검증: 빈 DB+부트스트랩 변수 기동 성공(특수문자 비밀번호 포함 로그인 확인)·재기동 무중복·ACTIVE ADMIN 없음+변수 없음 fail-fast·시크릿 컨테이너 간 격리(`docker inspect`)·actuator 이중 방어 전부 실측(`PLAN-prod-profile.md` "구현·검증 결과" 섹션). `./gradlew test` 523개 전체 통과. `/code-review-loop` 3라운드(codex 적대적 리뷰) 거쳐 환경변수 가드 누락·health 성공 판정의 CommandLineRunner 실패 은폐 가능성·RestartCount 오탐 가능성 반영. `/deploy-check` 실행(`adversarial-review/deploy-check-2026-07-30.md`) — 차단(no-ship) 0건. PR #23 머지 확인(`gh pr view 23` state=MERGED). **완료 기준 중 2건은 문자 그대로 미충족**(Swagger UI가 404 아닌 500 반환, `/deploy-check` 판정이 "ship"이 아닌 "needs-attention") — 둘 다 이번 PR이 만든 결함이 아니라 사전 발견된 기존 결함이며 사용자와 협의해 범위 밖으로 확정, 후속 과제로 기록(하단 "후속 과제 — ① 완료 시 발견" 참조).
- **실행 원본**: [`plan/PLAN-prod-profile.md`](plan/PLAN-prod-profile.md) (codex 적대적 리뷰 7라운드 ship, 구현·검증 결과 기록됨)
- **목표**: `prod` 프로파일로 기동하면 Swagger 비활성·시크릿 전부 환경변수 주입·안전한 로깅 수준이 보장되는 상태. 실배포(호스트 선정·도메인)는 별도 사용자 결정 사안으로 분리한다.
- **수정해야 할 정확한 파일** (실측 기준, 2026-07-29 재확인):
    - 신규: `src/main/resources/application-prod.yml` (`application.yml`·`application-dev.yml`만 현존 확인 — prod 프로파일 파일 자체가 없음. springdoc 비활성화, Flyway `validate` 유지, 시크릿 placeholder)
    - 수정: `src/main/resources/application.yml` (프로파일 공통/분리 재정리 필요 시)
    - 수정: `Dockerfile` (실측 존재 — JDK 17 gradle 멀티스테이지 빌드, `appuser` 비루트 전환·첨부파일 디렉터리 소유권 설정까지 이미 반영됨 — prod 기동 인자·JVM 옵션 점검)
    - 수정: `docker-compose.dev.yml`(실측 존재)·`Makefile`(실측 존재) — prod용 compose 신규 여부는 계획서에서 결정
    - 신규: `docs/development-workflow.md` 또는 신규 배포 문서
- **단계별 작업 순서**: `/plan-review-loop` 계획 확정(prod에서 잠글 항목 목록 승인) → `application-prod.yml` 작성 → Docker 빌드·기동 검증 → `/deploy-check` 스킬로 전수 점검 → 문서화
- **완료 기준**: `SPRING_PROFILES_ACTIVE=prod`로 Docker 기동 성공 / Swagger UI 404 확인 / 시크릿 하드코딩 0건(코드·yml 검사) / `/actuator/health` 공개·그 외 actuator 비노출 확인 / `/deploy-check` ship 판정
- **착수 게이트**: 신규 파일이라 계획서 승인 시 prod 보안 잠금 항목(actuator 노출 범위 등)을 인가 정책에 준해 사용자와 확인.

### ② 공개 공지 상세에 첨부파일 다운로드 노출 — ✅ 완료 (2026-08-03 · `10c28ff` #25)
- **유형**: 기능 추가 / **선정 이유**: `PLAN-public-notice.md`(v1 결정, 2026-07-28)가 "이번 범위 제외(본문만 공개)"로 명시적으로 미룬 항목. `NoticeAttachment`·`FileStorage` 인프라가 이미 있어 구현 범위가 작고, 공개 페이지의 콘텐츠 완성도를 바로 높인다.
- **완료 근거**: `PublicNoticeController.java:87` `@GetMapping("/{id}/attachments/{attachmentId}")` + `PublicNoticeService.java:71` `downloadPublishedAttachment()` 실존 확인. `PublicNoticeAttachmentIntegrationTest`(Testcontainers 실 DB)가 성공·TOCTOU(별도 트랜잭션 커밋 후 재요청 empty)·IDOR(`findByIdAndNoticeId` 복합 조건)·파일없음(`StorageFileNotFoundException` fail-closed) 4개 시나리오를 독립 테스트로 검증. `SecurityConfigTest`에 `/notices/{id}/attachments/{attachmentId}` GET 200·HEAD 200·POST(denyAll) 302 인가 회귀 3건 추가 확인. `PublicNoticeAttachment` DTO에 `storageKey` 필드 자체 부재, 응답에 `Cache-Control: no-store` 적용 확인. `NoticeAttachmentServiceTest`에 CR/LF 파일명 업로드 케이스 추가 확인. Playwright 실기 검증: 골든 패스(목록 렌더→200 다운로드→바이트 일치), 관리자 비노출 전환 후 같은 URL 재요청 시 첨부·상세 모두 404, 서로 다른 두 공지 조합 IDOR 404, 비숫자 id·존재하지 않는 id 전부 404(HTML), 관리 화면(대시보드·회원 관리·공지 관리) 회귀 없음 스크린샷 확인. 스키마 변경 없음(Flyway 최대 버전 V10 유지). `./gradlew test` 전체 통과(56개 테스트 클래스), PR #25 `state=MERGED`(2026-08-03) + GitHub Actions `test` **pass**(1m15s, run 30787387345) 확인. `plan-review-loop` 4라운드(ship) + `code-review-loop` 1라운드(지적 0건) 거침.
- **실행 원본**: [`plan/PLAN-public-notice-attachment.md`](plan/PLAN-public-notice-attachment.md) (적대적 리뷰 4라운드 ship, 구현·검증 결과 기록됨)
- **목표**: 비로그인 사용자가 공지 상세(`/notices/{id}`)에서 `useYn=true && deleted=false`인 공지에 첨부된 파일을 목록으로 보고 다운로드할 수 있다. 소프트 삭제·비노출 전환된 공지의 첨부는 여전히 접근 불가(다운로드 시점 재검증 — 목록 조회 이후 상태가 바뀌는 TOCTOU 방지).
- **수정해야 할 정확한 파일** (실측 기준):
    - 신규 또는 수정: `src/main/java/com/cms/publicweb/notice/service/PublicNoticeService.java`(66줄, 실측) — 첨부 목록 조립 메서드 추가, 또는 별도 `PublicNoticeAttachmentService` 신규(다운로드 시점 notice 공개 조건 재검증 책임 분리 목적, 계획서에서 결정)
    - 수정: `src/main/java/com/cms/publicweb/notice/dto/PublicNoticeDetail.java`(36줄, 실측) — 첨부 목록 필드(파일명·크기·id) 추가. `authorId` 미노출 원칙과 동일하게 서버 내부 경로는 노출 금지
    - 수정: `src/main/java/com/cms/publicweb/notice/controller/PublicNoticeController.java`(86줄, 실측) — 다운로드 라우트 추가(`/notices/{id}/attachments/{attachmentId}` 등, 기존 `id`/`page` String 수동 파싱 관례 유지). `SecurityConfig`의 `/notices/**` GET `permitAll`이 하위 경로까지 이미 포괄하는지 계획 단계에서 재확인(패턴 매칭 실측 필요 — 다르면 `SecurityConfig.java` 수정도 인가 정책 변경으로 승인 필요)
    - 재사용(수정 없음 원칙): `src/main/java/com/cms/admin/notice/repository/NoticeAttachmentRepository.java`(`findByNoticeIdOrderByIdAsc`·`findByIdAndNoticeId` 이미 존재), `src/main/java/com/cms/common/storage/FileStorage.java`
    - 수정: `src/main/resources/templates/public/notice/detail.html` — 첨부 목록·다운로드 링크 UI
    - 신규 테스트: `PublicNoticeController`/`PublicNoticeService` 첨부 관련 케이스(비노출·삭제 전환 후 다운로드 404, 다른 notice의 attachmentId IDOR 차단, 정상 다운로드)
- **단계별 작업 순서**: `/plan-review-loop` 계획 확정(다운로드 시점 재검증 설계·`SecurityConfig` 영향 범위 확정) → Service 계층(공개 조건 재검증 포함) → DTO → 컨트롤러 라우트 → 템플릿 → 테스트 → playwright 검증
- **완료 기준**: `./gradlew test` 통과 / 비로그인으로 공개 공지 상세에서 첨부 다운로드 골든 패스 playwright 확인 / 비노출·소프트 삭제 공지의 첨부 다운로드 404 확인(목록 조회 후 상태 전환 시나리오 포함) / 다른 notice의 attachmentId로 접근 시 404(IDOR 차단) / 관리 화면(첨부 CRUD) 회귀 없음
- **착수 게이트**: 없음(신규 의존성·스키마 변경 없음, 기존 승인된 `/notices/**` 공개 정책 범위 내 — 단, 계획 단계에서 `SecurityConfig` 수정이 실제로 필요하다고 판명되면 인가 정책 변경으로 재승인 필요).

### ③ 프로필 이미지 Base64-in-DB → FileStorage 이관 — ✅ 완료 (2026-08-11 · `e3175a9` #28)
- **유형**: 리팩토링 / **선정 이유**: 2026-07-20 로드맵에서 "②(파일 스토리지)의 `FileStorage` 인터페이스가 생긴 뒤 별도 마이그레이션 계획으로 다루는 게 안전"이라며 탈락시켰던 후보 — 그 선행 조건(`FileStorage`)이 이미 완료됐다. CLAUDE.md가 명시하는 "대용량 Base64 데이터가 API 응답에 포함될 수 있다"는 주의사항을 해소한다.
- **완료 근거**: `member.profile_image_kind`(enum, `V11__add_member_profile_image_kind.sql`) + `Member` 도메인 메서드 4종 분리(`changeUploadedProfileImage`/`changePresetProfileImage`/`resetProfileImage`/`migrateProfileImageToStorage`) 실존 확인. `FileStorage`/`LocalDiskFileStorage`에 네임스페이스 인자 오버로드 3종(하위 호환 default 메서드) 추가 확인, `"profile"` 네임스페이스로 공지 첨부파일과 물리적으로 분리된 디렉터리 저장 확인. `ProfileImageValidator`(화이트리스트 png/jpeg/gif, 헤더 선검사 기반 decompression bomb 방어, 애니메이션 거부, MIME-포맷 일치 검증, 신규 의존성 없음) 확인. 다운로드 라우트 `GET /admin/api/members/me/profile-image`·`/{id}/profile-image` 실존 확인(`AdminMemberController`). `ProfileImageMigrationRunner`(1회성 이관, 행별 트랜잭션+비관적 락 재검증, 크기 초과 조건부 벌크 UPDATE로 즉시 격리, `kind` 조건 자체로 멱등성 보장) 확인. 신규 테스트 4클래스(`ProfileImageUrlsTest`·`ProfileImageValidatorTest`·`ProfileImageMigrationRunnerTest`·`MemberProfileImageInsertIntegrationTest`) + 기존 3클래스 확장(`AdminMemberControllerTest`·`AdminMemberServiceTest`·`LocalDiskFileStorageTest`) 전부 통과 확인, `./gradlew test` 전체 통과 재확인(Docker Desktop 데몬 기동 후). `/code-review-loop` 1라운드(codex 리뷰 지적 사항 0건) 거침. PR #28 머지 확인(`gh pr view 28` state=MERGED, mergedAt=2026-08-11).
  - **완료 기준 중 2건은 부분 충족**(문자 그대로 완전 충족 아님, ①(prod 프로파일)과 동일한 패턴 — 사용자 협의 후 완료 처리, 잔여 항목은 하단 "후속 과제 — ③ 프로필 이미지 이관 완료 시 발견" 참조): (1) "기존 회원의 프로필 이미지가 이관 후에도 정상 표시 확인" — dev DB에 이관 대상 레거시 행이 0건이라 "0건 이관"만 Playwright로 확인, 실 레거시 데이터로 "이관 후 정상 표시"까지 이어지는 골든 패스는 미확인. (2) "이관 실패 시 롤백 경로 검증" — `ProfileImageMigrationRunnerTest`(Mockito 단위)로 스킵·벌크초기화 경로만 검증, 실 트랜잭션 커밋/롤백 + 동시 러너 실행을 검증하는 Testcontainers 통합 테스트는 계획에는 있었으나 시간 제약으로 미작성.
- **목표**: 프로필 이미지가 DB `LONGTEXT` 컬럼의 Base64 데이터 URI 대신 `FileStorage`(로컬 디스크)에 실파일로 저장되고, 회원 조회 API 응답에는 다운로드 URL만 포함된다. **기존 데이터 이관(마이그레이션)이 핵심 리스크** — 계획 단계에서 반드시 전략(배치 1회성 이관 vs 무중단 lazy 이관 vs 재업로드 유도)을 확정해야 한다.
- **수정해야 할 정확한 파일** (실측 기준):
    - 수정: `src/main/java/com/cms/admin/member/service/AdminMemberService.java`(344줄, 실측) — `updateMyProfileImage`(252행 Base64 인코딩)·`resetMyProfileImage`·`applyDefaultProfileImage` 메서드가 `FileStorage`를 사용하도록 전환
    - 수정: `src/main/java/com/cms/admin/member/domain/Member.java`(195줄, 실측) — `profileImageUrl` 필드 의미가 "Base64 data URI 또는 정적 프리셋 경로"에서 "저장 경로 또는 프리셋 경로"로 변경(기본 프리셋 4종은 그대로 정적 경로 유지 — 변경 범위 아님)
    - 신규: 프로필 이미지 다운로드 라우트 — `NoticeAttachmentController`의 다운로드 패턴(`application/octet-stream`+`attachment`+`nosniff`) 재사용 검토
    - 신규: `src/main/resources/db/migration/V11__migrate_profile_image_to_file_storage.sql` 또는 애플리케이션 레벨 1회성 이관 로직(SQL만으로는 Base64→파일 변환 불가 — 계획서에서 방식 확정)
    - 수정: `src/main/resources/templates/admin/member/*.html` — 프로필 이미지 `<img src>`가 data URI가 아닌 다운로드 URL을 참조하도록 변경 여부 확인
    - 신규 테스트: 이관 로직 테스트, `AdminMemberServiceTest`/`AdminMemberControllerTest` 회귀
- **단계별 작업 순서**: `/plan-review-loop` 계획 확정(이관 전략·롤백 계획 필수 포함) → `FileStorage` 기반 저장/서빙 경로 구현 → 기존 데이터 이관 → Service 전환 → 화면 반영 → 테스트 → playwright 검증
- **완료 기준**: `./gradlew test` 통과 / 신규 업로드·프리셋 선택·초기화 골든 패스 playwright 확인 / 기존 회원의 프로필 이미지가 이관 후에도 정상 표시 확인 / 회원 목록/상세 API 응답 페이로드 크기 감소 확인 / 이관 실패 시 롤백 경로 검증
- **착수 게이트**: **기존 데이터 이관 수반 → 이관 전략 및 실행 시점(다운타임 여부) 사용자 승인 필수**. ①·②와 독립적으로 착수 가능.

## 후속 과제 — ① prod 프로파일 완료 시 발견 (2026-07-30 기록)

> ①(prod 프로파일 부활, `a51d29d` #23) 완료 검증 중 발견해 **이번 PR 범위 밖으로 확정**한 항목. 전부 이번 PR이 새로 만든 문제가 아니라 기존에 있던 결함이거나 애초에 별도 범위로 분리돼 있던 사안이다. 다음 로드맵 갱신 때 별도 작업으로 재평가.

- ~~**핸들러 없는 경로가 404 대신 500 반환**~~ → **해소됨** (`7c64307` #26, 2026-08-07): `GlobalApiExceptionHandler`에 `NoResourceFoundException`·`NoHandlerFoundException` 전용 핸들러 추가 완료. `/admin/api/**`는 JSON 404(`RESOURCE_NOT_FOUND`), 그 외는 기존 `error/404.html`(`/admin/**` 하위는 `error/admin/404.html`) 재사용. 부수적으로 `/admin/api/**` 판정 매처(`SecurityConfig`·`AdminSessionExpiredStrategy`·신규 핸들러 3곳에 중복 존재하던 것)를 `GlobalApiExceptionHandler.API_MATCHER` 한 곳으로 단일화하고, `CustomErrorController`의 admin 접두사 오분류 기존 결함(`/administrator/missing` 등)도 `PathPattern` 기반 판정으로 함께 해결. `./gradlew test` 577개 전체 통과, `plan-review-loop` 4라운드(ship) + `code-review-loop` 1라운드(지적 0건) 거침, Playwright 실기 검증으로 재현 3사례(`/admin/logout` GET·`/favicon.ico`·prod swagger-ui) 중 dev 프로파일 기준 2건 해소 확인(prod 자체 실기 확인은 `.env.prod` 미비로 미시도, 동일 코드 경로라 프로파일 무관하게 적용될 것으로 예상). 리뷰 중 Spring Security의 기본 `StrictHttpFirewall`이 세미콜론 포함 경로를 필터 최상단에서 이미 차단한다는 사실을 실측 발견 — 상세는 `PLAN-not-found-handling.md` "구현·검증 결과" 참조. PR #26 머지 확인(`gh pr view 26` state=MERGED), CI success 확인.
- **실배포 인프라(nginx 리버스 프록시·TLS 인증서·실제 호스팅·CD 파이프라인)**: 현재 `docker-compose.prod.yml`은 `127.0.0.1:8080` 루프백 바인딩까지만 다루며 그대로 인터넷에 노출하면 안 된다. 로드맵 3단계에 이미 "별도 사용자 결정 사안"으로 명시된 범위 — 호스트·도메인이 정해지면 착수.
- **`forward-headers-strategy`·secure/SameSite 쿠키**: 리버스 프록시 뒤에서 `X-Forwarded-*` 헤더를 신뢰하려면 필요. 위 리버스 프록시 도입과 함께 다룰 사안이라 별도 선행 착수 불필요.
- ~~**DB 백업 전략**: named volume 보존만으로는 백업이 아니다 — 실배포 전 별도 수립 필요.~~ → **해소됨** (`95f264f` #30, 2026-08-12): `scripts/prod-backup.sh`(DB 논리 덤프 + 첨부/프로필 파일 볼륨 tar 백업, `gzip -t`+`tar tzf`+`sha256sum -c` 즉시 무결성 검증, `BACKUP_RETENTION_DAYS` 자동 정리, `mkdir` 기반 잠금)·`scripts/prod-restore.sh`(백업 무결성 + 대상 DB 이중 검증 → 대화형 확인 → 복구 전 안전 백업 자동 생성 → 볼륨 여유 공간 확인 → DB/파일 복구 → 재기동 → health/`RestartCount` 이중 안정성 재확인) 신규 확인. `Dockerfile`의 `appuser` UID·GID 10001 고정(`tar --numeric-owner` 정합성) 확인. `Makefile`에 `make prod-backup` 타깃 확인, `.gitignore`/`.dockerignore`에 백업 산출물(`/backups`) 제외 확인. `docs/deployment.md`에 백업/복구 절차·환경변수·범위 한계·재해복구·볼륨 UID 이관 절차 문서화 확인. `adversarial-review/plan/PLAN-db-backup.md` 완료 기준 21개 전 항목 실기 검증(`[x]`) 확인 — Docker Desktop+prod 스택+Playwright 골든 패스(공지 생성→백업→삭제→복구→재노출, 첨부·프로필 이미지 sha256 바이트 동일성), 오환경 복구 방지 이중 검증(manifest DB명 불일치·SQL `USE` 문 불일치 각각 fixture로 독립 차단 확인), 재해복구(빈 볼륨)·정지 상태 백업·보존 기간 정리·잠금 동시성까지 실기 확인. 구현 단계에서 텍스트 리뷰로는 못 잡은 실제 버그 4건(EXIT 트랩 종료 코드 덮어쓰기로 복구가 매번 실패로 오판되던 치명적 결함 포함) 발견·수정 기록. `/code-review-loop` 5라운드(수용 5건: 기존 볼륨 UID 이관 문서화·복구 안정성 재확인 health curl 추가·`.dockerignore` 백업 산출물 누락·이관 명령 `MSYS_NO_PATHCONV` 누락+시점 정합성 문서 보완·백업 디렉터리 충돌 시 데이터 유실 결함 수정) 거침. `./gradlew test` 전체 통과(Java 코드 무변경). PR #30 머지 확인(`gh pr view 30` state=MERGED, mergedAt=2026-08-12). **범위 밖으로 명시 수용**: 오프사이트 백업(같은 호스트 디스크만 보호, 디스크 전체 손실 미방어) — 바로 아래 신규 항목으로 별도 기록.
- **오프사이트 백업 미포함(2026-08-12 기록)**: `scripts/prod-backup.sh`는 백업 산출물을 prod 호스트 로컬 디스크(`${BACKUP_DIR:-./backups}`)에만 남긴다. 같은 호스트 디스크 전체가 손실되면(디스크 고장, 볼륨 삭제와 무관한 물리적 사고 등) 백업 자체도 함께 사라져 복구 수단이 없다 — `PLAN-db-backup.md`가 "범위" 절에서 처음부터 "논리적 오삭제·볼륨 오염으로부터의 로컬 롤백"만 목표로 명시하고 오프사이트 보관을 후속 과제로 분리해뒀다(설계 결함이 아니라 명시적으로 좁힌 범위). 로드맵 3단계 "실배포 인프라" 항목과 마찬가지로 호스트·도메인이 정해져 실배포가 시작되면 원격 저장소(S3 호환 오브젝트 스토리지, 별도 호스트로의 정기 동기화 등)로의 반출 방안을 재평가한다.
- **동시 부트스트랩 시 서로 다른 `ADMIN_BOOTSTRAP_*` 값 주입**: 서로 다른 컨테이너에 서로 다른 자격증명이 동시에 주입되면 관리자 계정이 두 개 생길 수 있다(`uk_member_user_id` 유니크 제약은 "같은" 자격증명 경합만 직렬화). "배포 파이프라인이 모든 인스턴스에 동일 설정을 배포한다"는 일반적인 전제를 벗어난 상황이라 `PLAN-prod-profile.md` 결정 4에서 위험 수용으로 남긴 설계 제약 — 현재 `docker-compose.prod.yml`은 고정 컨테이너명 단일 인스턴스라 애초에 재현 불가하나, 향후 다중 인스턴스 배포(K8s 등)로 전환 시 재평가 필요.
- **미확인(외부 의존, 재확인 필요)**: 실제 SMTP 자격증명으로 비밀번호 재설정 메일이 prod에서 정상 발송되는지(더미 값으로 기동만 확인) / `mariadb:10.11` 이미지의 `healthcheck.sh --connect --innodb_initialized`가 이미지 태그 업데이트 후에도 계속 존재하는지(배포 전 `docker pull` 후 재확인 필요) / prod 프로파일 자체의 로그인→관리 화면 브라우저 골든 패스(이번엔 curl·docker inspect로만 확인, dev 프로파일 기준으로만 Playwright 회귀 확인함).

## 후속 과제 — ③ 프로필 이미지 이관 완료 시 발견 (2026-08-11 기록)

> ③(프로필 이미지 Base64-in-DB → FileStorage 이관, `e3175a9` #28) 완료 검증 중 로드맵 완료 기준을 문자 그대로는 충족하지 못한 것으로 확인된 2건. `adversarial-review/plan/PLAN-profile-image-storage.md`가 "구현·검증 결과" 섹션에서 스스로 후속 과제로 명시한 항목이며, 사용자와 협의해 완료 처리와 함께 여기 별도 기록하기로 확정했다.

- **실 레거시 데이터가 있는 DB에서의 이관 Playwright 골든 패스 미확인**: 검증 시점 dev DB에 마이그레이션 대상 `data:` URI 레거시 행이 이미 0건이라(이전 세션에 정리됨) "처리 대상=0, 이관=0"만 실기 확인했다. 이관 로직 자체(Base64 디코딩→저장→바이트 동일성, 화이트리스트 밖 MIME 스킵, 크기 초과 벌크 초기화)는 `ProfileImageMigrationRunnerTest`(Mockito 단위)·`ProfileImageMigrationRunnerIntegrationTest`(Testcontainers 실 DB)로 검증됨. 실제 레거시 데이터를 가진 DB에서 "이관 실행 → 브라우저에 이미지가 정상 표시"까지 이어지는 골든 패스(Playwright)만 여전히 별도로 재현·확인 필요 — 이 항목은 미해소로 남는다.
- ~~**`ProfileImageMigrationRunnerIntegrationTest`(Testcontainers) 미작성**~~ → **해소됨** (2026-08-12 · `ca6446f` #29, PR MERGED·CI test pass 사실확인): 실 트랜잭션 커밋/롤백 + `findByIdForUpdate` 락 결정적 증명(`innodb_lock_wait_timeout` 세션 변수 기법) + 동시 러너 실행 시 중복 이관 방지(보조 검증, `CyclicBarrier` + Mockito) 테스트 4개 추가. `master` 반영 후 재확인: `./gradlew test --tests "com.cms.admin.member.ProfileImageMigrationRunnerIntegrationTest"` 4개 전부 통과(동시 실행 테스트 로그에서 두 스레드 중 정확히 하나만 이관, 다른 하나는 스킵 처리됨을 실측 확인 — 락이 실제로 중복 이관을 막음). `/plan-review-loop` 5라운드(스킬 최대 라운드, `PLAN-profile-image-storage.md` "후속 작업 계획" 섹션 v1~v6) + `/code-review-loop` 5라운드(수용 5건) 거침 — 컨텍스트 기동 시 러너 자동 실행이 완전한 사전 격리를 보장하지 못하는 잔여 위험 1건은 사용자 협의 후 현재 수준(대상 집합 정확히 일치 어서션 + 클래스 단위 `@BeforeAll`)에서 명시적으로 수용. 구현 중 `Mockito.spy()`가 Spring Data JPA 리포지토리 동적 프록시에서 `UnfinishedStubbingException`을 내는 것을 실측 발견해 `mock()` + 명시 위임으로 전환(`docs/troubleshooting.md` 기록).
- **WebP 레거시 행 영구 미이관**(설계상 수용된 제약, 버그 아님): 화이트리스트에서 WebP를 제외하기로 한 결정(사용자 확정)에 따라, 기존에 WebP로 저장된 레거시 행이 있다면 영구히 `LEGACY_INLINE`(Base64 pass-through)으로 남는다 — 데이터 손실은 없으나 해당 행만 "Base64 페이로드 제거" 목표에서 예외로 남는다.

## 후속 과제 — ② 공개 첨부 다운로드 완료 시 기록 (2026-08-06)

> ②(공개 공지 상세 첨부파일 다운로드, `10c28ff` #25) 완료 시 `PLAN-public-notice-attachment.md`가 명시적으로 로드맵 기록을 요구한 잔여 위험. 설계 결함이 아니라 사용자가 명시적으로 수용한 계약이다.

- **무인증 다운로드 경로의 자원 고갈 위험(명시적 수용)**: `/notices/{id}/attachments/{attachmentId}`는 파일을 `byte[]`로 전량 로딩하며(HEAD도 GET과 동일하게 서비스 진입·전체 로딩), 동시·반복 요청 제한이 없다. 파일당 10MB·공지당 5개 상한은 한 건당 비용만 제한한다. 소규모 운영을 전제로 이번 범위에서 수용한 위험이며(`PLAN-public-notice-attachment.md` 리스크 표), 실제 공개 트래픽·적대적 접근이 예상되면 `InputStreamResource` 스트리밍 전환·레이트리밋 도입을 재검토한다.
- **TOCTOU 약한 보장의 명시적 한계**: 다운로드 재검증은 "재검증 SELECT를 실행한 시점에 공개 상태였음"만 보장한다. 강한 보장(응답 전송 완료까지 락 유지)은 무인증 엔드포인트가 관리자 쓰기를 블로킹하는 DoS 표면을 만들어 미채택(2026-08-03 사용자 확정). 운영 요구가 달라지면 재평가 대상.

## 요약

갈림길은 "관리 골격을 더 다듬을 것인가 vs 관리할 대상을 만들 것인가"인데, 골격은 이미 충분히 좋고 1단계(계정 라이프사이클)·2단계(정체성 확보: 공지사항 도메인·파일 스토리지/첨부파일·공개 공지 페이지)에 이어 **3단계(운영 경험)의 유일한 개시 조건이던 prod 프로파일 부활(①)도 완료됐다(2026-07-30 · `a51d29d` #23)**. `PLAN-public-notice.md`가 의도적으로 미뤘던 공개 첨부파일 노출(②)도 완료됐다(2026-08-03 · `10c28ff` #25). **`FileStorage` 완료로 선행 조건이 풀렸던 프로필 이미지 이관(③)도 완료됐다(2026-08-11 · `e3175a9` #28) — 실행 로드맵 Top 3(2026-07-29 선정) 전 항목이 완료된 상태다.** ③의 검증 공백 중 마이그레이션 러너 Testcontainers 통합 테스트는 후속 작업으로 해소됐다(2026-08-12 · `ca6446f` #29). ① 완료 검증 중 발견된 범위 밖 항목(실배포 인프라·기존 500 오응답 결함 등)은 위 "후속 과제 — ① prod 프로파일 완료 시 발견" 참조, ② 완료 시 수용한 잔여 위험은 "후속 과제 — ② 공개 첨부 다운로드 완료 시 기록" 참조, ③의 남은 검증 공백(실 레거시 데이터 이관 Playwright 골든 패스 1건만 남음)은 바로 위 "후속 과제 — ③ 프로필 이미지 이관 완료 시 발견" 참조. **① 완료 검증 중 남아 있던 "DB 백업 전략" 미해소 항목도 완료됐다(2026-08-12 · `95f264f` #30) — prod DB·파일 로컬 백업/복구 도구(`scripts/prod-backup.sh`·`scripts/prod-restore.sh`) 도입, 완료 기준 21개 전 항목 실기 검증.** 오프사이트 백업은 범위 밖으로 명시 수용되어 신규 후속 과제로 기록됐다("후속 과제 — ① prod 프로파일 완료 시 발견" 참조). 다음 로드맵 갱신 때는 Top 3 전 항목 완료를 반영해 신규 후보를 재선정해야 한다. 실배포(호스트·도메인 확정, nginx·TLS·오프사이트 백업)는 별도 사용자 결정 사안으로 남아 있다.
