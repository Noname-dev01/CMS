# 프로젝트 방향성 리포트 — 분석 및 3단계 로드맵

> 작성일: 2026-07-10
> 기준 커밋: `03680cd` (기능: 메뉴 데이터 기반 사이드바 동적 렌더링 #6)
> 최근 갱신: 2026-11-12(72차) — `/updateRoadmap` 사실확인: **로그인 상태 유지(remember-me)와 재인증 제거 완료**(로드맵 본문 항목이 아니라 이 줄에만 기록 — 65~71차와 같은 방식). 71차 후속 후보 "콘텐츠 수정의 재확인 여부"를 논의하다 사용자가 **remember-me 자체를 제거**하기로 결정했다(관리자 페이지라 쿠키 탈취 시 7일 읽기·수정 노출을 남기지 않는다 — 재확인을 모든 쓰기로 넓혀도 읽기 전용 7일 세션이 남는다). PR #233 `2ce574c`(mergedAt=2026-11-12T08:00:15Z, PR CI `test`·`browser-test`·`prod-smoke` SUCCESS), 계획 `plan/PLAN-remember-me-removal.md` v3(적대적 계획 리뷰 2라운드 ship, 승인 단계에서 `LoginSessionMutexFilter` 유지로 변경, 코드 리뷰 2라운드 지적 0건). #225 직전 `da7149c` 기준 파일 단위 복원으로 #225·#227·#228·#230·#231(remember-me·만료 토큰 정리·민감 작업 재인증·콘텐츠 삭제 재인증·세션 같은 기기 안내)을 걷어 내고 로그인 화면 체크박스를 삭제했으며, **V16 `DROP TABLE IF EXISTS persistent_logins`**(평문 토큰 — **스키마 변경**)를 더했다. 같은 세션 두 탭의 동시 폼 로그인(서로 다른 계정)이 레지스트리 회원과 실제 인증 회원을 어긋나게 해 강제 만료를 피하는 경합은 독립 방어로 `LoginSessionMutexFilter`를 남겨 막는다(시험 `ConcurrentFormLoginIntegrationTest`로 이식). **URL 규칙·의존성 변경 없음.** master에서 remember-me·재인증 참조 0건·V16·필터 등록 실존 확인, 관련 시험 31건 재실행 통과, 머지 전 전체 `./gradlew test` 3,105건 통과(건너뜀 12), TDD 실패 확인·변이(필터 등록 제거 → F11 실패), 실기(dev + Playwright·curl — V16로 기존 토큰 행 삭제, 회원 상태 변경·FAQ 분류 삭제가 모달 없이 동작, 세션 단일 만료 → 다음 요청 401, MANAGER의 ADMIN API 403 JSON, 옛 쿠키만 가진 브라우저는 로그인 이동). **롤백 실측**(이전 master를 V16 DB로): 기동은 되지만 회원 상태 변경이 500(BEFORE_COMMIT 토큰 삭제 실패) → V15 `CREATE TABLE` 수동 실행 뒤 정상, 다시 올리면 테이블이 남아 수동 DROP 필요 — 두 절차를 `docs/migration-guide.md` V16 행에 기록. **정리된 71차 후속 후보**: "콘텐츠 수정의 재확인 여부"·"remember-me 쿠키 `Secure` 확인"·"세션별 같은 기기 표시"는 대상 기능이 없어져 종료. **남은 후속 후보**(본문 항목 아님): 프록시 경유 오류 본문 확인(`docs/verification/deployment-edge.md`). 다음 작업 선택은 `/suggestRoadmap`.
> 이 파일에는 미완료·후속 항목만 둔다. 이전 갱신 이력과 완료된 실행 로드맵·요약은 [`project-direction-roadmap-archive.md`](project-direction-roadmap-archive.md)로 옮겼다(2026-11-05, 내용 변경 없이 이동).
> 다른 문서가 가리키는 섹션이 여기 없으면 보관 파일에서 같은 제목을 찾는다.

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
5. ~~**버전 부채**: Spring Boot 3.4.x는 OSS 지원이 종료된 라인(2026-07 기준). 최소 3.5.x로 올리고,~~ → **해소됨** (`76bba41`, #8): Boot 3.5.16 업그레이드 완료. ~~중기적으로 Java 21 + Boot 4.x 검토 시점은 유효.~~ → **Boot 4.x는 해소됨** (`1505c43`, #97, 2026-10-06): Spring Boot 4.0.8(Spring Framework 7.0.9) 전환 — 6.2.x에 수정판이 없는 CVE-2026-47884가 계기. classic 스타터로 단계 전환했으며 ~~모듈식 스타터 이관은 후속~~ → **모듈식 스타터 이관 해소됨** (`b3566c4`, #99, 2026-10-06). ~~**Java 21은 미착수**(toolchain 17 유지).~~ → **Java 21 해소됨** (`b143f5a`, #106, 2026-10-07): toolchain·Docker 빌더 `eclipse-temurin:21-jdk`·런타임 `21-jre`(태그+index digest)·CI `setup-java` 네 곳을 함께 21로 상향. 이로써 공백 5(버전 부채)는 전부 해소됐다.

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
- **QueryDSL 원본(5.1.0)은 사실상 개발 중단 상태.** 당장 문제는 없지만 Boot 4.x 이행 시 OpenFeign 포크(`io.github.openfeign.querydsl`) 전환을 염두에 둘 것. → **2026-10-06 Boot 4.0.8 이행(#97) 시점 재평가**: 5.1.0(jakarta)이 Hibernate 7.2.24에서 동작함을 확인(공지·회원·알림 실 DB 테스트 + 신설 `AdminActionLogRepositoryDataJpaTest`, dev 실서버 활동 로그 검색) — 포크 전환은 하지 않고 보류. Hibernate 마이너 상향이나 QueryDSL 결함이 드러나면 재평가.
- ~~메뉴 3단계 확장~~ → **해소됨** (2026-10-01, #71·#73·#74·#75: 최대 3단·서브트리 이동·드래그 초안 + [반영]). ~~메뉴-권한 실제 연동(`accessRole`을 노출뿐 아니라 인가에 반영)은 콘텐츠 도메인이 생겨 메뉴가 실제로 늘어난 뒤 판단해도 늦지 않다.~~ → **해소됨** (2026-10-02, 권한관리 PR ①~④ · #81·#82·#83·#84: `access_role` 제거, 코드 카탈로그 ∩ DB 허용 행으로 노출·인가를 한 곳에서 도출, ADMIN이 권한 관리 화면에서 MANAGER 공지 권한 조절).

## 재평가 대상 후보 (각 선정에서 탈락·보류된 항목)

### 선정에서 탈락한 후보 (다음 갱신 때 재평가)

- ~~**비밀번호 재설정 공개 API의 IP 기반 rate limit**: 계정별 60초 쿨다운·열거 방지는 이미 있음 — 실배포(⑤) 이후 노출 환경이 생기면 재평가.~~ → **해소됨** (`38ee3bc` #32, 2026-08-27): 비밀번호 재설정 API 2종 + `/notices/**`(GET·HEAD)에 토큰 버킷 기반 레이트리밋 도입 완료 — 상세는 상단 "실행 로드맵 — Top 3 (2026-07-29 선정)" ④ 참조.
- ~~**메뉴 3단계**~~ ✅ **완료 (2026-10-01 · #71·#73·#74·#75)** — 사이드바 3단 렌더링, 생성·구조 반영의 깊이 상한(3), 서브트리 이동, 드래그 초안 + [반영] 일괄 반영 API(`PUT /admin/api/menus/structure`). 상세는 `plan/PLAN-menu-structure-apply.md`.
- **accessRole 인가 연동 / QueryDSL 포크(OpenFeign) 전환 / Java 21 + Boot 4.x**: 기존 "지속 항목" 판단 유지 — 콘텐츠 도메인 확장·Boot 4.x 이행 시점에 재평가. (`accessRole`은 여전히 사이드바 노출 제어일 뿐 인가에는 반영되지 않는다.) → 2026-10-06 기준: accessRole은 권한관리 PR ①~④에서 해소(21차), **Boot 4.x는 해소**(#97 `1505c43`, Boot 4.0.8), QueryDSL 포크는 Boot 4 재평가 결과 보류(5.1.0 동작 확인, "지속 항목" 참조), **Java 21만 남음**. → 2026-10-07: **Java 21 해소**(`b143f5a` #106) — 이 묶음의 항목은 QueryDSL 포크 보류(재평가 조건부)를 빼면 모두 끝났다.
- **docs/migration/ 레거시 수동 SQL 3개 정리**: Flyway 이관 완료로 역할 종료 — 이력 가치가 있어 보존, 별도 작업 불필요 판단.
- ~~**공개 공지 목록 검색**: `PLAN-public-notice.md`에서 의도적으로 범위 제외(목록+페이징+상세만). 관리 화면 대비 사용 빈도가 낮을 것으로 추정되는 공개 화면 기능이라 이번 갱신에서는 후순위 — 다음 갱신 때 재평가.~~ → **해소됨** (`c762fe4` #110, 2026-10-07): `/notices?keyword=`로 **제목** 검색(관리자·통합 검색과 같은 범위). 공개 전용 QueryDSL `searchPublishedByTitle`이 노출·미삭제 조건을 고정하고 목록·COUNT가 같은 조건을 쓴다. LIKE 와일드카드는 문자 그대로 검색되고, 키워드는 100 코드 유닛 상한이다. 스키마·인가 정책·레이트리밋은 바꾸지 않았다. 인덱스 없이 전체 행을 스캔하므로 **전체 행수 5만 초과 또는 운영 검색 응답 100ms 초과 시 FULLTEXT 재평가**(실측 5만 행 약 32~39ms, `plan/PLAN-public-notice-search.md`).

### 우선순위에서 밀린 감사 항목 (다음 갱신 때 재평가)

| 항목 | 내용 | 미룬 이유 | 다시 착수할 조건 |
|---|---|---|---|
| M-04 | `prod-up.sh`/`prod-restore.sh`가 health 200+3초 대기로 완료 판정 — 가변 시간 러너(`ProfileImageMigrationRunner` 등)의 실제 준비 완료와 어긋날 수 있음 | 실배포 자동화 전에만 유효한 리스크, 현재 로컬 학습 단계 | 실배포 자동화·CD 파이프라인 구축 시 |
| ~~M-05~~ ✅ **완료 (2026-09-30 · `2e10d24` #64)** | ~~`AppConfig`의 KST `Clock`과 별개로 `Member`·`AdminActionLogService`·`NoticeAttachmentService`가 `LocalDateTime.now()` 직접 호출 — JVM이 UTC면 시각이 9시간 어긋날 수 있음~~ → 도메인 변경 메서드가 `now` 파라미터를 받고 서비스가 주입된 KST `Clock`을 쓰도록 통일(`Member`·`Notice`·`Menu`·서비스 8종·감사 로그 조회 기본 기간), `ApiErrorResponse`는 `AppConfig.KST` 시간대만 공유, 재발 방지 `ClockUsageConventionTest`(허용 예외 `LocalDiskFileStorage`·`ApiErrorResponse` 2건). **정정: 운영 경로는 `main()`이 JVM 기본 시간대를 KST로 고정해 원래 영향이 없었고**, 실제 효과는 테스트 JVM(CI=UTC)의 시각 원천 정합성 방어 | 현재 컨테이너·로컬 모두 JVM 시간대를 KST로 맞춰 운용 중이라 즉시 영향 없음 | prod 이미지·Compose의 JVM 시간대가 명시적으로 고정되지 않은 채 실배포될 때 (조치 완료: 시간대와 무관하게 `Clock`으로 통일) |
| ~~M-06~~ ✅ **완료 (2026-09-29 · `ed193ed` #47, 선행 `9dac029` #46). 머지 차단 게이트도 활성 — 브랜치 보호 필수 체크 `["test","prod-smoke"]` 등록 확인(사용자 설정, 2026-09-29)** | ~~CI가 prod 이미지 기동·백업복구·의존성 스캔을 검증하지 않음, Dockerfile/Compose가 가변 태그(`mariadb:10.11`) 사용~~ → `prod-smoke` job(prod-up.sh 기동·Actuator/Swagger 보안 응답·백업복구 왕복·Trivy 이미지 스캔)·이미지 digest 고정(9곳)+일치 검사·Dependabot 도입, 실제 GitHub Actions에서 통과 확인 | 테스트 커버리지 자체는 충분(656개) — CI 확장은 실배포 임박 시 레버리지가 더 큼 | 실배포 준비 단계 진입 시 (조치 완료: 필수 체크에 `prod-smoke` 등록됨) |
| ~~M-08~~ ✅ **완료 (2026-09-29 · `821509b` #45)** | ~~`SecurityConfig` 마지막 규칙이 `anyRequest().permitAll()` — 미분류 URL 기본 공개~~ → `anyRequest().denyAll()` 전환 + ERROR 디스패치·정적 5경로(GET/HEAD) 명시 공개, 실서버 통합 테스트로 검증 | 현재 `/admin/**`가 먼저 보호되고 신규 엔드포인트 추가 시 매번 명시적으로 규칙을 넣어온 이력(승인 이력 참조) — 즉각적인 무인증 노출은 발견되지 않음 | 기본 거부 전환은 인가 정책 전면 변경이라 별도 계획·사용자 협의 필요 시점에 |
| ~~L-01~~ ✅ **완료 (2026-09-30 · `c45fae3` #67)** | ~~`MemberRepositoryImpl.toOrderSpecifiers`에 id 보조 정렬 없음(`NoticeRepositoryImpl`엔 있음)~~ → 요청 정렬에 `id`가 없으면 마지막에 `id desc`를 추가(`NoticeRepositoryImpl`과 동일 규칙), 단위 테스트 + Testcontainers DB 슬라이스 테스트로 검증. **정정: 관리자 화면은 `sort`를 보내지 않아 원래 영향이 없었고 API 직접 호출 시에만 해당**, 보장은 "조회 사이 데이터 불변 동안"의 동률 순서 결정성 한정(offset/limit) | 영향 범위 작음(동률 데이터가 페이지 경계를 넘는 드문 경우) | 회원 목록 화면을 다음에 수정할 때 함께 처리 (조치 완료: 화면 수정과 무관하게 API 정렬 결정성을 확보) |
| ~~L-02~~ ✅ **완료 (2026-10-06 · `70d6f43` #104)** | ~~README Boot 배지(3.4) vs 실제 3.5.16, `deployment.md`의 prod Swagger 404 설명 등 문서-코드 불일치~~ → README 배지(Boot 4.0·Security 7·Hibernate 7)·주요 기능(공지·위임 권한·알림·쪽지)·Quick Start `.env.dev` 작성 단계·Swagger 접근 조건, `migration-guide.md` 깨진 표 복구(V1~V22)·기존 DB 전환 절차, `deployment.md`·`deployment-edge.md`의 #44 이전 IP 소스 서술, `branching.md` 접두사 표, `dependabot.md` #56 후속 상태 정정. **정정: prod Swagger 404 설명은 #26(2026-08-06)으로 이미 정합이었고**, 배지 대상 버전은 그사이 Boot 4.0.8 전환으로 바뀌었다. 이력 문서(troubleshooting·plans·실증 기록)는 다시 쓰지 않음 | 기능에 영향 없는 문서 정확성 문제 | `/claudemd-check` 또는 문서 정리 작업 시 함께 처리 (조치 완료: 문서 정리 작업으로 단독 처리) |

### 이번 선정에서 탈락한 후보 (다음 갱신 때 재평가)

- ~~**휴지통(삭제 공지 복원)**: 가치는 있으나 기존 도메인 다듬기라 새 도메인보다 후순위.~~ → **완료** (2026-11-02 · PR #193 `803f8ef`): 공지가 공지 게시판의 게시글이라 **모든 게시글의 휴지통**으로 구현했다 — `GET /admin/api/boards/{boardId}/posts/trash`(삭제 시각 내림차순, 본문 제외)·`POST …/posts/{postId}/restoration`, 둘 다 그 게시판의 `DELETE` 권한(기존 게이트 `/admin/api/boards/*/posts/**` 안), 게시글 관리 화면의 "휴지통" 카드(`DELETE` 권한이 있을 때만 보임). 복원은 **항상 비노출·상단 고정 해제**로 되살리고 제목·본문·노출 기간·대표 이미지·조회수·작성자·작성일은 보존한다. 삭제 시각·삭제자는 전용 컬럼 없이 삭제된 글의 `updateDate`·`updaterId`(삭제 뒤 행을 다시 쓰지 않는 불변식). 복원은 게시글 `FOR UPDATE` → 게시판 `FOR SHARE`이고 **첫 DB 문장이 잠금 읽기**여야 한다(앞에 일관 읽기를 두면 `innodb_snapshot_isolation=ON`에서 동시 복원의 늦은 쪽이 404가 아니라 1020→409). 삭제된 게시판은 404, 쓰기 중지 게시판은 400, 감사 `POST_RESTORE`. **스키마·`SecurityConfig`·`AdminFeature`·의존성 변경 없음.** 계획서 `plan/PLAN-post-trash.md` v2 — 적대적 리뷰 2라운드 ship(지적 3건: 동시성 OFF·ON 계약은 수용, 삭제자 표시 예외는 문서화만 수용·정리 절차는 기각, 삭제·복원 시 휴지통 동기화와 빈 페이지 처리는 수용), `/code-review-loop` 1라운드 approve(지적 0건). 사실확인: `gh pr view` state=MERGED(mergedAt=2026-11-02T12:54:31Z, PR CI `test`·`browser-test`·`prod-smoke` SUCCESS), origin/master에서 `Post.restore`·`PostService.restorePost`·`getDeletedPosts`·컨트롤러 두 핸들러(`@RequireBoardPermission(DELETE)`)·`posts.html` `trashPanel`·시험(`PostTrashApiIntegrationTest` 8건, `AbstractBoardDeleteRaceTest`의 OFF·ON 경합 4종, `PostServiceTest` 복원 5건) 실존 확인. 검증: 머지 전 전체 `./gradlew test` 2,990건 통과(실패 0·건너뜀 12 — 건너뜀 12건의 이번 변경과의 관련은 확인하지 않았다), 변이 실험 2건(복원의 게시판 공유 잠금을 일반 읽기로 바꾸면 경합 시험 6건·첫 문장 앞에 일관 읽기를 넣으면 ON 3건·OFF 1건 실패), 실기(bootRun + Playwright·curl) — 삭제 → 휴지통 → 복원 → 비노출 → 공개 상세 404 → 노출 켜면 200, 예약글 노출 기간 보존, 휴지통을 연 채 삭제 시 자동 갱신, 마지막 페이지 복원 시 이전 페이지 이동, MANAGER 읽기·수정만이면 403·`DELETE`가 있으면 200·다른 게시판 403·CSRF 없이 403. **수용한 한계**: 영구 삭제·보존 기간·자동 비움·게시판 복원은 범위 밖(삭제된 글은 계속 쌓임), 수동 회수 절차 ②는 삭제된 글만 쓰던 이미지도 회수하므로 회수 전에 휴지통 확인 필요(`docs/deployment.md` "게시글 휴지통"), V5 이전 앱으로 롤백한 기간에 삭제된 글은 직전 수정자가 삭제자로 보일 수 있음(감사 로그가 원본), 공지 게시판 글의 복원은 같은 코드 경로이지만 공지 게시판을 대상으로 한 별도 시험은 두지 않았다. 같은 PR에 #192가 추가한 `SitePublicRateLimitConventionTest`의 시간 의존 단언 보정(간헐 실패)이 별도 커밋으로 포함됐다.
- ~~**조회수/다운로드 카운트 + 대시보드 고도화**~~ → **완료** (2026-10-13 · PR #140 `04bf78e`): `post.view_count`·`post_attachment.download_count`(V49, 롤백 호환, 엔티티 `updatable=false`)를 비트랜잭션 `ContentCounterService` + `@Transactional` `ContentCounterWriter`(SET STATEMENT 문장·락 대기 상한)로 집계한다 — (IP, 대상)당 30분 1회·동시 쓰기 상한 3·공개 판정을 통과한 상세 GET과 파일 열기에 성공한 첨부 GET만(HEAD·404 제외, `/boards`·`/notices` 모두). 대시보드 "콘텐츠 현황"(총 조회·총 다운로드·상위 5 글/첨부)은 ADMIN 전체, MANAGER는 READ 가능한 게시판만이며 방문자 지표와 폴백을 분리했다. 관리 화면에 조회수 열·정렬·다운로드 횟수 추가. 인가 정책·`SecurityConfig` 변경 없음. 수용한 한계: 프록시 뒤 과소 집계·재기동 시 중복 집계·폭주 시 일부 유실(최선 노력 지표), 기간별 추이·공개 화면 노출은 범위 밖. 계획서 `plan/PLAN-view-download-count.md`.
- ~~**사이트 설정 메뉴**~~ → **정식 항목으로 승격**(2026-10-13) → **완료**(2026-10-14 · PR #143~#147, 아래 "실행 로드맵 — 사이트 설정 메뉴").
- **감사 로그 CSV 내보내기·관리자 초대 메일 가입**·~~**비밀번호 이력 재사용 금지**~~: 운영 편의 항목 — 실배포 이후 운영 요구에 따라 재평가. → 비밀번호 이력 재사용 금지는 **구현하지 않고 대체 종료**(2026-11-05, 사용자 결정 — NIST SP 800-63B rev.4·OWASP 근거): 90일 만료 폐지 + 유출 비밀번호 차단 + 관리자 변경 강제 + 현재 값 재입력 거부로 대신했다(PR #203 `32f5dae`·#205 `feebecc`, 계획 `plan/PLAN-password-expiry-retirement-breach-check.md`). 만료가 없어져 같은 값으로 시계를 초기화할 이유가 사라졌다. A→B→A 순환은 막지 않는다(규정상 필요해지면 재평가).
- **2단계 인증(TOTP)·관리자 경로 IP 허용 목록**: 새 라이브러리 도입·인가 정책 변경이 필요해 별도 제안·협의 후 착수.

## 후속 과제 — ① prod 프로파일 완료 시 발견 (2026-07-30 기록)

> ①(prod 프로파일 부활, `a51d29d` #23) 완료 검증 중 발견해 **이번 PR 범위 밖으로 확정**한 항목. 전부 이번 PR이 새로 만든 문제가 아니라 기존에 있던 결함이거나 애초에 별도 범위로 분리돼 있던 사안이다. 다음 로드맵 갱신 때 별도 작업으로 재평가.

- ~~**핸들러 없는 경로가 404 대신 500 반환**~~ → **해소됨** (`7c64307` #26, 2026-08-07): `GlobalApiExceptionHandler`에 `NoResourceFoundException`·`NoHandlerFoundException` 전용 핸들러 추가 완료. `/admin/api/**`는 JSON 404(`RESOURCE_NOT_FOUND`), 그 외는 기존 `error/404.html`(`/admin/**` 하위는 `error/admin/404.html`) 재사용. 부수적으로 `/admin/api/**` 판정 매처(`SecurityConfig`·`AdminSessionExpiredStrategy`·신규 핸들러 3곳에 중복 존재하던 것)를 `GlobalApiExceptionHandler.API_MATCHER` 한 곳으로 단일화하고, `CustomErrorController`의 admin 접두사 오분류 기존 결함(`/administrator/missing` 등)도 `PathPattern` 기반 판정으로 함께 해결. `./gradlew test` 577개 전체 통과, `plan-review-loop` 4라운드(ship) + `code-review-loop` 1라운드(지적 0건) 거침, Playwright 실기 검증으로 재현 3사례(`/admin/logout` GET·`/favicon.ico`·prod swagger-ui) 중 dev 프로파일 기준 2건 해소 확인(prod 자체 실기 확인은 `.env.prod` 미비로 미시도, 동일 코드 경로라 프로파일 무관하게 적용될 것으로 예상). 리뷰 중 Spring Security의 기본 `StrictHttpFirewall`이 세미콜론 포함 경로를 필터 최상단에서 이미 차단한다는 사실을 실측 발견 — 상세는 `PLAN-not-found-handling.md` "구현·검증 결과" 참조. PR #26 머지 확인(`gh pr view 26` state=MERGED), CI success 확인.
- **실배포 인프라(nginx 리버스 프록시·TLS 인증서·실제 호스팅·CD 파이프라인)**: 현재 `docker-compose.prod.yml`은 `127.0.0.1:8080` 루프백 바인딩까지만 다루며 그대로 인터넷에 노출하면 안 된다. 로드맵 3단계에 이미 "별도 사용자 결정 사안"으로 명시된 범위 — 호스트·도메인이 정해지면 착수. **부분 진행(2026-09-27, PR 6 · `ec6336c` #42)**: 실제 ingress 구축·검증 자체는 여전히 미완료이나, 착수 시 통과해야 할 체크리스트(제품 중립, SameSite 쿠키 포함 10항목)가 `docs/verification/deployment-edge.md`로 사전 준비됨 — 문서 자체가 "이 체크리스트 작성은 이 항목의 완료를 의미하지 않는다"고 명시(계획 리뷰 v19 승인 사항). 호스트·도메인 확정 후 그 경로에서 항목별 실제 검증이 필요하다. **진행 갱신(2026-09-28, `adversarial-review/deploy-check-2026-09-28-3.md`)**: PR 1~6(#37~#42) 전체를 대상으로 한 최종 이미지 기반 **Gate A~H Production Readiness 점검**을 실행 — Gate A(빌드/테스트/마이그레이션)·B(보안/브라우저 회귀)·C(기동/복구)·D(동시성)·E(오류 계약/로깅)·F(SMTP)·G(백업/복구, 격리 nested Docker daemon에서 실제 quiesced 백업→fixture 변경→체크섬 거절→복구→참조 파일 전수 해시·권한·애플리케이션 조회까지 실기 확인) **전부 PASS**. **Gate H(ingress/TLS)는 실제 ingress/TLS가 아직 구축되지 않아 NOT RUN**(사용자 확인) — 최종 판정 "NOT READY FOR EXTERNAL PRODUCTION, A~G PASS·H NOT RUN". 즉 제품·문서 준비는 게이트 기준을 충족했고, 남은 유일한 외부 배포 차단 조건은 실제 ingress/TLS 구성 자체(호스트·도메인 확정)이다 — 착수 시 `docs/verification/deployment-edge.md` 체크리스트로 검증.
- **`forward-headers-strategy`·secure/SameSite 쿠키**: 리버스 프록시 뒤에서 `X-Forwarded-*` 헤더를 신뢰하려면 필요. 위 리버스 프록시 도입과 함께 다룰 사안이라 별도 선행 착수 불필요.
- ~~**DB 백업 전략**: named volume 보존만으로는 백업이 아니다 — 실배포 전 별도 수립 필요.~~ → **해소됨** (`95f264f` #30, 2026-08-12): `scripts/prod-backup.sh`(DB 논리 덤프 + 첨부/프로필 파일 볼륨 tar 백업, `gzip -t`+`tar tzf`+`sha256sum -c` 즉시 무결성 검증, `BACKUP_RETENTION_DAYS` 자동 정리, `mkdir` 기반 잠금)·`scripts/prod-restore.sh`(백업 무결성 + 대상 DB 이중 검증 → 대화형 확인 → 복구 전 안전 백업 자동 생성 → 볼륨 여유 공간 확인 → DB/파일 복구 → 재기동 → health/`RestartCount` 이중 안정성 재확인) 신규 확인. `Dockerfile`의 `appuser` UID·GID 10001 고정(`tar --numeric-owner` 정합성) 확인. `Makefile`에 `make prod-backup` 타깃 확인, `.gitignore`/`.dockerignore`에 백업 산출물(`/backups`) 제외 확인. `docs/deployment.md`에 백업/복구 절차·환경변수·범위 한계·재해복구·볼륨 UID 이관 절차 문서화 확인. `adversarial-review/plan/PLAN-db-backup.md` 완료 기준 21개 전 항목 실기 검증(`[x]`) 확인 — Docker Desktop+prod 스택+Playwright 골든 패스(공지 생성→백업→삭제→복구→재노출, 첨부·프로필 이미지 sha256 바이트 동일성), 오환경 복구 방지 이중 검증(manifest DB명 불일치·SQL `USE` 문 불일치 각각 fixture로 독립 차단 확인), 재해복구(빈 볼륨)·정지 상태 백업·보존 기간 정리·잠금 동시성까지 실기 확인. 구현 단계에서 텍스트 리뷰로는 못 잡은 실제 버그 4건(EXIT 트랩 종료 코드 덮어쓰기로 복구가 매번 실패로 오판되던 치명적 결함 포함) 발견·수정 기록. `/code-review-loop` 5라운드(수용 5건: 기존 볼륨 UID 이관 문서화·복구 안정성 재확인 health curl 추가·`.dockerignore` 백업 산출물 누락·이관 명령 `MSYS_NO_PATHCONV` 누락+시점 정합성 문서 보완·백업 디렉터리 충돌 시 데이터 유실 결함 수정) 거침. `./gradlew test` 전체 통과(Java 코드 무변경). PR #30 머지 확인(`gh pr view 30` state=MERGED, mergedAt=2026-08-12). **범위 밖으로 명시 수용**: 오프사이트 백업(같은 호스트 디스크만 보호, 디스크 전체 손실 미방어) — 바로 아래 신규 항목으로 별도 기록.
- **오프사이트 백업 미포함(2026-08-12 기록)**: `scripts/prod-backup.sh`는 백업 산출물을 prod 호스트 로컬 디스크(`${BACKUP_DIR:-./backups}`)에만 남긴다. 같은 호스트 디스크 전체가 손실되면(디스크 고장, 볼륨 삭제와 무관한 물리적 사고 등) 백업 자체도 함께 사라져 복구 수단이 없다 — `PLAN-db-backup.md`가 "범위" 절에서 처음부터 "논리적 오삭제·볼륨 오염으로부터의 로컬 롤백"만 목표로 명시하고 오프사이트 보관을 후속 과제로 분리해뒀다(설계 결함이 아니라 명시적으로 좁힌 범위). 로드맵 3단계 "실배포 인프라" 항목과 마찬가지로 호스트·도메인이 정해져 실배포가 시작되면 원격 저장소(S3 호환 오브젝트 스토리지, 별도 호스트로의 정기 동기화 등)로의 반출 방안을 재평가한다.
- **동시 부트스트랩 시 서로 다른 `ADMIN_BOOTSTRAP_*` 값 주입**: 서로 다른 컨테이너에 서로 다른 자격증명이 동시에 주입되면 관리자 계정이 두 개 생길 수 있다(`uk_member_user_id` 유니크 제약은 "같은" 자격증명 경합만 직렬화). "배포 파이프라인이 모든 인스턴스에 동일 설정을 배포한다"는 일반적인 전제를 벗어난 상황이라 `PLAN-prod-profile.md` 결정 4에서 위험 수용으로 남긴 설계 제약 — 현재 `docker-compose.prod.yml`은 고정 컨테이너명 단일 인스턴스라 애초에 재현 불가하나, 향후 다중 인스턴스 배포(K8s 등)로 전환 시 재평가 필요.
- **미확인(외부 의존, 재확인 필요)**: 실제 SMTP 자격증명으로 비밀번호 재설정 메일이 prod에서 정상 발송되는지(더미 값으로 기동만 확인) / ~~`mariadb:10.11` 이미지의 `healthcheck.sh --connect --innodb_initialized`가 이미지 태그 업데이트 후에도 계속 존재하는지(배포 전 `docker pull` 후 재확인 필요)~~ → **해소됨 (2026-09-29 · `ed193ed` #47, 사용자 확인)**: `prod-smoke`가 PR·master push마다 고정 digest(`mariadb:10.11@sha256:...`) 이미지를 기동하고 compose `depends_on: service_healthy`가 이 healthcheck에 의존해 app을 기동시키므로, 스크립트가 없으면 CI가 기동 단계에서 실패한다(실제 러너 통과 확인). digest 갱신 PR도 같은 CI를 거치므로 이미지 갱신 후 재확인이 자동화됐다 / ~~prod 프로파일 자체의 로그인→관리 화면 브라우저 골든 패스(이번엔 curl·docker inspect로만 확인, dev 프로파일 기준으로만 Playwright 회귀 확인함).~~ → **해소됨** (2026-11-07 · PR #214 `f5d3fd3`, 계획 `plan/PLAN-prod-path-verification.md`): `scripts/prod-up.sh`로 띄운 prod 이미지에서 Playwright로 로그인·게시판·글·본문 이미지·첨부·프로필 이미지·공개 화면·삭제와 복원·게시판별 권한 경계·재기동 후 데이터 유지를 확인했다. 로컬 평문 HTTP 기준이라 TLS·`Secure` 쿠키는 포함하지 않는다. 실제 SMTP 발송은 **여전히 미확인**이다 — 더미 자격증명으로 응답 200, 실패 로그(`MailAuthenticationException`), 토큰 정리만 확인했다.

## 후속 과제 — 문의하기 완료 시 기록 (2026-10-12)

> 문의하기(PR #138 `7ffe6a2`) 완료 시 사용자가 요청해 기록한 **운영 투입 전 필수 작업 2건**. 코드는 끝났고 이 둘은 코드 밖(발급·설정·문구) 작업이다 — 둘 다 끝나기 전에는 운영에서 `CMS_INQUIRY_TURNSTILE_ENABLED` 기본값(`false`)이라 `/inquiry`가 503(문의 기능 닫힘)으로 남는다. 상세 배경은 `docs/deployment.md` "문의 폼"·`PLAN-inquiry.md`.

- **① Cloudflare Turnstile 실제 키 발급과 prod 컨테이너 경로 실기 검증** — 미착수
  - **목표**: Cloudflare 대시보드에서 사이트 키·시크릿 키(`0x4…`)를 발급해 `.env.prod`에 넣고, 실제 prod 컨테이너 경로로 동작을 확인한다.
  - **수정/설정**: `.env.prod`의 `CMS_INQUIRY_TURNSTILE_ENABLED=true`·`CMS_INQUIRY_TURNSTILE_SITE_KEY`·`CMS_INQUIRY_TURNSTILE_SECRET_KEY`(커밋 금지). 코드 변경은 필요 없을 것으로 본다(검증 중 결함이 나오면 별도 수정).
  - **완료 기준**:
    - [ ] Compose 경로(`make prod-up` 등)로 `enabled=true` + 실제 키에서 앱 기동 성공
    - [ ] 같은 경로에서 Cloudflare 공개 테스트 키(`1x0…`·`2x0…`·`3x0…`)를 넣으면 **기동 실패**(`InquiryTurnstileConfigValidator`가 Compose·env 가드를 거쳐서도 작동)
    - [ ] 실제 도메인에서 위젯이 렌더링되고 제출 → 303 → 문의함 접수·상단바 알림이 동작(실제 siteverify 호출 성공), Cloudflare 응답 불가·잘못된 토큰이면 400(fail-closed)
    - [ ] 도메인·호스트명 제한 등 Cloudflare 쪽 설정 확인(사이트 키의 허용 호스트). ingress 도입 전이면 `getRemoteAddr()` 기준 레이트리밋이 프록시 IP 하나로 보이는 한계(`docs/deployment.md`)를 함께 점검
  - **선행**: 실제 호스트·도메인 확정(3단계 "실배포 인프라"와 같은 결정 사안) — 도메인 없이는 위젯 호스트 제한을 검증할 수 없다.
- **② 개인정보처리방침에 Cloudflare 전송 고지와 보존 기간 명시** — 미착수
  - **목표**: 정적 페이지(약관·방침 관리, `SITE_PAGE`)의 개인정보처리방침에 ⓐ 문의 폼이 방문자 IP·캡차 토큰을 Cloudflare로 전송한다는 고지(위탁/제3자 제공 구분은 운영자가 법률 검토로 정한다 — 이 로드맵 범위 밖), ⓑ 문의 수집 항목(이름·이메일·문의 내용·동의 시각)·이용 목적·**보존 기간**과 "처리 후 관리자가 삭제할 때까지 보관" 문구, ⓒ 백업에도 개인정보가 포함된다는 안내를 추가한다.
  - **수정/설정**: 관리 화면에서 정적 페이지 본문 편집(코드·마이그레이션 변경 없음). **보존 기간 수치는 운영자 결정 사항** — 스케줄러가 없어 자동 삭제가 없으므로 문의함에서 주기적으로 직접 삭제하는 운영 절차(담당·주기)도 함께 정한다.
  - **완료 기준**:
    - [ ] 공개 `/pages/<방침 슬러그>`에 위 고지·보존 기간 문구가 노출됨(비노출·없는 슬러그는 404 계약 그대로)
    - [ ] 문의 폼의 동의 안내 문구(`public/inquiry/form.html`)와 방침의 수집 항목·보관 문구가 서로 모순되지 않음 — 폼은 처리방침 링크를 하드코딩하지 않으므로, 방침 슬러그가 정해지면 폼에 링크를 추가할지 별도 결정
    - [ ] 보존·삭제 운영 절차(담당·주기)가 문서화됨(`docs/deployment.md` "문의 폼" 갱신)
  - **리스크**: 법률 판단이 필요한 문구는 이 문서·코드가 대신하지 않는다. ①과 ②는 서로 독립이지만 **운영 개시(① `enabled=true`)는 ② 이후**에 하는 것을 권장한다.

## 후속 과제 — ② 공개 첨부 다운로드 완료 시 기록 (2026-08-06)

> ②(공개 공지 상세 첨부파일 다운로드, `10c28ff` #25) 완료 시 `PLAN-public-notice-attachment.md`가 명시적으로 로드맵 기록을 요구한 잔여 위험. 설계 결함이 아니라 사용자가 명시적으로 수용한 계약이다.

- **무인증 다운로드 경로의 자원 고갈 위험(명시적 수용)**: `/notices/{id}/attachments/{attachmentId}`는 파일을 `byte[]`로 전량 로딩하며(HEAD도 GET과 동일하게 서비스 진입·전체 로딩), 동시·반복 요청 제한이 없다. 파일당 10MB·공지당 5개 상한은 한 건당 비용만 제한한다. 소규모 운영을 전제로 이번 범위에서 수용한 위험이며(`PLAN-public-notice-attachment.md` 리스크 표), 실제 공개 트래픽·적대적 접근이 예상되면 `InputStreamResource` 스트리밍 전환·레이트리밋 도입을 재검토한다. **(2026-08-27 부분 완화, `38ee3bc` #32)**: `cms.rate-limit`로 첨부 다운로드 경로(GET·HEAD)에 IP당 요청 수 상한이 추가돼 반복 요청에 의한 자원 고갈 위험이 완화됐다 — 단 fail-open(캐시 포화 시 IP별 정확한 누적치 미보장)을 수용한 최소 방어이며, 파일당 `byte[]` 전량 로딩 자체(요청 1건당 비용)는 그대로라 `InputStreamResource` 스트리밍 전환 여부는 여전히 별도 재평가 대상이다. **(2026-09-29 부분 해소, `ea7a288` #63)**: `byte[]` 전량 로딩(요청당 힙 최대 10MB 점유)은 4KB 버퍼 스트리밍으로 해소됐다(`FileStorage.open()`·서비스 2분할·HEAD 전용 핸들러·전송 중 실패 3구간 처리) — HEAD도 더는 파일을 읽지 않는다. 전송 중 DB 커넥션 점유도 전역 `spring.jpa.open-in-view=false`로 제거됐다. **남는 위험**: 동시 연결 수·디스크 IO·Tomcat 스레드 점유의 상한은 없다(`cms.rate-limit`은 진입 빈도만 완화) — 실제 공개 트래픽·적대적 접근이 예상되면 동시 전송 수 상한을 재평가한다. → **동시 전송 상한 해소됨** (2026-11-07 · PR #213 `3e22999`, 계획 `plan/PLAN-public-transfer-limit.md`): `@PublicFileTransfer` 표지 + 인터셉터로 공개 GET 4개를 전체 100·IP당 20으로 제한한다(IP 초과 429·전체 초과 503, `Retry-After: 5`, HEAD·로그인 세션 제외, `max-total`이 Tomcat 스레드 절반을 넘으면 기동 실패). 실기: 응답을 읽지 않는 연결로 상한을 채운 상태에서 health·공개 페이지·관리자 화면 정상, 해제 뒤 회복. **여전히 남는 것**: 전송 한 건의 총 시간 제한은 없어 느린 클라이언트가 자리를 오래 쥘 수 있고(공개 파일은 503이 이어지지만 나머지 서비스는 유지), 디스크 IO는 동시 수로만 간접 제한된다. prod·실제 프록시 경로는 미확인이다 — `docs/verification/deployment-edge.md` 11번째 항목. 상한이 없을 때 스레드가 차서 관리자 화면이 멈추는 대조 측정은 하지 않았다. 새로 알게 된 한계: 기존 `LocalDiskFileStorage.load()`는 최종 파일 자체가 심볼릭 링크인 경우를 막지 못한다(부모 디렉터리만 검증) — 신규 `open()`만 `NOFOLLOW_LINKS`로 거부하며 `load()`는 이번 범위 밖. → **해소됨** (`626764b` #108, 2026-10-07): `load()`도 `open()`과 같은 `openChannel()`(`NOFOLLOW_LINKS`)로 열어 최종 파일 링크를 `IllegalStateException`(500)으로 거부한다. 계획 리뷰에서 함께 발견한 네임스페이스 루트 링크 구멍(`root/profile` 자체가 외부 링크이면 검증 기준이 따라 나감)도 기준을 "설정 루트 실경로 + 네임스페이스 이름"으로 바꿔 막았다. 하드 링크·부모 디렉터리 교체 경합은 수용된 잔여 위험(`plan/PLAN-storage-load-nofollow.md`).
- **TOCTOU 약한 보장의 명시적 한계**: 다운로드 재검증은 "재검증 SELECT를 실행한 시점에 공개 상태였음"만 보장한다. 강한 보장(응답 전송 완료까지 락 유지)은 무인증 엔드포인트가 관리자 쓰기를 블로킹하는 DoS 표면을 만들어 미채택(2026-08-03 사용자 확정). 운영 요구가 달라지면 재평가 대상. **(2026-09-29)** 스트리밍 전환 후 이 창은 "재검증 SELECT 이후 ~ 응답 전송 완료" 즉 **전송 시간만큼 길어진다**(계약 자체는 불변 — 전송 중 비공개 전환·첨부 삭제는 진행 중 다운로드를 막지 않으며 열린 파일 핸들은 삭제 후에도 끝까지 읽힘).

## 후속 과제 — 게시판 정의 고도화 완료 시 기록 (2026-10-30)

> 게시판 정의 고도화(`plan/PLAN-board-definition.md` — PR 0~6과 썸네일 실패 자리표시 후속, 마지막 머지 #188 `9be277a`·#189 `0de208d`)에서 **의도적으로 범위 밖에 둔 후보**. 로드맵 정식 항목이 아니므로 착수하려면 새 계획(`/feature`)부터 세운다. 근거는 계획 문서 "구현·검증 결과 — PR 6"의 "후속"과 그 뒤의 실측이다.

- **① 상세 화면·공개 메인의 대표 이미지 표시(+ `og:image`)** — ✅ 완료 (2026-11-03 · PR #195 `99fd84e`, 계획 `plan/PLAN-home-cover-og-image.md`) — 상세 화면 자체에는 대표를 따로 보이지 않고(본문 안에 이미 있음) 공개 메인 "새 글" 썸네일 + 상세 `og:image`만 했다
  - **목표**: 대표 이미지(`post.cover_image_id`)를 공개 목록 밖에서도 쓴다 — 공개 메인 최신 글 목록의 썸네일, 공유 미리보기용 `og:image` 메타.
  - **배경**: PR 5·6은 공개 **목록**(갤러리·웹진)에서만 썸네일을 보인다. 상세는 대표가 항상 본문 안에 있어 따로 보일 필요가 없다고 보고 뺐고, 공개 메인 최신 글(`findLatestPublished` → `PublishedPostRow`)은 게시판 유형과 무관해 건드리지 않았다.
  - **수정 대상(예상)**: `PublishedPostRow`(대표 ID 추가)·`PublicHomeService`·메인 템플릿, 상세 템플릿 `<head>` 메타, 필요하면 사이트 단위 기본 공유 이미지.
  - **결정·주의**: `og:image`는 외부 크롤러가 이미지를 요청하므로 **절대 URL**이 필요해 호스트·도메인 확정(실배포 인프라 — 위 "후속 과제 — ① prod 프로파일 완료 시 발견")과 얽힌다. 크롤러 요청도 `content-image` IP 한도를 쓰고 썸네일 응답은 `no-store`다. 공개 메인 템플릿은 스크립트 금지 규칙(`com.cms.publicweb.home`)이라 #189의 실패 자리표시 스크립트를 그대로 넣을 수 없어 대체 방식을 따로 정해야 한다.
  - **완료 기준**:
    - [x] 공개 메인에서 대표가 있는 글의 썸네일이 보이고, 비공개·예약·만료·삭제 글은 나오지 않음(기존 공개 판정 그대로) — **"새 글" 섹션만**(공지 섹션은 메인 한 번의 썸네일 요청을 5건 이하로 묶으려 제외), `CoverImageExposureIntegrationTest`(새 글만·공지 제외·비공개 게시판/미노출/삭제/예약 제외·참조 ≤5). **"만료" 글은 신규 시험에 별도 케이스가 없고** 같은 공개 조건 객체(`isPublished`)를 쓰는 기존 `PostRepositoryDataJpaTest`·`PostPublishPeriodPublicIntegrationTest`의 경계 시험이 근거다
    - [x] 상세의 `og:image`가 공개 글의 대표 썸네일을 가리키고, 대표가 없거나 비공개인 글에는 메타가 없음 — `PublicOgImage`가 `app.base-url` 기반 절대 URL(`{APP_BASE_URL}/content-images/{id}?size=thumb`)을 만들고, 그 경로가 익명 200 `image/*`임을 시험이 확인(대표 없음 → 메타 없음, 미노출·예약 글은 404). **실제 공유 크롤러(Facebook·Kakao·Slack)의 이미지 선택은 공개 도메인 확정 후 수동 확인**(이번에는 검증 불가)
    - [x] 공개 메인·상세 템플릿 컨벤션 시험(스크립트 규칙 포함) 통과 — `PublicHomeTemplateConventionTest`(스크립트 0·`th:style` 한 곳·`<img>` 재도입 금지)·`PublicBoardTemplateConventionTest`(`og:image` 메타 형태 고정), 머지 후 master에서 직접 재실행 통과. **계획 대비 변경**: 메인의 실패 대체는 `<img alt="">` 겹침이 아니라 CSS `background-image` 방식(Chromium이 크기가 지정된 깨진 `<img>`에 깨진 아이콘을 그림 — 브라우저 시험이 근거). 스키마·`SecurityConfig`·URL 인가 규칙·의존성 변경 없음
- **② 게시글 관리 화면의 갤러리형·웹진형 게시판 안내** — ✅ 완료 (2026-10-31 · PR #191 `d9ed29c`, 계획 `plan/PLAN-board-type-editor-note.md`)
  - **목표**: 게시글을 쓰는 사람이 그 게시판이 갤러리형·웹진형이고 대표 이미지가 목록에 쓰인다는 것을 알 수 있게 한다(예: 대표를 고르지 않은 글은 목록에서 자리표시로 보인다는 안내).
  - **배경**: 게시글 관리 화면이 쓰는 `MyBoardResponse`(`GET /admin/api/members/me/boards`)에는 게시판 유형이 없다 — 유형은 ADMIN 전용 게시판 정의 응답(`BoardResponse.boardType`)에만 있고, MANAGER는 그 API를 쓸 수 없어 화면이 유형을 알 방법이 없다.
  - **수정 대상(예상)**: `MyBoardResponse`·`MyBoardService`에 `boardType`(해석값 — 알 수 없는 저장값은 `LIST`), `posts.html`의 대표 이미지 묶음 문구.
  - **완료 기준**:
    - [x] 해당 게시판 READ 권한만 가진 MANAGER도 내 게시판 목록 API에서 유형을 받음(게시판 정의 API 권한은 그대로) — `MyBoardResponse.boardType` = `Board.type()` 해석값, `BoardTypeApiIntegrationTest.myBoardsExposeTypeToReadOnlyManager`(정의 API 403 포함)·`myBoardsUnknownStoredValueReadsAsList`
    - [x] 갤러리·웹진 게시판의 편집 폼에 대표 안내가 보이고 목록형에서는 보이지 않음 — `posts.html` `coverTypeNote`(대표 묶음 밖, 본문 이미지가 없어도 보임, 이미지 금지 게시판은 꼬리 문장 생략), 실기에서 6개 게시판 연속 열기로 확인
- **③ 이미지 한도(`content-image` 240/60초)로 인한 실패 — 상세 본문 이미지와 한도 자체** — ✅ 완료 (2026-11-02 · PR #192 `63b734e`, 계획 `plan/PLAN-content-image-rate-limit.md`) — 선택지 (d) + 관리 화면 로그인 세션 면제(`exempt-authenticated`), **한도 수치 240/60초는 유지**(상향하지 않음 — IP당 최대 전송량이 함께 커지므로)
  - **목표**: 같은 IP가 이미지를 많이 요청해도 화면이 깨지지 않게 한다. 목록 썸네일은 실패하면 자리표시로 바뀌지만(#189) **상세 화면의 본문 이미지와 관리 편집기의 이미지는 한도에 걸리면 여전히 깨진 이미지 아이콘**이 보인다.
  - **배경(실측, 2026-10-29)**: 한도가 찬 상태에서 연속 요청 250건까지 성공하고 251번째부터 429(`Retry-After: 1`, HTML 오류 페이지)였다. 50칸 갤러리를 끝까지 스크롤하며 약 1초에 한 페이지씩 넘기면 7회차부터 43~46장이 실패했고 15초쯤 뒤 회복됐다. 갤러리·웹진 썸네일, 상세의 본문 원본 이미지, 관리 편집기 이미지가 한 버킷을 쓰며 로그인 사용자도 예외가 아니다(키는 IP).
  - **선택지**: (a) `content-image` 한도 상향 — 본문 원본까지 함께 올라가고 IP 하나가 쓸 수 있는 전송량 상한도 커진다 (b) 갤러리·웹진의 목록 크기 상한 제한(예: 30 이하) — 유형과 목록 크기 사이 교차 검증이 생긴다 (c) 썸네일을 별도 경로로 분리해 독자 한도 부여 — 레이트리밋 규칙은 경로 패턴으로만 고르므로 `?size=thumb` 쿼리로는 분리할 수 없고 **공개 경로 변경이라 `SecurityConfig` 인가 정책 협의가 필요**하다 (d) 상세 본문 이미지에도 같은 실패 대체 — 공개 상세 템플릿에는 스크립트가 없어 컨벤션 시험(`PublicBoardTemplateConventionTest`)의 규칙을 함께 바꿔야 한다.
  - **완료 기준**:
    - [x] 선택한 방식으로 위 실측 시나리오(연속 요청·50칸 스크롤)에서 깨진 이미지가 사라지거나 자리표시로 대체됨 — 공개 상세: 한도 소진 직후 새로고침에서 본문 이미지 3장 모두 안내 상자로 바뀌고 깨진 `<img>` 0개(`board-content-image-fallback.js`, 시험 `BoardThumbFallbackBrowserTest.detailBodyImageFailureBecomesNotice` — `alt` 보존·HTML 비해석·`role` 부재), 관리 화면: 익명 429 중에도 로그인 세션 600/600 통과·편집 모달 이미지 정상(`exempt-authenticated`, `RateLimitFilterTest`·`SitePublicRateLimitConventionTest`). **50칸 갤러리 스크롤 시나리오는 이번에 다시 돌리지 않았다** — 목록 썸네일은 #189의 자리표시 그대로(`BoardThumbFallbackBrowserTest`가 고정)이고 이번 변경이 건드리지 않는다
    - [x] 공개 경로·인가 정책을 건드리면 사전 승인 기록, 레이트리밋 컨벤션·공개 인벤토리 시험 통과 — 공개 경로·`SecurityConfig`·URL 인가 규칙 변경 없음, 레이트리밋 계약(로그인 면제)은 2026-11-02 사용자 승인으로 루트·`com.cms.config` `CLAUDE.md`에 기록, 전체 `./gradlew test` 2,969건 통과(`SitePublicRateLimitConventionTest`·`PublicExposureInventoryTest` 포함)
- **④ 웹진 요약을 저장 시 계산하는 컬럼으로 이동(조건부)** — 🚫 미채택 종료 (2026-11-04 · 측정 PR #200 `acba3b9`, 대체 처방 PR #201 `b624e65`, 계획 `plan/PLAN-webzine-excerpt-measurement.md`·`plan/PLAN-public-list-projection.md`) — 컬럼은 만들지 않고 목록 전용 조회 + 웹진만 본문 앞부분 읽기로 해결했다
  - **목표**: 웹진형 목록이 느려질 때 요청마다 하던 본문 파싱을 없앤다.
  - **배경**: 요약(`PostExcerpt`)은 조회 때 저장된 본문 앞 20,000자를 jsoup으로 읽어 만든다(목록 한 페이지 최대 50건). 목록 쿼리가 이미 본문을 읽으므로 추가 쿼리는 없지만 **파싱 비용은 측정하지 않았다**(시험은 정확성만 본다). 앞부분이 이미지뿐인 글은 요약이 비어 보이는 한계도 있다.
  - **착수 조건**: 웹진형 목록 응답 시간이 문제로 확인될 때(측정이 먼저). `post.excerpt` 컬럼과 기존 글 백필 마이그레이션, `PostService` 저장 경로 변경이 따르므로 계획 단계에서 스키마 변경을 고지한다. 목록 쿼리가 본문 전체(`content`, MEDIUMTEXT)를 읽는 기존 동작을 함께 줄일 수 있다.
  - **결과(2026-11-04)**: 측정에서 현실적인 글(4.4KB, 50건)은 웹진·목록 응답 차이가 없고 스트레스 사례(200KB)에서만 사전 기준을 넘었다. 요약 컬럼은 요약 계산만 없애고 본문 조회 비용은 그대로라 사용자가 채택하지 않았다. 대신 공개 목록 쿼리가 유형과 무관하게 본문을 읽지 않게 했고(#201), 스트레스 사례 목록형이 약 123ms → 38ms, 웹진형이 약 181ms → 110ms로 줄었다(로컬 단일 요청 값). 요약 파싱 비용(스트레스 사례 약 59ms)과 "앞부분이 이미지뿐인 글은 요약이 빈다"는 한계는 남는다. 다시 문제가 되면 먼저 볼 선택지는 `PARSE_LIMIT` 축소다(측정 문서 "후속" 안 ④).
- **참고 — 첫 실배포 직전 "마지막 합치기" 범위 확대(기존 후속 후보, 새 항목 아님)**: 이 계획이 V5~V13(Java 마이그레이션 V10·V11 포함)을 더했다. 합칠 때 흡수 대상이 V3~V13으로 늘고, 대응하는 업그레이드 경로 시험(`BoardDisplaySettingsUpgradeTest`·`BoardWritePolicyUpgradeTest`·`BoardSortOrderPinUpgradeTest`·`BoardSlugLinkUpgradeTest`·`NoticeLinkUpgradeTest`·`PostCoverImageUpgradeTest`·`BoardTypeUpgradeTest`)은 대상이 없어져 지운다(`docs/migration-guide.md` "시험 정책"). 51차 기록의 후속 후보 ③에 해당한다.

