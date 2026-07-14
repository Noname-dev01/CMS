# 프로젝트 방향성 리포트 — 분석 및 3단계 로드맵

> 작성일: 2026-07-10
> 기준 커밋: `03680cd` (기능: 메뉴 데이터 기반 사이드바 동적 렌더링 #6)
> 최근 갱신: 2026-07-14 — Top 5 ② 로그인 실패 잠금 완료 반영 (`f0ecc15` #12)

## 현재 상태 진단

**한 줄 요약: "CMS"라는 이름과 달리, 지금 이 프로젝트는 콘텐츠가 없는 잘 만든 관리자 백오피스 골격이다.** 이 간극을 어떻게 메울지가 방향성의 핵심이다.

### 강점 (계속 유지할 것)

- **공학적 규율이 좋다.** 계층 분리, DTO 경계, 생성자 주입, QueryDSL 동적 쿼리, 전역 예외 처리까지 일관성이 있고, `AdminPageAnnotationConventionTest`처럼 컨벤션 자체를 테스트로 강제하고 있다.
- **횡단 관심사가 이미 성숙하다.** AOP 감사 로깅(REQUIRES_NEW 독립 트랜잭션 + 예외 격리), CSRF 전면 활성화, API 경로 JSON 401/403 분기(CSRF 필터 순서로 인한 401/403 오분류까지 처리 — `SecurityConfig.java:69-72`), ADMIN/MANAGER 역할 세분화 완료.
- **기록 문화**: 트러블슈팅 24건, 마이그레이션 SQL 기록, CI + GitHub Flow. 이 습관이 프로젝트의 가장 큰 자산이다.

### 공백 (방향성을 정하는 제약)

1. **콘텐츠 도메인 부재.** 도메인이 member(관리자 계정)·menu·log·visit·dashboard뿐 — 전부 "관리를 위한 관리" 기능이고, 관리할 대상(게시글, 페이지, 미디어)이 없다.
2. ~~**스키마 관리가 임계점.** `ddl-auto: update` + `docs/migration/` 수동 SQL 3개. 이 방식은 도메인이 하나만 더 늘어도 깨진다.~~ → **해소됨** (`020b203`, #7): Flyway 도입 완료, `ddl-auto: validate` 전환.
3. **prod 프로파일이 의도적으로 제거된 상태**(#3 커밋)라 배포 목표가 없다. Dockerfile과 nginx 설정은 남아 있다.
4. **미완성 기능이 반쯤 열려 있다.** ~~`resetToken`·SMTP 설정은 있으나 비밀번호 재설정 발송/사용 로직 없음.~~ → **해소됨** (`99359d3`, #10): 비밀번호 재설정(메일 링크 발급 + 토큰 검증) 구현 완료. ~~`MemberStatus`의 `LOCKED` 상태 전이 로직(로그인 실패 잠금) 없음.~~ → **해소됨** (`f0ecc15`, #12): 로그인 연속 5회 실패 시 자동 잠금(30분 lazy 해제) 구현 완료. `PASSWORD_EXPIRED` 상태 전이 로직(비번 만료)은 여전히 미구현. ~~타 관리자 계정 수정/상태 변경 API(`PATCH /admin/api/members/{id}`) 없음 → 관리자 CRUD 미완.~~ → **해소됨** (`163367e`, #9): 타 관리자 수정/상태 변경 API + 대상자 세션 강제 만료 구현 완료.
5. ~~**버전 부채**: Spring Boot 3.4.x는 OSS 지원이 종료된 라인(2026-07 기준). 최소 3.5.x로 올리고,~~ → **해소됨** (`76bba41`, #8): Boot 3.5.16 업그레이드 완료. 중기적으로 Java 21 + Boot 4.x 검토 시점은 유효.

## 3단계 로드맵

### 1단계 — 기반 마감 (새 기능보다 먼저)

- ✅ **Flyway 도입 (완료, 2026-07-10 · `020b203` #7).** 스키마 baseline + access_role 백필 + 메뉴 시드를 Flyway 마이그레이션(V1~V3)으로 이관하고 `ddl-auto`를 `validate`로 전환 완료.
- **계정 라이프사이클 마감** (진행 중): 필드·상태값이 이미 있어 마감 비용이 낮고, 감사 로깅 인프라와 시너지가 크다.
    - ✅ 관리자에 의한 타 계정 수정/잠금 해제 API (완료, 2026-07-10 · `163367e` #9). `PATCH /admin/api/members/{id}` — 부분 수정, 최후 활성 ADMIN 가드(비관적 락), 상태·권한 변경 시 대상자 세션 강제 만료(AFTER_COMMIT, best-effort)까지 포함.
    - ✅ 로그인 실패 N회 → `LOCKED` 자동 전이 (완료, 2026-07-14 · `f0ecc15` #12). 연속 5회 실패(`BadCredentialsException`만 카운트) 시 자동 잠금 + 30분 lazy 자동 해제, 성공 핸들러 fail-closed 재확인, 잠금 전이 감사 로그·세션 만료, 최후 ADMIN 복구 절차 troubleshooting 기록까지 포함.
    - ✅ 비밀번호 재설정 메일 발송·토큰 검증 (완료, 2026-07-14 · `99359d3` #10). 메일 링크 발급(토큰 SHA-256 해시 저장·30분 TTL·일회용·60초 쿨다운·계정 열거 방지) + 토큰 검증 재설정, 재설정 성공 시 기존 세션 만료·`PASSWORD_EXPIRED`→`ACTIVE` 복귀까지 포함.
    - ⬜ 비밀번호 90일 만료(`PASSWORD_EXPIRED`) 전이 → 실행 계획: [`plan/PLAN-password-expiry.md`](plan/PLAN-password-expiry.md) (Top 5 ③, ① 선행 필수 — ① 완료로 선행 조건 해소)
- ✅ **Boot 3.5.x 업그레이드 (완료, 2026-07-10 · `76bba41` #8).** Spring Boot 3.4.3 → 3.5.16 마이너 업그레이드 완료.

### 2단계 — 정체성 확보: 첫 콘텐츠 도메인 (프로젝트의 본론)

"CMS"가 되려면 관리 대상이 필요하다. **공지사항/게시판 도메인 하나를 끝까지** 만드는 것을 추천.

- Board/Post + 첨부파일 → 기존 패턴(Controller→Service→Repository, QueryDSL 검색, AOP 감사 로깅, 메뉴 등록, `@AdminPage` 화면)을 그대로 재사용하는 첫 실전 검증. 지금까지 만든 골격이 "새 도메인을 얼마나 싸게 추가할 수 있는가"로 증명된다.
- **파일 스토리지 추상화**가 강제로 필요해진다(첨부파일을 Base64로 DB에 넣을 수는 없으므로). 로컬 디스크 구현으로 시작해 인터페이스만 잡아두면, 프로필 이미지의 Base64-in-DB 방식도 자연스럽게 이관 가능.
- 콘텐츠 소비자 결정: 공개 프론트 페이지를 Thymeleaf로 소박하게 붙이는 방안(진짜 CMS 완성형) vs 공개 조회 API만 제공하는 headless 방안. **전자 추천** — 현재 스택과 일관되고 배포 데모가 명확하다.

### 3단계 — 운영 경험 (포트폴리오 가치의 완성)

- prod 프로파일 부활: Swagger 차단, actuator 보호, `ddl-auto: none`, 시크릿 외부화. Dockerfile·nginx 설정이 이미 있어 절반은 준비돼 있다.
- 저비용 VPS나 홈서버에 실배포 + 최소 모니터링(actuator + 로그). "배포 가능한 master"라는 GitHub Flow 원칙은 실제 배포가 있어야 의미를 갖는다.

### 지속 항목 (급하지 않지만 방향은 정해둘 것)

- **Testcontainers 검토**: 현재 테스트가 로컬 MariaDB 기동에 의존 — Testcontainers로 바꾸면 CI/로컬 환경 차이가 사라진다.
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

### ③ 비밀번호 90일 만료(PASSWORD_EXPIRED) 자동 전이
- **실행 원본**: [`plan/PLAN-password-expiry.md`](plan/PLAN-password-expiry.md)
- **목표**: 계정 라이프사이클 마감 — `MemberStatus` 5종 중 마지막 미구현 전이 제거.
- **파일·순서·완료 기준**: 계획서에 명시 (`password_changed_at` 컬럼 + 백필 마이그레이션, 로그인 시점 검사, 재설정 흐름으로 복귀).
- **착수 게이트**: **① 완료 필수** (만료 사용자의 유일한 복구 경로 — 2026-07-14 해소) + 만료 정책 사용자 승인.

### ④ 대시보드 데모 위젯 정리 + 실데이터 차트
- **실행 원본**: [`plan/PLAN-dashboard-demo-cleanup.md`](plan/PLAN-dashboard-demo-cleanup.md)
- **목표**: SB Admin 2 더미 콘텐츠 제거, 최근 7일 방문자 실데이터 차트 1개로 대체. 소규모·독립 작업이라 ①~③ 사이 어느 시점에든 끼워 넣을 수 있고, ⑤의 화면 회귀 검증 기준선을 깨끗하게 만든다.
- **파일·순서·완료 기준**: 계획서에 명시 (chart-*-demo.js 제거, 집계 실패 시 500 없는 폴백 포함).
- **착수 게이트**: 없음 (승인 불필요).

### ⑤ 첫 콘텐츠 도메인 — 공지사항(notice) 관리
- **실행 원본**: 없음 — **착수 시 `/plan-review-loop`로 계획서를 먼저 작성·검증한다.** 아래는 그 계획의 확정 골격이다.
- **목표**: "관리할 대상"이 없는 CMS에 첫 콘텐츠 도메인을 추가해 2단계(정체성 확보)를 개시한다. 기존 골격(계층 분리·QueryDSL 검색·AOP 감사 로깅·메뉴·`@AdminPage` 화면)을 새 도메인에 그대로 재사용하는 첫 실전 검증.
- **1차 범위 (확정)**: 제목·내용·사용여부(노출)·작성자·작성/수정일의 관리 화면 CRUD. **첨부파일 제외** (파일 스토리지 추상화가 필요하므로 별도 후속 계획), **공개 프론트 제외** (관리 화면만).
- **수정해야 할 정확한 파일** (member 도메인 패키지 패턴 미러 — 실측 기준):
    - 신규: `src/main/java/com/cms/admin/notice/` 하위 `domain/Notice.java`, `repository/NoticeRepository.java`, `repository/NoticeRepositoryImpl.java`(QueryDSL 검색), `service/NoticeService.java`, `controller/NoticeController.java`(REST), `controller/NoticePageController.java`(`@AdminPage` 필수), `dto/request/NoticeCreateRequest.java`·`NoticeUpdateRequest.java`, `dto/response/NoticeResponse.java`
    - 신규: `src/main/resources/templates/admin/notice/manage.html` (`templates/admin/menu/manage.html` 구조 미러, CSRF 헤더 필수)
    - 신규: `src/main/resources/db/migration/V<N>__create_notice.sql` — 번호는 작성 시점 최대 버전+1, 컬럼 규약(감사 컬럼 등)은 `V1__init_schema.sql`의 member·menu 정의를 그대로 따름
    - 신규 테스트: `NoticeServiceTest`, `NoticeControllerTest` (MockMvc + spring-security-test)
    - 수정 없음이 원칙 (사이드바 메뉴는 코드가 아니라 데이터 — 아래 참조)
- **단계별 작업 순서**: `/plan-review-loop`로 계획 확정 → `feat/notice-board` 브랜치 → Flyway → 엔티티 → Repository(+Impl) → Service(`@AdminActionLogged` 부착) → DTO → REST Controller(`GET/POST /admin/api/notices`, `GET/PATCH/DELETE /admin/api/notices/{id}`, DELETE는 소프트 삭제 204) → PageController + manage.html → 메뉴 등록(`POST /admin/api/menus`, `accessRole=ALL` — V3 시드는 빈 테이블 전용이므로 마이그레이션 아님) → 테스트 → playwright 검증
- **완료 기준**: `./gradlew test` 통과 / 빈 DB `bootRun` 기동(`validate` 통과) / ADMIN·MANAGER 각각 로그인해 CRUD 골든 패스 + 목록 검색·페이징 playwright 확인 / 소프트 삭제 후 목록 미노출 / 감사 로그(`AdminActionLog`)에 생성·수정·삭제 기록 확인 / 사이드바에 메뉴 노출
- **착수 게이트**: 스키마 변경 수반 — 계획서 승인 시 함께 고지.

### 선정에서 탈락한 후보 (다음 갱신 때 재평가)

- **Testcontainers 전환**: CI/로컬 환경 차이 제거 — 가치는 있으나 ⑤로 테스트 수가 늘어난 뒤가 전환 적기.
- **prod 프로파일 부활 + 실배포 (3단계)**: 콘텐츠 도메인(⑤) 완성 전에는 배포할 대상이 없다.
- **메뉴 3단계·accessRole 인가 연동 / QueryDSL 포크 전환**: 기존 "지속 항목" 판단 유지.

## 요약

갈림길은 "관리 골격을 더 다듬을 것인가 vs 관리할 대상을 만들 것인가"인데, 골격은 이미 충분히 좋다. **Flyway + 계정 기능 마감(1단계)을 짧게 끝내고, 게시판 도메인(2단계)으로 프로젝트의 이름값을 채운 뒤, 실배포(3단계)로 마무리**하는 순서를 추천한다.
