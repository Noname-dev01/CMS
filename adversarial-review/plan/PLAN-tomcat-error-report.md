# PLAN — 컨테이너 단계 오류 화면의 서버 버전 노출 차단 (이슈 #223)

- 상태: **완료 (2026-11-10 · #224 `da7149c`)** — v2, 적대적 리뷰 2라운드 ship
- 브랜치: `security/hide-tomcat-error-report` (master `bbdf014`에서 분기)
- 유형: security / fix — 스키마·인가 정책·API 변경 없음, `SecurityConfig` 무수정

## Context

이슈 #223: 퍼센트 인코딩이 잘못된 주소(`/main/%zz`, `/admin/%zz`, `/main/%00`, `/main/a%2fb`, `/main/..%2f..%2fadmin`, `/main/boards/%`)를 요청하면 Tomcat 기본 오류 화면이 나오고, 본문에 `Apache Tomcat/11.0.26`과 내부 메시지 `Invalid URI: [The hexadecimal encoding is invalid]`가 보인다. 재현 환경은 **dev 프로파일 bootRun**이었다.

### 정찰에서 확인한 사실 (추측 아님 — 근거 포함)

1. **이 요청들은 Spring에 오지 않는다.** Tomcat `CoyoteAdapter.postParseRequest`가 URI 디코딩 단계에서 `sendError(400, "coyoteAdapter.invalidURI…")`를 부르고(javap로 확인), 본문은 Host 파이프라인의 `ErrorReportValve`가 그린다. 필터·`CustomErrorController`·보안 헤더가 모두 적용되지 않는다.
2. **Boot 4.0.8은 이미 이 밸브를 숨기도록 구성한다 — 조건부로.** `TomcatWebServerFactoryCustomizer.customizeErrorReportValve`(javap)가 `spring.web.error.include-stacktrace == NEVER`일 때만 컨텍스트 커스터마이저로 `ErrorReportValve(showServerInfo=false, showReport=false)`를 Host 파이프라인에 추가한다. 그 밖의 값이면 아무것도 하지 않고, `StandardHost`가 기본 밸브(서버 정보·상세 보고 표시)를 넣는다.
3. **devtools가 그 조건을 깬다.** `spring-boot-autoconfigure-4.0.8.jar!/META-INF/spring-devtools.properties`에 `defaults.spring.web.error.include-stacktrace=always`가 있다. `build.gradle:65`의 `developmentOnly 'spring-boot-devtools'`라 bootRun에서만 켜진다.
4. **운영 이미지는 영향이 없다(현재 설정 기준).** `./gradlew bootJar` 산출물 `BOOT-INF/lib`에 devtools 0건, `application*.yml` 어디에도 `include-stacktrace` 설정 없음, `Dockerfile`은 `java -jar`로 bootJar를 실행한다.
5. **실측(임시 실서버 스파이크, 원시 소켓으로 요청 — 커밋하지 않음):**
   - 기본 설정(devtools 없음): Host 밸브 `ErrorReportValve showServerInfo=false showReport=false` 1개. `/main/%zz` 등 4건 모두 400, 본문은 `<h1>HTTP Status 400 – Bad Request</h1>`뿐(버전·메시지 없음).
   - `spring.web.error.include-stacktrace=always`(devtools 모사): 밸브 `showServerInfo=true showReport=true`, 본문에 `Message Invalid URI: [...]`·`<h3>Apache Tomcat/11.0.26</h3>` — **이슈 증상을 그대로 재현**.
   - `/main/;jsessionid=x`는 두 경우 모두 프로젝트 400 화면(Spring Security 방화벽 경로, 이슈 #123 수정분).

결론: 이슈 증상은 "운영에서 지금 새는" 것이 아니라 **"Boot의 보호가 `include-stacktrace` 한 설정값에 암묵적으로 묶여 있어, dev에서는 이미 풀려 있고 운영에서도 그 값 하나만 바뀌면 조용히 풀린다"**는 문제다. 이 의존을 끊는 것이 이번 작업의 목표다.

## 성공 기준 (검증 가능)

1. `include-stacktrace` 값과 무관하게(`never`·`always` 둘 다) 실서버에서 잘못된 인코딩 요청 6종의 400 응답 본문에 `Apache Tomcat`·`Tomcat/`·`Invalid URI`가 없다.
2. Host 파이프라인의 `ErrorReportValve`는 정확히 1개이고 `showServerInfo=false`·`showReport=false`다.
3. Spring까지 오는 기존 오류 화면(404·403·`;jsessionid` 400 프로젝트 화면)은 그대로다 — `DefaultDenyErrorDispatchIntegrationTest` 무수정 통과.
4. dev bootRun 실기에서 `/main/%zz`·`/admin/%zz`가 버전 없는 400을 돌려준다(이슈 재현 환경 그대로).

## 쟁점과 결정

### 쟁점 1 — 무엇으로 서버 정보를 숨길 것인가

| 선택지 | 내용 | 트레이드오프 |
|---|---|---|
| A. 설정 1줄 | `application.yml`에 `spring.web.error.include-stacktrace: never` 명시(devtools 기본값보다 우선) | 가장 작다. 그러나 보호가 여전히 "이 값이 never일 때만 Boot가 밸브를 만든다"는 Boot 내부 조건에 묶인다. 누가 디버깅용으로 `on_param`/`always`를 켜면(환경변수 한 줄) 운영에서 조용히 다시 샌다. 우리 오류 화면은 스택트레이스를 어차피 쓰지 않으므로(`CustomErrorController`는 상태 코드만) 이 값 자체로 얻는 것은 없다 |
| **B. 명시 커스터마이저(채택)** | `WebServerFactoryCustomizer<TomcatServletWebServerFactory>` 하나가 컨텍스트 커스터마이저로 Host에 **`AFTER_START` 생명주기 리스너**를 등록하고, 리스너가 Host 파이프라인의 **모든** `ErrorReportValve`(하위 클래스 포함)를 `showServerInfo=false`·`showReport=false`로 고정한다. 밸브를 추가하지 않는다 | 설정 클래스 1개(약 30줄). `include-stacktrace`·devtools·Boot 내부 조건·커스터마이저 실행 순서와 무관하게 보장된다(아래 구현 세부) |
| C. 문서만 | "운영 무관"으로 종결 + 기본값 고정 시험 | 코드 변경 0. 그러나 dev 노출이 남고(실기 검증·외부 시연 때 그대로 보인다), 설정 한 줄로 운영이 풀리는 의존이 그대로다 |

**결정: B.** 이유: 이슈의 실제 결함은 "보호가 무관한 설정값에 암묵적으로 묶임"이다. A는 그 묶음을 우리 쪽 설정으로 한 번 더 고정할 뿐 끊지 않는다. B는 서버 정보 숨김을 그 자체의 계약으로 만들고 비용은 클래스 1개다. `include-stacktrace`는 건드리지 않는다(Spring 오류 속성 노출과는 별개 관심사이고, 우리 화면은 그 속성을 쓰지 않는다).

구현 세부:
- 위치: `com.cms.config.TomcatErrorReportConfig`(`@Configuration` + `@Bean WebServerFactoryCustomizer<TomcatServletWebServerFactory>`). Boot 4 모듈 패키지 `org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory`(javadoc 확인).
- **적용 시점은 Host 시작 직후(`Lifecycle.AFTER_START_EVENT`)**: 컨텍스트 커스터마이저 안에서 `context.getParent()`(Host — 이 시점엔 아직 시작 전)에 리스너를 붙인다. Host가 시작되면 ① Boot가 (`never`일 때) 넣은 밸브, ② `StandardHost.startInternal`이 (같은 클래스명 밸브가 없을 때) 넣은 기본 밸브가 모두 들어와 있다. 리스너는 그 뒤에 파이프라인의 `instanceof ErrorReportValve` 전부를 고치므로 **커스터마이저 순서·밸브 출처·하위 클래스 여부와 무관**하다(v2 — v1의 "있으면 고치고 없으면 추가"는 Boot가 기존 밸브를 확인하지 않고 무조건 추가하므로 우리가 먼저 실행되면 밸브가 2개가 되는 순서 의존이 있었다).
- 밸브를 **추가하지 않는다** — 추가할 필요가 없다(Host는 `errorReportValveClass` 기본값으로 항상 하나를 넣는다). 그래서 밸브 수는 Boot·Tomcat이 정한 그대로이고(현재 구성 실측 1개), 우리 코드는 중복을 만들지 않는다.
- 플래그는 밸브가 보고서를 그릴 때마다 읽는 단순 필드라 시작 뒤 변경이 즉시 반영되고, 리스너는 Host 시작 1회만 실행된다(요청 처리 시작 전 — 커넥터는 Host 시작 뒤에 열린다).
- `ErrorReportValve`는 Tomcat 공개 API(`org.apache.catalina.valves`)라 새 의존성이 없다.
- **업그레이드 시 재검증 조건**: Tomcat이 Host 파이프라인 밖(엔진 등)에서 오류 본문을 그리는 경로를 새로 만들거나, Host가 시작 뒤에 밸브를 교체하면 이 보장이 깨진다 — 실서버 시험(F1·F3)이 잡는다. "자동 대응"을 주장하지 않는다.

### 쟁점 2 — 이 단계의 오류도 프로젝트 오류 화면·보안 헤더로 맞출 것인가 (이슈 제안 2)

| 선택지 | 내용 | 트레이드오프 |
|---|---|---|
| **A. 하지 않음(채택)** | 버전·메시지 없는 Tomcat 최소 본문(`<h1>HTTP Status 400 – Bad Request</h1>`)을 그대로 둔다 | 화면이 프로젝트 디자인과 다르다. 그러나 정상 사용자가 만들 수 없는 주소(브라우저·앱 링크는 이런 인코딩을 만들지 않는다)라 UX 영향이 사실상 없다 |
| B. `ErrorReportValve.setProperty("errorCode.400", 정적 파일)` | 400에 정적 HTML 파일 지정 | 파일 경로가 jar 밖 실제 파일이어야 하고(밸브는 클래스패스를 읽지 않는다) 컨테이너·로컬 경로가 갈린다. 다른 상태 코드(예: 커넥터가 거부하는 다른 경우)는 따로 지정해야 한다 |

**결정: A.** 이유: 이슈의 영향은 정보 노출(서버 종류·버전·내부 메시지)이고 B안의 비용(배포 경로 관리)이 이득보다 크다. **보안 헤더 부재**는 판단 결과 문제로 보지 않는다: 본문이 요청 내용을 전혀 반영하지 않는, 상태 코드와 요청 언어로만 정해지는 제한된 본문이라(쟁점 1 적용 후 `showReport=false`로 메시지도 빠짐) `X-Content-Type-Options`·`X-Frame-Options` 부재로 생기는 스니핑·클릭재킹 표면이 없다. 캐시 헤더 부재도 고정 오류 본문이라 무해하다. 이 판단은 PLAN과 `com.cms.config`의 `CLAUDE.md`에 기록해 후속 판단 근거로 남긴다.

### 쟁점 3 — 회귀 시험을 어떻게 고정할 것인가

- MockMvc는 커넥터 단계를 재현하지 못하므로 **실서버(`RANDOM_PORT`) + 원시 소켓**을 쓴다. `java.net.http.HttpClient`는 `URI.create("/main/%zz")`에서 클라이언트 쪽 예외를 던져 요청을 보낼 수 없다(스파이크에서 원시 소켓으로 우회).
- 시험 클래스 하나 `ContainerErrorReportIntegrationTest`(`com.cms.config`)를 두되 `@SpringBootTest(properties = "spring.web.error.include-stacktrace=always")`로 **devtools 상황을 모사**한다 — 수정 전 실패(빨강)가 확인되는 유일한 구성이다.
- 기본 구성(devtools 없음 = 운영)의 고정은 **기존 `DefaultDenyErrorDispatchIntegrationTest`에 시험 1건을 추가**해 캐시된 컨텍스트를 재사용한다(추가 컨텍스트 비용 없음). Boot 업그레이드로 기본 동작이 바뀌어도 이 시험이 잡는다(다만 B 적용 후에는 B가 보장하므로 이중 방어).
- 추가 비용: Spring 컨텍스트 1개(속성이 달라 캐시 공유 불가, 로컬 기준 수십 초).

## 놓치기 쉬운 입력·실패 모드 → 고정 시험

| # | 실패 모드 | 고정 시험 |
|---|---|---|
| F1 | devtools·환경변수로 `include-stacktrace`가 `never`가 아니면 Boot 보호가 꺼진다(이슈의 실제 원인) | `ContainerErrorReportIntegrationTest`(always 속성) — 6종 경로 본문에 `Apache Tomcat`·`Tomcat/`·`Invalid URI` 없음 |
| F2 | 밸브가 여러 개이거나(Boot 추가분 + Host 기본분, 커스터마이저 순서) 나중에 들어온 밸브가 보호에서 빠진다 | 두 구성 시험 모두 서버 시작 완료 후 Host 파이프라인의 **모든** `ErrorReportValve`가 두 플래그 false이고, 현재 구성에서 개수가 정확히 1개임을 단언(우리 코드가 중복을 만들지 않음의 관측) |
| F3 | 거부 지점이 다른 잘못된 주소(`%00` NUL, `%2f` 인코딩 슬래시, `..%2f` 경로 탈출, 끝의 단독 `%`)가 다른 분기로 가서 다른 밸브·본문을 탄다 | 6종 경로를 모두 시험 목록에 넣는다(이슈 표 그대로) |
| F4 | 수정이 Spring까지 오는 오류 화면(404·403·`;jsessionid` 400)을 바꾼다 | 기존 `DefaultDenyErrorDispatchIntegrationTest` 무수정 통과 + 새 시험에 `/main/;jsessionid=x`가 프로젝트 400 화면(`/css/error.css` 링크)임을 단언 |
| F5 | 시험이 엉뚱한 이유로 통과(연결 실패·빈 본문·200) 또는 엉뚱한 이유로 실패(밸브가 `request.getLocales()`로 제목을 번역 — 요청 언어에 따라 `HTTP Status 400` 문자열이 바뀜) | 원시 요청에 `Accept-Language: en`·`Connection: close`를 고정하고 소켓 연결·읽기 타임아웃(5초)을 둔다. 상태 줄 `400` + 본문 `HTTP Status 400` **양성** 단언 뒤 부정 단언. 각 시험은 `Environment`의 `spring.web.error.include-stacktrace`가 기대값(기본 구성: 미설정 = Boot 기본 `NEVER`, 모사 구성: `always`)인지 먼저 단언해 두 시험이 서로 다른 Boot 분기를 지난다는 근거를 남긴다 |

## 위험 변경

- 없음(인가·데이터 정합성·동시성·마이그레이션 무관). 서버 컨테이너 설정 변경이지만 Spring까지 오는 요청 경로는 바뀌지 않는다(F4로 고정).

## 작업 단계

1. (빨강) `ContainerErrorReportIntegrationTest` 작성 → 실행해 F1·F2 실패 확인.
2. `TomcatErrorReportConfig` 구현 → 초록 확인. `./gradlew compileJava` 중간 점검.
3. `DefaultDenyErrorDispatchIntegrationTest`에 기본 구성 시험 1건 추가.
4. 영향 범위 시험: `--tests 'com.cms.config.*' --tests '*ConventionTest'`.
5. 실기: dev bootRun(devtools 켜짐)에서 원시 요청(`curl --path-as-is`)으로 6종 경로 + 정상 404·로그인 화면 회귀 확인, 응답 저장.
6. 문서: `com.cms.config`의 `CLAUDE.md`(컨테이너 단계 오류 행), `docs/verification/deployment-edge.md` 체크리스트 12번째 항목(프록시 경유 시 잘못된 인코딩 요청 본문에 서버 버전 없음 + 프록시 자체 오류 화면의 버전 노출), `docs/troubleshooting.md`(devtools가 Boot의 밸브 숨김 조건을 끈다), 이 PLAN 결과 갱신, `plan/README.md` 인덱스.

## 리스크

- Host 시작 뒤 전수 적용이라 Boot·Tomcat이 시작 과정에서 넣는 밸브(하위 클래스 포함)는 덮인다. 시작 뒤 밸브 교체나 Host 밖 오류 보고 경로는 덮이지 않는다 — 업그레이드 시 재검증 조건(쟁점 1)이며 실서버 시험이 잡는다.
- 프록시를 두면 프록시 자체의 오류 화면이 버전을 노출할 수 있다 — 앱 범위 밖이라 `deployment-edge.md` 체크리스트로 넘긴다.

## 구현 결과

### 핵심 확정 사항
- 계획 v2 그대로 구현했다(계획과 달라진 결정 없음). Host `AFTER_START` 리스너가 모든 `ErrorReportValve`의 `showServerInfo`·`showReport`를 끄고, 밸브는 추가하지 않는다.
- 시험 보조 클래스 `RawHttpRequests`를 둔 것은 계획에 명시하지 않은 세부다 — 두 시험 클래스가 같은 원시 소켓 요청·단언·밸브 조회를 쓰므로 한 곳에 모았다(테스트 코드 전용).

### 구현 파일
- `src/main/java/com/cms/config/TomcatErrorReportConfig.java` (신규)
- `src/test/java/com/cms/config/RawHttpRequests.java` (신규 — 원시 HTTP/1.1 요청·`Accept-Language: en`·`Connection: close`·연결/읽기 타임아웃 5초, 잘못된 인코딩 6종 단언, Host 밸브 조회)
- `src/test/java/com/cms/config/ContainerErrorReportIntegrationTest.java` (신규 — `include-stacktrace=always` 모사, 3건)
- `src/test/java/com/cms/config/DefaultDenyErrorDispatchIntegrationTest.java` (기본 구성 시험 (f) 1건 추가)
- 문서: `src/main/java/com/cms/config/CLAUDE.md`(컨테이너 단계 거절 행), `docs/verification/deployment-edge.md`(12번째 항목), `docs/troubleshooting.md`(빌드/의존성), `adversarial-review/plan/README.md`(55번 행)

### 검증 결과
- **RED**: 구현 전 `ContainerErrorReportIntegrationTest` 3건 중 2건 실패 — 본문에 `Apache Tomcat/11.0.26`·`Invalid URI`(F1), 밸브 `showServerInfo=true`(F2). `;jsessionid` 회귀 시험은 통과(기존 동작).
- **GREEN**: 구현 후 3건 통과. (f)는 구현 전에도 통과하는 기본 구성 고정 시험이다(운영 무영향의 관측 — 계획대로).
- **영향 범위 시험**: `./gradlew test --tests 'com.cms.config.*' --tests '*ConventionTest'` 통과(`ContainerErrorReportIntegrationTest` 3/3, `DefaultDenyErrorDispatchIntegrationTest` 8/8 포함). 전체 `./gradlew test`는 PR 직전(code-review-loop 마무리)에 1회 돌린다.
- **실기(dev bootRun — 로그 `Devtools property defaults active!`, 이슈 재현 환경과 같음)**: `curl --path-as-is`로 6종 경로 모두 `400`, 본문 `<h1>HTTP Status 400 – Bad Request</h1>`만, `Tomcat/`·`Invalid URI` 0건. `Accept-Language: ko`도 같은 본문(번역 리소스 없음). 회귀: `/main/;jsessionid=x` 400 프로젝트 화면(`error.css`), `/main/nosuch/deep`·`/foo/unclassified` 로그인 302, `/admin/login`·`/main` 200. Playwright로 `/admin/%zz` 화면 확인(스크린샷 `.playwright-mcp/issue223-admin-zz-after.png`, git 무시 경로 — 콘솔 오류는 400 자체와 기존 favicon 404).

### 이슈
- 스파이크(임시 실서버 시험)로 원인을 확정한 뒤 삭제했다. Playwright MCP는 스크린샷을 저장소 밖(스크래치패드)에 저장하지 못해 무시 경로 `.playwright-mcp/`에 저장했다.

### 후속
- 프록시(ingress) 자체 오류 화면의 버전 노출 — `deployment-edge.md` 12번째 항목(실배포 인프라 범위).
- Tomcat·Boot 업그레이드 때: 시작 뒤 밸브 교체·Host 밖 오류 보고 경로가 생기면 이 보장이 깨진다(쟁점 1 재검증 조건) — 실서버 시험이 잡는다.

## 개정 이력

- v2 (2026-11-10): 1라운드 리뷰 반영. v2 변경: ① 적용 시점을 컨텍스트 커스터마이저 즉시 수정에서 Host `AFTER_START` 리스너의 전수 수정으로 옮기고 밸브 추가를 없앰 — Boot가 기존 밸브를 확인하지 않고 무조건 추가하므로 v1의 "순서 무관" 주장이 틀렸음(수용). ② 시험 요청에 `Accept-Language: en`·`Connection: close`·소켓 타임아웃 고정, 각 시험이 `include-stacktrace` 실효값을 먼저 단언(수용). ③ "하위 클래스 자동 대응" 주장 삭제, 업그레이드 재검증 조건으로 기록 — ①로 시작 과정의 하위 클래스·기본 밸브 추가분은 덮임(수용). ④ 본문 서술을 "고정 문자열" → "상태 코드·요청 언어로 정해지는 제한된 본문"으로 정정(수용). 기각 없음.
- v1 (2026-11-10): 최초 작성. 정찰로 원인이 devtools 기본값 `include-stacktrace=always`임을 실측 확인, 운영 이미지 무영향 확인.
