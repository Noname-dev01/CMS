# PLAN — 공개 파일 전송 동시 상한

> 상태: 완료 (2026-11-07 · PR #213 `3e22999`, v4, 적대적 리뷰 4라운드 ship)
> 위험 변경: 동시성(슬롯 획득·반환). 인가 정책·스키마·`SecurityConfig` 변경 없음.
> 출처: 로드맵 "후속 과제 — ② 공개 첨부 다운로드 완료 시 기록"의 남는 위험.

## 개정 이력

- v1: 최초 작성.
- v2 변경: R1-1 수용 — `afterCompletion` 호출은 획득 증거가 아니다(면제·비대상 요청도 `preHandle`이 `true`). 요청 속성의 반환 토큰으로 정확히 한 번 반환한다(D2·F1). R1-2 수용 — 거절 우선순위를 "레이트리밋 → IP 429 → 전체 503"으로 고정하고 성공 기준에 진입 버킷 조건을 달았다(D5). 시험에서 레이트리밋 켜짐을 명시한다.
- v3 변경: R2-1 수용 — 스레드 여력을 시험뿐 아니라 기동 조건으로 강제한다. `max-total * 2 <= server.tomcat.threads.max`(기본 200)를 어기면 기동 실패(D3·F5). 서버 시험은 실제 커넥터 값이 검증 값과 같은지 확인한다.
- v4 변경: R3-1 문제 수용 — 기존 서비스를 재사용한 새 전송 경로를 컨벤션 시험이 놓친다. 응답 스트림 쓰기 지점을 파일별 횟수까지 승인 목록과 맞추고 변이 검증을 명시한다(F2). 기각: 전송 코드를 공통 스트리머로 통합 — 첨부·본문 이미지의 3구간 실패 처리를 건드리는 무관한 리팩터링이고, 소스 검사로 같은 회귀를 잡는다.

## 문제

공개 파일 전송은 스트리밍(#63)과 OSIV 해제로 요청당 힙·DB 커넥션 점유가 사라졌다. 그러나 전송 중에는 Tomcat 스레드 하나를 계속 쓴다. 현재 `server.tomcat.threads`를 설정하지 않아 Boot 기본값 200을 쓴다(`application*.yml`에 설정 없음).

레이트리밋은 진입 빈도만 줄인다. `content-image`는 IP당 240/60초라서, 응답을 천천히 읽는 클라이언트 하나가 1분 안에 200개 전송을 동시에 열 수 있다. 스레드가 다 차면 공개 화면뿐 아니라 **관리자 화면과 `/actuator/health`도 응답하지 못한다.** 막으려는 피해는 이것이다.

## 목표와 비목표

**목표**

- 공개 파일 전송이 Tomcat 스레드 전체를 차지하지 못하게 해 관리자·공개 페이지 처리 여력을 남긴다.
- 한 IP가 전송 자리를 전부 차지하지 못하게 한다.

**비목표**

- 전송 한 건의 총 시간 제한(느린 전송을 끊는 기능). 슬롯은 묶일 수 있지만 피해 범위는 상한 안으로 제한된다.
- 공개 페이지(HTML) 요청, 디스크 IO 대역폭, Tomcat 연결 수(`max-connections`) 제한.
- 분산 공격 방어(프록시·WAF 몫). 다중 인스턴스 합산(기존 레이트리밋과 같은 단일 인스턴스 전제).

## 정찰 결과

| 사실 | 근거 |
|---|---|
| 본문을 보내는 공개 핸들러는 GET 4개다 | `PublicBoardController.attachment`, `PublicContentImageController.image`, `PublicBannerImageController.image`, `PublicPopupImageController.image` |
| HEAD는 전용 핸들러이고 본문을 읽지 않는다 | 같은 컨트롤러의 `*Head` |
| `publicweb`에서 `FileStorage`를 쓰는 클래스는 서비스 4개다 | `PublicBoardService`, `PublicContentImageService`, `PublicBannerService`, `PublicPopupService` |
| 핸들러 표지 기반 인터셉터 선례가 있다 | `@SiteScopedPublic` + `PublicSiteInterceptor`, 등록은 `PublicWebMvcConfig`, 서비스는 `ObjectProvider` 지연 조회 |
| 공개 핸들러는 동기만 허용된다 | `com.cms.publicweb.support`의 `CLAUDE.md` |
| 레이트리밋은 `SecurityConfig` 체인 안 `CsrfFilter` 다음에서 돌고, 인터셉터는 그 뒤에 돈다 | `RateLimitFilter` |
| 관리 편집기 이미지는 `/content-images/*`를 쓰며, 익명 소진으로 깨지던 문제를 #192가 로그인 면제로 풀었다 | `com.cms.config`의 `CLAUDE.md` |
| 429 화면은 있고 503 화면은 없다(`error.html`이 숫자만 표시) | `CustomErrorController` |

## 설계 결정

### D1. 적용 대상 — 본문을 보내는 GET 4개 전부

| 선택지 | 판단 |
|---|---|
| 첨부만 | 기각. 스레드 고갈은 `content-images`로 더 쉽게 일어난다(240/60초, 최대 5MB). |
| **GET 4개 전부** | **채택.** 네 경로가 같은 스레드 풀을 쓴다. |

HEAD는 파일 본문을 읽지 않아 제외한다.

### D2. 적용 위치 — 핸들러 표지 + 인터셉터

| 선택지 | 판단 |
|---|---|
| `RateLimitFilter`식 서블릿 필터 + 경로 패턴 | 기각. 경로 패턴과 실제 매핑이 어긋날 수 있고, `SecurityConfig`를 고쳐야 슬라이스 시험까지 파급된다. |
| 컨트롤러 4곳에서 직접 획득·반환 | 기각. `try/finally`가 4벌이 되고 조기 반환 경로마다 누수 위험이 있다. |
| **`@PublicFileTransfer` 표지 + `HandlerInterceptor`** | **채택.** `@SiteScopedPublic` 선례와 같고, `afterCompletion`이 정상·예외 모두에서 불려 반환이 한 곳이다. |

- 인터셉터는 `PublicSiteInterceptor` **뒤**에 등록한다. 없는 사이트의 요청은 슬롯을 쓰지 않는다.
- 슬롯은 공개 판정 **전에** 잡는다. 포화 상태에서는 파일 유무와 무관하게 같은 429/503이므로 존재 여부가 드러나지 않는다. 판정의 DB 조회도 상한 안에 들어온다.
- **반환은 토큰으로만 한다.** `afterCompletion`은 `preHandle`이 `true`를 반환한 모든 요청에 불린다 — 표지 없는 요청, HEAD, 로그인 면제 요청도 포함된다. 그래서 호출 자체를 획득 증거로 쓰지 않는다. 획득에 성공하면 요청 속성에 획득 당시 IP를 담은 반환 토큰을 저장하고, `afterCompletion`은 토큰이 있을 때만 제거하면서 그 IP로 한 번 반환한다. 표지·인증 상태·IP를 다시 판정하지 않는다.
- 획득 거절로 `preHandle`이 `false`를 반환하면 토큰이 없고 `afterCompletion`도 불리지 않는다.

### D3. 상한 — 전체 + IP별

| 선택지 | 판단 |
|---|---|
| 전체 상한만 | 기각. 관리자 화면은 지켜지지만 IP 하나가 다른 방문자의 이미지·첨부를 모두 막는다. 진입 한도 240/60초로 몇 초면 채운다. |
| **전체 + IP별** | **채택.** 구현은 카운터 맵 하나만 늘고, 공격자가 자리를 다 채우려면 IP가 여러 개 필요하다. |

- 수치: `cms.public-transfer.max-total: 100`, `max-per-ip: 20`.
  - 100: Tomcat 스레드 200의 절반을 페이지·관리자·health 몫으로 남긴다.
  - 20: 브라우저의 HTTP/1.1 호스트당 동시 연결 6개를 기준으로 같은 IP 뒤의 사용자 몇 명을 수용한다.
- 값은 `application.yml`에만 둔다(레이트리밋 규칙과 같은 방식). 환경변수 경로는 요구가 없어 만들지 않는다.
- 기동 시 `max-per-ip <= max-total`과 `max-total * 2 <= server.tomcat.threads.max`(`@Value` 기본 200)를 확인하고 어기면 기동 실패다. 둘째 조건이 "스레드 절반을 남긴다"를 설정 변경 뒤에도 지킨다.
- IP 키는 `request.getRemoteAddr()`로 고정한다(`X-Forwarded-For` 금지, 기존 규약).

### D4. 슬롯 구현 — 잠금 하나의 카운터

```java
// 전체 수와 IP별 맵을 함께 바꾸므로 한 잠금으로 묶는다. 임계 구역은 맵 연산뿐이다.
synchronized Result tryAcquire(String ip)
synchronized void release(String ip)   // 0이 되면 키 제거
```

- `Semaphore` + `ConcurrentHashMap` 조합은 두 자원의 원자성을 따로 맞춰야 해 기각한다.
- 맵 크기는 전체 상한(100)을 넘지 않는다. 키는 슬롯이 있을 때만 있고 0이 되면 지운다.
- 가상 스레드는 켜져 있지 않고, 임계 구역에서 IO를 하지 않는다.

### D5. 초과 응답

| 경우 | 응답 | 이유 |
|---|---|---|
| IP별 초과 | **429** + `Retry-After: 5`, 기존 `error/429` | 그 클라이언트의 사용량 문제이며 화면이 이미 있다. |
| 전체 초과 | **503** + `Retry-After: 5`, 신규 `error/503` | 서버 여력 문제다. `ServiceBusyException` 503 + `Retry-After` 선례와 같다. |

- **거절 우선순위는 기존 레이트리밋 429 → IP별 상한 429 → 전체 상한 503이다.** 레이트리밋 필터가 인터셉터보다 먼저 돌고, 리미터는 IP별을 먼저 검사한다. 두 상한이 함께 차 있으면 429다.
- 첨부 경로는 진입 버킷이 20/60초라 레이트리밋이 먼저 걸리는 일이 많다. 이 동시 상한은 주로 버킷이 큰 이미지 경로와 여러 IP에서 작동한다.
- `sendError`로 끝내므로 ERROR 재디스패치가 일어나고 이 표지가 없는 `CustomErrorController`에서 그린다. 슬롯은 없다.
- 이미지 실패는 기존 대체(`board-thumb-fallback.js`, `board-content-image-fallback.js`, 메인의 CSS 배경)가 그대로 처리한다. 배너·팝업 `<img>`는 429 때와 같이 깨질 수 있음을 수용한다.
- 관측: 거절이 있으면 분당 최대 1회 WARN으로 거절 건수와 현재 사용 수만 남긴다. IP는 남기지 않는다. 시각은 `System.nanoTime()`을 쓴다(저장·조회 시각이 아니라 `ClockUsageConventionTest` 대상이 아니다).

### D6. 로그인 세션 면제

- 로그인한(익명이 아닌) 요청은 슬롯을 쓰지 않는다. 판정은 `RateLimitFilter.isAuthenticatedUser`와 같다.
- 이유: 관리 편집기 이미지가 익명 포화로 다시 깨지면 #192가 푼 문제가 되살아난다. 로그인 사용자는 공격 대상 위협 모델 밖이다.
- 네 경로 공통 규칙 하나로 둔다. 규칙별 설정은 만들지 않는다.

### D7. 설정 배선

`PublicTransferProperties`(`@ConfigurationProperties`, `@Validated`, `@Positive`)와 리미터 빈을 `PublicTransferConfig`에 둔다. `PublicWebMvcConfig`는 `ObjectProvider`로 지연 조회해 `@WebMvcTest` 슬라이스 컨텍스트를 깨지 않는다.

## 수정 파일

| 파일 | 변경 |
|---|---|
| `publicweb/support/PublicFileTransfer.java` | 신규 표지 |
| `publicweb/support/PublicTransferLimiter.java` | 신규 카운터 |
| `publicweb/support/PublicTransferInterceptor.java` | 신규 인터셉터 |
| `publicweb/support/PublicTransferProperties.java`, `PublicTransferConfig.java` | 신규 설정 |
| `publicweb/support/PublicWebMvcConfig.java` | 인터셉터 등록(사이트 인터셉터 뒤) |
| GET 핸들러 4개 | 표지 추가 |
| `error/CustomErrorController.java`, `templates/error/503.html` | 503 화면 |
| `application.yml` | `cms.public-transfer` |
| `docs/deployment.md`, `docs/verification/deployment-edge.md`, `publicweb/support/CLAUDE.md`, 로드맵 | 문서 |

## 작업 단계

1. `PublicTransferLimiter` 단위 시험 → 구현.
2. 표지·인터셉터·설정 + 실서버 시험.
3. 핸들러 4개 표지 + 컨벤션 시험.
4. 503 화면과 응답 시험.
5. 실기 검증: 느린 클라이언트로 상한을 채운 뒤 공개 페이지·관리자 화면·health 응답 확인, 해제 후 회복 확인, 첨부 503 화면 스크린샷.
6. 문서.

## 성공 기준

아래 기준은 진입 버킷(레이트리밋)에 여유가 있는 요청을 전제로 한다.

- [ ] 전체 상한이 찬 상태에서 자기 IP 슬롯에 여유가 있는 익명 GET 4개 경로가 모두 503 + `Retry-After`다.
- [ ] IP별 상한이 찬 상태에서 그 IP는 429 + `Retry-After`이고 다른 IP는 200이다.
- [ ] 두 상한이 함께 차 있으면 429다. 레이트리밋이 먼저 거절하면 슬롯 사용 수가 변하지 않는다.
- [ ] 포화 상태에서도 HEAD, 공개 페이지, 로그인 세션의 이미지, 관리자 화면, `/actuator/health`가 정상이다.
- [ ] 전송 완료·실패·클라이언트 중단·404·301 뒤 사용 수가 0으로 돌아온다.
- [ ] 익명 전송이 진행 중일 때 같은 IP의 HEAD·HTML·로그인 이미지 요청이 끝나도 사용 수가 변하지 않는다.
- [ ] `max-total`이 스레드 수의 절반을 넘으면 기동이 실패하고, 실제 Tomcat의 `maxThreads`가 검증에 쓴 값과 같다.

## 실패 모드와 고정 시험

| # | 실패 모드 | 고정 시험 |
|---|---|---|
| F1 | 슬롯 누수·오반환 — 전송 중 예외, 클라이언트 중단, 404, 301에서 반환 누락, 또는 슬롯을 잡지 않은 요청(HEAD·HTML·로그인 면제)의 반환으로 상한이 줄거나 무력화됨 | `PublicTransferLimitServerTest`(실제 Tomcat, `cms.rate-limit.enabled=true`로 레이트리밋 순서까지 확인): 경우마다 `limiter.inUse()`가 0으로 돌아옴, 전송을 붙잡은 채 다른 요청을 끝내도 사용 수 불변, `PublicTransferLimiterTest`: 경계·우선순위·키 제거 |
| F2 | 새 전송 경로가 표지를 빠뜨림(기존 서비스 재사용 포함) | `PublicTransferConventionTest`: ① 표지 핸들러가 승인된 GET 4개와 일치 ② `src/main/java/com/cms/publicweb`에서 `getOutputStream(`·`PublicImageStreamer.stream(` 호출이 파일별 횟수까지 승인 목록과 일치(`PublicBoardController`·`PublicContentImageController`의 `getOutputStream` 각 1회, `PublicBannerImageController`·`PublicPopupImageController`의 `stream` 각 1회, 정의 파일 `PublicImageStreamer` 제외 규칙 명시) ③ `FileStorage`를 쓰는 클래스가 승인된 4개와 일치. 변이 검증: 배너 컨트롤러에 `PublicImageStreamer.stream`을 호출하는 표지 없는 GET을 추가하면 실패 |
| F3 | 포화가 관리자에게 번짐 — 로그인 세션 이미지 거부 | `PublicTransferLimitIntegrationTest`: 포화 + 로그인 세션 이미지 통과, 공개 목록·관리자 화면 정상 |
| F4 | `X-Forwarded-For`로 IP별 상한 우회 | 같은 시험: 위조 헤더를 바꿔도 같은 IP로 집계 |
| F5 | 설정 실수 — `max-per-ip > max-total`, 상한이 스레드 풀의 절반 초과 | `PublicTransferConfigTest`(`ApplicationContextRunner`): 경계값 통과, 초과·역전은 기동 실패, 서버 시험: 실제 커넥터 `maxThreads`가 검증 값과 같음 |

## 리스크와 수용 한계

- **느린 클라이언트가 슬롯을 오래 쥔다.** 총 전송 시간 제한이 없어 IP 5개면 상한 100을 오래 채울 수 있다. 이때 공개 파일은 503이지만 나머지 서비스는 산다. 이것이 이번 범위의 보장이다.
- **프록시 뒤 IP 뭉침.** 리버스 프록시를 도입하고 `forward-headers-strategy`를 안 맞추면 모든 요청이 한 IP로 보여 사실상 전체 상한이 20이 된다. `deployment-edge.md` 체크리스트에 추가한다.
- **HTTP/2 프록시.** 프록시 뒤에서는 브라우저 6연결 가정이 깨져 갤러리 썸네일이 동시에 몰리면 20을 넘을 수 있다. ingress 확정 시 재평가한다.
- **마지막 버퍼.** 핸들러가 반환한 뒤 Tomcat이 마지막 출력 버퍼(최대 8KB)를 비우는 동안은 슬롯 없이 스레드가 쓰일 수 있다. 소켓 버퍼에 대부분 흡수돼 수용한다.
- **단일 인스턴스 전제.** 인스턴스마다 따로 센다.
- **거절도 레이트리밋 토큰을 쓴다.** 레이트리밋을 먼저 통과하기 때문이다.

## 구현·검증 결과 (2026-11-07)

### Context

로드맵 "후속 과제 — ② 공개 첨부 다운로드 완료 시 기록"의 남는 위험(동시 연결·스레드 점유 상한 없음)을 해소했다. `/suggestRoadmap`에서 선택.

### 핵심 확정 사항

계획 D1~D7 그대로다. 구현 중 달라진 점은 세 가지다.

- **503 문구를 중립으로 바꿨다.** `CustomErrorController`는 컨테이너의 503도 받으므로 "파일을 보낼 수 없습니다" 대신 "지금은 처리할 수 없습니다"를 쓴다.
- **기존 시험 1건을 고쳤다.** `CustomErrorControllerTest`가 503을 폴백 뷰로 고정하고 있어 목록에서 빼고 전용 화면 시험을 더했다. 503 전용 화면은 계획대로의 의도된 변경이다.
- 계획의 "수정 파일" 표에 있던 로드맵은 여기서 고치지 않는다. 머지 뒤 `/updateRoadmap`이 사실확인과 함께 반영한다.

### 구현 파일

- 신규: `publicweb/support/PublicFileTransfer`·`PublicTransferLimiter`·`PublicTransferInterceptor`·`PublicTransferProperties`·`PublicTransferConfig`, `templates/error/503.html`
- 변경: `PublicWebMvcConfig`, GET 핸들러 4개, `CustomErrorController`, `application.yml`
- 문서: `publicweb/support/CLAUDE.md`, `config/CLAUDE.md`, 루트 `CLAUDE.md`, `docs/deployment.md`, `docs/verification/deployment-edge.md`, `plan/README.md`

### 검증 결과

**시험**

- 새 시험 43건: `PublicTransferLimiterTest` 6, `PublicTransferConfigTest` 6, `PublicTransferLimitIntegrationTest` 18, `PublicTransferLimitServerTest` 10, `PublicTransferConventionTest` 3.
- 영향 범위 통과: `com.cms.publicweb.*`, `com.cms.error.*`, `*ConventionTest` 합계 395건.
- 전체 `./gradlew cleanTest test`: 3,084건 실패 0, 건너뜀 12(이번 변경과의 관련은 확인하지 않았다).
- 코드 리뷰: 기본 리뷰 1라운드, 동시성 초점 리뷰 1라운드 모두 지적 0건.

**변이 실험 11건 — 모두 시험이 실패함을 확인**(뒤의 4건은 PR을 만든 뒤에 돌렸다. 기동 검증은 컴파일 실패만 보고 넘어갔고 503 분기는 코드를 먼저 고쳐 동작 실패를 보지 못했기 때문이다)

| 변이 | 잡은 시험 |
|---|---|
| 토큰 무시(모든 요청이 반환) | 서버·통합 시험 다수, 사용 수가 음수까지 떨어짐 |
| 반환 누락 | 서버·통합 시험 다수 |
| 로그인 면제 제거 | `authenticatedImageBypassesLimit` |
| 인터셉터 순서 뒤집기 | `unknownSiteIsResolvedBeforeSlot` |
| IP별·전체 우선순위 뒤집기 | `ipLimitTakesPrecedenceOverTotalLimit`, `bothLimitsAnswerTooManyRequests` |
| 표지 없는 전송 핸들러 추가 | `bodyWritesMatchApprovedCounts` |
| 한 경로 표지 제거 | `markedHandlersAreApprovedGets`, 해당 경로 포화 시험 |
| IP별 상한 > 전체 상한 검증 제거 | `perIpOverTotalFails` |
| 스레드 절반 검증 제거 | `PublicTransferConfigTest` 3건 |
| 절반 경계 완화(`* 2` 제거) | `PublicTransferConfigTest` 3건 |
| 503 분기 제거 | `serviceUnavailable_dedicatedViewWithoutInternalMessage` |

**실기(`bootRun` + dev DB + 실제 느린 수신자 + Playwright, 기본 설정 100·20)**

수신 버퍼를 줄이고 응답을 읽지 않는 TCP 연결을 루프백 IP별로 열어 100MB 파일 전송을 붙잡았다.

| 상황 | 결과 |
|---|---|
| 상한이 비어 있음 | 이미지·첨부 GET 200, 100MB 전량 수신, HEAD 200 |
| 127.0.0.2가 20개 보유 | 그 IP 429 + `Retry-After: 5`, `X-Forwarded-For` 위조도 429, 다른 IP는 200 전량 수신, 같은 IP HEAD 200 |
| 5개 IP가 100개 보유 | 새 IP의 네 경로 모두 503 + `Retry-After: 5`, 가득 찬 IP는 429 |
| 같은 포화 상태의 나머지 서비스 | health·공개 메인·목록·상세·로그인 화면 200(6~120ms), HEAD 200, 브라우저 503 화면 정상 |
| 같은 포화 상태의 로그인 세션 | 로그인 성공, 대시보드 200, 본문 이미지 200 |
| 느린 연결 해제 뒤 | 즉시 회복, 보유했던 IP 모두 200 전량 수신 |
| 거절 로그 | WARN 1회(분당 제한) |

시험 데이터(게시판 38·글 786·첨부 2·이미지 114)와 파일은 지웠다. dev DB의 `visit_log`에는 실기 로그인 1건이 남았을 수 있다.

**확인하지 못한 것**

- prod 컨테이너 경로와 실제 프록시 경로. 프록시 관련 항목은 `deployment-edge.md`에 추가했다.
- 상한이 없을 때 실제로 스레드 200개가 차서 관리자 화면이 멈추는 대조 측정은 하지 않았다. 문제 서술은 Tomcat 스레드 모델에 근거한 것이고, 이번에 확인한 것은 상한이 있을 때 나머지 서비스가 산다는 점이다.
- 마지막 출력 버퍼 구간의 스레드 점유는 측정하지 않았다.

### 이슈

- Docker Desktop이 꺼져 DB 시험이 `initializationError`로 실패했다(코드 문제 아님, 켠 뒤 해소).
- 첨부 경로는 진입 버킷이 20/60초라 IP별 동시 상한보다 레이트리밋이 먼저 걸리는 일이 많다. 실기의 슬롯 채우기는 그래서 본문 이미지 경로로 했다.

### 후속

- 전송 한 건의 총 시간 제한: 실제 공격 정황이나 503 지속이 관측되면 재평가한다.
- 실배포 시 `deployment-edge.md` 11번째 항목 확인.
