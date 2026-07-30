# PLAN — prod 프로파일 부활 + 배포 준비

> 작성일: 2026-07-29
> 로드맵 근거: `adversarial-review/project-direction-roadmap.md` "실행 로드맵 — Top 3 (2026-07-29 선정)" ①
> 선행 완료: ① 공지사항(notice) 관리 (`6c5ca4c` #16), ② 파일 스토리지+첨부파일 (`174e925` #18), ③ 공개 공지 페이지 (`7ab80a5` #21)

## 개정 이력
- v1 (2026-07-29): 최초 작성 (plan 모드 정찰·설계 결과). 사용자 확정 4건: 초기 관리자 계정은 환경변수 부트스트랩 러너, 인프라는 compose까지(nginx 제외), actuator는 health만 무인증 공개, 프로파일 기본값(`:dev`) 제거. `/plan-review-loop` 리뷰 대상으로 제출.
- v2 (2026-07-29, codex 리뷰 1차 반영 — no-ship, 10개 지적 중 9개 수용 + 1개 사용자 결정):
  - **수용(차단1)**: `dev,prod` 동시 활성화를 막는 방어가 없었음을 인정. `${SPRING_PROFILES_ACTIVE}`(속성은 유지하고 기본값만 제거 — 값 삭제와 다름을 명시)로 YAML을 확정하고, `EnvironmentPostProcessor`로 dev+prod 동시 활성화를 컨텍스트 생성 전에 차단하는 신규 컴포넌트를 결정 1에 추가.
  - **수용(차단2+차단3, 통합 반영)**: 부트스트랩 필수 필드 누락(`Member.userName`·`email` NOT NULL 미충족)과 "빈 DB+변수없음 조용히 통과"의 모순을 함께 재설계. **사용자 결정(2026-07-29)**: 빈 DB(회원 0명) + 변수 미설정/유효하지 않음 → **기동 실패**로 정책 변경(기존 "경고만" 방침 폐기). 회원이 이미 존재하면 기존대로 변수 미검사·정상 기동 유지. `ADMIN_BOOTSTRAP_EMAIL` 필수 3번째 변수 추가, `userName`은 `userId`를 그대로 사용(추가 변수 없음), 기존 관리자 생성 DTO의 Bean Validation 재사용.
  - **수용(높음4)**: `count()==0` 후 `save()`의 동시 기동 경합 미방어를 인정. `DataIntegrityViolationException` 캐치 후 재확인 패턴으로 결정 4에 추가.
  - **수용(차단5)**: `SecurityConfigTest`가 실제 핸들러 없이도 404로 거짓 통과할 수 있다는 지적을 인정. 실제 스텁 컨트롤러 등록 + 정확한 기대 코드(302/403, "404 또는 403" 모호 표현 폐기)로 테스트 계획 수정. "actuator 무인증 전면 공개"라는 Context 서술이 부정확함(기본 노출은 health뿐)을 인정해 문구 정정. dev의 `info` 노출이 신규 `denyAll()`과 모순됨을 인정 — dev도 health만 노출하도록 결정 2를 단순화(불일치 제거).
  - **수용(높음6)**: prod compose가 `.env.prod` 전체를 두 서비스에 그대로 뿌려 DB 컨테이너가 SMTP 비밀번호까지 받는다는 지적을 인정. 서비스별 `environment:` 명시 목록 + `${VAR:?}` 필수 검증으로 결정 7(신규)에 반영.
  - **수용(높음7)**: 멀티스테이지 빌드에서 최종 이미지 `docker history`로는 builder 레이어의 `.env` 포함 여부를 증명할 수 없다는 지적을 인정. builder 스테이지만 별도 빌드해 검사하는 방식으로 검증 절차 수정.
  - **수용(중간8)**: `expose: 8080`만으로는 호스트 접근이 불가하다는 사실관계와 헬스체크의 `$` 이스케이프 문제를 인정. `127.0.0.1:8080:8080` 바인딩 + `healthcheck.sh` 우선 검토로 반영.
  - **수용(중간9)**: 프로파일 기본값 제거 영향을 받는 통합 테스트가 8개가 아니라 10개(`CmsApplicationTests`, `PasswordResetConcurrencyIntegrationTest` 누락)임을 인정 — 목록 정정.
  - **수용(낮음10)**: `Dockerfile`이 시스템 Gradle 8.7을 쓰지만 `gradle-wrapper.properties`는 8.12.1이라 버전이 어긋난다는 지적을 인정. builder를 `./gradlew` 기반으로 변경.
- v3 (2026-07-29, codex 리뷰 2차 반영 — no-ship, v1 지적 10건 중 7건 완전 해소·3건 부분 해소 확인 + 신규 9건 중 8건 수용·1건 사용자 결정):
  - **수용(차단1)**: Compose의 `${VAR:?}` 필수 검사가 결정 7의 "부트스트랩 성공 후 변수 제거" 절차와 모순됨(변수 제거 시 회원이 있어도 Compose가 기동 자체를 거부)을 인정. `ADMIN_BOOTSTRAP_*` 세 변수는 Compose 레벨에서 선택 입력(`${VAR-}`)으로 낮추고, 필수 여부 판단은 애플리케이션(결정 4)에만 둔다 — "빈 DB인데 없음"은 앱이 fail-fast하고, "회원 있는데 없음"은 앱이 정상 통과시키므로 Compose가 무조건 요구할 이유가 없다.
  - **수용(차단2)**: `denyAll()`이 Security 필터 단계에서 요청을 가로채므로 미인증 요청은 MVC 핸들러 탐색(및 그로 인한 404)에 도달하지 못하고 302로 리다이렉트된다는 지적을 인정 — "정확히 404" 검증은 구현이 맞아도 실패할 수밖에 없는 잘못된 기대값이었다. Docker 검증 항목의 기대 코드를 302(비인증)/403(인증됨 ADMIN)으로 정정하고, "노출 설정 자체가 health만 등록됐는지"는 HTTP 응답이 아니라 `WebEndpointsSupplier` 기반 컨텍스트 테스트로 검증하도록 결정 3에 추가(Security 계층에 가로막혀 HTTP로는 등록 여부를 구분할 수 없기 때문). Swagger(`/swagger-ui.html`)도 기존 `hasRole("ADMIN")` 규칙 때문에 미인증 요청은 302이며, "springdoc 비활성화로 404"는 **인증된 ADMIN 세션**으로만 확인 가능함을 명시.
  - **수용(높음3, 사용자 결정 반영)**: `memberRepository.count()>0`이 "관리 가능한 관리자 존재"를 보장하지 않는다는 지적(ROLE_USER만 있거나 관리자가 전부 비활성인 DB에서도 부트스트랩이 생략됨)을 인정. **사용자 결정(2026-07-29)**: 트리거를 "ACTIVE ROLE_ADMIN 존재 여부"로 강화한다. 정찰 결과 기존 "최후 활성 ADMIN 가드"가 쓰는 `MemberRepository.findActiveAdminIdsForUpdate()`를 그대로 재사용할 수 있음을 확인 — 신규 쿼리를 추가하지 않는다.
  - **수용(높음4)**: `DataIntegrityViolationException` 캐치 후 `count()>0` 재확인이 "의도한 계정이 실제 생성됐는지"가 아니라 "회원이 누군가 있는지"만 확인해 다른 무결성 오류까지 성공으로 오판할 수 있다는 지적을 인정. `count()` 대신 `findByUserId(bootstrapUserId)`로 **의도한 계정이 실제로 존재하는지** 확인하도록 결정 4를 수정.
  - **수용(높음5)**: "기존 관리자 생성 DTO(`AdminMemberCreateRequest` 등)의 검증 재사용"이라는 전제가 부정확했음을 실측으로 확인 — 실제 `AdminSignupRequest`(회원 생성 REST API가 쓰는 DTO)는 비밀번호에 `@NotBlank`만 있고 길이 정책이 없다. 대신 정찰로 앱 전역에서 실제로 쓰이는 비밀번호 정책(`AdminMyPasswordChangeRequest`·`PasswordResetConfirmRequest`의 `@Size(min=4, max=100)`)과 이메일·이름 정책(`AdminMyInfoUpdateRequest`·`AdminMemberUpdateRequest`의 `@Size(max=100)`)을 확인해 이 값들과 일치하는 부트스트랩 전용 검증 계약을 결정 4에 명시.
  - **수용(중간6)**: `${SPRING_PROFILES_ACTIVE}`가 빈 문자열로 정의되면 fail-fast를 우회해 활성 프로파일 0개로 조용히 넘어갈 수 있다는 지적을 인정. `ProfileGuardEnvironmentPostProcessor`가 활성 프로파일이 0개인 경우도 거부하도록 결정 1에 추가.
  - **수용(중간7)**: `application.yml`의 `APP_BASE_URL`·`APP_FILE_STORAGE_ROOT` 기본값(로컬호스트·상대경로)이 prod 프로파일 직접 실행(Compose를 거치지 않는 경우)에서 그대로 남을 수 있다는 지적을 인정. `application-prod.yml`에 두 값을 기본값 없이 재선언하도록 명시.
  - **수용(중간8)**: `docker compose up -d`는 앱이 직후 종료해도 성공으로 보고할 수 있어 결정 4의 fail-fast가 운영자에게 전달되지 않는다는 지적을 인정. `scripts/prod-up.sh`가 헬스체크를 폴링해 실패 시 비정상 종료하도록 결정에 추가.
  - **수용(낮음9)**: Gradle wrapper 배포판 다운로드가 레이어 캐시에 반영되려면 `COPY` 뒤에 다운로드를 트리거하는 명령이 별도로 필요하다는 지적, `.dockerignore` 검증 명령의 `-maxdepth 1`이 중첩 `.env*`를 놓친다는 지적, `.dockerignore`의 `*.png`가 향후 정적 자산까지 배제할 수 있다는 지적을 모두 인정 — 각각 결정 9·검증 절차·결정 6에 반영.
- v4 (2026-07-29, codex 리뷰 3차 반영 — no-ship, 차단 2건 포함 8건 전부 수용, 사용자 결정 불필요):
  - **수용(차단1)**: `MemberRepository.findActiveAdminIdsForUpdate()`(네이티브 `FOR UPDATE`) 재사용이 잘못됐음을 인정 — 이 메서드는 `@Transactional` 호출자 없이는 잠금 자체가 성립하지 않고(Repository 커스텀 쿼리는 자동으로 트랜잭션이 걸리지 않음), 단순 존재 확인 목적에 잠금 쿼리를 쓰면 인덱스 없는 `user_type`/`status` 컬럼 스캔이 갭 락과 얽혀 불필요한 경합을 만들 수 있다. **"신규 쿼리 없음" 결정을 폐기**하고 `MemberRepository`에 비잠금 `existsByUserTypeAndStatus(Role, MemberStatus)` 파생 쿼리를 신규 추가한다 — 우리가 실제로 필요한 건 잠금이 아니라 단순 존재 확인이고, 진짜 직렬화 지점은 이미 `Member.userId` 유니크 제약(결정 4 "동시성" 항목)이 맡고 있다.
  - **수용(차단2)**: `DataIntegrityViolationException` 캐치 후 `findByUserId(bootstrapUserId)`가 **아무 회원이나** 찾으면 성공 처리하는 것도 여전히 거짓 성공(찾은 행이 `ROLE_USER`·`DISABLED`여도 통과)이라는 지적을 인정 — 재조회한 회원이 `userType==ROLE_ADMIN && status==ACTIVE`인지까지 확인하도록 결정 4를 수정. 저장 실패로 rollback-only가 된 트랜잭션 안에서 재조회하면 안 된다는 지적도 인정 — 저장 시도와 재조회를 별도 트랜잭션 경계로 분리하도록 명시.
  - **수용(높음3)**: `AdminBootstrapCredentials`를 record로 만들면 Java record의 기본 `toString()`이 모든 컴포넌트(비밀번호 포함)를 그대로 노출한다는 지적을 인정 — `PasswordResetService.IssueResult`가 이미 쓰는 패턴(민감 필드를 가리는 `toString()` 재정의)과 동일하게, record 대신 일반 클래스로 만들고 `toString()`을 재정의한다.
  - **수용(높음4)**: `WebEndpointsSupplier` API 자체는 유효하다는 확인(codex가 직접 대조)과 함께, (a) 신규 `ActuatorExposureTest`가 `MariaDbContainerSupport`를 상속하지 않으면 컨텍스트가 뜨지 않는다는 점, (b) dev 컨텍스트만 검사하면 `application-prod.yml`이 공통 설정을 오버라이드하지 않았다는 사실은 증명하지 못한다는 점, (c) `SecurityConfigTest`가 `/actuator/health` 200을 검증하려면 env 스텁뿐 아니라 health 스텁도 별도 등록해야 한다는 점을 인정. "API를 못 찾으면 축소" 폴백은 삭제(불필요 — API가 유효함이 확인됐으므로)하고, `application-prod.yml`을 YAML 파싱해 `management` 키 자체가 없음을 확인하는 경량 테스트로 (b)를 보강한다.
  - **수용(높음5)**: 최종 이미지(`eclipse-temurin:17-jre`)에 `curl`이 없어 `docker compose exec app curl ...` 기반 readiness 검사가 실행 불가능한 명세였다는 지적을 인정 — 이미 `127.0.0.1:8080:8080`으로 호스트 바인딩하기로 했으므로(결정 8), 컨테이너 안이 아니라 **호스트에서** `curl`로 폴링하도록 `prod-up.sh` 설계를 구체화.
  - **수용(중간6)**: Compose 필수 변수 목록이 예시로만 서술돼 불명확했다는 지적을 인정 — 정확한 전체 목록을 명시하고, 앱의 `DB_USER`/`DB_PASS`는 별도 키가 아니라 `MYSQL_USER`/`MYSQL_PASSWORD`를 직접 매핑해 값 drift를 구조적으로 차단한다.
  - **수용(중간7)**: v3 반영 과정에서 "회원 존재"라는 구 계약 문구가 확정된 결정 표·검증 절차 일부에 그대로 남아 "ACTIVE ROLE_ADMIN 존재"라는 실제 계약과 불일치했다는 지적을 인정 — 문서 전체에서 "회원 0명/회원 존재" 표현을 "ACTIVE ROLE_ADMIN 없음/존재"로 통일.
  - **수용(중간8)**: `prod-down.sh`가 named volume(운영 DB·첨부파일)을 실수로 삭제하지 않는다는 계약이 명시돼 있지 않았다는 지적을 인정 — `docker compose down`만 쓰고 `-v`/`--volumes`를 쓰지 않는다는 원칙을 결정에 명시.
- v5 (2026-07-29, codex 리뷰 4차 반영 — no-ship, 차단 1건 포함 6건 전부 수용, 사용자 결정 불필요):
  - **수용(차단1)**: v4의 "별도 `@Transactional` 메서드로 저장" 서술이 **같은 클래스 내부 호출**로 구현될 경우 Spring의 프록시 기반 `@Transactional`이 자기 자신을 호출할 때는 적용되지 않는다(self-invocation 문제)는 지적을 인정 — 이 프로젝트가 이미 동일한 문제로 `PasswordResetService`에서 `TransactionTemplate`을 명시적으로 쓰고 있는 기존 패턴(`PasswordResetService.java:99-100,114`)을 그대로 재사용하도록 결정 4를 구체화. Mockito 단위 테스트만으로는 트랜잭션 경계 자체를 검증할 수 없다는 지적도 인정해 Testcontainers 기반 통합 테스트를 테스트 계획에 추가.
  - **수용(높음2)**: `.env.prod`가 "단일 소스"라는 표현이 Docker Compose의 실제 변수 우선순위(호스트 셸 변수가 `--env-file`보다 우선)와 `.env` 파일의 따옴표 규칙(따옴표 없음/큰따옴표는 `$` 보간 대상, 작은따옴표만 리터럴)을 고려하지 않았다는 지적을 인정 — `docs/deployment.md`에 시크릿은 작은따옴표로 감싸라는 규칙을 명시하고, `prod-up.sh`가 `ADMIN_BOOTSTRAP_*` 등 민감 변수명이 호스트 셸에 이미 설정돼 있으면 그 사실만 경고(값은 출력 안 함)하고 `unset` 후 진행하도록 결정 8에 추가.
  - **수용(높음3)**: DB 계정(`DB_USER`/`DB_PASS`)은 `MYSQL_USER`/`MYSQL_PASSWORD` 직접 매핑으로 drift를 막았지만, `DB_URL`의 DB 이름 부분은 `MYSQL_DATABASE`와 별개 값이라 여전히 어긋날 수 있다는 지적을 인정 — Compose에서 `DB_URL: jdbc:mariadb://db:3306/${MYSQL_DATABASE:?}`로 조합해 `.env.prod`의 필수 목록에서 `DB_URL`을 제거(compose 경로 한정 — compose를 거치지 않는 직접 실행은 여전히 `DB_URL` 자체가 필요하며 `application-prod.yml`의 기본값 없는 선언이 이를 강제).
  - **수용(중간4)**: `application-prod.yml`의 "management 키 부재" 검증이 단순 루트 키 검사라면 `management.endpoints.web.exposure.include: "*"` 같은 점(dot) 표기 키를 놓칠 수 있다는 지적을 인정 — Spring Boot의 `YamlPropertySourceLoader`로 실제 로딩 방식과 동일하게 평탄화한 뒤 `management.`로 시작하는 속성이 0건인지 확인하도록 테스트 설계를 구체화.
  - **수용(중간5)**: `curl --fail`에 타임아웃이 없어 응답이 없는 서버에 한 번의 호출이 오래 매달릴 수 있고, 실패 후 `restart: unless-stopped`로 인한 재시작 루프를 멈추는 절차가 없었다는 지적을 인정 — `--connect-timeout 2 --max-time 4`를 명시하고, 폴링 실패 시 `docker compose stop app`(컨테이너·볼륨·로그는 보존)으로 재시작 루프를 멈추도록 결정 8에 추가.
  - **수용(낮음6)**: 결정 1 "문제 2" 설명에 dev·prod 로더가 "둘 다 `count()==0`에서만 동작"한다는 v1 시절 문구가 v4 이후에도 남아 있었다는 지적을 인정 — `AdminBootstrapLoader`는 v4부터 `existsByUserTypeAndStatus()` 기반임을 반영해 문구 수정(결론 자체는 그대로 유효).
- v6 (2026-07-29, codex 리뷰 5차 반영 — no-ship, 차단 1건 포함 4건 전부 수용, 사용자 결정 불필요):
  - **수용(차단1)**: 핵심 설계(결정 4)는 `TransactionTemplate`으로 정확히 수정됐으나, **작업 단계 6에 "저장은 별도 `@Transactional` 메서드로 분리"라는 v4 시절 문구가 그대로 남아 있어 구현 체크리스트가 폐기된 방식을 다시 지시**하고 있었다는 지적을 인정 — 작업 단계 6을 "저장·재조회는 각각 `TransactionTemplate.execute(...)`로 독립 실행"으로 정정.
  - **수용(높음2)**: 신규 `AdminBootstrapConcurrencyIntegrationTest`(v5)의 "첫 저장 커밋 후 두 번째 부트스트랩을 순차 호출" 설계가, 두 번째 호출이 트리거 1단계(`existsByUserTypeAndStatus`)에서 이미 `true`를 받아 **즉시 반환**되므로 정작 검증하려던 `DataIntegrityViolationException` 경로·재조회 로직을 전혀 실행하지 않는다는 지적을 인정 — 트리거 검사를 우회하고 저장·재조회 로직만 직접 호출할 수 있도록 해당 로직을 package-private 메서드로 분리해 테스트에서 직접 호출하는 설계로 테스트 계획을 재작성.
  - **수용(높음3)**: 셸 변수 우선순위 방어(결정 7·8, v5)가 `ADMIN_BOOTSTRAP_*` 세 변수에만 적용돼 "`.env.prod`가 유일한 입력"이라는 계약이 `MYSQL_ROOT_PASSWORD`·`MYSQL_DATABASE`·`MYSQL_USER`·`MYSQL_PASSWORD`·`MAIL_USER`·`MAIL_PASS`·`APP_BASE_URL`에는 여전히 성립하지 않는다는 지적을 인정 — `prod-up.sh`가 검사·`unset`하는 변수 목록을 compose가 참조하는 전체 변수로 확장.
  - **수용(중간4)**: "최대 60초" 폴링을 반복 횟수(간격×횟수)로 구현하면 `curl` 자체의 소요 시간이 누적돼 실제 총 소요 시간이 60초를 넘을 수 있다는 지적을 인정 — 벽시계 기준 데드라인(bash `SECONDS` 등)으로 총 대기 시간 상한을 명시하도록 결정 8을 구체화.
- v7 (2026-07-29, codex 리뷰 6차 반영 — no-ship, 중간 2건 전부 수용, 사용자 결정 불필요):
  - **수용(중간1)**: v6의 `SECONDS` 기반 루프가 데드라인을 `curl` **실패 후에만** 검사해, 마지막 반복에서 새 `curl` 호출(`--max-time 4`)이 시작되면 60초 데드라인을 최대 4초 초과할 수 있다는 지적을 인정 — 매 호출 **전**에 남은 시간을 계산해 `curl`의 `--max-time`과 `sleep` 시간을 모두 `min(고정값, 남은 시간)`으로 제한하도록 결정 8을 수정.
  - **수용(중간2)**: `docker compose logs app`과 `docker compose stop app`을 단순 순차 실행하면, 이 프로젝트의 기존 셸 스크립트 관례(`set -euo pipefail`)상 `logs` 명령이 실패할 경우 스크립트가 그 자리에서 종료돼 `stop app`이 실행되지 않고 재시작 루프가 계속될 수 있다는 지적을 인정 — 두 명령 모두 `|| true`로 실패를 흡수해 `stop app`이 항상 실행되도록 결정 8을 수정.
  - **7차 확인 리뷰(2026-07-29) 결과: ship.** 매 `curl` 호출 전 `remaining` 계산·`--max-time`/`sleep` 상한·`bash -n` 문법 검증·`logs`/`stop` 각각의 `|| true` 처리를 모두 확인해 실질적 지적 없음 — `plan-review-loop` 7라운드 종료, 승인 단계로 진행.

## Context

이 프로젝트는 커밋 `a8ffb9a`(#3)에서 "실제 운영 서버가 없는데 있는 것처럼 보이는 혼란"을 이유로 prod 골격(`application-prod.yml`·`docker-compose.prod.yml`·nginx 설정·스크립트)을 **전부 삭제**했다. 이후 공지사항 도메인(#16)·첨부파일(#18)·공개 공지 페이지(#21)가 완료되어 **배포할 콘텐츠가 실제로 생겼고**, 로드맵 3단계(운영 경험) 개시의 유일한 남은 조건이 prod 프로파일 부활이다.

정찰 결과 단순한 yml 복원이 아니라 **실제 보안 결함 4건**이 드러났다:

1. **actuator에 SecurityConfig 방어가 없다** — `SecurityConfig.java`에 `/actuator/**` 규칙이 전혀 없어 `anyRequest().permitAll()`(L66)로 떨어진다. (v2 정정) Spring Boot 3.5의 기본 HTTP 노출은 `health`뿐이고 현재 dev 설정(`application-dev.yml:28-32`)도 `health,info`로 이미 좁혀둔 상태라 "모든 actuator 엔드포인트가 전면 공개"된 것은 아니다 — 정확한 위험은 **"현재 노출된 엔드포인트(health,info)에 별도 인증이 없고, 노출 설정이 실수로 넓어지면 SecurityConfig가 이를 전혀 막지 못해 즉시 전면 공개로 이어지는 구조"**라는 점이다. 노출 제한 설정 자체가 **dev 프로파일 파일에만** 있어 prod 파일 신설 시 빠뜨리기 쉽다는 문제도 여전하다.
2. **prod 초기 관리자 계정 생성 경로 부재** — `TestMemberLoader`는 `@Profile("dev")`(L17). prod로 뜨면 로그인 가능한 계정이 0명이고, BCrypt 해시는 런타임 생성이 필요해 SQL 시드도 불가하다.
3. **프로파일 기본값이 dev** — `application.yml:5` `${SPRING_PROFILES_ACTIVE:dev}`. 운영 서버에서 환경변수를 누락하면 조용히 dev로 떠서 `TestMemberLoader`가 `admin`/`1234` 계정을 자동 생성한다.
4. **`.dockerignore` 부재** — `Dockerfile:3` `COPY . .`가 `.env.dev`(DB·메일 시크릿), `.git`, `build/`, `data/`(첨부 실파일)를 전부 빌더 스테이지 레이어에 올린다.

목표는 `SPRING_PROFILES_ACTIVE=prod`로 기동하면 Swagger 비활성·시크릿 전량 환경변수 주입·actuator 최소 노출·안전한 로깅이 보장되는 상태다. **실배포(호스트 선정·도메인·TLS)는 이번 범위 밖.**

## 스키마 · 인가 정책 영향 (승인 필요 항목)

- **스키마 변경: 없음.** Flyway 마이그레이션 파일 추가 없음(현재 최대 버전 V10 유지).
- **인가 정책 변경: 있음 (사용자 승인 완료, 2026-07-29).** `SecurityConfig`에 아래 두 줄을 `anyRequest().permitAll()`보다 앞에 추가한다:
  ```java
  .requestMatchers("/actuator/health").permitAll()
  .requestMatchers("/actuator/**").denyAll()
  ```
  - 현재 `/actuator/health`를 포함한 모든 actuator 경로가 `anyRequest().permitAll()`로 무인증 공개된 상태다 — `/actuator/health` 자체의 무인증 접근성은 변하지 않는다(로드밸런서 헬스체크용으로 의도된 것).
  - 변경의 실질은 **"그 외 actuator 엔드포인트(env, beans, metrics 등)를 명시적으로 차단"**하는 것이다. `management.endpoints.web.exposure.include: health`(설정 레벨 제한)에 더해 Security 레이어에서 이중으로 막아, 노출 설정이 실수로 넓어져도 뚫리지 않게 한다(`/notices`의 GET permitAll + 나머지 denyAll과 동일한 이중 방어 패턴).

## 확정된 결정 (사용자 승인 완료, 2026-07-29)

| # | 항목 | 결정 |
|---|------|------|
| 1 | prod 초기 관리자 계정 | 환경변수 부트스트랩 러너 신설 — ACTIVE ROLE_ADMIN이 0명일 때만 환경변수로 ROLE_ADMIN 1개 생성(v3, 사용자 결정) |
| 2 | 인프라 범위 | compose까지, nginx 제외 — `docker-compose.prod.yml` 부활, nginx/TLS는 실배포 시점으로 미룸 |
| 3 | actuator 노출 | health만 무인증 공개, `show-details: never`, 나머지 비노출 |
| 4 | 프로파일 안전장치 | `:dev` 기본값 제거해 명시 요구 |

## 핵심 설계 결정

### 1. 프로파일 기본값 제거의 파급을 test 태스크로 흡수하고, dev+prod 동시 활성화를 기동 전에 차단한다

`application.yml:5`의 `${SPRING_PROFILES_ACTIVE:dev}`에서 기본값을 없애면 프로파일 미지정 기동이 실패한다(의도된 fail-fast). **(v2 명확화)** 정확한 변경은 `active: ${SPRING_PROFILES_ACTIVE}` — 속성 자체(`spring.profiles.active`)는 유지하고 `:dev` 기본값 부분만 제거한다. 속성을 통째로 삭제하는 것과는 다르다(삭제하면 Boot가 실패 없이 `default` 프로파일로 조용히 기동한다 — 우리가 막으려는 바로 그 상황). `${SPRING_PROFILES_ACTIVE}`로 두면 환경변수가 없을 때 Spring의 placeholder 해석이 실패해 기동 자체가 예외로 중단된다.

문제 1 — **테스트가 이 기본값에 의존하고 있다**는 점:

- `@SpringBootTest(classes = CmsTestApplication.class)` 통합 테스트 **(v2 정정) 10개**(`MenuConcurrencyIntegrationTest`, `AdminSessionRevocationIntegrationTest`, `NoticeConcurrencyIntegrationTest`, `AdminMemberUpdateConcurrencyIntegrationTest`, `LoginFailureLockoutIntegrationTest`, `PasswordExpiryIntegrationTest`, `LoginFailureConcurrencyIntegrationTest`, `NoticeAttachmentTransactionIntegrationTest`, **`CmsApplicationTests`, `PasswordResetConcurrencyIntegrationTest`**)에 `@ActiveProfiles`가 없다 — 현재는 `:dev` 기본값으로 dev 프로파일이 적용되어 `ddl-auto: validate` 등이 걸린다. 특히 `CmsApplicationTests`는 dev `TestMemberLoader`의 시드 동작을 직접 단언하므로 프로파일이 바뀌면 즉시 실패한다.
- `.github/workflows/ci.yml:39-40`은 `SPRING_PROFILES_ACTIVE: dev`를 명시하므로 CI는 무사하지만, 로컬 `./gradlew test`는 기본값에 의존한다.

**채택**: `build.gradle`의 `test` 태스크에 `environment 'SPRING_PROFILES_ACTIVE', 'dev'`를 추가해 로컬·CI 동작을 일치시킨다. 테스트 10개에 `@ActiveProfiles("dev")`를 개별 부착하는 대안보다 변경 지점이 1곳이고, CI가 이미 하던 일을 빌드 스크립트로 내리는 것이라 일관성이 높다.

**기각**: 기본값을 `prod`로 바꾸는 안 — 로컬 개발이 즉시 깨지고, 실수 방향이 "운영 설정으로 개발"이 되어 더 위험하다.

문제 2 — **(v2 신규, v5 표현 정정)** Spring Boot는 복수 프로파일 동시 활성화를 허용한다(`SPRING_PROFILES_ACTIVE=dev,prod`도 유효한 값). 이 경우 `@Profile("dev")`인 `TestMemberLoader`(회원 0명일 때만 시드)와 `@Profile("prod")`인 `AdminBootstrapLoader`(결정 4, **v4부터 `existsByUserTypeAndStatus(ROLE_ADMIN, ACTIVE)`가 false일 때만 동작**)가 **둘 다** 실행 후보가 되고, 트리거 조건은 서로 다르지만 실행 순서가 보장되지 않는 것은 동일하다 — dev 로더가 먼저 실행되면 약한 고정 자격증명(`admin`/`1234`)이 prod급 환경에 생길 수 있다. 이는 codex 리뷰 1차(차단1)에서 지적된 실제 결함이다.

**채택**: `com.cms.config.ProfileGuardEnvironmentPostProcessor`(신규, `org.springframework.boot.env.EnvironmentPostProcessor` 구현)를 `src/main/resources/META-INF/spring.factories`에 등록한다. 이 인터페이스는 `ApplicationContext`가 만들어지기 **전**, 즉 `TestMemberLoader`·`AdminBootstrapLoader` 같은 빈이 생성되기 전에 실행되므로, `environment.getActiveProfiles()`에 `dev`와 `prod`가 동시에 포함되면 `IllegalStateException`을 던져 기동 자체를 막는다. 개별 로더에 `@Profile("dev & !prod")` 같은 방어 표현식을 추가하는 대안도 검토했으나, 이 방식은 로더가 늘어날 때마다 각각 신경 써야 하는 반복 지점이 생긴다 — 진입점 하나(EnvironmentPostProcessor)에서 조합 자체를 거부하는 편이 더 강한 보장이고 새 프로파일 전용 컴포넌트가 추가돼도 자동으로 방어된다.

**(v3 추가) 활성 프로파일이 0개인 경우도 거부한다.** codex 리뷰 2차(중간6)에서 지적된 대로, `${SPRING_PROFILES_ACTIVE}`는 환경변수가 아예 없으면 placeholder 해석 실패로 기동이 막히지만, 환경변수가 **빈 문자열**로 정의되면(`SPRING_PROFILES_ACTIVE=`) 해석 자체는 성공해 활성 프로파일이 0개인 채로 조용히 기동될 수 있다 — 이 경우 `default` 프로파일만 적용돼 우리가 막으려던 "프로파일 누락" 상황과 사실상 동일해진다. `ProfileGuardEnvironmentPostProcessor`는 `dev`+`prod` 동시 활성화뿐 아니라 `environment.getActiveProfiles().length == 0`인 경우도 `IllegalStateException`으로 거부한다. 테스트 환경은 `build.gradle`의 test 태스크가 `SPRING_PROFILES_ACTIVE=dev`를 OS 환경변수로 주입하므로(결정 1 앞부분), `@ActiveProfiles`를 따로 선언하지 않는 슬라이스 테스트도 최소 1개(`dev`)의 활성 프로파일을 항상 갖게 되어 이 가드에 걸리지 않는다.

### 2. 공통 설정을 `application.yml`로 올려 prod 누락 사고를 구조적으로 막는다

현재 `ddl-auto: validate`와 `management` 설정이 `application-dev.yml`에만 있다. prod 파일을 새로 쓰면서 이 둘을 빠뜨리면 조용히 안전장치가 사라진다(`ddl-auto` 미설정 시 Hibernate 기본 동작, actuator는 Boot 기본 노출로 회귀).

**채택**: 프로파일 무관하게 항상 참인 값은 `application.yml`로 승격한다.
- `spring.jpa.hibernate.ddl-auto: validate` — Flyway 전용 정책이라 전 프로파일 공통
- `management.endpoints.web.exposure.include: health` + `management.endpoint.health.show-details: never` — 안전한 기본을 전 프로파일 공통으로 둔다.
- `management.health.mail.enabled: false` — 메일 서버 상태가 앱 health를 좌우하지 않게 하는 정책이라 공통

**(v2 수정)** 기존 dev 설정의 `info` 추가 노출은 **제거**한다(공통값 그대로 `health`만 사용). codex 리뷰 1차(차단5)에서 지적된 대로, 결정 3에서 신설하는 `/actuator/** denyAll()`은 프로파일과 무관하게 적용되는 Security 규칙이라 dev에 `info`를 남겨둬도 Security 레이어에서 막혀 실제로는 동작하지 않는 모순이 생긴다. `/actuator/info`는 코드베이스 어디에서도 참조되지 않아(정찰 확인) 잃을 게 없고, "노출 설정과 Security 규칙이 항상 일치한다"는 더 단순하고 안전한 불변식을 얻는다. `application-dev.yml`의 `management` 블록은 이번 변경으로 완전히 비워진다(공통값을 그대로 상속).

프로파일 파일에는 **차이(datasource·mail 자격증명·show-sql·springdoc 토글)만** 남긴다. 이렇게 하면 prod 파일에서 무언가를 빠뜨려도 공통 기본값이 안전한 쪽이다.

### 3. actuator는 노출 제한 + SecurityConfig 명시 규칙 이중 방어

`management.endpoints.web.exposure.include: health`만으로도 다른 엔드포인트는 404가 되지만, 설정 한 줄이 실수로 넓혀지면 즉시 무인증 공개로 돌아가는 구조다(`anyRequest().permitAll()`가 뒤에 있으므로).

**채택**: `SecurityConfig`에 `/actuator/health`만 `permitAll()`, `/actuator/**`는 `denyAll()`을 명시 추가한다. `/notices`에서 이미 검증된 "GET permitAll + 나머지 denyAll" 패턴(`SecurityConfig.java:63-65`)과 동일한 형태다.

**(v2 추가) 테스트가 실제로 이 규칙을 증명하도록 설계를 고정한다.** codex 리뷰 1차(차단5)에서 지적된 대로, 현재 `SecurityConfigTest`는 명시된 스텁 컨트롤러만 로드하는 `@WebMvcTest`다 — `/actuator/env`에 대응하는 핸들러 자체를 등록하지 않으면, `denyAll()` 규칙을 실제로 추가하지 않아도 (핸들러가 없어 발생하는) 404로 테스트가 거짓 통과할 수 있다. 두 가지를 분리해서 검증한다:

1. **Security 규칙 검증** (`SecurityConfigTest`, 자동화): `/notices` 검증에 쓴 것과 동일한 패턴으로 `/actuator/env` 경로에 응답하는 최소 스텁 컨트롤러(`ActuatorEnvStubController`, 200 반환)를 테스트에서 명시 등록한 뒤, `denyAll()`이 실제로 막는지 검증한다. `/actuator/**`는 `/admin/api/**`가 아니므로 `PLAN-public-notice.md`(v4)에서 이미 실측 확정된 패턴을 그대로 따른다: **비인증 GET → 302(`authenticationEntryPoint`를 통해 `/admin/login`으로 리다이렉트)**, **인증된 `@WithMockUser(roles="ADMIN")` GET → 정확히 403**(커스텀 `accessDeniedHandler`의 비-API 분기). "404 또는 403" 같은 모호한 기대값은 쓰지 않는다. `/actuator/health`는 비인증 GET → 200(무인증 공개 유지)도 같은 테스트 클래스에서 확인한다.
2. **(v3 수정, v4 보강) 노출 설정 검증** — HTTP가 아니라 컨텍스트 레벨 자동화 테스트로 전환한다. codex 리뷰 2차(차단2)에서 지적된 대로, `denyAll()`이 Security 필터 단계에서 모든 요청을 가로채므로(미인증 302, 인증됨 403) **HTTP 응답만으로는 `/actuator/env`가 진짜 미등록인지, 등록됐지만 거부된 것인지 구분할 수 없다** — v2가 계획한 "정확히 404" 실기 검증은 애초에 관측 불가능한 조건이었다. 대신 `management.endpoints.web.exposure.include: health`가 결정 2에서 **전 프로파일 공통값**이 됐다는 점을 활용해, `org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier`를 주입해 실제 등록된 웹 엔드포인트 집합이 `{health}` 하나뿐인지 검증하는 `ActuatorExposureTest`(신규)를 추가한다. codex 리뷰 3차에서 이 API 자체(`WebEndpointsSupplier#getEndpoints()`+`getEndpointId()`)가 Spring Boot 3.5에서 유효함을 직접 대조 확인했으므로 "API를 못 찾으면 축소" 같은 폴백은 두지 않는다.

**(v4 보강, codex 리뷰 3차 높음4 반영)** 세 가지를 추가로 확정한다:
- `ActuatorExposureTest`는 `@SpringBootTest(classes = CmsTestApplication.class)`만으로는 DB·메일 프로퍼티가 없어 컨텍스트가 뜨지 않는다 — `MariaDbContainerSupport`를 상속(다른 `@SpringBootTest` 통합 테스트와 동일 패턴)한다.
- `WebEndpointsSupplier` 컨텍스트 테스트는 dev 프로파일로 뜨므로, "`application-prod.yml`이 공통 `management` 설정을 오버라이드하지 않았다"는 사실 자체는 증명하지 못한다(별도 prod 전용 `@SpringBootTest` 컨텍스트를 새로 띄우는 것은 datasource·mail·부트스트랩 변수까지 전부 채워야 해 비용이 과하다고 판단). 대신 `application-prod.yml` 파일을 파싱해 `management`로 시작하는 속성이 존재하지 않는지 확인하는 경량 테스트(예: `ApplicationProdYamlTest` 또는 `ActuatorExposureTest`에 케이스 추가)를 더한다 — "공통값을 건드리지 않았다"는 정적 사실은 파일을 직접 읽어 확인하는 편이 새 컨텍스트를 띄우는 것보다 훨씬 저렴하고 명확하다. 실제 prod 기동에서 `/actuator/health`가 200, 나머지가 302/403으로 응답하는지는 Docker 실기 검증이 최종적으로 증명한다.

  **(v5 수정) 단순 루트 키 검사가 아니라 Spring이 실제로 로딩하는 방식과 동일하게 평탄화해서 검사한다.** codex 리뷰 4차(중간4)에서 지적된 대로, 일반 YAML 라이브러리로 최상위 `management:` 키만 확인하면 `management.endpoints.web.exposure.include: "*"`처럼 **점(dot) 표기로 평탄화된 키**를 놓칠 수 있다. `org.springframework.boot.env.YamlPropertySourceLoader`(Spring Boot가 실제로 `application-prod.yml`을 로딩할 때 쓰는 바로 그 클래스)로 파일을 읽어 `PropertySource`로 변환한 뒤, 그 안의 모든 프로퍼티 이름 중 `management.`로 시작하는 것이 0건인지 확인한다 — 파일 형식이 어떻게 쓰여 있든(중첩 맵이든 점 표기든) Boot가 실제로 해석하는 최종 프로퍼티 이름 기준으로 검증되므로 우회 가능성이 없다.
- `SecurityConfigTest`가 `/actuator/health` 200을 검증하려면, `@WebMvcTest`가 지정한 컨트롤러만 로드한다는 기존 관례상 `/actuator/env` 스텁뿐 아니라 `/actuator/health` 응답용 스텁도 별도로 등록해야 한다 — 실제 actuator 자동 구성이 이 슬라이스에 로드되지 않기 때문이다.

두 검증을 분리하는 이유: (1)은 "SecurityConfig가 막는다"를 증명하고, (2)는 "애초에 열려 있지도 않다"를 증명한다 — 하나로 뭉치면 어느 쪽이 실제로 방어하고 있는지 테스트만으로는 알 수 없다.

### 4. 부트스트랩 러너 — "ACTIVE ROLE_ADMIN 존재 여부"로 검사 자체를 분기하고, 없으면 fail-fast한다 (v3 전면 재설계, v4 동시성·검증객체 재수정)

`TestMemberLoader`(`@Profile("dev")`, 회원 0명일 때만 시드)의 구조를 참고하되 prod용으로 새로 설계한다(트리거 조건 자체가 다르므로 단순 미러링은 아니다).

**(v2) codex 리뷰 1차(차단2·차단3·높음4)를 통합 반영해 재설계.** v1은 세 가지 문제가 있었다:
1. `USER_ID`·`PASSWORD` 두 변수만 정의했는데 `Member`는 `userName`·`email`도 NOT NULL이라 구현자가 계획에 없는 값을 임의로 채워야 했다(차단2).
2. "회원이 있는 환경에서 변수를 지워도 기동돼야 한다"는 이유로 **빈 DB에서 변수가 없어도** 경고만 남기고 정상 기동시켰다 — 그 결과 "앱은 뜨지만 로그인 가능한 관리자가 0명"인, 겉보기엔 성공한 실패 상태가 만들어졌다(차단3). **사용자 결정(2026-07-29)**: 이 경우 기동을 실패시키는 쪽으로 정책을 바꾼다.
3. `count()==0` 확인 후 `save()`까지 원자적이지 않아 동시 기동 시 경합이 있었다(높음4).

**(v3 수정) 트리거 조건을 "회원 존재"에서 "ACTIVE ROLE_ADMIN 존재"로 강화.** codex 리뷰 2차(높음3)에서 지적된 대로, `count()>0`은 `ROLE_USER`만 있거나 관리자가 전부 `DISABLED`/`LOCKED`/`PASSWORD_EXPIRED`인 DB에서도 참이 되어 "관리 불가능한데 정상 기동처럼 보이는" 상태를 만든다. 스키마가 `ROLE_USER`를 허용하고(`V1__init_schema.sql:29`) `CustomUserDetails`가 역할과 무관하게 인증 객체를 만든다는 사실(역할 필터링은 URL 인가 레벨에서만 이뤄짐)을 실측으로 확인했다. **사용자 결정(2026-07-29)**: "ACTIVE ROLE_ADMIN 존재 여부"로 강화한다.

**(v4 수정) 트리거 쿼리는 기존 잠금 쿼리를 재사용하지 않고 신규 비잠금 존재 쿼리를 추가한다.** v3은 "최후 활성 ADMIN 가드"가 쓰는 `MemberRepository.findActiveAdminIdsForUpdate()`(네이티브 `FOR UPDATE`)를 재사용하려 했으나, codex 리뷰 3차(차단1)에서 두 가지 결함을 지적받았다: (1) Spring Data JPA의 커스텀 `@Query` 메서드는 호출자가 `@Transactional`이 아니면 잠금 자체가 성립하지 않는데, 이 트리거 판정은 단순 존재 확인일 뿐이라 트랜잭션을 새로 감쌀 이유가 없었다. (2) 잠금 쿼리를 존재 확인에 쓰면 인덱스 없는 `user_type`/`status` 컬럼 스캔이 MariaDB의 갭 락과 얽혀 불필요한 경합을 만들 수 있다. **`MemberRepository`에 비잠금 파생 쿼리 `boolean existsByUserTypeAndStatus(Role userType, MemberStatus status)`를 신규 추가**하고 이것으로 트리거를 판정한다 — 진짜 직렬화가 필요한 지점(동시 부트스트랩 저장 경합)은 애초에 `Member.userId` 유니크 제약이 맡고 있으므로(아래 "동시성" 참조), 트리거 판정 자체에는 잠금이 필요 없다.

**재설계된 로직 (v4)**:
```
1. memberRepository.existsByUserTypeAndStatus(Role.ROLE_ADMIN, MemberStatus.ACTIVE)가
   true이면 → 즉시 반환 (환경변수 검사 자체를 하지 않음)
2. (여기부터 "관리 가능한 관리자 없음") ADMIN_BOOTSTRAP_USER_ID · ADMIN_BOOTSTRAP_PASSWORD ·
   ADMIN_BOOTSTRAP_EMAIL 세 변수를 모두 읽는다.
3. 하나라도 없거나 공백이면 → IllegalStateException으로 기동 실패
   (에러 메시지에 어떤 변수가 빠졌는지 "이름"만 포함, 값은 포함하지 않음)
4. AdminBootstrapCredentials(아래 "검증 계약" 참조)로 값을 담고 Validator를 주입받아
   프로그래매틱하게 검증한다. 위반 시 필드명만 담은 IllegalStateException으로
   기동 실패(값 노출 금지).
5. userName은 userId를 그대로 사용한다(표시용 필드라 값 자체보다 NOT NULL 충족이 목적 —
   4번째 환경변수를 추가하지 않기 위한 의도적 선택).
6. (v5 수정 — TransactionTemplate으로 경계 명시) 주입받은 TransactionTemplate으로
   Member 저장(saveAndFlush)을 감싼다: transactionTemplate.execute(status -> { ...save... }).
   이 실행이 DataIntegrityViolationException을 던지면 바깥(부트스트랩 러너 본체,
   @Transactional 아님)에서 그 예외를 잡는다.
7. 예외를 잡은 뒤, **같은 TransactionTemplate 인스턴스로 두 번째 독립 실행**을 만들어
   memberRepository.findByUserId(bootstrapUserId)를 깨끗한 새 트랜잭션에서 재조회한다.
   - 존재하고 userType == ROLE_ADMIN && status == ACTIVE이면 →
     "다른 인스턴스가 이미 이 부트스트랩 계정을 생성함"으로 판단해 info 로그만
     남기고 정상 진행(원 예외를 재던지지 않음).
   - 존재하지 않거나, 존재해도 역할·상태가 다르면(예: 우연히 같은 userId를 가진
     ROLE_USER나 DISABLED 계정) → 진짜 다른 원인이므로 원 예외를 그대로 전파해
     기동 실패.
```

**(v5 수정) 트랜잭션 경계는 `@Transactional` 메서드 분리가 아니라 `TransactionTemplate`으로 명시한다.** codex 리뷰 4차(차단1)에서 지적된 대로, v4의 "별도 `@Transactional` 메서드로 저장"이라는 서술은 그 메서드가 **같은 클래스 안**에 있으면 Spring의 프록시 기반 `@Transactional`이 자기 자신을 호출할 때(self-invocation)는 적용되지 않는다는 근본적인 함정을 감추고 있었다 — 이 실수를 하면 저장이 아예 트랜잭션 없이 실행되거나, 재조회가 실패한 트랜잭션에 얹혀 rollback-only 예외를 다시 던지게 된다. 이 프로젝트는 이미 정확히 같은 문제를 `PasswordResetService`에서 겪었고, 그 해결책(`TransactionTemplate`을 주입받아 `execute(status -> ...)`로 트랜잭션 경계를 코드에서 직접 표시)이 이미 검증된 관례로 존재한다(`PasswordResetService.java:99-100,114` — "같은 클래스 내부 호출은 `@Transactional` 프록시를 타지 않으므로 경계는 `TransactionTemplate`으로 코드에 명시한다"). `AdminBootstrapLoader`도 동일 패턴을 그대로 재사용한다 — 별도 빈을 새로 만들지 않고, 프로젝트에 이미 있는 관용구를 따른다. **(v6 추가)** 이 문서 전체(특히 "작업 단계")에서 "별도 `@Transactional` 메서드"라는 폐기된 표현이 남아있지 않도록 확인했다 — codex 리뷰 5차(차단1)에서 작업 단계 6에 그 표현이 남아있는 것을 지적받아 정정했다.

**(v6 추가) 저장·재조회 로직은 테스트가 트리거 검사를 우회해 직접 호출할 수 있도록 package-private으로 분리한다.** codex 리뷰 5차(높음2)에서 지적된 대로, 동시성 통합 테스트가 `AdminBootstrapLoader.run()` 전체(트리거 검사부터)를 두 번 호출하는 방식으로는 두 번째 호출이 1단계 `existsByUserTypeAndStatus()`에서 이미 `true`를 보고 즉시 반환해버려 정작 검증하려는 저장 충돌·재조회 경로가 전혀 실행되지 않는다. 저장(`TransactionTemplate.execute`로 `saveAndFlush`) + 충돌 시 재조회(`TransactionTemplate.execute`로 `findByUserId`+역할·상태 확인)를 묶은 로직을 `AdminBootstrapLoader`의 package-private 메서드(예: `createOrReconcile(AdminBootstrapCredentials)`)로 분리한다 — `run()`은 트리거 검사 후 이 메서드를 호출하고, 테스트(같은 패키지)는 트리거 검사를 건너뛰고 이 메서드를 직접 두 번 호출해 충돌 경로를 결정적으로(deterministic) 재현한다.

**(v3 신규, v4 표현 수정) 부트스트랩 전용 검증 계약**: codex 리뷰 2차(높음5)에서 "기존 관리자 생성 DTO(`AdminSignupRequest`)의 검증 재사용"이라는 v2의 전제가 부정확함을 지적받았다 — 실측 확인 결과 `AdminSignupRequest.pwd`는 `@NotBlank`뿐이고 길이·강도 정책이 없다(`AdminSignupRequest.java:21-23`). 대신 정찰로 앱 전역에서 **실제로 쓰이는** 정책을 다시 확인했다:
- 비밀번호: `AdminMyPasswordChangeRequest.newPassword`·`PasswordResetConfirmRequest.newPassword`가 공통으로 쓰는 `@Size(min = 4, max = 100)` — 이것이 앱의 실질적인 비밀번호 정책이다. 부트스트랩도 동일하게 `@NotBlank @Size(min = 4, max = 100)`를 적용한다.
- 이메일: `AdminMyInfoUpdateRequest.email`·`AdminMemberUpdateRequest.email`이 공통으로 쓰는 `@Email @Size(max = 100)`(DB 컬럼 `email varchar(100)`과 일치, `V1__init_schema.sql:22`). 부트스트랩도 동일하게 적용.
- userId: DB 컬럼 `user_id varchar(50)`(`V1__init_schema.sql:27`)과 `AdminMemberSearchRequest.userId`의 `@Size(max = 50)` 관례에 맞춰 `@NotBlank @Size(max = 50)`.

**(v4 수정) `AdminBootstrapCredentials`는 record가 아니라 일반 클래스로 만들고 `toString()`을 재정의한다.** codex 리뷰 3차(높음3)에서 지적된 대로, Java record는 모든 컴포넌트를 그대로 나열하는 기본 `toString()`을 생성하므로 "비밀번호 값은 어떤 경우에도 로그에 출력하지 않는다"는 원칙과 충돌한다 — `PasswordResetService.IssueResult`가 민감 필드를 가리려고 `toString()`을 명시적으로 재정의한 것과 동일한 이유로, `AdminBootstrapCredentials`도 일반 `final` 클래스로 선언하고 `toString()`이 `userId`만 노출하도록(비밀번호·이메일은 제외) 재정의한다. 이 클래스를 신설하고 기존 DTO(`AdminSignupRequest` 등)를 재사용하지 않는다 — 재사용을 시도하면 회원가입 API 전용 필드(`userType` 등)가 섞여 들어가 오히려 계약이 불명확해진다. "기존 검증 재사용"이 아니라 "**앱이 실제로 쓰는 정책값과 일치시킨 신규 검증**"으로 표현을 정정한다.

**왜 트리거를 이렇게 나누나**: "관리 가능한 관리자 존재"와 "환경변수 존재"를 하나의 스위치로 묶었던 v1의 결함이 모순의 근원이었다 — 정상 운영 환경(활성 ADMIN 있음)에서 변수를 지워도 여전히 기동되고(가용성 유지), 관리 가능한 관리자가 전혀 없는데 자격증명도 없는 것은 "관리자를 못 만드는 게 아니라 관리 불가능한 채로 뜨는 것 자체가 사고"이므로 fail-fast로 막는다(사용자 결정). 두 상황을 같은 검사로 취급하지 않으면 모순이 해소된다.

**동시성 (v4 수정)**: 두 인스턴스가 동시에 "관리 가능한 관리자 없음"을 보고 저장을 시도해도, `Member.userId`의 유니크 제약(`uk_member_user_id`)이 실제 직렬화 지점 역할을 한다 — 완전한 분산 락을 새로 도입하지 않고 기존 DB 제약을 재사용한다(트리거 판정에는 잠금이 필요 없다는 것이 결정 4의 핵심 — 위 "트리거 쿼리" 참조). 경합 발생 시 "아무 회원이나 존재한다"가 아니라 "**내가 만들려던 그 계정이, 내가 만들려던 바로 그 역할·상태로 실제 존재한다**"(`findByUserId` + `ROLE_ADMIN`·`ACTIVE` 확인, 위 로직 7번)를 확인해야 다른 무결성 오류나 우연한 userId 충돌을 성공으로 오판하지 않는다. 이 재확인은 저장 시도와 별도의 트랜잭션에서 이뤄져야 한다(rollback-only 트랜잭션 안에서 조회하면 안 되므로, 위 로직 6·7번). 값이 다른 두 세트의 부트스트랩 변수가 동시에 주입되는 경우(서로 다른 컨테이너에 서로 다른 `.env.prod`)까지는 방어하지 않는다 — 이는 배포 파이프라인이 동일한 설정을 모든 인스턴스에 배포한다는 일반적인 전제를 벗어난 상황이라 이번 범위의 위험 수용으로 남긴다.

**공통 사항 (v1에서 유지)**:
- `@Profile("prod")` + `CommandLineRunner`, `TestMemberLoader`와 같은 패키지(`com.cms.admin.member`)에 `AdminBootstrapLoader`로 신설
- 비밀번호는 `passwordEncoder.encode()`로 해시, `passwordChangedAt`은 `LocalDateTime.now(clock)` — 기존 `TestMemberLoader`와 동일하게 주입된 `Clock` 사용
- **비밀번호 값은 어떤 경우에도 로그에 출력하지 않는다** (`PasswordResetService`의 토큰 로깅 금지 규약과 동일 선상) — 검증 실패 메시지·`toString()`에도 필드명만 담고 값은 담지 않는다

**(v5 신규) 검증은 단위 테스트만으로 끝내지 않는다.** codex 리뷰 4차(차단1)에서 지적된 대로, `TransactionTemplate` 기반 경계 분리가 실제로 작동하는지는 Mockito 단위 테스트(트랜잭션이 mock되어 rollback-only 등 실제 JPA/DB 동작을 재현하지 못함)로는 증명할 수 없다 — `MariaDbContainerSupport`를 상속하는 통합 테스트를 별도로 추가해, 실제 MariaDB에 대해 동일 자격증명으로 두 스레드(또는 두 순차 호출로 유니크 제약 위반을 인위 재현)가 경합할 때 한쪽은 저장에 성공하고 다른 쪽은 재조회로 정상 처리되는지 확인한다(테스트 계획에 반영).

**기각**: 기동 시 항상 계정을 강제 생성/갱신하는 안 — 운영 중 환경변수가 남아 있으면 비밀번호가 매 재시작마다 되돌아가는 사고가 난다.

### 5. springdoc은 `enabled: false`로 끄고, SecurityConfig 규칙은 손대지 않는다

`springdoc.api-docs.enabled: false` + `swagger-ui.enabled: false`면 핸들러 자체가 등록되지 않아 404가 된다. `SecurityConfig.java:50`의 `hasRole("ADMIN")` 규칙은 그대로 두어도 무해하며(매칭될 경로가 없음), dev에서는 계속 필요하다. 불필요한 인가 규칙 변경을 만들지 않는다.

### 6. `.dockerignore` 신설은 이번 범위의 필수 항목

시크릿이 빌더 레이어에 들어가는 것은 prod 준비의 전제 조건이다. `.env*`, `.git`, `build/`, `out/`, `.gradle/`, `.idea/`, `data/`, `/*.png`(v3 수정 — 아래 참조), `adversarial-review/`, `.claude/`를 제외한다. 부수 효과로 빌드 컨텍스트 전송량도 줄어든다.

**(v3 수정)** `*.png`는 저장소 어디든 재귀적으로 매칭돼 향후 `src/main/resources/static/`에 정적 PNG 자산이 추가되면 함께 배제될 수 있다는 지적(codex 리뷰 2차, 낮음9)을 인정 — 정찰에서 확인된 실제 대상(루트의 회귀 검증 스크린샷들, `.gitignore`의 `/*.png` 규칙과 동일 범위)만 제외하도록 `/*.png`(루트 한정)로 좁힌다.

**(v2 추가) 검증 방법 자체가 틀렸었다.** codex 리뷰 1차(높음7)에서 지적된 대로, `Dockerfile`은 멀티스테이지 빌드라 최종 이미지에는 builder 스테이지의 `COPY . .` 레이어가 아예 포함되지 않는다(`COPY --from=builder /workspace/build/libs/*.jar app.jar`만 최종 이미지로 넘어온다) — 최종 이미지의 `docker history`를 봐도 `.dockerignore`가 실제로 작동했는지 증명할 수 없다. **검증은 builder 스테이지만 별도로 빌드해서 확인한다**: `docker build --target builder -t cms-builder-check .` 후 `docker run --rm cms-builder-check sh -c "find /workspace -maxdepth 1 -iname '.env*'"`로 빈 결과가 나오는지 확인한다(검증 섹션에 반영).

### 7. prod compose는 서비스별로 필요한 환경변수만 명시 주입한다 (v2 신규)

codex 리뷰 1차(높음6)에서 지적된 대로, v1처럼 `.env.prod` 하나를 두 서비스의 `env_file:`로 그대로 쓰면 **DB 컨테이너가 SMTP·부트스트랩 비밀번호까지 받고, 앱 컨테이너가 DB root 비밀번호까지 받는다** — `env_file:`은 파일의 변수 전체를 해당 컨테이너 환경에 주입하기 때문이다.

**채택**:
- `.env.prod`는 모든 키의 단일 소스로 유지하되(운영 편의), 각 서비스는 `env_file:` 대신 `environment:`에 **필요한 변수만** `${VAR}` 형태로 명시 나열한다.
  - `db` 서비스: `MYSQL_ROOT_PASSWORD`, `MYSQL_DATABASE`, `MYSQL_USER`, `MYSQL_PASSWORD`
  - `app` 서비스: `SPRING_PROFILES_ACTIVE=prod`(리터럴), `DB_URL`, `DB_USER`, `DB_PASS`(=`MYSQL_USER`/`MYSQL_PASSWORD`와 동일 값을 다른 변수명으로 — **DB 앱 계정과 root 계정은 분리**), `MAIL_USER`, `MAIL_PASS`, `ADMIN_BOOTSTRAP_USER_ID`, `ADMIN_BOOTSTRAP_PASSWORD`, `ADMIN_BOOTSTRAP_EMAIL`, `APP_BASE_URL`, `APP_FILE_STORAGE_ROOT`
- **(v3 수정) 필수 변수 표기는 항상 참인 것에만 쓴다.** codex 리뷰 2차(차단1)에서 지적된 대로, v2처럼 `ADMIN_BOOTSTRAP_*` 세 변수까지 `${VAR:?}`로 강제하면 결정 4의 "부트스트랩 성공 후 변수 제거" 절차(바로 아래)와 정면으로 모순된다 — 변수를 지우면 이미 관리자가 있는 정상 운영 환경에서도 Compose가 앱을 띄우기 전에 기동을 거부해버린다. `ADMIN_BOOTSTRAP_*` 세 변수만 `${VAR-}`(미설정 시 빈 문자열)로 **선택 입력**으로 낮춘다. "관리 가능한 관리자가 없는데 이 값들도 없다"는 필수성 판단은 결정 4의 `AdminBootstrapLoader`가 애플리케이션 레벨에서 전담한다 — Compose는 무조건 요구할 근거가 없다(ACTIVE ROLE_ADMIN 존재 여부를 compose가 알 방법이 없으므로).
- **(v4 수정) 나머지 변수의 필수 목록을 정확히 못 박고, 앱의 DB 계정은 `MYSQL_*` 키를 직접 매핑한다.** codex 리뷰 3차(중간6)에서 "다른 값은 예시가 아니라 정확한 전체 목록이 필요하다"는 지적을 인정 — `${VAR}`는 미설정 시 경고만 내고 빈 문자열로 조용히 대체될 수 있어(`${VAR:?}`와 다름) 필수 변수는 반드시 `:?`를 붙여야 한다. 전체 목록을 다음과 같이 확정한다:
  ```yaml
  # db
  MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD:?필수 환경변수입니다}
  MYSQL_DATABASE:      ${MYSQL_DATABASE:?필수 환경변수입니다}
  MYSQL_USER:           ${MYSQL_USER:?필수 환경변수입니다}
  MYSQL_PASSWORD:       ${MYSQL_PASSWORD:?필수 환경변수입니다}

  # app
  SPRING_PROFILES_ACTIVE: prod
  DB_URL:            jdbc:mariadb://db:3306/${MYSQL_DATABASE:?필수 환경변수입니다}
  DB_USER:           ${MYSQL_USER:?필수 환경변수입니다}      # 별도 키를 두지 않고 db 서비스와 동일 소스를 직접 매핑 — 값 drift 차단
  DB_PASS:           ${MYSQL_PASSWORD:?필수 환경변수입니다}  # 위와 동일한 이유
  MAIL_USER:         ${MAIL_USER:?필수 환경변수입니다}
  MAIL_PASS:         ${MAIL_PASS:?필수 환경변수입니다}
  APP_BASE_URL:      ${APP_BASE_URL:?필수 환경변수입니다}
  APP_FILE_STORAGE_ROOT: /app/data/attachments   # dev compose와 동일 패턴 — 컨테이너 내부 절대경로는 .env 키가 아니라 리터럴 고정값
  ADMIN_BOOTSTRAP_USER_ID: ${ADMIN_BOOTSTRAP_USER_ID-}
  ADMIN_BOOTSTRAP_PASSWORD: ${ADMIN_BOOTSTRAP_PASSWORD-}
  ADMIN_BOOTSTRAP_EMAIL: ${ADMIN_BOOTSTRAP_EMAIL-}
  ```
  `DB_USER`/`DB_PASS`를 `.env.prod`의 별도 키로 두지 않고 `MYSQL_USER`/`MYSQL_PASSWORD`를 직접 참조하게 한 이유: 별도 키를 두면 두 값이 실수로 어긋나는(예: `.env.prod`를 고칠 때 한쪽만 갱신) drift가 구조적으로 가능해진다 — 소스를 하나로 합치면 애초에 어긋날 수 없다.

  **(v5 수정) `DB_URL`도 같은 이유로 `MYSQL_DATABASE`에서 직접 조합한다.** codex 리뷰 4차(높음3)에서 지적된 대로, v4는 DB 계정(`MYSQL_USER`/`MYSQL_PASSWORD`) drift는 막았지만 `DB_URL`을 별도 `.env.prod` 키로 남겨둬 **DB 이름**이 `MYSQL_DATABASE`와 어긋날 수 있는 여지가 그대로 남아 있었다 — 위 표처럼 `DB_URL`을 `jdbc:mariadb://db:3306/${MYSQL_DATABASE:?}`로 compose 안에서 직접 조합해 `.env.prod`의 필수 목록에서 `DB_URL` 자체를 제거한다. 이는 **compose 경로에 한정된 수정**이다 — compose를 거치지 않고 `application-prod.yml`을 직접 실행하는 경우(결정 10)는 여전히 `DB_URL`이 기본값 없이 필요하며, 그 상황은 compose가 관여하지 않으므로 이 조합 자체가 적용되지 않는다.
- `docs/deployment.md`에 "부트스트랩 성공 후 `ADMIN_BOOTSTRAP_*` 세 변수를 `.env.prod`에서 제거하고 컨테이너를 재생성하라"는 절차를 명시한다(장기 보관 시 불필요한 평문 비밀번호 노출을 줄이기 위함) — 위 수정으로 이 절차와 Compose의 필수 검사가 더 이상 충돌하지 않는다.
- **(v5 신규) `.env.prod`가 "단일 소스"라는 말은 호스트 셸 환경변수를 배제하지 않는다.** codex 리뷰 4차(높음2)에서 지적된 대로, Docker Compose는 **호스트 셸에 이미 설정된 환경변수가 `--env-file`보다 우선**한다 — `.env.prod`에서 `ADMIN_BOOTSTRAP_PASSWORD`를 지워도 셸에 같은 이름의 변수가 남아 있으면 그 값이 여전히 컨테이너에 주입된다. 또한 `.env` 파일에서 따옴표 없는 값과 큰따옴표로 감싼 값은 `$` 보간 대상이 되므로(작은따옴표만 리터럴), 비밀번호에 `$`가 포함되면 의도와 다른 값으로 바뀔 수 있다. `docs/deployment.md`에 "`.env.prod`의 시크릿 값은 항상 작은따옴표로 감싼다"는 규칙을 명시하고, `scripts/prod-up.sh`가 실행 시작 시 `ADMIN_BOOTSTRAP_USER_ID`·`ADMIN_BOOTSTRAP_PASSWORD`·`ADMIN_BOOTSTRAP_EMAIL`이 **호스트 셸에 이미 설정돼 있으면**(값은 출력하지 않고 변수명만) 경고를 출력한 뒤 `unset`하고 진행하도록 결정 8에 추가한다 — `.env.prod` 파일만이 유일한 입력임을 스크립트 레벨에서 강제한다.

### 8. prod compose의 포트 바인딩·헬스체크를 명확히 한다 (v2 신규)

codex 리뷰 1차(중간8)에서 지적된 대로, v1의 `expose: 8080`은 컨테이너 네트워크 **내부** 공개일 뿐 호스트에서 접근할 수 없다 — nginx를 빼기로 한 결정(사용자 확정 2번)과 조합하면 "compose는 기동되는데 아무도 접속할 수 없는" 상태가 된다.

**채택**:
- `ports: - "127.0.0.1:8080:8080"` — 루프백에만 바인딩한다. 실제 인터넷 노출은 리버스 프록시·TLS가 갖춰진 뒤(범위 밖)의 일이므로, 이번 범위에서는 로컬/같은 호스트에서의 검증만 가능하게 하고 `docs/deployment.md`에 "이 바인딩은 검증용이며 그대로 인터넷에 노출하면 안 된다"를 명시한다.
- MariaDB 헬스체크는 기존 dev compose 패턴(`mariadb-admin ping -h localhost -p$MYSQL_ROOT_PASSWORD`)을 쓰면 Compose가 `$MYSQL_ROOT_PASSWORD`를 셸 변수로 오인해 치환을 시도하므로 `$$MYSQL_ROOT_PASSWORD`로 이스케이프해야 한다(dev compose에는 없던 문제 — dev는 `env_file:`이라 이 헬스체크 자체가 컨테이너 내부 셸에서 실행되며 우연히 동작했을 가능성 확인 필요). 대안으로 mariadb 공식 이미지에 내장된 `healthcheck.sh --connect --innodb_initialized`를 우선 시도한다 — 명령줄에 비밀번호를 아예 넣지 않아 프로세스 목록 노출 위험이 없다. 두 방식 모두 구현 단계에서 `mariadb:10.11` 이미지에 실제로 존재하는지 확인 후 최종 확정한다.

**(v3 추가, v4 구체화) `docker compose up -d`의 성공이 앱 정상 기동을 의미하지 않는다.** codex 리뷰 2차(중간8)에서 지적된 대로, `up -d`는 컨테이너가 시작만 하면 성공을 보고한다 — 결정 4의 fail-fast(ACTIVE ROLE_ADMIN 없음+변수 없음 → 앱 종료)가 실제로 발동해도 `docker compose up -d`는 여전히 "성공"이라고 말할 수 있다.

**(v4 수정)** codex 리뷰 3차(높음5)에서 v3가 제시한 "`docker compose exec app curl ...` 또는 `docker compose ps`" 선택지가 실행 불가능한 명세였음을 지적받았다 — 최종 이미지(`eclipse-temurin:17-jre`)에는 `curl`이 설치돼 있지 않아 컨테이너 내부에서 `curl`을 실행할 수 없고, `docker compose ps`는 프로세스가 떠 있다는 것만 보여줄 뿐(healthcheck 미정의) 준비 완료를 증명하지 못한다. **결정을 확정한다**: 결정 8에서 이미 `127.0.0.1:8080:8080`으로 호스트 바인딩하기로 했으므로, `scripts/prod-up.sh`(신규)는 컨테이너 안이 아니라 **호스트에서** `curl`을 폴링한다. `curl`을 호스트에서 실행하므로 이미지에 `curl`을 추가로 설치할 필요가 없다.

**(v5 수정, v6 데드라인 구체화) 폴링 호출 자체에 타임아웃을 걸고, 실패 시 재시작 루프를 멈춘다.** codex 리뷰 4차(중간5)에서 지적된 대로, `curl --fail`만으로는 연결·응답 대기에 상한이 없어 서버가 연결만 받고 응답하지 않으면 폴링 루프의 한 번의 호출이 "최대 60초" 예산을 넘겨 오래 매달릴 수 있다. 호출을 다음처럼 고정한다:
```bash
curl --fail --silent --show-error --connect-timeout 2 --max-time 4 \
  http://127.0.0.1:8080/actuator/health
```
**(v6 수정, v7 정확도 수정)** codex 리뷰 5차(중간4)에서 "5초 간격 × N회" 같은 반복 횟수 기반 구현은 `curl` 호출 자체의 소요 시간(최대 4초)이 누적되어 실제 총 대기 시간이 60초를 넘을 수 있다는 지적을 인정 — bash의 `SECONDS`(스크립트 시작 후 경과 초를 자동 추적하는 내장 변수)로 **벽시계 기준 데드라인**을 건다. **(v7 수정)** codex 리뷰 6차(중간1)에서 v6의 루프가 데드라인을 `curl` 실패 **후**에만 검사해 마지막 반복의 새 `curl` 호출이 최대 4초까지 데드라인을 초과할 수 있다는 지적, codex 리뷰 6차(중간2)에서 `logs`·`stop` 명령을 단순 순차 실행하면 이 프로젝트 셸 스크립트 관례(`set -euo pipefail`)상 `logs` 실패 시 `stop`이 실행되지 않을 수 있다는 지적을 함께 반영해 다음처럼 확정한다:
```bash
deadline=$((SECONDS + 60))
while true; do
  remaining=$((deadline - SECONDS))
  if (( remaining <= 0 )); then
    docker compose -f docker-compose.prod.yml --env-file .env.prod logs app || true
    docker compose -f docker-compose.prod.yml --env-file .env.prod stop app || true
    exit 1
  fi
  timeout=$(( remaining < 4 ? remaining : 4 ))
  if curl --fail --silent --show-error --connect-timeout 2 --max-time "$timeout" \
       http://127.0.0.1:8080/actuator/health; then
    break
  fi
  remaining=$((deadline - SECONDS))
  if (( remaining <= 0 )); then
    docker compose -f docker-compose.prod.yml --env-file .env.prod logs app || true
    docker compose -f docker-compose.prod.yml --env-file .env.prod stop app || true
    exit 1
  fi
  sleep "$(( remaining < 5 ? remaining : 5 ))"
done
```
매 `curl` 호출 **전**에 남은 시간을 계산해 `--max-time`을 `min(4, 남은 시간)`으로 제한하므로 마지막 호출도 데드라인을 넘기지 않는다. `logs`·`stop` 모두 `|| true`로 실패를 흡수해, 어느 한쪽이 실패해도 `stop app`(재시작 루프 정지)이 항상 실행된다 — `down`이 아니라 `stop`이므로 컨테이너·로그·볼륨은 그대로 남아 사후 분석이 가능하다.

**(v5 신규, v6 범위 확장) 실행 시작 시 호스트 셸의 compose 참조 변수 전체를 방어한다.** codex 리뷰 4차(높음2)에서 지적된 대로(결정 7 참조), Docker Compose는 호스트 셸 환경변수를 `--env-file`보다 우선 적용한다. **(v6 수정)** codex 리뷰 5차(높음3)에서 v5의 방어가 `ADMIN_BOOTSTRAP_*` 세 변수에만 적용돼 "`.env.prod`가 유일한 입력"이라는 계약이 나머지 변수에는 성립하지 않는다는 지적을 인정 — `scripts/prod-up.sh`가 `docker compose up`을 호출하기 전에 검사·`unset`하는 대상을 **compose가 참조하는 전체 변수**로 확장한다: `MYSQL_ROOT_PASSWORD`, `MYSQL_DATABASE`, `MYSQL_USER`, `MYSQL_PASSWORD`, `MAIL_USER`, `MAIL_PASS`, `APP_BASE_URL`, `ADMIN_BOOTSTRAP_USER_ID`, `ADMIN_BOOTSTRAP_PASSWORD`, `ADMIN_BOOTSTRAP_EMAIL`. 셸에 설정돼 있으면 (값은 출력하지 않고 변수명만) 경고 로그를 남긴 뒤 `unset`하고 진행한다 — `.env.prod` 파일만이 이 변수들의 유일한 입력임을 스크립트가 보장한다.

**(v4 신규) `prod-down.sh`는 데이터를 보존한다.** codex 리뷰 3차(중간8)에서 지적된 대로, 운영 DB와 첨부파일이 named volume에 있으므로 `scripts/prod-down.sh`는 반드시 `docker compose -f docker-compose.prod.yml --env-file .env.prod down`만 사용하고 `-v`/`--volumes` 옵션을 쓰지 않는다 — 이 스크립트가 실수로 볼륨까지 삭제하는 일이 없도록 계약으로 명시한다. 볼륨을 실제로 비우고 싶은 경우는 이 스크립트의 책임이 아니며, 운영자가 별도로 명시적인 명령을 직접 실행해야 한다(스크립트화하지 않음 — 되돌릴 수 없는 작업을 원클릭으로 만들지 않기 위함).

### 9. Dockerfile 빌더를 저장소 Gradle Wrapper 버전과 일치시킨다 (v2 신규)

codex 리뷰 1차(낮음10)에서 지적된 대로, `Dockerfile:1`은 `gradle:8.7-jdk17` 이미지의 시스템 Gradle을 쓰는데 `gradle/wrapper/gradle-wrapper.properties`가 지정하는 버전은 8.12.1이다 — 로컬·CI(`./gradlew`로 실행)와 배포 이미지가 서로 다른 Gradle로 빌드되는 불일치가 있다.

**채택**: builder 베이스 이미지를 `eclipse-temurin:17-jdk`로 바꾸고 `./gradlew clean bootJar --no-daemon`을 쓴다. `gradlew`·`gradle/wrapper/`를 먼저 `COPY`해 레이어 캐시를 살린 뒤 전체 소스를 복사하는 순서로 조정한다.

**(v3 수정) wrapper 파일만 먼저 복사하는 것으로는 캐시가 성립하지 않는다.** codex 리뷰 2차(낮음9)에서 지적된 대로, `COPY gradlew gradle/wrapper/ ...`는 파일을 이미지에 올려놓을 뿐 Gradle Wrapper 배포판(실제 gradle-8.12.1-bin.zip)을 **다운로드**하지는 않는다 — 다운로드는 `./gradlew` 최초 실행 시 일어나므로, 전체 소스를 복사한 뒤의 `RUN ./gradlew clean bootJar`가 매번 다운로드까지 다시 하게 되어 캐시 효과가 없다. wrapper 관련 파일 복사 직후, 전체 소스 복사 **전**에 `RUN ./gradlew --version --no-daemon` 같은 가벼운 명령을 추가해 배포판 다운로드 자체를 별도 레이어로 캐시한다.

### 10. prod 프로파일 직접 실행 시 운영 필수값을 다시 한번 못 박는다 (v3 신규)

codex 리뷰 2차(중간7)에서 지적된 대로, `application.yml`(공통)의 `app.base-url: ${APP_BASE_URL:http://localhost:8080}`·`app.file-storage.root: ${APP_FILE_STORAGE_ROOT:./data/attachments}`(정찰 확인, `application.yml:32,37`)는 기본값이 있어 prod compose가 값을 강제해도, **compose를 거치지 않고** `SPRING_PROFILES_ACTIVE=prod`로 앱을 직접 실행하는 경우(예: 다른 오케스트레이터, 수동 배포)에는 로컬호스트 URL로 비밀번호 재설정 메일이 발송되거나 컨테이너 재시작 시 사라지는 상대 경로에 첨부파일이 쌓일 수 있다.

**채택**: `application-prod.yml`에 두 값을 기본값 없이 재선언한다.
```yaml
app:
  base-url: ${APP_BASE_URL}
  file-storage:
    root: ${APP_FILE_STORAGE_ROOT}
```
공통 파일의 기본값 있는 선언은 dev용으로 그대로 두고, prod 파일이 이를 오버라이드해 값이 없으면 prod 자체가 fail-fast하게 만든다(결정 1과 동일한 원칙 — 안전한 실패가 조용한 오작동보다 낫다).

## 작업 단계

의존 방향 안쪽부터. 각 단계 후 `./gradlew compileJava`로 컴파일 확인.

1. **브랜치**: `feat/prod-profile`
2. **설정 재편**: `application.yml` 공통 승격(결정 2) → `application-dev.yml` 정리(결정 2) → `build.gradle` test env 추가(결정 1) → **여기서 `./gradlew test` 전체를 돌려 결정 1의 파급이 실제로 흡수됐는지 조기 확인**
3. `ProfileGuardEnvironmentPostProcessor`(dev+prod 동시 활성화·활성 프로파일 0개 모두 거부) + `META-INF/spring.factories` + 테스트 (결정 1)
4. `application-prod.yml` 신규 작성 — datasource·mail 전량 환경변수(기본값 없음), `show-sql: false`, springdoc 비활성(결정 5), `app.base-url`·`app.file-storage.root` 기본값 없이 재선언(결정 10)
5. `SecurityConfig` actuator 규칙 추가(결정 3) + 스텁 컨트롤러(env·health) + `SecurityConfigTest` 케이스 추가 + `MemberRepository.existsByUserTypeAndStatus()` 신규 파생 쿼리 추가(결정 4) + `WebEndpointsSupplier` 기반 `ActuatorExposureTest`(`MariaDbContainerSupport` 상속) + `application-prod.yml` YAML 파싱 검증 테스트 추가
6. `AdminBootstrapCredentials`(일반 클래스, `toString()` 재정의, 부트스트랩 전용 검증 계약) + `AdminBootstrapLoader`(`existsByUserTypeAndStatus()` 기반 트리거, 저장·재조회는 각각 `TransactionTemplate.execute(...)`로 독립 실행, `findByUserId`+역할·상태 확인 기반 동시성 재확인) + `AdminBootstrapLoaderTest` + `AdminBootstrapConcurrencyIntegrationTest` (결정 4)
7. `.dockerignore`(결정 6) → `docker-compose.prod.yml`(결정 7·8, 필수 변수 전체 목록 확정·`ADMIN_BOOTSTRAP_*`만 선택 입력) → `scripts/prod-up.sh`(호스트 `curl` 헬스체크 폴링)·`prod-down.sh`(`-v` 금지 명시) → `Makefile` 타겟 추가
8. `Dockerfile` builder를 `./gradlew` 기반으로 전환 + wrapper 다운로드 레이어 캐시(결정 9)
9. `.env.example` 작성 (키 이름만, `ADMIN_BOOTSTRAP_*` 3종은 선택 입력으로 표기)
10. `./gradlew test` 전체 통과 재확인
11. Docker 실기 검증 (아래 검증 절차 — builder 스테이지 별도 빌드 검증 포함)
12. `/deploy-check` 스킬로 전수 점검
13. 문서화: `docs/deployment.md` 신규(부트스트랩 변수 제거 절차·127.0.0.1 바인딩 경고 포함), `README.md` 배포 섹션, `CLAUDE.md` 현행화 + 본 계획서에 구현·검증 결과 기록

### 신규 파일

```
src/main/resources/application-prod.yml
src/main/java/com/cms/config/ProfileGuardEnvironmentPostProcessor.java
src/main/resources/META-INF/spring.factories
src/test/java/com/cms/config/ProfileGuardEnvironmentPostProcessorTest.java
src/test/java/com/cms/config/ActuatorExposureTest.java          # WebEndpointsSupplier 기반 노출 설정 검증 + application-prod.yml management 키 부재 검증(결정3)
src/main/java/com/cms/admin/member/AdminBootstrapCredentials.java   # 부트스트랩 전용 검증 계약(결정4, 일반 클래스 — record 아님)
src/main/java/com/cms/admin/member/AdminBootstrapLoader.java
src/test/java/com/cms/admin/member/AdminBootstrapLoaderTest.java
src/test/java/com/cms/admin/member/AdminBootstrapConcurrencyIntegrationTest.java   # TransactionTemplate 경계 실제 DB 검증(결정4, v5)
.dockerignore
docker-compose.prod.yml
.env.example
scripts/prod-up.sh
scripts/prod-down.sh
docs/deployment.md
```

### 수정 파일

```
src/main/resources/application.yml       # :dev 기본값 제거(결정1), ddl-auto·management 공통 승격(결정2)
src/main/resources/application-dev.yml   # management 블록 완전 제거(결정2 v2 — info 노출 모순 해소)
src/main/java/com/cms/config/SecurityConfig.java   # /actuator/health permitAll + /actuator/** denyAll(결정3)
src/main/java/com/cms/admin/member/repository/MemberRepository.java  # existsByUserTypeAndStatus(Role, MemberStatus) 신규 파생 쿼리 추가(결정4, v4 — findActiveAdminIdsForUpdate() 재사용은 폐기)
build.gradle                              # test 태스크 SPRING_PROFILES_ACTIVE=dev(결정1)
Dockerfile                                # builder를 gradle:8.7-jdk17 → eclipse-temurin:17-jdk + ./gradlew로 전환(결정9)
Makefile                                  # prod-up/prod-down/logs-prod 타겟
README.md                                 # 배포 섹션 추가
CLAUDE.md                                 # 환경 설정·보안 규칙 섹션에 prod 반영
src/test/java/com/cms/config/SecurityConfigTest.java   # actuator 케이스 추가(결정3 — 스텁 컨트롤러 포함)
```

## 테스트 계획

- **`ProfileGuardEnvironmentPostProcessorTest`** (신규): `dev`만 활성 → 정상 / `prod`만 활성 → 정상 / `dev,prod` 동시 활성 → `IllegalStateException` / **(v3 추가)** 활성 프로파일 0개(빈 문자열) → `IllegalStateException` / 그 외 조합(`test`,`webmvc-test` 등) → 정상
- **`AdminBootstrapLoaderTest`**: **(v4 수정)** `existsByUserTypeAndStatus(ROLE_ADMIN, ACTIVE)`가 false(ACTIVE ROLE_ADMIN 없음) + 필수 3변수(USER_ID·PASSWORD·EMAIL) 전부 존재·유효 → ROLE_ADMIN 계정 1개 생성, `userName`=userId, 비밀번호 BCrypt 해시 확인 / **ACTIVE ROLE_ADMIN이 1명이라도 존재**(`existsByUserTypeAndStatus`=true) → 환경변수 유무와 무관하게 미동작 / `ROLE_USER`만 있거나 관리자가 전부 `LOCKED`인 경우(`existsByUserTypeAndStatus`=false) → 미동작하지 **않고** 정상적으로 부트스트랩을 시도 / 활성 ADMIN 없음 + 변수 중 하나라도 없음 → **`IllegalStateException`으로 기동 실패**(에러 메시지에 값 미노출 확인) / 활성 ADMIN 없음 + `AdminBootstrapCredentials` 검증 실패(이메일 형식 불량·비밀번호 4자 미만·userId 50자 초과 등) → 기동 실패, 값 미노출 / **(v4 수정)** 활성 ADMIN 없음 + 저장 시도 중 `DataIntegrityViolationException` 발생했으나 별도 트랜잭션에서 `findByUserId(bootstrapUserId)` 재조회 시 값이 존재하고 `userType==ROLE_ADMIN && status==ACTIVE` → 예외 삼키고 정상 진행 로그만 남김 / 재조회 결과가 없거나 역할·상태가 다르면(예: 같은 userId의 `ROLE_USER`) → 원 예외 전파 / **(v4 신규)** `AdminBootstrapCredentials.toString()` 결과에 비밀번호·이메일 원문이 없음을 확인(record가 아닌 일반 클래스의 재정의된 `toString()` 검증)
- **`SecurityConfigTest`**: 신규 `ActuatorEnvStubController`(`/actuator/env`, 200) + **(v4 신규)** `ActuatorHealthStubController`(`/actuator/health`, 200) 등록 후 — 비인증 `GET /actuator/health` → 200 / 비인증 `GET /actuator/env` → **정확히 302**(`/admin/login`로 리다이렉트, JSON 아님) / `@WithMockUser(roles="ADMIN")` `GET /actuator/env` → **정확히 403** / **(v3 추가)** 비인증 `GET /swagger-ui.html` → 302(기존 `hasRole("ADMIN")` 규칙 회귀 확인, 새로 생기는 동작 아님)
- **(v3 신규, v4 보강) `ActuatorExposureTest`**: `MariaDbContainerSupport`를 상속하는 `@SpringBootTest(classes = CmsTestApplication.class)` 컨텍스트에서 `WebEndpointsSupplier`가 실제로 등록한 웹 엔드포인트 집합이 `{health}` 하나뿐인지 확인(결정 2·3이 공통 설정으로 승격돼 dev/test 컨텍스트에도 그대로 적용됨을 이용). **(v4 추가, v5 구체화)** 같은 클래스 또는 별도 케이스로 `YamlPropertySourceLoader`를 사용해 `src/main/resources/application-prod.yml`을 Boot와 동일한 방식으로 평탄화한 뒤 `management.`로 시작하는 프로퍼티가 0건인지 확인(prod 파일이 공통 노출 설정을 오버라이드하지 않았다는 사실 증명 — 컨텍스트 재기동 없이)
- **(v5 신규, v6 재설계) `AdminBootstrapConcurrencyIntegrationTest`**: `MariaDbContainerSupport` 상속. **(v6 수정)** codex 리뷰 5차(높음2)에서 `run()`을 두 번 호출하는 방식은 두 번째 호출이 트리거 검사(`existsByUserTypeAndStatus`)에서 즉시 반환돼 저장 충돌 경로 자체가 실행되지 않는다는 지적을 인정 — 트리거 검사를 우회하고 저장·재조회 로직만 검증하도록 재설계한다. `AdminBootstrapLoader`의 package-private `createOrReconcile(AdminBootstrapCredentials)`(위 결정 4 참조)을 같은 패키지의 테스트에서 직접 호출한다: 먼저 `createOrReconcile`을 1회 호출해 계정을 커밋하고, **같은 자격증명으로 다시 한번** `createOrReconcile`을 호출해 이번에는 `DataIntegrityViolationException`이 실제로 발생하고(유니크 제약 위반) 재조회(`findByUserId` + `ROLE_ADMIN`·`ACTIVE` 확인)로 정상 흡수되는지 확인한다 — 예외가 밖으로 전파되지 않고, 최종 `memberRepository.count()`가 1인지(중복 생성 없음) 검증. Mockito 단위 테스트로는 트랜잭션 경계 자체(자기 자신 호출 시 프록시 미적용 등)를 검증할 수 없다는 codex 리뷰 4차 지적을 반영한 것 — 단위 테스트(`AdminBootstrapLoaderTest`, 트리거 분기·검증 실패·`toString()` 등 나머지 로직 담당)와 역할을 분담한다.
- **회귀**: `./gradlew test` 전체 통과, 결정 1이 흡수한 통합 테스트 **10개** 특히 확인(`CmsApplicationTests` 포함)

## 검증 (실기)

**정적 검증**
- `./gradlew test` 전체 통과 (474개 기준, 누락 없음)
- 시크릿 하드코딩 0건 — `application*.yml`·소스 전체 grep
- 프로파일 미지정 기동이 **실패**하는지 확인 (결정 1의 fail-fast가 실제로 동작)
- `SPRING_PROFILES_ACTIVE=dev,prod`로 기동 시도 → `ProfileGuardEnvironmentPostProcessor`에 의해 **기동 실패** 확인 (결정 1)
- **(v3 추가)** `SPRING_PROFILES_ACTIVE=`(빈 문자열)로 기동 시도 → 동일하게 **기동 실패** 확인 (결정 1)

**`.dockerignore` 검증 — builder 스테이지만 별도 빌드**
- `docker build --target builder -t cms-builder-check .` → **(v3 수정)** `docker run --rm cms-builder-check sh -c "find /workspace -iname '.env*'"`(`-maxdepth 1` 제거 — 중첩 경로까지 검사) 결과가 빈 값인지 확인 (결정 6 — 최종 이미지 `docker history`가 아니라 builder 스테이지 자체를 검사)

**Docker 실기 검증** (`.env.prod`를 로컬에 임시 작성해 수행 — 커밋 금지)
- `SPRING_PROFILES_ACTIVE=prod`로 compose 기동 성공, Flyway V1~V10 전체 적용 확인
- **(v3 수정)** 인증된 ADMIN 세션으로 `GET /swagger-ui.html`·`GET /v3/api-docs` → **404**(springdoc 비활성화 확인 — 비인증 세션은 애초에 `hasRole("ADMIN")`에 걸려 302이므로 이 경로로는 검증 불가)
- `GET /actuator/health` → 200 (무인증), 응답에 DB 상세가 없는지 확인(`show-details: never`)
- **(v3 수정)** 비인증 `GET /actuator/env`·`/actuator/beans`·`/actuator/metrics` → **정확히 302**(`/admin/login` 리다이렉트), 인증된 ADMIN 세션으로는 **정확히 403** — "정확히 404"는 관측 불가능한 조건이었음(결정 3 v3 참조). 노출 설정 자체(health만 실제 등록)는 `ActuatorExposureTest`가 자동으로 검증
- 부트스트랩 3변수(USER_ID·PASSWORD·EMAIL)를 준 상태로, ACTIVE ROLE_ADMIN이 없는 빈 DB로 기동 → 해당 계정으로 `/admin/login` 로그인 성공
- 같은 컨테이너 재기동(ACTIVE ROLE_ADMIN 존재 상태) → 계정이 중복 생성되거나 비밀번호가 리셋되지 않는지 확인
- 부트스트랩 변수를 뺀 채 **ACTIVE ROLE_ADMIN이 없는 DB**로 기동 → **기동이 실패**하는지 확인
- 부트스트랩 변수를 뺀 채 **ACTIVE ROLE_ADMIN이 있는 DB**로 기동 → 정상 기동되는지 확인(가용성 유지 확인)
- **(v3 추가, v4 표현 정정)** 부트스트랩 변수를 **뺀 채**(결정 7 — compose에서 선택 입력으로 낮춘 뒤) `docker compose up -d`가 **ACTIVE ROLE_ADMIN이 이미 있는 상태**에서 필수 변수 누락으로 거부되지 않고 정상 기동되는지 확인(결정 7의 codex 리뷰 2차 차단1 해소 확인 — v2였다면 이 시나리오에서 Compose 자체가 기동을 거부했을 것)
- `docker inspect`로 `db` 컨테이너 환경변수에 `MAIL_PASS`·`ADMIN_BOOTSTRAP_PASSWORD`가 **없는지**, `app` 컨테이너에 `MYSQL_ROOT_PASSWORD`가 **없는지** 확인 (결정 7)
- 로그에 비밀번호·DB 비밀번호가 출력되지 않는지 확인
- 호스트에서 `curl http://127.0.0.1:8080/actuator/health` 접근 가능한지 확인 (결정 8)
- **(v3 추가, v4 재확인, v5 재확인)** `scripts/prod-up.sh`를 부트스트랩 변수 없는, ACTIVE ROLE_ADMIN이 없는 DB(즉, 결정 4가 fail-fast하는 상황)로 실행 → 호스트 `curl --connect-timeout 2 --max-time 4` 폴링이 60초 내 타임아웃돼 스크립트가 `docker compose stop app` 실행 후 **비정상 종료(non-zero exit)**하고 `docker compose logs app`을 출력하는지 확인, **재시작 루프가 멈췄는지**(`docker compose ps`에서 app이 계속 재시작되지 않는지) 확인(결정 8)
- **(v4 신규)** `scripts/prod-down.sh` 실행 후 `docker volume ls`로 `db_data_prod`·`notice_attachments_prod`(가칭) named volume이 **그대로 남아있는지** 확인(결정 8 — `-v` 미사용 계약 검증)
- **(v5 신규)** 부트스트랩 비밀번호에 `$`·공백·`#`을 포함한 값(예: `P@ss $ w0rd#1`)을 `.env.prod`에 **작은따옴표로 감싸** 넣고 기동 → 컨테이너에 전달된 값이 원본과 동일한지(`$` 등이 셸에 의해 다른 값으로 치환되지 않았는지) 확인 — 그 계정으로 실제 로그인 성공까지 확인(결정 7)
- **(v5 신규, v6 범위 확장)** 호스트 셸에 `export ADMIN_BOOTSTRAP_PASSWORD=shell-leftover`와 `export MAIL_PASS=shell-leftover`(부트스트랩 변수 외 1건도 함께)를 미리 설정해둔 상태로 `scripts/prod-up.sh` 실행 → 스크립트가 두 변수 모두에 대해 경고를 출력(값은 미노출)하고 `unset` 후 `.env.prod`의 값으로 정상 진행하는지 확인(결정 7·8)

**회귀 검증**
- dev 프로파일 `make dev-up` 기존 동작 무회귀 — Swagger 접근 가능, `/actuator/health` 정상(`/actuator/info`는 이번 변경으로 dev에서도 제거됨 — 의도된 동작)
- Playwright로 관리 화면(대시보드·공지 관리·회원 관리) + 공개 공지(`/notices`) 회귀 확인

## 리스크

| 리스크 | 대응 |
|--------|------|
| 결정 1의 테스트 파급이 예상보다 큼 — 통합 테스트 10개가 프로파일 기본값에 의존 | 작업 단계 2에서 설정 재편 직후 즉시 전체 테스트를 돌려 조기 발견. 실패 시 `@ActiveProfiles("dev")` 개별 부착으로 폴백 |
| `@ServiceConnection`·`@DynamicPropertySource`가 프로퍼티를 오버라이드하는 순서상, 프로파일 변경이 Testcontainers 설정과 간섭 | 동일하게 작업 단계 2에서 검증. `MariaDbContainerSupport`는 수정하지 않는 것을 원칙으로 함 |
| prod에서 `ddl-auto: validate` + Flyway 미적용 DB → 기동 실패 | `docs/migration-guide.md`의 기존 baseline 절차를 `docs/deployment.md`에서 링크 |
| `a8ffb9a`가 prod 파일을 지운 사유("운영 서버 없는데 있는 것처럼 보임")가 재발 | `docs/deployment.md` 서두에 "실배포 미수행, 배포 가능 상태 검증까지가 범위"임을 명시 |
| 컨테이너 환경에서 첨부파일 volume 소유권 문제 | `Dockerfile:13-15`가 이미 `appuser` 소유권을 처리 — dev compose와 동일한 named volume 패턴을 prod compose에 적용 |
| **(v2 추가)** `EnvironmentPostProcessor`가 `META-INF/spring.factories`로 정상 등록되지 않으면 dev+prod 방어가 조용히 무력화됨 | `ProfileGuardEnvironmentPostProcessorTest`로 클래스 단위 로직은 보증하되, **`SPRING_PROFILES_ACTIVE=dev,prod` 실기동 실패 확인**(정적 검증 항목)으로 등록 자체까지 end-to-end 검증 |
| **(v2 추가)** `healthcheck.sh --connect --innodb_initialized`가 `mariadb:10.11` 이미지에 없을 수 있음 | 구현 단계에서 `docker run --rm mariadb:10.11 which healthcheck.sh`로 실존 확인 후 없으면 `$$MYSQL_ROOT_PASSWORD` 이스케이프 방식으로 폴백(결정 8) |
| **(v4 해소, 구 v3 항목)** `WebEndpointsSupplier` 기반 노출 설정 검증(결정3)의 API 유효성 | codex 리뷰 3차가 `WebEndpointsSupplier#getEndpoints()`+`getEndpointId()`가 Spring Boot 3.5에서 유효함을 직접 대조 확인 — 폴백 불필요. 남은 리스크는 `MariaDbContainerSupport` 상속 누락으로 컨텍스트가 안 뜨는 것뿐이라 리뷰 코멘트를 테스트 작성 체크리스트로 남김 |
| **(v4 신규)** 호스트에서 `curl`이 없는 CI/실행 환경(예: minimal 컨테이너 기반 실행기)에서는 `scripts/prod-up.sh`의 readiness 폴링 자체가 동작하지 않음 | 이 스크립트는 로컬/서버에서 사람이 직접 실행하는 운영 스크립트로 범위를 한정(`docs/deployment.md`에 `curl` 전제 조건 명시) — CI에 이 스크립트를 그대로 재사용할 계획은 이번 범위에 없음 |
| **(v3 신규)** `scripts/prod-up.sh`의 헬스체크 폴링 로직이 정상 기동에도 타임아웃보다 느리게 반응해 오탐(false negative)을 낼 수 있음(Flyway 마이그레이션이 오래 걸리는 첫 기동 등) | 폴링 주기·최대 대기 시간을 넉넉히 잡고(예: 5초 간격 60초), 실패 시 `docker compose logs app`을 함께 출력해 운영자가 원인(진짜 실패 vs 느린 기동)을 바로 구분할 수 있게 함 |
| **(v3 신규)** `AdminBootstrapCredentials`의 비밀번호 정책(`min=4`)이 실제로는 약함 — 이는 새 정책이 아니라 앱이 이미 전역에서 쓰는 기존 정책(`AdminMyPasswordChangeRequest` 등)을 그대로 따른 것 | 이번 계획의 범위가 아님(기존 정책 강화는 별도 로드맵 항목으로 판단). 부트스트랩만 더 엄격한 정책을 적용하면 "왜 부트스트랩 계정만 다른 기준이냐"는 불일치가 생기므로 일관성을 우선 |

## 범위 밖 (명시적 제외)

- nginx 리버스 프록시, TLS 인증서, `forward-headers-strategy`, secure/SameSite 쿠키 — 실제 도메인이 정해진 뒤 별도 작업
- 실제 호스팅(VPS 선정·도메인·DNS), CD 파이프라인
- 모니터링/APM 도입

## 승인 후 이어갈 워크플로우

이 계획은 8단계 워크플로우의 1(정찰)·2(설계)에 해당한다. 승인 시:
3. `plan-review-loop` 스킬로 적대적 리뷰 라운드 반복(ship 판정까지)
4. 리뷰 반영 결과를 다시 보고 → 승인
5~8. 구현 → 테스트 → Docker/Playwright 실기 검증 → CLAUDE.md·계획서 기록

커밋/PR은 사용자 확인 후 `/code-review-loop` → `/commitPR`로 처리한다.

## 구현·검증 결과 (2026-07-30)

### 핵심 확정 사항

계획서 v7(ship) 그대로 구현했다. 구현·검증 중 계획에 없던 실제 결함 1건을 발견해 즉시 반영했다:

- **(계획에 없던 추가) Docker Compose 프로젝트 이름 충돌**: `docker-compose.dev.yml`·`docker-compose.prod.yml` 둘 다 프로젝트 이름을 명시하지 않아 디렉터리명("CMS") 기준 같은 암묵적 프로젝트로 묶였다 — Docker 실기 검증 중 prod compose를 먼저 실행하자 **기존 dev DB 컨테이너가 실제로 prod db 서비스로 교체**되는 사고가 발생했다(named volume은 보존되어 데이터 유실은 없었음, `docker inspect` 라벨 `com.docker.compose.replace`로 원인 확인). 두 compose 파일에 `name: cms-dev`/`name: cms-prod`를 추가하고 볼륨 이름도 `name:`으로 고정(프로젝트 이름과 무관하게 데이터 볼륨 자체를 못박아, 향후 프로젝트 이름이 또 바뀌어도 안전하도록)해 재현되지 않음을 재검증했다. 사용자 확인 후 반영(2026-07-30).
- **(발견, 범위 밖으로 확정)** `GET /swagger-ui.html`·`/v3/api-docs`가 springdoc 비활성 시 계획한 404가 아니라 500을 반환한다 — 근본 원인은 `GlobalApiExceptionHandler`의 selector 없는 전역 catch-all이 `NoResourceFoundException`까지 500으로 바꾸는 기존 결함(`PLAN-public-notice.md`에서 `/admin/logout`·`/favicon.ico`로 이미 1차 발견된 것과 동일 패턴, 이번이 3번째 발견 사례)이며, prod 프로파일이 새로 만든 결함이 아니다. 사용자와 협의해 이번 PR 범위 밖으로 확정하고 `docs/troubleshooting.md`에 근본원인·해결 방향을 기록했다.

### 구현 파일

**신규**
- `src/main/resources/application-prod.yml`
- `src/main/java/com/cms/config/ProfileGuardEnvironmentPostProcessor.java` + `src/main/resources/META-INF/spring.factories`
- `src/main/java/com/cms/admin/member/AdminBootstrapCredentials.java`, `AdminBootstrapLoader.java`
- `.dockerignore`, `docker-compose.prod.yml`, `.env.example`
- `scripts/prod-up.sh`, `scripts/prod-down.sh`
- `docs/deployment.md`
- 테스트: `ProfileGuardEnvironmentPostProcessorTest`, `ActuatorExposureTest`, `AdminBootstrapLoaderTest`, `AdminBootstrapConcurrencyIntegrationTest`

**수정**
- `src/main/resources/application.yml`(공통 승격: `ddl-auto`·`management`·프로파일 기본값 제거), `application-dev.yml`(management 블록 제거)
- `src/main/java/com/cms/config/SecurityConfig.java`(actuator 규칙), `SecurityConfigTest.java`(actuator 케이스+스텁 2종)
- `src/main/java/com/cms/admin/member/repository/MemberRepository.java`(`existsByUserTypeAndStatus` 신규 파생 쿼리)
- `build.gradle`(test 태스크 `SPRING_PROFILES_ACTIVE=dev`)
- `Dockerfile`(builder `./gradlew` 기반 전환)
- `docker-compose.dev.yml`(`name:`·볼륨 이름 고정 — 위 "핵심 확정 사항" 참조)
- `Makefile`(prod-up/prod-down/logs-prod), `README.md`(배포 섹션), `CLAUDE.md`(환경 설정·보안 규칙·패키지 구조), `docs/troubleshooting.md`(핸들러 없는 경로 500 신규 항목)

### 검증 결과

- `./gradlew test` 전체 통과 — **523개, 실패 0, 오류 0**(2026-07-30 최종 실행). 결정 1이 흡수 대상으로 지목한 통합 테스트 10개(`CmsApplicationTests` 포함) 전부 정상.
- 정적 검증: 프로파일 미지정 기동 실패(placeholder 해석 실패 실측), `dev,prod` 동시 활성화 기동 실패(`ProfileGuardEnvironmentPostProcessor` 실측), Flyway 마이그레이션 파일 무변경(`git diff --stat` 확인).
- **Docker 실기 검증** (전부 실측, 2026-07-29~30):
  - `docker build --target builder`로 builder 스테이지만 별도 빌드 → `.env*` 부재 확인(빈 결과) + 전체 이미지 빌드 성공(gradle-8.12.1-bin.zip 다운로드 로그로 저장소 wrapper 버전과 일치 확인)
  - `docker run --rm mariadb:10.11 which healthcheck.sh` → `/usr/local/bin/healthcheck.sh` 실존 확인
  - 빈 DB + 부트스트랩 3변수 → `docker compose up` 성공, Flyway V1~V10 적용, 부트스트랩 계정(`bootadmin`, 특수문자 비밀번호 `P@ss $ w0rd#1`) 생성 → 실제 로그인 성공(`/admin` 200) 확인 — Compose `.env` 작은따옴표 리터럴 처리까지 검증
  - 같은 컨테이너 재기동(ACTIVE ROLE_ADMIN 존재) → 계정 미중복(`SELECT` 결과 1행), 기존 비밀번호로 재로그인 성공(비밀번호 미초기화)
  - **ACTIVE ROLE_ADMIN 없음 + 부트스트랩 변수 없음 → 기동 실패 실측**: 앱 로그에서 `IllegalStateException: ACTIVE 상태의 ROLE_ADMIN 계정이 없어...` 반복 확인(재시작 루프), `prod-up.sh`가 60초 데드라인 후 `docker compose logs app`→`stop app`→**exit 1**까지 정확히 수행, 재시작 루프가 실제로 멈춘 것을 이후 상태 조회로 재확인
  - ACTIVE ROLE_ADMIN 있음 + 부트스트랩 변수 없음 → 정상 기동(가용성 유지)
  - `GET /actuator/health` 200(무인증, DB 상세 없음) / 비인증 `GET /actuator/env`·`/beans`·`/metrics` 302 / 인증 세션 403 — 계획과 정확히 일치
  - `docker inspect`로 `db` 컨테이너에 `MAIL_PASS`·`ADMIN_BOOTSTRAP_PASSWORD` 없음, `app` 컨테이너에 `MYSQL_ROOT_PASSWORD` 없음 확인(시크릿 격리)
  - `docker logs`에 비밀번호 원문(`P@ss`, DB 비밀번호) 미노출 확인
  - `prod-down.sh` 이후 `db_data_prod`·`notice_attachments_prod` 볼륨 보존 확인
  - 호스트 셸에 `ADMIN_BOOTSTRAP_PASSWORD`·`MAIL_PASS` leftover 설정 → `prod-up.sh`가 경고 후 unset, 컨테이너에는 `.env.prod` 값만 주입됨을 `docker inspect`로 확인
- **회귀 검증**: dev DB(`cms-db-dev`, named volume `cms_db_data_dev`)가 이번 세션의 모든 prod 실기 검증 전후로 데이터 무결성 유지(`SELECT COUNT(*)` 동일값) 확인 — compose 프로젝트 이름 수정의 실효성 재검증 겸함.
- **Playwright 실기 검증** (dev 프로파일, `./gradlew bootRun` + 기존 `cms-db-dev`): ADMIN(`admin`/`1234`) 로그인 → `/admin`(대시보드) → `/admin/member/manage`(관리자 조회, 기존 회원 2건 정상 표시) → `/admin/notice/manage`(공지사항 관리) → `/notices`(공개 공지 목록, 비로그인 전제 아님이나 페이지 자체 렌더링 확인) 전부 정상 렌더링, 신규 회귀 없음 확인(스크린샷: `regression-dashboard-dev.png`, `regression-member-manage-dev.png`, `regression-notice-manage-dev.png`, `regression-public-notices-dev.png`). 콘솔 오류는 `/favicon.ico` 500 1건뿐 — 이번 PR이 새로 만든 것이 아니라 오늘 `docs/troubleshooting.md`에 함께 기록한 기존 결함(핸들러 없는 경로 500)과 동일 근본 원인.
- `/deploy-check` 실행 결과: **needs-attention**(차단 0건). 리포트: `adversarial-review/deploy-check-2026-07-30.md`.

### 이슈

- 위 "핵심 확정 사항" 참조 — compose 프로젝트 이름 충돌(수정 완료), swagger-ui/v3-api-docs 500(범위 밖 확정, `docs/troubleshooting.md` 기록).

### 후속

- `GlobalApiExceptionHandler`에 `NoResourceFoundException` 전용 핸들러 추가 — swagger-ui/v3-api-docs·`/admin/logout` GET·`/favicon.ico` 500 문제를 한 번에 해소할 것으로 예상되나, admin API를 포함한 앱 전체 예외 처리 범위를 건드리는 변경이라 별도 작업으로 분리.
- nginx 리버스 프록시·TLS·실제 호스팅은 로드맵 "범위 밖"으로 명시된 대로 별도 사용자 결정 사안.
