# 프로젝트 방향성 리포트 — 분석 및 3단계 로드맵

> 작성일: 2026-07-10
> 기준 커밋: `03680cd` (기능: 메뉴 데이터 기반 사이드바 동적 렌더링 #6)
> 최근 갱신: 2026-07-21 — ① 공지사항(notice) 관리 완료 표기 정정 (`feat/notice-board` → `6c5ca4c` #16, 모순되는 구 문구 삭제) + `./gradlew test` 전체 통과 재확인
> 이전 갱신: 2026-07-20 — ④ 대시보드 정리 완료 반영 (`3e652e6` #15) + 전수 재분석으로 **Top 5 재선정** (2단계 콘텐츠 도메인 중심)

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

### ② 파일 스토리지 추상화 + 공지 첨부파일
- **유형**: 기능 추가 / **선정 이유**: 첨부파일이 강제하는 스토리지 추상화는 프로필 이미지 Base64-in-DB 방식의 탈출구도 함께 열어주는 구조적 투자.
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

### ③ 공개 공지 페이지 (첫 비관리자 화면)
- **유형**: 기능 추가 / **선정 이유**: "관리 화면만 있는 CMS"에서 "콘텐츠를 내보내는 CMS"로 — 2단계 정체성의 완성이자 3단계(⑤ 실배포)의 배포 대상을 만든다.
- **실행 원본**: 없음 — 착수 시 `/plan-review-loop`로 계획서 작성·검증.
- **목표**: 비로그인 사용자가 노출(`useYn`) 상태의 공지 목록·상세를 볼 수 있는 공개 Thymeleaf 페이지를 추가한다. 기존 로드맵의 "공개 프론트 vs headless" 갈림길에서 **공개 프론트(Thymeleaf)** 방안을 실행하는 것.
- **수정해야 할 정확한 파일** (실측 기준):
    - 신규: `src/main/java/com/cms/publicweb/` (신규 패키지 — 관리자 `admin` 패키지와 분리) 공개 페이지 컨트롤러 (`@AdminPage` 부착 금지 — `AdminPageAnnotationConventionTest`·`AdminSidebarAdvice` 범위는 admin 패키지 한정)
    - 신규: `src/main/resources/templates/public/` 하위 공지 목록·상세 템플릿 (admin 프래그먼트 재사용 불가 — 공개용 최소 레이아웃 신규)
    - 수정: `src/main/java/com/cms/config/SecurityConfig.java` — 공개 경로 추가 (**인가 정책 변경 → 사용자 승인 필수**; 현재 `anyRequest().permitAll()`이라 실제로는 `/admin/**` 밖 경로 추가 시 코드 변경이 불필요할 수 있음 — 계획서에서 실측 확정)
    - 신규 테스트: 공개 컨트롤러 MockMvc 테스트 (비인증 접근 200, 미노출 공지 404)
- **단계별 작업 순서**: `/plan-review-loop` 계획 확정(공개 URL 구조·인가 정책 승인 포함) → 공개 조회 Service 메서드(노출 필터 강제) → 페이지 컨트롤러 → 템플릿 → 테스트 → playwright 검증
- **완료 기준**: `./gradlew test` 통과 / 비로그인 브라우저로 공지 목록·상세 열람 playwright 확인 / 미노출·소프트 삭제 공지 404 / 관리 화면 회귀 없음 / XSS 방어(본문 이스케이프 정책) 테스트 통과
- **착수 게이트**: **① 완료 필수** + 공개 경로 신설 = **인가 정책 협의 필수** (CLAUDE.md 보안 규칙).

### ④ Testcontainers 전환 — 테스트 DB 격리
- **유형**: 테스트·인프라 / **선정 이유**: 테스트 파일이 43개(동시성·통합 테스트 다수)로 늘어 "⑤ 이후가 적기"라던 기존 유보 사유가 이미 해소됨. 로컬 MariaDB(3307) 기동 의존과 CI service container 이중 구성을 단일화한다.
- **실행 원본**: 없음 — 착수 시 `/plan-review-loop`로 계획서 작성·검증.
- **목표**: 테스트가 로컬 MariaDB 기동·환경변수 주입 없이 `./gradlew test` 단독으로 도는 상태. CI/로컬 환경 차이(포트 점유·시드 오염 함정 포함)가 사라진다.
- **수정해야 할 정확한 파일** (실측 기준):
    - 수정: `build.gradle` (`org.testcontainers:mariadb`·`junit-jupiter` 테스트 의존성 추가 — 신규 의존성이므로 착수 시 제안·승인)
    - 신규: `src/test/resources/` (현재 디렉터리 자체가 없음 — 테스트 전용 설정) + `src/main/java/com/cms/support/` 아님 → 실측 위치는 `src/test/java/com/cms/support/`에 Testcontainers 설정 클래스 추가 (`CmsTestApplication.java`가 이미 있는 패키지)
    - 수정: `.github/workflows/ci.yml` (MariaDB service container 제거 또는 유지 여부 — 계획서에서 결정)
    - 수정: `CLAUDE.md`·`docs/development-workflow.md` (테스트 실행 전제 변경 반영)
- **단계별 작업 순서**: `/plan-review-loop` 계획 확정 → 의존성 추가 승인 → 컨테이너 설정(재사용 전략 포함) → 기존 통합·동시성 테스트 전 클래스 전환 → CI 워크플로 정리 → 문서 갱신
- **완료 기준**: 로컬 MariaDB를 **내린 상태**에서 `./gradlew test` 전체 통과 / CI 통과 / 테스트 소요 시간이 기존 대비 과도하게(예: 2배 이상) 늘지 않음
- **착수 게이트**: 신규 의존성 추가 → 사용자 승인 (CLAUDE.md 주의사항). ①~③과 독립이라 언제든 끼워 넣기 가능.

### ⑤ prod 프로파일 부활 + 배포 준비 (3단계 개시)
- **유형**: 인프라·보안 / **선정 이유**: ①·③으로 "배포할 대상"이 생기는 시점에 맞춰 준비 — "배포 가능한 master"라는 GitHub Flow 원칙을 실배포로 증명하는 마지막 조각.
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

- **프로필 이미지 Base64-in-DB → 파일 스토리지 이관**: ②의 `FileStorage` 인터페이스가 생긴 뒤 별도 마이그레이션 계획으로 다루는 게 안전 (기존 데이터 이관 수반).
- **비밀번호 재설정 공개 API의 IP 기반 rate limit**: 계정별 60초 쿨다운·열거 방지는 이미 있음 — 실배포(⑤) 이후 노출 환경이 생기면 재평가.
- **메뉴 3단계·accessRole 인가 연동 / QueryDSL 포크(OpenFeign) 전환 / Java 21 + Boot 4.x**: 기존 "지속 항목" 판단 유지 — 콘텐츠 도메인 확장·Boot 4.x 이행 시점에 재평가.
- **docs/migration/ 레거시 수동 SQL 3개 정리**: Flyway 이관 완료로 역할 종료 — 이력 가치가 있어 보존, 별도 작업 불필요 판단.

## 요약

갈림길은 "관리 골격을 더 다듬을 것인가 vs 관리할 대상을 만들 것인가"인데, 골격은 이미 충분히 좋고 1단계(계정 라이프사이클)는 마감됐다. **공지사항 도메인(①)으로 2단계를 열고, 첨부파일·공개 페이지(②③)로 "콘텐츠를 내보내는 CMS"를 완성한 뒤, prod 부활(⑤)로 실배포까지 마무리**하는 순서를 추천한다. Testcontainers(④)는 독립 작업이라 어느 틈에든 끼워 넣을 수 있다.
