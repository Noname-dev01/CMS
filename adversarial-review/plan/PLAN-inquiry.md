# PLAN — 문의하기 (공개 폼 → 관리자 문의함) (로드맵 Top 8 ③ 후속)

> 상태: v7 — 적대적 리뷰 6라운드 **ship**(사용자 요청으로 5라운드 한도 초과 1회 추가)(미구현). 로드맵 "③ 후속 — 문의하기"의 계획서다.
> **인가 정책 변경(무인증 POST 공개)과 스키마 변경(V47·V48)을 수반한다 — 승인 전에는 코드를 고치지 않는다.**

## 개정 이력

- 6라운드: **ship — 설계 결함 없음**(P3 1건은 시험 설계 보강, 수용). codex `gpt-6.1-sol`이 R5-1의 SQL 형식·접근 자체는 MariaDB 문서와 일치한다고 확인.
- v7 변경(6라운드 반영 — P3×1, 수용, 결정 필요 0건):
  - R6-1(P3 합산 대기 시험이 개별 락 타임아웃과 문장 전체 타임아웃을 구별하지 못함 — 3명×0.8초=2.4초면 `max_statement_time`을 빼도 통과하고 1초 이상이면 `innodb_lock_wait_timeout`이 먼저 실패): 수용 — 쟁점 9·완료 기준 변경. 시험은 **각 대기가 1초 미만(약 0.8초)이지만 누적이 3초를 넘는** 조건(수신자 5명 이상의 회원 행을 서로 다른 트랜잭션이 순차 해제)을 만들고, 실패 원인이 문장 전체 타임아웃임·앞서 삽입한 알림이 남지 않음(문장 단위 롤백)·문의는 저장됨을 확인한다. **`max_statement_time`만 제거하면 이 시험이 실패함을 변이 실험으로 확인**한다. `max_statement_time`은 검사 지점에서 중단하는 방식이라 엄밀한 벽시계 상한이 아니므로 문서·주석에 "대략의 상한"으로 표현한다.
- v6 변경(5라운드 반영 — codex `gpt-6.1-sol` `codex exec`, P2×1, 수용, 결정 필요 0건):
  - R5-1(P2 `innodb_lock_wait_timeout`은 락 획득 대기마다 새로 계산돼 다중 수신자 INSERT의 합산 대기 상한이 아님 — A·B·C 회원 행을 각각 2초씩 기다려도 통과하지만 303은 6초+ 지연, 단일 행 잠금 시험으로는 못 잡음): 수용 — 쟁점 9 변경. 대기 상한을 **문장 전체**로 건다: `SET STATEMENT max_statement_time=3, innodb_lock_wait_timeout=1 FOR INSERT …`(MariaDB 10.11, 문장 실행 시간 전체를 3초로 끊고 개별 락 대기도 1초로 제한). 실제 MariaDB·드라이버에서 문장 형식이 통하는지 시험으로 확정하고, 안 통하면 같은 트랜잭션 안에서 `SET SESSION`으로 올리고 `finally`로 복원한다. 시험을 "서로 다른 수신자 N명의 회원 행을 순차 해제하며 합산 대기가 상한 안에 끝남"으로 확장.
- v5 변경(4라운드 반영 — codex `gpt-6.1-sol` `codex exec`(첫 호출은 `Selected model is at capacity`로 실패해 1회 재시도 성공), P2×1, 수용, 결정 필요 0건):
  - R4-1(P2 `AFTER_COMMIT` 리스너는 트랜잭션 자원 정리 전에 실행돼 기존 커넥션을 쥔 채 `REQUIRES_NEW`로 두 번째 커넥션을 요구 → 풀 크기만큼 동시 제출이 오면 풀 획득 타임아웃까지 지연·다른 DB 요청도 막힘, 락 대기 제한으로는 못 막고 IP 제한은 전체 동시성을 제한하지 못함): 수용 — 쟁점 9·10 변경. 프로젝트에 이미 있는 규칙("요청당 커넥션 2개 금지", `LoginFailureService` 주석·알림 `CLAUDE.md`의 로그인 경로)을 따른다: `InquiryReceivedEvent`와 `NotificationEventListener` 확장을 **버리고**, 컨트롤러가 트랜잭션 프록시 호출(`InquiryService.submit`)이 **반환된 뒤** 트랜잭션 없는 `InquiryNotifier.notifyReceived()`를 호출한다(내부에서 `NotificationRecorder.recordInquiryReceived()`를 `REQUIRED`로 자기 트랜잭션을 열어 호출, try/catch로 알림만 포기). 작은 풀 동시 제출 시험 추가.
- v4 변경(3라운드 반영 — codex `gpt-6.1-sol` `codex exec`, P2×1·P3×1, 수용 1·부분 수용 1, 결정 필요 0건):
  - R3-1(P2 알림 저장이 요청 스레드에서 동기 실행돼 수신자 회원 행 락 대기(FK 검사) 시 접수 완료 응답이 지연·끊김 → 재제출 중복): **부분 수용** — 쟁점 9 변경. 지연 위험은 인정하고 알림 INSERT의 **락 대기를 3초로 제한**한다(`SET STATEMENT innodb_lock_wait_timeout=3 FOR INSERT …` 우선, 드라이버가 거부하면 `SET SESSION` + `finally` 복원 — 풀 커넥션에 세션 값이 남지 않게. 시험으로 확정). 초과하면 기존 계약대로 예외를 삼키고 알림만 포기한다. 완료 기준에 "수신자 회원 행을 다른 트랜잭션이 잠근 상태에서 POST가 락 해제 전에 303을 반환하고 문의는 저장됨" 추가. **기각: 알림 전용 비동기 실행기 신설** — 스레드풀·거절 정책·종료 처리라는 새 인프라와 실패 모드가 생기는데 필요한 것은 대기 상한뿐이다(기존 E1~E4도 같은 동기 계약).
  - R3-2(P3 `cf-turnstile-response` 토큰 자체의 누락·길이 검증 없음): 수용 — 쟁점 4·8 변경. 토큰은 비어 있거나 2048자 초과면 로컬 400(입력 보존), 외부 호출 0회.
- v3 변경(2라운드 반영 — codex `gpt-6.1-sol` `codex exec`, P2×2·P3×1, 전부 수용, 결정 필요 0건):
  - R2-1(P2 OpenJDK 21 `HttpClient`는 응답 헤더 수신 후 요청 타이머를 취소해 본문 지연을 막지 못함): 쟁점 4 변경 — `sendAsync` future를 `get(5초)`로 기다리고 초과/오류 시 `cancel(true)`, 검증 URL을 설정 가능하게 해 헤더만 보내고 본문을 멈추는 로컬 서버 시험 추가.
  - R2-2(P2 `utf8mb4_bin`은 PAD SPACE라 후행 공백 변형이 일치): 쟁점 9 변경 — `utf8mb4_nopad_bin` 비교, 후행 공백 행 제외 시험.
  - R2-3(P3 "시간당 10회" 안내가 토큰 버킷 실제 동작과 다름): 쟁점 3 변경 — 안내 문구를 "짧은 시간에 여러 번 제출하면 잠시 제한됩니다"로 하고 실제 동작(연속 10회 소진 후 약 6분마다 1회)을 계획에 정확히 기록.
- v2 변경(1라운드 반영 — codex `gpt-6.1-sol` `codex exec`, P1×1·P2×4·P3×1, 전부 수용, 결정 필요 0건):
  - R1-1(P1 prod Compose가 환경변수를 명시 매핑하고 호스트 셸 변수 가드 목록이 따로 있어 `.env.prod`에 키를 넣어도 컨테이너에 전달되지 않음): 쟁점 4 변경 — `docker-compose.prod.yml`에 3변수(`enabled`·두 키) 매핑, `scripts/_prod-env-guard.sh`의 `PROD_ENV_GUARD_VARS`에 등록, `.env.example`에 키 이름. **추가 발견**: CI `prod-smoke`가 더미 값으로 prod 이미지를 기동하므로 키를 필수로 하면 CI가 깨진다 → `enabled` 기본 `false`, 키 검증은 `enabled=true`일 때만(R1-3과 한 묶음).
  - R1-2(P2 `PublicWebExceptionAdvice`가 `log.error(msg, e)`로 원인 체인을 남겨 저장·커밋 실패 시 입력이 로그로 복제될 수 있음): 쟁점 3 변경 — 제출 경로는 컨트롤러 경계에서 서비스 예외를 잡아 **예외 클래스명만** 로그에 남기고 원인 체인·메시지는 남기지 않는다. 표식 입력을 담은 저장 예외를 주입해 응답·로그 비유출을 시험(완료 기준 추가).
  - R1-3(P2 prod 공개 테스트 키 통과·`enabled=false` 동작 미정의): 쟁점 4 변경 — prod 프로파일 검증기가 Cloudflare 테스트 키 패턴(site `1x0…`·`2x0…`·`3x0…`, secret `1x0…AA`·`2x0…AA`·`3x0…AA`)을 거부. **`enabled=false`는 "문의 기능 닫힘"** — GET·POST `/inquiry` 모두 503 안내 페이지, 캡차 없이 제출받는 상태는 존재하지 않는다.
  - R1-4(P2 알림 수신자 SQL의 `general_ci` 비교가 `PermissionCache`의 `Enum.valueOf` 정확 일치와 어긋남): 쟁점 9 변경 — 수신자 쿼리는 `utf8mb4_bin` 이진 비교로 정확 일치만 인정, 변형 행(`inquiry`/`read`/후행 공백 `'INQUIRY '`·`'READ '`) MANAGER 제외 시험 추가.
  - R1-5(P2 429는 필터가 `sendError`로 끝내 입력 보존 불가): 쟁점 1·3 변경 — **429는 입력 보존 대상에서 제외**를 계약으로 명시(필터 위치는 CSRF 우회 quota 고갈 공격 방어 때문에 바꿀 수 없음). 폼에 "시간당 10회까지" 안내, 한도가 입력 오류 재시도까지 흡수하도록 10회 유지.
  - R1-6(P3 최종 입력 정책이 캡차 뒤 서비스에서 실행): 쟁점 4·8 변경 — `InquiryInputPolicy`(MessageTextPolicy 포함 + 이름·이메일·동의)를 **컨트롤러에서 캡차 전에** 실행하고 정규화된 값을 서비스에 넘긴다. 순서 고정: 바인딩 → 정책(trim·정규화·검증) → 캡차 → 서비스.
- v1: 최초 작성(정찰 → 쟁점 14개 결정). 사용자 결정(2026-10-12): 수집 항목 = 이름+이메일+제목+본문 + 동의 체크 필수 / 보존 = 수동 삭제 + 미처리 건수 상한 / 스팸 = **Cloudflare Turnstile(외부 서비스)** + IP 레이트리밋 / 답변 메일 없음·상태만 관리 / 알림 수신 = ADMIN + 문의함 조회 권한 MANAGER.

## Context

비로그인 방문자가 공개 `/inquiry` 폼으로 문의를 남기면, 관리자는 문의함(`/admin/inquiry/manage`)에서 목록·상세를 보고 처리 상태(접수 → 처리 중 → 답변 완료)를 바꾸고 삭제한다. 새 문의는 상단바 알림으로 알린다. **답변 메일은 보내지 않는다** — 관리자가 문의자 이메일로 직접 회신하고 시스템은 상태만 기록한다.

### 정찰 요약 (읽은 근거)

| 읽은 곳 | 알게 된 것 |
|---|---|
| `config/SecurityConfig` + `config/CLAUDE.md` | 공개 경로는 정확 경로별 permit + 나머지 `denyAll`, 끝은 `anyRequest().denyAll()`. **공개 POST는 비밀번호 재설정 JSON API 2종뿐**(`/admin/api/...` 아래, 페이지 컨트롤러 POST는 없음). 이 계획이 두 번째 무인증 POST다. |
| `publicweb/support/PublicWebExceptionAdvice` | `com.cms.publicweb`의 **모든 `Exception`을 HTML 500으로 바꾼다**(`@Order(HIGHEST)`). 이 패키지에서 `@Valid` 실패 예외를 던지면 400이 아니라 500이 된다 → 컨트롤러가 `BindingResult`로 직접 처리해야 한다(쟁점 3). |
| `config/ratelimit/*` + `application.yml` | IP(`getRemoteAddr()`) 키 토큰 버킷, 규칙은 yml 순서대로 첫 일치. 필터는 `CsrfFilter` **다음**이라 CSRF 실패는 quota를 쓰지 않는다. POST 규칙 선례 `reset-request`(5/3600초). |
| `member/controller/PasswordResetController` | 무인증 JSON POST + 페이지 JS가 `X-CSRF-TOKEN`을 붙이는 방식. 익명 CSRF 토큰은 **세션 기반**이라 폼 GET이 방문자마다 세션을 만든다. |
| `admin/permission/AdminFeature` | `DELEGABLE` 기능 5개(BANNER·SITE_PAGE·POPUP·FAQ·MEDIA). 항목 하나 추가로 `SecurityConfig`의 `DELEGABLE` 루프가 게이트를 만든다. `AdminFeatureTest`는 게이트 패턴·메뉴 URL이 `/admin` 안이고 서로 겹치지 않음을 고정. |
| `admin/notification/*` + `CLAUDE.md` | 알림 문장은 **서버 상수 템플릿**(사용자 입력 금지 — 단 E4 아이디 예외), 생성은 AFTER_COMMIT 리스너 + `REQUIRES_NEW` 최선 노력, 메서드 `@Order`(100), 다중 행 `INSERT … VALUES`. 수신자 쿼리는 지금 "ACTIVE ADMIN"뿐. **스케줄러가 없다**(정리는 로그인 시). D11 열람 필터는 `ADMIN_ACCOUNT_LOCKED` 한 종류 상수로 하드코딩. |
| `admin/message/service/MessageTextPolicy` | 평문 제목·본문 검증 파이프라인(서로게이트·제어문자·양방향 제어문자·길이)이 `public static`. 예외는 `InvalidRequestException` + 고정 문구. |
| `admin/faq/*` (최신 DELEGABLE, #135) | 파급 목록(매트릭스 시험·사이드바 스냅샷·권한 평가 시험·메뉴 노출 시험·`SecurityConfigTest` 게이트 절·`AdminActionTypes`·`docs/deployment.md` 롤백 문구), 감사 `safeErrorMessage`, 메뉴 시드 `WHERE NOT EXISTS`, DDL/시드 분리(V41/V42). |
| `publicweb/faq/*` + 템플릿 | 공개 컨트롤러는 `@AdminPage` 미부착, 템플릿 컨벤션 시험(`th:utext` 위치·스크립트 허용 범위)이 있다. |
| `build.gradle`·`ClientIpResolver` | HTTP 클라이언트 스타터 없음(JDK 21 `java.net.http.HttpClient`는 의존성 추가 없이 사용 가능). `getRemoteAddr()`만 신뢰, 전달 헤더 미신뢰. CSP 헤더 설정 없음(외부 스크립트 차단 없음). |
| `db/migration` | 최신 **V46**. 다음은 V47(DDL)·V48(메뉴 시드). 메뉴 시드는 `WHERE NOT EXISTS` 멱등. |

## 핵심 쟁점과 결정

### 쟁점 1 — 무인증 POST 공개 경로와 전송 방식 (인가 정책 변경)

- 선택지: (a) JSON API(`POST /inquiries`) + JS fetch (b) 공개 폼 `POST /inquiry`(application/x-www-form-urlencoded) + 서버 렌더링 (c) 관리자 API 아래(`/admin/api/...`)에 두기.
- **결정 (b)**. 이유: Turnstile 위젯은 폼 안에 hidden `cf-turnstile-response`를 넣어 주므로 폼 POST가 가장 자연스럽고, 검증 실패 시 **입력을 보존한 채 폼을 다시 그릴 수 있다**(JSON은 JS가 복구해야 한다). (c)는 `/admin/**` 전체가 로그인 영역이라 의미가 어긋나고 `/admin/api` JSON 오류 계약과도 맞지 않아 기각.
- 경로는 **정확 경로 `/inquiry` 1개**만 GET·HEAD·POST 공개(`permitAll`), `/inquiry`·`/inquiry/**`의 나머지 메서드·하위 경로는 `denyAll`. 성공은 **PRG** — `303 → GET /inquiry?sent`(같은 경로, 쿼리로 완료 안내만 표시). 새로고침이 문의를 중복 등록하지 않게 하려는 것이고 새 경로를 만들지 않는다.
- **`SecurityConfig` 변경(승인 필요)**: `.requestMatchers(GET,HEAD,POST, "/inquiry").permitAll()` + `.requestMatchers("/inquiry", "/inquiry/**").denyAll()`(공개 FAQ 규칙 바로 뒤). `/**`를 열지 않는다. CSRF는 그대로 활성 — 폼에 `_csrf` hidden을 Thymeleaf가 자동 삽입한다.
- 라우트는 `GET /inquiry`·`POST /inquiry` 둘뿐이고, 컨벤션 시험으로 `publicweb` 아래 비-GET 매핑이 `/inquiry` 하나뿐임을 고정한다(쟁점 12).

### 쟁점 2 — 권한: 새 `DELEGABLE` 기능 `INQUIRY`

- 선택지: (a) `ADMIN_ONLY` (b) `DELEGABLE`(READ·UPDATE·DELETE).
- **결정 (b)** — 사용자 선택. `INQUIRY(DELEGABLE, "문의", {READ, UPDATE, DELETE}, 메뉴 /admin/inquiry/manage, 게이트 /admin/inquiry/manage·/admin/api/inquiries·/admin/api/inquiries/**)`. CREATE는 관리자가 문의를 만들지 않으므로 지원 동작에서 뺀다(MEDIA가 UPDATE를 뺀 선례). 동작 매핑: 목록·상세 = READ, 상태 변경 = UPDATE, 삭제 = DELETE. 다른 기능 권한과 독립.
- 개인정보(이름·이메일)가 있는 화면이라 MANAGER 기본값은 **권한 없음**(신규 `DELEGABLE`의 기본과 같다) — 마이그레이션이 기존 MANAGER에게 권한을 부여하지 않는다.

### 쟁점 3 — 컨트롤러 검증과 공개 예외 advice

- **결정**: `POST /inquiry`는 `@ModelAttribute @Valid InquiryForm form, BindingResult` + 서비스 호출을 컨트롤러가 직접 분기한다. `BindException`·`MethodArgumentNotValidException`을 **던지지 않는다**(던지면 `PublicWebExceptionAdvice`가 500으로 바꾼다). 검증 실패·캡차 실패·상한 초과는 모두 **같은 폼 뷰를 상태 코드와 함께 반환**한다: 필드 검증/캡차 실패 400, 상한 초과 503, 레이트리밋 429(필터가 처리).
- 글로벌 advice를 바꾸지 않는다(무관한 변경 금지). 서비스가 던지는 `InvalidRequestException`은 컨트롤러가 잡아 필드 오류로 변환한다(전용 `try/catch`는 이 경계 한 곳, 코딩 컨벤션 "try-catch 남발 금지"의 예외로 계획서에 명시).
- 응답 본문에 **사용자가 입력한 값을 `th:value`/`th:text`로만** 되돌린다(자동 이스케이프). 오류 문구는 고정 한국어 문구다.
- **입력 보존 계약**: 400(검증·캡차 실패)과 503(상한 초과)은 입력을 보존해 폼을 다시 그린다. **429는 보존하지 않는다** — `RateLimitFilter`가 `sendError`로 끝내 컨트롤러에 도달하지 못하고(필터는 CSRF 우회 quota 고갈 공격 방어 때문에 `CsrfFilter` 다음에 있어야 한다), 폼이 "짧은 시간에 여러 번 제출하면 잠시 제한됩니다" 수준으로 미리 안내한다(R2-3: 토큰 버킷은 초기 10개 + 연속 충전이라 정확히 "시간당 10회"가 아니다 — 연속 10회 소진 후 약 6분마다 1회 재시도 가능, 첫 60분에 최대 19회). 세션 만료로 인한 CSRF 403도 보존하지 않는다(안내만).
- **로그 비유출(R1-2)**: `PublicWebExceptionAdvice`는 예외와 원인 체인을 그대로 로그에 남기므로 문의 제출은 그 경로에 기대지 않는다. 컨트롤러가 서비스 호출을 `try/catch`로 감싸 실패 시 **예외 클래스명만** WARN/ERROR로 남기고(메시지·원인·입력 없음) 폼에 "일시적 오류" 문구를 400대가 아닌 **500**으로 되돌린다. 응답 본문에도 예외 정보를 싣지 않는다.

### 쟁점 4 — 스팸 대책: Turnstile + IP 레이트리밋 (외부 서비스 도입)

- 선택지: Turnstile / reCAPTCHA / 자체 이미지 캡차. **사용자가 Turnstile을 선택**했다.
- 서버 검증: 폼의 `cf-turnstile-response`를 `POST https://challenges.cloudflare.com/turnstile/v0/siteverify`(`secret`·`response`·`remoteip`)로 확인한다. HTTP 클라이언트는 **JDK `java.net.http.HttpClient`**(새 스타터·의존성 없음 — "검증되지 않은 새 라이브러리는 먼저 제안" 규칙에 걸리지 않게 한다). 연결 3초 + **호출 전체 5초 벽시계 제한**: `HttpRequest.timeout`은 OpenJDK 21에서 응답 헤더 수신 후 타이머가 취소되어 본문 수신 지연을 막지 못하므로(R2-1) `sendAsync(...)` 결과 `CompletableFuture`를 `get(5초)`로 기다리고 초과/오류 시 `cancel(true)`로 진행 중 요청을 취소한다. 검증 URL은 `cms.inquiry.turnstile.verify-url`(기본 Cloudflare)로 설정 가능하게 해 시험이 로컬 서버를 가리킨다. 시험: 헤더만 즉시 보내고 본문은 멈추는 로컬 서버에서 5초대 안에 실패(fail-closed) 반환.
- **트랜잭션 밖에서 호출한다**(오픈-인-뷰 꺼짐, 컨트롤러가 검증을 먼저 하고 통과하면 서비스 `@Transactional`에 진입) — 외부 호출 중 DB 커넥션을 쥐지 않는다. 호출 순서(고정, R1-6): 폼 바인딩(`@Size` raw 상한) + **캡차 토큰 형식(빈 값·누락·2048자 초과는 로컬 400, 입력 보존, 외부 호출 0회 — R3-2)** → **`InquiryInputPolicy` 전체**(이름·이메일 trim·검증, 제목·본문 `MessageTextPolicy`, 동의 — DB 없음) → **캡차 검증** → 서비스(상한 확인·저장). 서비스는 정책이 정규화한 값만 받는다. 어떤 형식 오류도 외부 호출·토큰 소비를 일으키지 않는다.
- **장애 정책 = fail-closed**: Cloudflare 응답 불가·타임아웃·`success=false`는 모두 "캡차 확인 실패, 다시 시도" 400 폼. 스팸 방어 우선이고 문의 중단은 수용한다(Cloudflare 장애 시 문의가 일시 중단됨을 문서에 기록). 응답 JSON은 `success`만 신뢰한다(`hostname`·`action` 대조는 하지 않는다 — 과설계).
- 설정 `cms.inquiry.turnstile.{enabled, site-key, secret-key}`. **`enabled` 기본값은 `false`**(base yml). dev는 `application-dev.yml`에서 `enabled: true` + Cloudflare 공개 **테스트 키**(항상 통과)를 둔다. 시크릿은 코드·git에 두지 않는다(`.env.prod`, `.env.example`에 키 이름만).
- **`enabled=false` = 문의 기능 닫힘(R1-3)**: `GET /inquiry`·`POST /inquiry` 모두 503과 "문의를 받지 않습니다" 안내 페이지를 돌려준다. 캡차 없이 제출을 받는 상태는 어떤 설정 조합으로도 만들 수 없다(fail-closed 유지). CI `prod-smoke`는 더미 환경으로 `enabled=false` 그대로 기동하므로 CI 변경이 없다.
- **`enabled=true` 기동 검증(prod 프로파일)**: 두 키가 비어 있으면 기동 실패, **Cloudflare 공개 테스트 키 패턴이면 기동 실패**(site key `1x0…`·`2x0…`·`3x0…` 접두, secret `1x0…AA`·`2x0…AA`·`3x0…AA` 형태 — 항상 통과/실패/상호작용 테스트 키) — 공개 테스트 키를 복사해 캡차가 무력화되는 운영 실수를 막는다. `RateLimitConfigValidator`처럼 비활성 시 검증을 건너뛰는 것은 위 "닫힘" 의미라 안전하다.
- **prod 배선(R1-1)**: `docker-compose.prod.yml`의 `app.environment`에 `CMS_INQUIRY_TURNSTILE_ENABLED: ${…:-false}`·`CMS_INQUIRY_TURNSTILE_SITE_KEY: ${…-}`·`CMS_INQUIRY_TURNSTILE_SECRET_KEY: ${…-}` 매핑을 추가하고 `scripts/_prod-env-guard.sh`의 `PROD_ENV_GUARD_VARS`에 세 이름을 등록한다. 완료 기준에 "Compose 경로로 `enabled=true` + 비-테스트 키 기동 성공 / 테스트 키 기동 실패" 확인을 둔다.
- 테스트에서는 `CaptchaVerifier` 인터페이스(구현 `TurnstileCaptchaVerifier`)를 가짜로 대체한다 — 네트워크 의존 없는 시험을 위한 최소 이음매이며 다른 용도로 확장하지 않는다.
- **IP 레이트리밋 병행**: `cms.rate-limit.rules`에 `inquiry-submit`(POST `/inquiry`, **10회/3600초**)와 `public-inquiry`(GET·HEAD `/inquiry`, 120/60초). 입력 오류 재시도도 quota를 쓰므로 재설정(5)보다 넉넉히 10. 키는 `getRemoteAddr()`, 공유 IP에서는 서로 차단될 수 있음을 수용한다(기존 규칙과 같은 계약).
- 허니팟·최소 제출 시간은 두지 않는다(캡차가 있어 과설계).
- 프라이버시: 방문자 IP와 토큰이 Cloudflare로 전송된다 — 개인정보처리방침에 위탁/제3자 제공 고지가 필요하다는 점을 `docs/deployment.md` 운영 안내에 쓴다(법률 판단은 하지 않는다).
- 폼 페이지가 외부 스크립트 `https://challenges.cloudflare.com/turnstile/v0/api.js`(`async defer`) 하나를 로드한다 — 공개 템플릿 컨벤션 시험의 "허용 스크립트" 규칙에 이 한 곳을 명시 허용으로 추가한다(CSP 헤더가 없어 차단 설정 변경은 없다).

### 쟁점 5 — 개인정보 수집·동의·보존

- **수집**: 이름(1~50자), 이메일(형식 검증, ≤100자), 제목(1~100자), 본문(1~2000자) + 동의 시각. **전화번호·IP·User-Agent는 저장하지 않는다**(IP는 메모리 레이트리밋에만 쓰이고 DB에 남지 않는다).
- **동의**: 체크박스가 필수이며 폼에 수집 항목·목적·보관 안내 문구를 직접 싣는다("문의 응대를 위해 이름·이메일·문의 내용을 수집하며, 처리 완료 후 관리자가 삭제할 때까지 보관합니다"). 동의 안 하면 400. 저장 시 `consent_at`(주입된 KST `Clock`)을 기록한다. 정확한 처리방침 문구는 운영자가 정적 페이지로 관리하므로 폼은 링크를 하드코딩하지 않는다(없는 슬러그로 가는 깨진 링크 방지).
- **보존·파기**: 스케줄러가 없으므로 **자동 삭제하지 않는다**. 삭제는 DELETE 권한자의 하드 삭제뿐이다. 운영 권고(보존 기간은 처리방침에 맞춰 운영자가 정함)를 `docs/deployment.md`에 쓴다.
- **미처리 건수 상한**: `status <> 'ANSWERED'`인 행이 **1,000건 이상이면 새 문의를 503으로 거부**하고 폼에 "현재 문의를 받을 수 없습니다" 문구를 보인다. 스팸으로 늘어나는 행은 항상 미처리 상태이고 답변 완료는 관리자 행위로만 늘기 때문에, 이 상한이 스팸 주도의 DB 성장을 막는다. **소프트 상한**이다(동시 요청이 몰리면 상한을 조금 넘을 수 있음 — 가드 행 직렬화는 하지 않는다. 팝업의 소프트 상한 선례, 스팸 방어 목적에 정확한 개수가 필요 없음). 정확한 상한은 쟁점 15의 시험에서 "상한 도달 시 거부"까지만 고정한다.

### 쟁점 6 — 도메인·스키마 (V47, V48)

- `inquiry(id BIGINT PK AI, name VARCHAR(50) NOT NULL, email VARCHAR(100) NOT NULL, title VARCHAR(100) NOT NULL, body TEXT NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'RECEIVED', consent_at DATETIME(6) NOT NULL, create_date DATETIME(6) NOT NULL, update_date DATETIME(6) NOT NULL)` + 인덱스 `idx_inquiry_status_id (status, id)`(상한 COUNT·상태 필터 목록)와 `idx_inquiry_id`는 PK로 충분(최신순 목록). `utf8mb4_general_ci`, `ENGINE=InnoDB`. `body`는 2000자 상한이라 `TEXT` 충분. 날짜는 `datetime(6)`, 시각은 주입된 KST `Clock`.
- `InquiryStatus`: `RECEIVED`(접수) · `IN_PROGRESS`(처리 중) · `ANSWERED`(답변 완료). 상태 전이는 **제한하지 않는다**(관리자가 실수를 되돌릴 수 있게; 같은 값 재요청은 멱등 200, `update_date` 갱신 없음).
- **V47 = DDL 1문**(`CREATE TABLE inquiry`), **V48 = 메뉴 시드 DML**(`/admin/inquiry/manage` '문의함', 최상위 맨 끝, `fas fa-fw fa-envelope-open-text`, `WHERE NOT EXISTS`). DDL/시드 분리는 V41/V42 선례.
- 엔티티는 `@Setter` 없음, 도메인 메서드 `changeStatus(status, now)`가 `LocalDateTime now`를 받는다(시각 원천 규약).

### 쟁점 7 — 관리자 API·화면

- 페이지 `GET /admin/inquiry/manage`(`@AdminPage`) — 상태 탭(전체/접수/처리 중/답변 완료) + 목록 + 상세 모달/패널. 새 알림 링크 `/admin/inquiry/manage`(목록)로 연결.
- API(`/admin/api/inquiries`, `api-conventions` 스킬 규칙을 구현 시 확인):
  - `GET ?status=&page=&size=` — 최신순(`id desc`, id 보조 정렬), **본문 미포함**(이름·이메일·제목·상태·접수 시각). READ.
  - `GET /{id}` — 상세(본문 포함). **조회가 상태를 바꾸지 않는다**(GET 부작용 없음 — 상태 변경은 명시적 PATCH). READ.
  - `PATCH /{id}` `{"status": "IN_PROGRESS"}` — 상태 변경. UPDATE. **행 잠금**(`@Lock(PESSIMISTIC_WRITE)` 조회)으로 삭제와 직렬화, 사라진 행은 404.
  - `DELETE /{id}` — 하드 삭제. DELETE. 행 잠금 후 삭제, 없으면 404.
- 모든 핸들러는 `@RequirePermission(INQUIRY, 동작)`(인가 선언 컨벤션 시험이 강제). 컨트롤러는 DTO만 노출(엔티티 노출 금지).
- **표시 XSS 방어**: 이름·이메일·제목·본문은 모두 **사용자 입력 평문**이다. 서버는 원문 저장(필터 없음), 화면은 `textContent`/`th:text` + CSS `white-space: pre-wrap`만 쓰고 **자동 링크화·HTML 출력을 하지 않는다**(쪽지 선례). `th:utext`를 쓰지 않는다 — 템플릿 컨벤션 시험이 잠근다.
- 이메일은 `mailto:` 링크를 만들지 않는다(링크 생성 시 `href` 주입 검토가 필요해 범위 밖; 화면은 텍스트 + 복사만. 관리자가 직접 메일 클라이언트에서 회신).
- 목록 검색(이름·이메일)은 만들지 않는다(요청 범위 밖).
- **감사 로그**: `INQUIRY_STATUS`·`INQUIRY_DELETE`(`targetType=INQUIRY`, `targetId`=문의 ID, **targetLabel 없음** — 사용자 입력이라). `@AdminActionLogged`에 `safeErrorMessage`(실패 사유에 입력이 실리지 않게; FAQ·팝업 선례). `AdminActionTypes`·`admin/log/manage.html` 라벨 동기화 시험 갱신. 문의 상세 열람은 감사하지 않는다(다른 관리 화면과 같은 계약).

### 쟁점 8 — 입력 검증 파이프라인

- 이 정책 전체는 **컨트롤러가 캡차 전에 실행**한다(쟁점 4 순서). 제목·본문은 `MessageTextPolicy.normalizeTitle/normalizeBody`(서로게이트·제어문자·양방향 제어문자·길이·공백만 거부)를 **그대로 재사용**한다 — 이미 시험된 평문 검증이고 상한(제목 100·본문 2000)이 이 계획과 같다. `admin.message.service` → `publicweb` 의존이 생기는 점은 정적 유틸 호출 한 곳이므로 수용한다(정책 클래스를 `common`으로 옮기는 리팩터링은 무관한 변경이라 하지 않는다). 던져진 `InvalidRequestException`의 고정 문구를 컨트롤러가 필드 오류로 옮긴다.
- 이름(≤50, 제어문자 불가·공백만 불가)·이메일(`@Email`+길이, 앞뒤 공백 trim)은 서비스 입력 정책 클래스 `InquiryInputPolicy`에서 검증한다(제목 규칙을 이름에 재사용하되 상한만 다르게 — 새 규칙 발명 금지). 이메일은 **정규화하지 않고 입력한 대로**(앞뒤 공백만 제거) 저장한다 — 관리자가 회신 주소로 쓰므로 소문자화가 이득이 없다.
- 원문 크기 방어: `@Size` raw 상한(제목 200·본문 4000·이름 100·이메일 200)으로 거대 본문을 컨트롤러 진입에서 먼저 거른다. 서블릿 form 한도(`max-http-form-post-size` 기본 2MB)가 최종 방어선이다.

### 쟁점 9 — 알림: 새 종류 `INQUIRY_RECEIVED`

- 수신자: **ACTIVE ADMIN 전원 + ACTIVE MANAGER 중 `member_permission`에 `INQUIRY`/`READ` 허용 행이 있는 회원**. `MemberRepository`에 잠금 없는 일반 SELECT 한 개 추가(기존 `findActiveAdminIdsExcluding` 패턴, **네이티브 조인**). **비교는 `utf8mb4_nopad_bin` 정확 일치만**(R1-4·R2-2: `utf8mb4_bin`은 PAD SPACE라 `'INQUIRY '`도 일치하므로 NO PAD 이진 콜레이션을 쓴다) — 판정기(`PermissionCache`)가 `Enum.valueOf`로 정확한 문자열만 인정하는데 `general_ci`는 `inquiry`·`read` 변형 행도 일치시켜 "문의함은 403인데 알림은 옴"이 된다. 변형 행을 가진 MANAGER가 제외되는 시험을 둔다.
- 문장: **상수** "새 문의가 접수되었습니다. 문의함에서 확인해주세요."(문의 내용·이름·이메일을 넣지 않는다 — 알림 계약 "서버 상수 템플릿"과 개인정보 최소 노출). 링크 `/admin/inquiry/manage`.
- 생성(R4-1): **이벤트/`AFTER_COMMIT` 리스너를 쓰지 않는다.** `AFTER_COMMIT` 콜백은 트랜잭션 자원 정리 전에 실행돼 제출 트랜잭션의 커넥션을 쥔 채 `REQUIRES_NEW`가 두 번째 커넥션을 요구하므로, 풀 크기만큼 동시 제출이 오면 풀 획득 대기로 응답·다른 DB 요청이 막힌다(공개 무인증 경로에서는 동시성이 공격자 쪽에 있다). 대신 로그인 경로와 같은 모양이다: 컨트롤러가 `InquiryService.submit()`(트랜잭션 프록시 — 반환 시점에 커밋·커넥션 반환 완료)을 부르고 **반환된 뒤** 트랜잭션 없는 `InquiryNotifier.notifyReceived()`를 호출한다. 이 클래스가 `NotificationRecorder.recordInquiryReceived()`(`@Transactional(isolation = READ_COMMITTED)`, REQUIRED — 호출자에 트랜잭션이 없어 새 트랜잭션 하나만 연다)를 부르고 예외를 삼켜 ERROR 로그만 남긴다(문의는 이미 커밋 — 알림 최선 노력 계약, 완료 기준 "알림 실패가 문의를 롤백하지 않음"). 요청당 커넥션은 항상 1개씩 순차 사용이다.
- **스팸 넘침 방지(병합)**: 수신자 중 이미 **미읽음 `INQUIRY_RECEIVED` 알림이 있는 회원은 새 알림을 만들지 않는다**(벨에 문의 알림은 회원당 미읽음 최대 1건). 구현: 수신자 후보 ID → 그중 미읽음 문의 알림 보유자를 잠금 없는 SELECT로 찾아 제외 → 남은 회원에게 다중 행 INSERT. 트랜잭션은 `REQUIRES_NEW + READ_COMMITTED`(갭 락 회피, 쪽지 선례). **경합으로 중복 1건이 생겨도 무해**(허용, 문서화).
- **락 대기 상한(R3-1)**: 알림 저장은 요청 스레드에서 동기 실행되고 `notification.member_id` FK 검사가 수신자 회원 행의 공유 잠금을 요구해 다른 트랜잭션이 그 행을 수정 중이면 대기한다(기본 50초). 문의는 이미 커밋됐으므로 접수 완료 응답이 늦어지는 것이 문제다. 그래서 문의 알림 INSERT에만 **락 대기 3초 제한**을 둔다(R5-1: 락마다 새로 계산되는 `innodb_lock_wait_timeout`만으로는 합산 상한이 아니므로 `SET STATEMENT max_statement_time=3, innodb_lock_wait_timeout=1 FOR INSERT …`로 문장 전체를 끊는다 — 우선 시도, 드라이버·프리페어드 호환이 안 되면 같은 `REQUIRES_NEW` 트랜잭션 안에서 `SET SESSION`으로 올리고 `finally`에서 원래 값으로 복원 — 풀 커넥션에 세션 값이 남지 않게). 초과·실패는 기존 계약대로 삼키고 ERROR 로그만 남긴다(알림만 포기).
- 시험: 수신자(ADMIN) 회원 행을 다른 트랜잭션이 `FOR UPDATE`로 잡은 상태에서 `POST /inquiry`가 락 해제 전에 303을 반환하고 문의 행은 저장됨(알림은 없음), 락 해제 뒤에는 정상 생성.
- 읽지 않은 상태에서 문의가 여러 건 쌓였다는 사실은 알림이 아니라 문의함의 "접수" 탭이 보여 준다.
- **열람 시점 권한(D11 확장은 하지 않는다)**: 권한을 회수당한 MANAGER에게 기존 문의 알림이 남아 있을 수 있다. 문장이 상수라 노출되는 것은 "문의가 왔다"는 사실뿐이고 링크는 403으로 막힌다 — 알림 필터를 권한 인지형으로 일반화하는 변경은 범위 밖이라 **수용**한다(알림 `CLAUDE.md`에 기록).
- 알림 목록 JS·DTO가 알 수 없는 type 문자열에서 깨지지 않는지 구현 시 확인한다(`type`을 표시에 쓰지 않으면 영향 없음).

### 쟁점 10 — 동시성

- 제출: 문의 INSERT 한 문장 + 상한 COUNT는 **잠금 없는 일관 읽기** + 이벤트 발행. 가드 행·행 잠금 불필요(문의 간 순서·유일성 제약 없음, 소프트 상한 수용).
- 상태 변경/삭제: 문의 행 `FOR UPDATE` 후 처리 → 같은 문의에 대한 PATCH와 DELETE가 직렬화된다. 서로 다른 문의는 서로를 막지 않는다. 격리 수준은 기본(REPEATABLE READ)에서 PK 조회 `FOR UPDATE`만 쓰므로 `innodb_snapshot_isolation` 1020 위험이 없다(조건부 UPDATE가 아닌 PK 잠금 읽기 — 구현 시 시험으로 고정).
- 동시에 같은 문의를 서로 다른 상태로 PATCH하면 나중에 잠금을 얻은 쪽이 이긴다(마지막 쓰기 우선, 낙관적 버전 없음 — 상태가 세 값뿐이라 충돌 비용이 낮고 `update_date`가 최신 변경을 보여 준다).

### 쟁점 11 — 롤백·배포 주의 (운영 문서)

- **롤백 시 알림 목록 파손 위험**: `Notification.type`은 `@Enumerated(STRING)`이라 롤백된 구버전 앱이 `INQUIRY_RECEIVED` 행을 읽으면 enum 매핑 예외로 **알림 목록 조회가 실패**한다. 롤백 전 `DELETE FROM notification WHERE type = 'INQUIRY_RECEIVED'`가 필요하다(`docs/deployment.md`·`docs/migration-guide.md`에 절차 기록).
- 롤백 후 `member_permission`의 `INQUIRY` 행이 남아 앱 재배포 시 권한이 부활한다(다른 DELEGABLE과 같은 주의, 문서 문구 추가).
- `inquiry` 테이블은 롤백 후에도 남는다(구버전은 무시). 문의 데이터가 개인정보이므로 롤백 시 테이블 보존/삭제 결정은 운영자 몫이라고 명시한다.
- 백업/복구(`prod-backup.sh`)는 DB 덤프에 포함되어 별도 변경이 없다(개인정보가 백업에도 들어간다는 점을 처리방침 안내에 함께 쓴다).

### 쟁점 12 — 공개 템플릿·컨벤션 시험

- `templates/public/inquiry/form.html` — 폼 + 완료 안내(`sent` 파라미터) + 오류 표시. 스크립트는 Turnstile 외부 스크립트 **하나뿐**(자체 JS 없음; 폼 이중 제출은 PRG와 서버 레이트리밋으로 방어하고 버튼 비활성화 JS는 두지 않는다 — 스크립트 최소). 정적 CSS `/css/public/inquiry.css`(예약 정적 접두사 안).
- `PublicInquiryTemplateConventionTest`(신규): `th:utext` 없음, 외부 스크립트는 Turnstile 한 곳, 폼에 CSRF가 렌더링됨.
- 라우트 컨벤션: `publicweb` 아래 `@PostMapping`은 `POST /inquiry` 하나뿐(새 공개 쓰기 경로가 정책 승인 없이 늘지 않게 고정).
- 홈 푸터/FAQ 하단에서 문의 폼으로 가는 링크는 FAQ 페이지 하단 링크 한 줄만 추가한다(홈은 스크립트·템플릿 컨벤션이 엄격해 건드리지 않는다).

### 쟁점 13 — 카탈로그 확장의 파급 (FAQ 선례로 확인한 목록)

`AdminFeature.INQUIRY` 추가 시 갱신: `AdminFeatureTest`(자동 통과 확인), `AdminPermissionEvaluatorTest`, `MenuExposureSidebarIntegrationTest`(자동 생성이라 확인), `AdminSidebarAdviceSnapshotTest`(메뉴 시드 반영), `SecurityConfigTest`(문의 게이트 절 신규), `MemberPermissionServiceTest`의 `AdminFeature.values()` 기대(자동), 권한관리 화면(매트릭스는 카탈로그에서 생성되므로 자동 — 구현 때 실기 확인), `AdminActionTypes`·`admin/log/manage.html`, `docs/deployment.md`·`docs/migration-guide.md`(V47·V48 표), 각 패키지 `CLAUDE.md`(신규 `admin/inquiry`·`publicweb/inquiry`, `config`·`notification`·`permission` 갱신), 루트 `CLAUDE.md` 지침 파일 지도.

### 쟁점 14 — 범위 밖 (명시)

답변 메일 발송, 첨부파일, 문의 검색·CSV, 관리자 답변 메모, IP/UA 저장, 보존기간 자동 삭제(스케줄러), `X-Forwarded-For` 신뢰(실배포 ingress 이후 재평가), 알림 권한 인지형 필터, 문의자 본인 조회 화면, 허니팟.

## 구현 파일 (예정)

신규: `admin/inquiry/{controller,domain,dto,repository,service}`, `publicweb/inquiry/*`(컨트롤러·폼 DTO·`CaptchaVerifier`·`TurnstileCaptchaVerifier`·properties), `V47__create_inquiry.sql`, `V48__seed_inquiry_menu.sql`, `templates/admin/inquiry/manage.html`, `templates/public/inquiry/form.html`, 정적 CSS/JS, 각 `CLAUDE.md`. 수정: `AdminFeature`, `SecurityConfig`, `application.yml`·`application-dev.yml`·`application-prod.yml`·`.env.example`, `NotificationType`, `NotificationMessages`, `NotificationRecorder`, `MemberRepository`, `AdminActionTypes`·log 화면, FAQ 공개 템플릿(링크 한 줄), 문서들.

## 단계 (의존 방향 안쪽 → 바깥쪽)

1. V47·V48 + 엔티티·리포지토리·`InquiryStatus` → 컴파일
2. 입력 정책·서비스(제출·상한·상태 변경·삭제)·이벤트 → 서비스 시험
3. 알림(타입·문장·수신자 쿼리·`InquiryNotifier`·병합)
4. `AdminFeature.INQUIRY` + 관리자 API + 감사 + 화면
5. 공개 폼(캡차 검증기·컨트롤러·템플릿) + `SecurityConfig` + 레이트리밋 규칙 + 설정
6. 전체 시험 → 실기(Playwright) → 문서·CLAUDE.md

## 완료 기준

- [ ] `./gradlew test` 통과 — 공개 POST: CSRF 토큰 없음 403, 검증 실패 400(입력 보존), 캡차 실패 400, 상한 도달 503, 레이트리밋 429, 하위 경로/타 메서드 거부, 성공 시 PRG 303
- [ ] 알림 저장 실패가 문의 저장을 롤백하지 않음 / 미읽음 문의 알림 보유 회원은 중복 생성 안 됨 / 수신자 = ADMIN + INQUIRY READ MANAGER(권한 없는 MANAGER 제외)
- [ ] 권한 매트릭스: MANAGER 권한 조합 × 핸들러 4개, ADMIN 허용, 위임 불가·무권한 403, `AdminEndpointAuthorizationConventionTest` 통과
- [ ] 관리자 화면에서 이름·제목·본문에 HTML/스크립트를 넣어도 이스케이프되어 텍스트로만 렌더링됨(통합 + 브라우저 시험)
- [ ] 상태 변경·삭제 동시 실행 시 교착/예외 없이 직렬화(404 또는 정상)
- [ ] Playwright: 공개 폼 제출(테스트 키) → 완료 안내 → 관리자 상단바 알림 → 문의함에서 상세 확인·상태 변경·삭제
- [ ] Flyway 새 DB 기동 + V46 DB 업그레이드 시험
- [ ] Turnstile: `enabled=false` 시 GET·POST 503(저장 없음), prod 프로파일 `enabled=true`에서 키 누락·공개 테스트 키 기동 실패, Compose 경로로 비-테스트 키 기동 성공, 캡차 검증 호출이 DB 트랜잭션 밖에서 일어남, Cloudflare 응답 불가·`success=false` 시 400(fail-closed)
- [ ] 로그 비유출: 표식 입력을 담은 저장 예외를 주입해 응답·캡처한 로그의 원인 체인 어디에도 표식이 없음
- [ ] 캡차 검증 호출: 헤더만 보내고 본문을 멈추는 로컬 서버에서 5초대 안에 fail-closed 반환
- [ ] 알림 수신자: 변형 행(`inquiry`/`read`/후행 공백) MANAGER 제외, 권한 없는 MANAGER 제외
- [ ] 형식 오류(제어문자·공백만·길이)는 캡차 검증기를 호출하지 않음(가짜 검증기 호출 횟수 0) — 캡차 토큰 누락·빈 값·2048자 초과도 같다
- [ ] 수신자 회원 행 잠금 중에도 POST가 락 해제 전에 303 반환·문의 저장(알림 락 대기 상한) — 각 대기는 1초 미만(약 0.8초)이지만 누적 3초를 넘는 조건(수신자 5명 이상의 회원 행을 서로 다른 트랜잭션이 순차 해제)에서 문장 전체 타임아웃으로 중단되고 앞서 삽입한 알림은 남지 않으며 문의는 저장됨, `max_statement_time` 제거 변이 실험에서 이 시험이 실패
- [ ] 작은 커넥션 풀(예: 최대 2~3, 획득 타임아웃 짧게)에서 풀 크기 이상 동시 제출이 모두 풀 획득 타임아웃 없이 303·저장됨(알림이 요청당 두 번째 커넥션을 쥐지 않음)

## 구현·검증 결과 (2026-10-12)

### 구현 파일
- 신규: `V47__create_inquiry.sql`·`V48__seed_inquiry_menu.sql`, `admin/inquiry/{domain,repository,service,controller,dto}`(`InquiryService`·`InquiryInputPolicy`·`InquiryNotifier`·`InquiryClosedException`), `publicweb/inquiry/*`(`PublicInquiryController`·`CaptchaVerifier`·`TurnstileCaptchaVerifier`·`InquiryTurnstileProperties`·`InquiryTurnstileConfigValidator`·`InquiryConfig`), 템플릿(`admin/inquiry/manage`·`public/inquiry/{form,closed}`), `css/public/inquiry.css`, 두 `CLAUDE.md`.
- 수정: `AdminFeature`(`INQUIRY`), `SecurityConfig`(공개 `/inquiry` 규칙), `application*.yml`(Turnstile·레이트리밋 2규칙), `docker-compose.prod.yml`·`scripts/_prod-env-guard.sh`·`.env.example`, `NotificationType`·`NotificationMessages`·`NotificationRecorder`·`NotificationRepository`·`MemberRepository`, `AdminActionTypes`·활동 로그 화면 라벨, FAQ 하단 링크, 문서(`CLAUDE.md` 지도·config·notification·permission, `docs/deployment.md`·`migration-guide.md`).

### 계획과 달라진 점
- 없음(계획서 v7 그대로). 시험 작성 중 시험 코드 결함 3건만 고쳤다(토큰 파라미터 중복, 감사 건수 계산 오류, 컬럼 길이 초과 입력).

### 검증
- `./gradlew test` **2218건 실패 0·오류 0**(UTC 재실행은 하지 않음). 신규 시험 53건: `InquiryInputPolicyTest`·`TurnstileCaptchaVerifierTest`(헤더만 보내고 본문을 멈추는 서버 5초대 종료)·`InquiryTurnstileConfigValidatorTest`(ApplicationContextRunner 배선 포함)·`PublicInquiryIntegrationTest`(12)·`PublicInquiryRateLimitIntegrationTest`·`PublicInquiryClosedIntegrationTest`·`PublicInquirySmallPoolIntegrationTest`·`InquiryNotificationIntegrationTest`·`InquiryAdminApiIntegrationTest`·`InquiryPermissionMatrixIntegrationTest`·두 템플릿 컨벤션 시험·`InquiryMigrationTest`, `SecurityConfigTest` 문의 절 8건. 기존 시험 중 `AdminFeature` 전 동작 키 목록 두 곳만 갱신.
- **변이 실험**: (1) `max_statement_time` 제거 → `lockWaits_areBoundedByStatementTimeout` 실패(4007ms), (2) 컨트롤러 `@Transactional` + `REQUIRES_NEW`로 구 설계 재현 → `PublicInquirySmallPoolIntegrationTest` 8건 중 6건 500으로 실패. 둘 다 원복.
- **실기(Playwright, bootRun 8081 + dev DB)**: 공개 폼 → Turnstile 테스트 위젯이 토큰 발급 → 제출 → 303 `/inquiry?sent`; DB에 원문 그대로 저장(이름 `<b>홍길동</b>`, 제목 `<img …onerror>`, 본문 `<script>`), admin에게 상수 문장 알림 1건·벨 배지 1; 문의함 목록·상세 모달에서 태그가 텍스트로만 표시(DOM에 img·script 0개); 상태 변경 → DB `IN_PROGRESS`·감사 `INQUIRY_STATUS` 성공(targetId, label NULL); 삭제 204·재삭제 404. 경계: 토큰 없는 POST/PUT 403, `/inquiry/1` 302 로그인, 비로그인 문의함 302·API 401. 스크린샷 `inquiry-screenshots/01~03`. 검증 뒤 dev DB의 문의·문의 알림 행은 삭제(V47·V48 스키마는 남음).

### 이슈·한계
- **dev 8080의 `cms-app-dev`(구 이미지)는 건드리지 않았다.** 같은 dev DB에 V47·V48이 적용돼 있다(구버전은 미래 버전을 무시하고 기동).
- Cloudflare 실서비스 키·prod 컨테이너 경로(`enabled=true` + 비-테스트 키 기동, 테스트 키 기동 실패)는 **실기 검증하지 못했다** — 기동 실패 로직은 단위·`ApplicationContextRunner` 시험으로, compose 매핑은 코드로만 확인했다. 운영자가 실제 키를 발급해 확인해야 한다.
- 6라운드 이후 코드 변경(구현)에 대한 계획 재리뷰는 없다 — 구현 리뷰는 `code-review-loop`가 맡는다.

### 후속
- 코드 리뷰 게이트(`code-review-loop`) → `commitPR` → 로드맵 `/updateRoadmap`("③ 후속 — 문의하기" 완료 반영).
- 운영 투입 전: Cloudflare 키 발급·개인정보처리방침 고지(Cloudflare 전송·보관)·보존 기간 정책.
