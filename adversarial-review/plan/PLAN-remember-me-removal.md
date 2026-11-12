# PLAN — 로그인 상태 유지(remember-me)와 재인증 인프라 제거

> 작성: 2026-11-12 · 브랜치(예정): `security/remove-remember-me`
> **위험 변경: 인증·로그인 경로**(쿠키 자동 로그인 제거, 재인증 API 제거), **마이그레이션**(V16 `DROP TABLE persistent_logins`), **동시성**(세션 레지스트리 모니터·로그인 세션 뮤텍스 제거)
> 선행 계획(되돌리는 대상): `PLAN-remember-me-login.md`(#225), `PLAN-remember-me-token-purge.md`(#227), `PLAN-sensitive-reauthentication.md`(#228), `PLAN-content-delete-reauthentication.md`(#230), `PLAN-session-same-device-notice.md`(#231)

## Context

- 이슈 #222(로그인 화면의 "로그인 상태 유지" 체크박스가 동작하지 않음)에서 2026-11-10에 체크박스 제거 대신 **구현**을 골랐고, 이후 만료 토큰 정리·민감 작업 재인증(모달)·콘텐츠 삭제 재인증·세션 화면 안내가 차례로 붙었다.
- 2026-11-12 로드맵 후속 후보 "콘텐츠 수정에도 재확인을 할지"를 논의하면서 사용자가 **remember-me 자체를 제거(A안)** 하기로 결정했다. 근거: 관리자 페이지라서 쿠키를 탈취당하면 7일 동안 읽기·수정이 노출되는 위험을 없애는 쪽이 맞다. 재확인을 모든 쓰기로 넓히더라도(B안) "읽기 전용 7일 세션"이 남고, 그 대가로 유지해야 할 복잡도(탈취 감지, 메모리 차단 목록, 승격 경합 직렬화)가 크다.
- 사용자는 **재인증 인프라도 함께 제거**하기로 했다. remember-me가 없으면 모든 세션이 완전 인증이라 재인증 기능은 한 번도 동작하지 않는다. 그런데도 재인증 API(`POST /admin/api/members/me/reauthentication`)를 남기면, 아무 쓸모 없이 비밀번호를 추측할 수 있는 창구만 남는다.
- 정찰 결과(`git diff da7149c HEAD --stat -- src`, `da7149c` = #225 직전 master): #225 이후 `src/`를 바꾼 커밋은 5개 PR(#225·#227·#228·#230·#231)과 의존성 갱신 2건(#219 `Dockerfile`·#220 `build.gradle` — `src` 밖)뿐이다. 즉 **`src/main`·`src/test`의 차이 90개 파일이 전부 이번에 지울 기능**이다. 지울 기능의 식별자(`RememberMe*`·`Reauth*`·`persistent_logins`·`CmsReauth`·`reauth`)를 참조하는 파일도 이 차이 집합 밖에서는 문서 2개(`docs/migration-guide.md`·`docs/troubleshooting.md`)뿐이다.
- 토큰은 해시 없이 평문으로 저장돼 있다(`persistent_logins.token`, Spring `JdbcTokenRepositoryImpl` 방식). DB나 백업이 새면 유효 기간 7일짜리 로그인 수단이 함께 샌다.

## 핵심 확정 사항

### 쟁점 1 — 어떻게 걷어 낼 것인가

| 선택지 | 내용 | 트레이드오프 |
|---|---|---|
| **(a) 파일 단위 복원** | 차이 집합의 기존 파일은 `da7149c` 내용으로 되돌리고(`git checkout da7149c -- <파일>`), 그 뒤 추가된 파일은 삭제한다. 예외는 아래에 따로 적는다 | #224까지 CI·실기를 통과한 코드로 돌아가 회귀 위험이 가장 낮다. 차이 집합이 순수하다는 것을 정찰에서 확인했다 |
| (b) 현재 코드에서 손으로 제거 | `LoginVisitRecorder` 분리 같은 구조는 남기고 remember-me 분기만 지운다 | 구조 변경을 남기는 근거가 없다(그 분리는 자동 로그인과 공유하려고 만든 것). 손으로 지우면 빠뜨릴 수 있다 |
| (c) `git revert` 5개 커밋 | 커밋 이력을 그대로 되돌린다 | 계획 문서·로드맵·troubleshooting 같은 이력 문서까지 지워지고, 머지된 V15 파일을 삭제하게 된다(금지 규칙 위반). 충돌 해소도 손이 간다 |

**결정: (a).** **왜**: 차이 집합이 순수하다는 것을 확인했으므로, 검증된 기준점으로 돌아가는 편이 새로 짜는 것보다 안전하다. 예외(복원이 아니라 새로 작성하거나 유지하는 것):

1. `V15__persistent_logins.sql`은 **유지**한다(머지된 마이그레이션은 수정·삭제 금지). 새로 `V16`을 추가한다(쟁점 3).
2. `login.html`은 `da7149c`로 되돌리면 **동작하지 않는 체크박스(#222의 원래 문제)가 다시 생긴다** → 체크박스 블록(`form-group` 하나)을 **삭제**한다.
3. `BaselineSchemaTest`는 `da7149c`로 되돌린 뒤, "`persistent_logins`가 없다" 단언을 추가한다(V15+V16을 적용한 최종 결과 고정).
4. 문서는 복원하지 않고 편집한다(이력 보존 — 쟁점 6).
5. 새 시험을 추가한다(실패 모드 표).

### 쟁점 2 — `LoginSessionMutexFilter`(폼 로그인 세션 뮤텍스)도 지우나

- 이 필터는 #228에서 재인증 승격과 폼 로그인을 직렬화하려고 넣었다. 그런데 정찰해 보니 **재인증과 무관한 경합 하나(F11)도 함께 막고 있다**. 같은 세션의 두 탭이 **서로 다른 두 계정**으로 동시에 폼 로그인하면, Spring Security 7.0.7은 레지스트리 등록을 세션 뮤텍스 밖에서 하기 때문에 "실제 인증 회원 ≠ 레지스트리 회원"이 남을 수 있다. 그러면 그 세션은 실제 인증 회원의 강제 만료(역할·상태 변경 등)에 걸리지 않는다.
- 선택지: (a) 필터 유지 — 47줄짜리이고 같은 세션의 로그인만 줄을 세운다. F11 시험은 재인증 시험 파일 안에 있어서 새 시험으로 옮겨야 한다. (b) 제거 — #225 이전 상태로 돌아가며, F11은 수용한 한계로 기록한다.
- ~~v1·v2 결정: (b) 제거~~ → **v3 결정: (a) 유지(2026-11-12 사용자 결정).** **왜**: 처음에는 "두 계정의 비밀번호를 아는 내부자가 자기 계정의 다음 강제 만료를 피하는 것"뿐이라고 보고 제거를 택했으나, 다시 따져 보니 과소평가였다. ① ADMIN은 두 번째 계정을 직접 만들 수 있어(`POST /admin/api/members`) **혼자서** 이 상황을 만들 수 있다. ② 세션 관리 화면이 레지스트리 기준으로 보여 주므로 성공했는지 확인하고 재시도할 수 있다. ③ 요청마다 회원 상태를 다시 확인하는 장치가 없고 `AdminPermissionEvaluator`는 `ROLE_ADMIN`이면 DB를 보지 않으므로, 어긋난 세션은 **강등·비활성화된 뒤에도 ADMIN 권한으로 요청을 계속 보내는 동안 살아 있다**(퇴사 예정 관리자의 접근 유지 시나리오). 실제 서버에서 이 경합이 얼마나 쉽게 나는지는 측정하지 않았다(F11 시험은 스파이로 순서를 강제한다). 이 경합은 Spring 동작이라 remember-me·재인증과 무관하게 존재하므로, 필터는 독립된 방어로 남긴다.
  - 유지에 따르는 일: 필터와 `LoginSessionMutexFilterTest`는 남기고 주석·`DisplayName`의 재인증 언급을 "두 탭 동시 로그인의 레지스트리 불일치 방지"로 고친다. 단위 시험의 "다른 경로의 POST" 예시도 지워질 재인증 경로가 아닌 경로로 바꾼다. **F11 시험**은 지워질 `ReauthenticationApiIntegrationTest` 안에 있으므로 새 `ConcurrentFormLoginIntegrationTest`로 옮긴다(폼 로그인만 쓰고, `@MockitoSpyBean SessionRegistry`로 B의 등록 직전에 멈춘 뒤 C의 로그인이 세션 뮤텍스에서 막힘을 `Thread.State`로 확인 → 풀고 나서 컨텍스트 회원 = 레지스트리 회원 → 그 회원 만료로 다음 요청 401). F10(재인증과의 직렬화)은 대상이 없어져 옮기지 않는다. 변이: 필터를 체인에서 빼면 F11이 실패함을 확인한다.
  - 이 파일들은 `git diff da7149c`의 "남는 차이"에 들어간다(성공 기준 반영). `SecurityConfig`의 `addFilterBefore(new LoginSessionMutexFilter(), UsernamePasswordAuthenticationFilter.class)` 한 줄과 주석도 남는다.
- `AdminSessionService`의 레지스트리 모니터(`registryMutation`)와 `changeSessionIdIfActive`는 `da7149c`로 되돌린다. 모니터는 "만료와 재인증의 세션 ID 교체"를 직렬화하려고 만든 것이다. 남는 ID 교체는 폼 로그인의 세션 고정 방지 하나뿐인데, 그것은 새 인증을 만들기 때문에 옛 인증의 만료 표시가 사라져도 문제가 없다. 필터의 잠금 순서 주석(세션 뮤텍스 → 레지스트리 모니터)도 함께 고친다.

### 쟁점 3 — `persistent_logins` 테이블을 어떻게 처리하나

| 선택지 | 트레이드오프 |
|---|---|
| **(a) V16 `DROP TABLE IF EXISTS persistent_logins`** | 평문 토큰이 DB와 이후 백업에서 사라진다. 이전 버전 앱으로 롤백하려면 테이블을 다시 만들어야 한다(쟁점 4) |
| (b) V16 `DELETE FROM persistent_logins`(테이블 유지) | 롤백은 쉽지만, 쓰지 않는 테이블이 계속 남는다. 다시 넣을 계획이 없다 |
| (c) 마이그레이션 없이 둠 | 엔티티가 없어 `ddl-auto: validate`는 통과하지만, 평문 토큰(최대 7일 유효)이 그대로 남는다. 코드를 다시 넣으면 그 토큰이 되살아난다 |

**결정: (a).** **왜**: 사용자가 제거를 택한 이유가 "탈취 노출을 없애는 것"이므로 자격 증명 저장소도 남기지 않는다. 아직 실배포 전이라서 첫 실배포 직전의 마지막 합치기 때 V15와 V16이 함께 사라진다(`docs/migration-guide.md` 표에 그렇게 적는다). DDL 한 문장이고 `IF EXISTS`라 다시 실행해도 같다(V15의 `IF NOT EXISTS`와 짝). **DML이 섞이지 않는다.**

- 시험: "기존 행에 영향"(행 삭제)이 있으므로 **업그레이드 경로 시험** `PersistentLoginsDropUpgradeTest`를 둔다 — V15까지 적용 → 토큰 행 삽입 → V16 적용 → 테이블 없음·이력 `success=1`, 그리고 테이블이 이미 없는 상태(수동 정리)에서도 V16이 성공함을 확인한다. 새로 설치한 결과는 `BaselineSchemaTest`가 고정한다.

### 쟁점 4 — 롤백(이전 버전 앱을 V16이 적용된 DB로 띄우기)

- 코드로 확인한 사실: 이전 앱의 `RememberMeTokenRevokeListener`는 **BEFORE_COMMIT**에서 토큰을 지운다. 테이블이 없으면 이 삭제가 실패하고 **원 트랜잭션이 롤백**된다. 그래서 역할·상태 변경, 본인 비밀번호 변경, 비밀번호 재설정, 자동 잠금이 **모두 실패**할 것으로 예상한다. 수동 단일 만료(`revokeDevice`)도 상태 유지 세션이면 실패하고, 상태 유지를 체크한 로그인은 토큰 저장 실패를 로그만 남기고 진행할 것으로 예상한다(`AdminRememberMeServices.onLoginSuccess`의 catch).
- Flyway: 이전 앱은 V16을 모르므로 DB의 V16은 "future" 이력이 된다. Flyway 기본값(`ignoreMigrationPatterns` = `*:future`)으로 기동은 통과할 것으로 예상한다.
- **결정**: 롤백 절차를 "이전 버전 앱을 띄우기 **전에** V15의 `CREATE TABLE`을 수동 실행"으로 문서화한다(`docs/migration-guide.md` V16 행). **위 예상은 7단계에서 실측한다** — 이전 master 빌드를 V16 적용 DB로 띄워 ① 기동, ② 역할 변경 API, ③ 수동 SQL 실행 뒤 같은 API를 확인한다. 실측과 다르면 문서를 실측대로 고친다.
- **다시 업그레이드(v2, 리뷰 1라운드)**: 롤백 기간에 다시 만든 테이블은 제거 버전을 재배포해도 **지워지지 않는다**. V16 성공 이력이 남아 있어 Flyway가 다시 실행하지 않고, 매핑이 없어 `validate`도 잡지 못한다. 그래서 문서에 순서를 고정한다: **이전 앱 정지 → `DROP TABLE IF EXISTS persistent_logins` 수동 실행 → 제거 버전 기동**. 구버전과 신버전을 같은 DB에 동시에 띄우지 않는다(V14와 같은 전제). 매 기동마다 DROP하는 Flyway `afterMigrate` 콜백 같은 자동 장치는 두지 않는다. 실배포가 없어 V15가 적용된 운영 DB가 없고, 이 경로는 개발용 DB의 롤백에만 해당하기 때문이다.

### 쟁점 5 — 이미 발급된 브라우저 쿠키

- 배포 뒤에도 브라우저에는 `remember-me` 쿠키가 남는다(최대 7일). `rememberMe()` 설정이 없으면 이 쿠키를 읽는 필터가 없고 DB 행도 없으므로 **아무 효과가 없다**. 로그아웃 때 이 쿠키를 지우는 처리(`deleteCookies("remember-me")`)는 **추가하지 않는다**. **왜**: 효과 없는 쿠키를 지우려고 로그아웃 경로를 바꾸는 것은 과설계다. 남는 것은 브라우저의 무해한 쿠키뿐이다.
- 시험으로 고정한다: **보안 필터 체인에 `RememberMeAuthenticationFilter`가 없다**(구조 단언 — 테이블이 없어 인증만 실패하는 잔존 구현도 잡는다). 보조 회귀로, 세션 없이 `remember-me` 쿠키만 가진 요청은 미인증(API 401·페이지 302)이다. 이 보조 시험은 임의 쿠키를 쓰므로 **제거 전에도 통과할 수 있어** 필터 잔존을 구별하지 못한다(v2 — 고정 장치는 구조 단언이다).

### 쟁점 6 — 문서

- **복원하지 않고 편집**: 루트 `CLAUDE.md`(remember-me·재인증 두 줄 삭제 → "로그인 상태 유지 없음(2026-11-12 제거, 사유·계획서)" 한 줄), `com.cms.config` `CLAUDE.md`(두 절 삭제 → 짧은 제거 기록), `com.cms.admin.session`·`member`·`permission` `CLAUDE.md`(`da7149c` 문구로 되돌리고 제거 기록 한 줄), `docs/migration-guide.md`(V15 행에 "V16에서 삭제", V16 행 추가 + 롤백 SQL), `docs/verification/deployment-edge.md`(쿠키 항목을 세션 쿠키만으로 되돌리고 변경 이력에 한 줄), `docs/troubleshooting.md`(두 항목은 **보존** — 다시 넣을 때 참고할 Spring 동작 기록이다. 항목 머리에 "2026-11-12 기능 제거" 표시 한 줄), 계획 인덱스(`README.md` 56~60행에 "제거됨(#이번 PR)" 표시 + 61행 추가).
- 선행 계획 문서 5개는 이력이므로 고치지 않는다.

### 설계 제약

- `master` 직접 작업 금지, 브랜치 `security/remove-remember-me`.
- 머지된 마이그레이션(V15) 수정·삭제 금지.
- `SecurityConfig`의 **URL 접근 규칙(authorizeHttpRequests)은 한 줄도 바꾸지 않는다** — 걷어 내는 것은 `.rememberMe()`, 필터 2개, 진입점 람다의 remember-me 분기뿐이다. 그래서 위 경로표와 승인 이력은 그대로다.
- 반드시 남는 것(복원 기준점에도 있는 것): `VisitLoggingAuthenticationSuccessHandler`의 성공 직전 DB 재확인(`ACTIVE`·역할·해시, fail-closed)과 실패 잠금 연동, 방문 기록·읽은 알림 정리, `AdminSessionRevokeListener`(AFTER_COMMIT 세션 강제 만료), 핸들 기준 단일 만료·현재 세션 409·회원 단위 만료의 현재 세션 제외, API 401/403 JSON 분기(CSRF 실패 미인증 401 포함).
- 세션 유휴 만료는 Boot 기본 30분 그대로 둔다(요청 범위 밖).

## 작업 단계

1. 브랜치 생성.
2. **시험 먼저(TDD)**: 새 시험(아래 실패 모드 1·2·5)을 쓰고, 현재 코드에서 실패함을 확인한다 — 1은 필터 체인에 `RememberMeAuthenticationFilter`가 있어 실패, 2는 `remember-me=on` 로그인 응답에 쿠키가 실려 실패, 5는 V16 파일이 없어 실패. 1의 보조 시험(임의 쿠키 → 미인증)은 현재 코드에서도 통과할 수 있으며, 실패를 먼저 확인하는 대상이 아니다.
3. `src/main` 복원·삭제(쟁점 1 (a)): 수정 파일 → `git checkout da7149c --`, 추가 파일 → 삭제. 이어서 `login.html` 체크박스 삭제 → `./gradlew compileJava`.
4. `V16__drop_persistent_logins.sql` 작성.
5. `src/test` 복원·삭제 + `BaselineSchemaTest` 단언 추가 → `./gradlew compileTestJava`.
6. 영향 범위 시험: `--tests 'com.cms.config.*' --tests 'com.cms.admin.session.*' --tests 'com.cms.admin.member.*' --tests 'com.cms.admin.permission.*' --tests 'com.cms.common.api.*' --tests 'com.cms.BaselineSchemaTest' --tests 'com.cms.*UpgradeTest' --tests '*ConventionTest'`, 이어서 콘텐츠 삭제 화면 7종의 패키지 시험(배너·팝업·FAQ·문의·미디어·메뉴·정적 페이지·사이트·게시판). 공용 `config/**`·마이그레이션을 바꾸므로 **전체 `./gradlew test`** 를 한 번 돌린다.
7. 실기(dev + Playwright): 로그인 화면에 체크박스 없음, 로그인 → 회원 수정·권한 교체·세션 만료·콘텐츠 삭제(배너 등)가 모달 없이 바로 동작, 세션 화면에 같은 기기 안내 없음·단일 만료가 고른 세션만 끊음, MANAGER의 ADMIN API 403 JSON, 옛 `remember-me` 쿠키만으로 접근 시 로그인 이동, 로그아웃. **롤백 실측**(쟁점 4)은 이전 master 빌드를 별도 worktree(스크래치패드)에서 띄워 확인한다.
8. 문서(쟁점 6)·계획 결과 기록.

## 성공 기준

- [ ] `src/main`·`src/test`에 `RememberMe`·`Reauth`·`REAUTHENTICATION`·`CmsReauth`·`reauth.js`·`persistent_logins` 참조가 없다(V15·V16 마이그레이션과 업그레이드 시험 제외).
- [ ] `git diff da7149c -- src`가 다음만 남긴다: V16, `login.html` 체크박스 삭제, `BaselineSchemaTest` 부재 단언, 새 시험 파일, 그리고 유지하는 `LoginSessionMutexFilter`(필터·`SecurityConfig` 등록 한 줄·단위 시험·옮긴 F11 시험).
- [ ] 같은 세션 두 탭의 동시 폼 로그인 뒤 컨텍스트 회원 = 레지스트리 회원이고, 그 회원을 만료하면 세션이 끊긴다(F11). 필터를 빼면 이 시험이 실패한다(변이).
- [ ] 폼 로그인에 `remember-me=on`을 보내도 `remember-me` 쿠키가 발급되지 않는다. 세션 없이 그 쿠키만 보내면 미인증이다.
- [ ] 새로 설치하든 V15에서 올리든 `persistent_logins`가 없다.
- [ ] 기존 보호 회귀 없음: 성공 직전 재확인·자동 잠금·역할 변경 세션 만료·세션 관리 409·API 401/403 시험 통과, 전체 `./gradlew test` 통과.
- [ ] 롤백 절차가 실측대로 문서화된다.

## 놓치기 쉬운 입력·실패 모드

| # | 실패 모드 | 고정할 시험 |
|---|---|---|
| 1 | 배포 전에 발급된 `remember-me` 쿠키가 여전히 인증한다(필터나 서비스가 남은 경우) | 신규 `RememberMeRemovalIntegrationTest`: ① **주 고정**: 실제 `SecurityFilterChain` 빈의 필터 목록에 `RememberMeAuthenticationFilter`가 없다 ② 보조 회귀: 세션 없이 임의의 `remember-me` 쿠키를 보낸 `GET /admin/api/members/me` → 401 JSON, `GET /admin` → `/admin/login` 302(제거 전에도 통과할 수 있어 ①을 대신하지 못한다) |
| 2 | 로그인 폼이 `remember-me=on`을 보내면 쿠키나 토큰이 생긴다(체크박스를 다시 넣거나 `rememberMe()`가 남은 경우) | 같은 시험: 실제 BCrypt 회원으로 `POST /admin/login`(CSRF 포함, `remember-me=on`) → 302 `/admin`, 응답에 `remember-me` 쿠키가 없다. 로그인 화면 GET에 `name="remember-me"` 입력이 없다 |
| 3 | 공용 로그인 처리기를 복원하다가 성공 직전 재확인이나 세션 강제 만료 리스너가 빠진다 | 복원된 `VisitLoggingAuthenticationSuccessHandlerTest`(재확인 6종·fail-closed), `AdminSessionRevokeListenerTest`, `LoginFailureServiceTest`, `AdminSessionManagementIntegrationTest`(409·회원 단위 현재 세션 제외) |
| 4 | 진입점 람다를 되돌리다가 MANAGER의 ADMIN 전용 API 403 JSON이 401로 바뀌거나, CSRF 실패 미인증 401 분기가 깨진다 | 복원된 `ApiSecurityConfigTest`·`ApiErrorContractIntegrationTest`·`SecurityConfigTest` |
| 4b | 복원 중 `LoginSessionMutexFilter` 등록이 함께 빠져, 두 탭 동시 로그인의 레지스트리 불일치가 다시 열린다(v3) | 옮긴 F11 `ConcurrentFormLoginIntegrationTest`, `LoginSessionMutexFilterTest` |
| 5 | V16이 테이블이 있을 때나 없을 때 실패하거나, 행이 남는다 | 신규 `PersistentLoginsDropUpgradeTest`(V15 + 행 → V16 → 테이블 없음·이력 성공 / V15 뒤 수동 DROP → V16 성공), `BaselineSchemaTest`(새로 설치 시 부재) |

## 검증 범위

- 개발 중: 작업 단계 6의 영향 범위 시험.
- 공용 `config/**`·마이그레이션·`src/test/java/com/cms/support/BrowserTestSupport`(`TEST_PASSWORD` 공개 범위 복원)를 바꾸므로 **전체 `./gradlew test`** 를 구현 단계 끝에 한 번 돌리고, PR 직전에 `code-review-loop` 마무리로 한 번 더 돌린다.

## 구현·검증 결과 (2026-11-12)

### Context

계획 v3대로 `security/remove-remember-me` 브랜치에서 구현했다. 계획과 달라진 결정은 없다(쟁점 2의 필터 유지는 v3 승인 단계에서 이미 반영했다).

### 핵심 확정 사항

- `src/main`·`src/test`의 수정 파일은 `da7149c` 내용으로 되돌리고(`git checkout da7149c --`), 그 뒤 추가된 파일은 삭제했다. 예외는 계획대로다: V15 유지·V16 추가, `login.html` 체크박스 블록 삭제, `LoginSessionMutexFilter`와 그 단위 시험 유지(주석·시험 경로 예시만 재인증 언급 제거), `SecurityConfig`에 필터 등록 한 줄 재추가, `BaselineSchemaTest`에 부재 단언 추가.
- `git diff da7149c -- src`에 남는 차이(작업 트리 기준 확인): `config/CLAUDE.md`, `SecurityConfig`(필터 등록), `LoginSessionMutexFilter`, V15·V16, `login.html`, `BaselineSchemaTest`, 새 시험 3개, `LoginSessionMutexFilterTest` — 성공 기준과 일치한다.
- 패키지 지침 `admin/member`·`admin/permission`·`admin/session`의 `CLAUDE.md`는 #225 이후 추가만 있었으므로 `da7149c`로 되돌렸다. 제거 기록은 루트 `CLAUDE.md`와 `com.cms.config` `CLAUDE.md` "로그인 상태 유지 없음" 한 곳에 둔다.

### 구현 파일

- 새로 작성: `V16__drop_persistent_logins.sql`, `RememberMeRemovalIntegrationTest`(4건), `PersistentLoginsDropUpgradeTest`(2건), `ConcurrentFormLoginIntegrationTest`(1건 — F11 이식).
- 고침: `SecurityConfig`(필터 등록), `LoginSessionMutexFilter`(설명), `LoginSessionMutexFilterTest`(설명·예시 경로), `login.html`, `BaselineSchemaTest`.
- 복원·삭제: 5개 PR이 바꾼 main 63개·test 23개 파일(목록은 `git diff da7149c HEAD --name-status -- src`).
- 문서: 루트·`com.cms.config` `CLAUDE.md`, `docs/migration-guide.md`(V15 삭제 표시·V16 행·롤백과 재업그레이드 절차), `docs/verification/deployment-edge.md`(쿠키 항목·이력), `docs/troubleshooting.md`(두 항목 머리 표시), 계획 인덱스(56~60 제거 표시·61 추가).

### 검증 결과

- **TDD Red**(구현 전 현재 코드): 필터 체인 구조 시험은 `RememberMeAuthenticationFilter`가 있어 실패, 로그인 화면 시험은 `name="remember-me"`가 있어 실패, `remember-me=on` 로그인 시험은 쿠키가 발급돼 실패, V16 시험 2건은 `No migration with a target version 16`로 실패. 임의 쿠키 보조 시험과 이식한 F11은 통과(계획대로 — 기존 동작).
- **Green**: 새 시험 7건 + `LoginSessionMutexFilterTest` 2건 + `BaselineSchemaTest` 22건 통과.
- **변이**: `SecurityConfig`에서 필터 등록을 빼면 F11이 "login-c가 세션 뮤텍스에서 기다려야 한다"로 실패 → 원복 뒤 통과.
- **전체 `./gradlew test`**: 3,105건 통과(실패 0, 건너뜀 12 — 직전 머지 #231 때와 같은 건너뜀 수). 줄어든 수는 제거한 시험이다.
- **실기 — 새 버전(dev DB, Playwright·curl)**: 기동 시 V16 적용(dev DB의 V15 테이블과 토큰 1행 삭제). 로그인 화면에 상태 유지 입력 없음(입력은 `_csrf`·`username`·`password`뿐), 로그인 뒤 쿠키는 `JSESSIONID`만. 회원 상태 변경(`sdn-manager` DISABLED→ACTIVE→DISABLED)이 모달 없이 200, 세션 관리 화면에 같은 기기 안내·`reauth.js` 없음, 확인창 문구가 #225 이전 문구, MANAGER 세션 단일 만료 성공 → MANAGER의 다음 API 401(`세션이 만료되었습니다`)·페이지는 로그인 이동. MANAGER의 ADMIN 전용 API 403 JSON(`ACCESS_DENIED`)·페이지 403. FAQ 분류 생성 → 화면에서 삭제(모달 없음, DB 행 없음). 옛 `remember-me` 쿠키만 가진 브라우저는 페이지 로그인 이동·API 401(쿠키는 남지만 효과 없음). 로그아웃 → 로그인 화면. 콘솔 오류는 기존 favicon 404와 실험 중 잘못 보낸 요청의 400뿐(`CmsReauth` 참조 오류 없음). 원복: `sdn-manager`의 상태는 화면으로, 임시로 바꾼 비밀번호 해시는 저장해 둔 원래 값으로 되돌렸다.
- **실기 — 롤백 실측(쟁점 4, 이전 master `26113bd`를 스크래치패드 worktree에서 실행)**: ① V16 적용 DB에서 **정상 기동**("Current version 16 … up to date"). ② 상태 유지를 체크한 폼 로그인은 302 `/admin`으로 성공하고, 토큰 저장·만료 토큰 정리는 `Table 'cms.persistent_logins' doesn't exist`로 실패해 ERROR 로그만 남겼다(쿠키 미발급). ③ **회원 상태 변경 `PATCH`는 500, DB 값은 그대로**(BEFORE_COMMIT 토큰 삭제 실패로 원 변경이 롤백) — 예상과 같다. ④ V15 SQL 수동 실행 뒤 같은 `PATCH` 200·원복 200, 상태 유지 로그인으로 토큰 1행 생성. ⑤ 이전 앱 정지 → 새 앱 기동: "up to date"로 기동하지만 **테이블과 토큰 1행이 그대로 남음**(리뷰 1라운드 지적 실측) → 수동 `DROP TABLE IF EXISTS persistent_logins` → 새 앱 로그인 302·쿠키 없음·`/me` 200, 기동 로그 ERROR 0건. `docs/migration-guide.md` V16 행의 절차가 실측과 일치한다.
- 스크린샷: 스크래치패드 `screenshots/rmr-01-login-no-checkbox.png`·`rmr-03-faq-category-deleted.png`(세션 만료 화면 스크린샷은 확인창 처리 중 스크립트가 끊겨 남지 않았고, 결과는 위 문장 기록으로 대신한다).

### 이슈

- Playwright MCP의 스크린샷·스냅샷은 프로젝트 안(`.playwright-mcp/`, git 무시)에만 쓸 수 있어, 저장한 뒤 스크래치패드로 옮기고 이번 세션의 MCP 임시 파일은 지웠다.
- MCP가 `confirm()`을 먼저 가로채 스크립트 안의 `page.once('dialog')` 처리기가 돌지 않았다 → 확인창은 `browser_handle_dialog` 또는 MCP의 자동 처리로 다뤘다(제품 동작과 무관).
- `config/CLAUDE.md`를 셸로 이어 붙여 다시 쓰면서 작업 사본의 줄바꿈이 CRLF·LF로 섞였다 → CRLF로 통일했다(git 저장 내용은 같음).

### 후속

- 없음(계획 범위 안에서 끝났다). 로드맵 반영은 `/updateRoadmap` — 71차 후속 후보 "콘텐츠 수정의 재확인 여부"와 "remember-me 쿠키 `Secure` 확인"은 대상이 사라졌다.

## 개정 이력

- v1 (2026-11-12): 최초 작성.
- v2 변경 (리뷰 1라운드, codex): ① 쟁점 4에 롤백 뒤 **다시 업그레이드**하는 절차를 추가했다(이전 앱 정지 → 수동 DROP → 제거 버전 기동, 구·신버전 동시 실행 금지) — V16 성공 이력 때문에 재배포가 테이블을 지우지 않는다는 지적을 수용. ② 실패 모드 1의 주 고정을 "필터 체인에 `RememberMeAuthenticationFilter` 없음" 구조 단언으로 바꾸고, 임의 쿠키 시험은 보조 회귀로 낮췄다. TDD 단계에서도 보조 시험은 실패를 먼저 확인하는 대상에서 뺐다 — 임의 쿠키는 현재 코드에서도 인증되지 않아 잔존을 구별하지 못한다는 지적을 수용. 기각: "V16 이력을 유지한 채 테이블을 재생성하는 시험" — 막는 동작이 없어 위험의 존재만 고정하게 되고, 실배포가 없어 해당 운영 DB도 없다. 같은 이유로 `afterMigrate` 자동 DROP 콜백도 두지 않는다.
- 리뷰 2라운드(codex): v2에 대해 **ship**(새 지적 없음 — 차이 집합 순수성, 기준점의 재확인·AFTER_COMMIT 만료 존재, URL 규칙 동일, 구버전 BEFORE_COMMIT 리스너를 저장소에서 대조 확인). 계획 문서 변경 없음.
- v3 변경 (승인 단계, 사용자 결정 2026-11-12): 쟁점 2를 **`LoginSessionMutexFilter` 유지**로 바꿨다. 위험을 다시 따져 보니 ADMIN이 두 번째 계정을 직접 만들어 혼자서 "강등·비활성화돼도 끊기지 않는 ADMIN 세션"을 만들 수 있고, 성공했는지 세션 화면에서 확인할 수 있어 v1의 "작은 내부자 위험" 평가가 과소였다. F11 시험을 새 파일로 옮기고(F10은 대상이 없어져 삭제), 성공 기준·실패 모드(4b)·남는 차이 목록을 반영했다.
