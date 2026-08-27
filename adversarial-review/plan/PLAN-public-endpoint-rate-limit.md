# PLAN: 무인증 공개 엔드포인트 레이트리밋 도입

> v1 — 최초 작성 (2026-08-12). `/plan-review-loop` 대상.
> v2 변경 (2026-08-12, codex 1라운드 반영): HEAD 요청 우회 / 토큰 버킷 표기 정정 / 버킷 키에 규칙 식별자 부재 / `maxKeys` 정리의 CPU DoS / 필터 이중 등록 위험 / `Clock`→`Ticker` 교체 / 프록시 안전 문구 정정 / 설정 검증 부재 / JSON 필드명 오기(`errorCode`→`code`).
> v3 변경 (2026-08-12, codex 2라운드 반영): v2의 `computeIfAbsent` admission 설계 결함(치명적) → `AtomicInteger` CAS 슬롯 예약으로 재설계 / 버킷 내부 동시 소비 경쟁 → `synchronized tryConsume` / sweep 제거 조건 불명확 / `RateLimitRule`에 `HttpMethod` 필드 부재 / `RateLimitProperties` Bean 미등록 / 오버플로 방지 상한값 부재 / 폴백 샤딩 도입(사용자 확정) / 버킷 키 구조화(record) / 통합 테스트 방식 고정.
> v4 변경 (2026-08-12, codex 3라운드 반영 — 방향 전환, 사용자 확정): v3의 자체 구현 admission–sweep 설계에 ship blocker 4건(retired 상태 부재로 sweep가 활성 버킷 제거 가능, `tokens==capacity`가 유휴 판정 아님, admission 실패마다 sweep해도 CPU 증폭 벡터 잔존, Bean 배선 미완결) 추가 발견 → **3라운드 연속 자체 구현 동시성 결함이 반복된 것을 근거로 "신규 의존성 0건" 원칙을 사용자가 재검토·번복**, Caffeine(`com.github.ben-manes.caffeine:caffeine`) 도입으로 전환. 토큰 버킷 알고리즘 자체(`Bucket.tryConsume`, `synchronized`)와 `Ticker`는 자체 구현으로 유지.
> **v5 변경 (2026-08-12, codex 4라운드 반영 — 근본 트레이드오프 확정, 사용자 결정):**
> - **수용(치명적)**: Caffeine은 순수 LRU가 아니라 W-TinyLFU라서 "상한 초과 시에도 신규 IP는 항상 정상 버킷을 받는다"는 v4의 핵심 전제가 성립하지 않는다 — 신규 엔트리가 admission window에서 거부되거나 evict될 수 있고, 이미 참조 중이던 스레드가 evict된 버킷 객체를 계속 소비할 수 있어 v3의 retired 문제가 사실상 재현된다. **근본 원인 확인**: 외부 원자적 저장소(Redis 등) 없이는 "메모리 상한 + 개별 IP의 완벽한 정확성"이 Caffeine이든 자체 구현이든 동시 달성 불가능하다. → **사용자 확정(2026-08-12)**: 이 계획은 처음부터 "무제한 요청을 값싸게 차단하는 최소 방어"를 목표로 명시해왔다(정확한 유량 계약을 제공하는 API 게이트웨이가 아니다). **Caffeine의 fail-open 특성(캐시 포화 시 개별 IP의 정확한 누적치가 보장되지 않을 수 있음)을 운영 위험으로 명시 수용**하고, overflow 안전망 재도입(v3 스타일)이나 Redis 도입은 하지 않는다. "무차별 대입 완전 차단" 등 과장된 보장 문구는 계획·문서 전체에서 삭제하고 "값싸게 차단하는 최소 방어"로 통일(쟁점 3 재작성).
> - **수용(치명적)**: 전역 `expireAfterAccess(10분)`가 `reset-request`/`reset-confirm`(1시간 윈도우) 규칙을 10분마다 강제 리셋시켜 정책을 직접 우회하는 버그(예: 5회 소진 후 10분 대기하면 다시 5회 버스트 획득 — 원래는 1시간 대기해야 함) → **시간 기반 만료를 아예 두지 않고 `maximumSize`(크기 상한)만 사용**한다. fail-open을 이미 운영 위험으로 수용했으므로 별도 TTL로 메모리를 더 줄일 필요가 없다(쟁점 3 보강).
> - **수용(높음)**: `MOCK` webEnvironment + `MockMvc`로는 실제 서블릿 컨테이너의 필터 이중 등록·`sendError(429)`의 실제 ERROR 재디스패치·`CustomErrorController` 렌더링을 검증할 수 없다는 지적(`SecurityConfigTest.java:310` 실측 확인 — "MockMvc는 컨테이너 ERROR 디스패치를 수행하지 않으므로... Playwright로 검증한다"는 주석이 이미 존재, 기존 프로젝트 관례와 완전히 일치) → 테스트 전략을 분리한다: **MockMvc는 필터 매칭·JSON 응답·`SecurityFilterChain` 내부 순서까지만** 검증하고, **실제 429 HTML 렌더링·컨테이너 이중 등록 확인은 Playwright(8단계 워크플로우의 실기 검증 단계)로 이관**한다(쟁점 10 보강).
> - **수용(중간)**: "Caffeine이 요청 스레드가 아니라 내부 비동기 유지보수 큐에서 정리한다"는 v4 문구가 과도하게 단정적("항상 요청 경로 밖"이라는 보장은 Caffeine 공식 설계 문서상 없음 — executor 거부·caller-runs 상황에서는 요청 스레드가 유지보수를 수행할 수 있다) → "amortized 저비용 유지보수이며, 항상 요청 경로 밖이라는 보장은 아니다"로 정정. 다만 자체 `sweepBatchSize` 순회가 사라져 요청당 `O(batch)` CPU 증폭 벡터 자체가 없어진다는 결론은 유지(쟁점 3 문구 정정).
> - **수용(중간)**: `RateLimitConfigValidator`의 `methods` 검증이 허용 값 목록만 확인해 **빈 집합(`methods=[]`)이 조용히 통과하면서 그 규칙이 완전히 무력화**되는 fail-open 오설정 문제 → `methods`에 `@NotEmpty` 추가, `id`·`pattern`도 `@NotBlank`로 고정(쟁점 11 보강).
> - **수용(낮음)**: "`127.0.0.1:8080` 바인딩 = `getRemoteAddr()`가 실 클라이언트 IP" 설명이 부정확(현재는 외부 접근이 아예 차단된 로컬 검증 전용 구성이라 "실 클라이언트 IP"라는 표현 자체가 성립하지 않는다. nginx 도입 후 forward-header 처리가 없으면 peer는 nginx가 된다는 점도 명확히 해야 함) → "현재 외부 서비스 미개방, 로컬 peer 주소"로 표현 정정. 결정 자체(`getRemoteAddr()`만 신뢰, nginx 신뢰 체인은 인프라 작업으로 이월)는 안전하다는 codex 판단 유지(쟁점 1 보강).
> - v4의 Bean 배선(전체 명시)·HttpMethod 모델링·버킷 키 구조화·설정 검증 상한값·오버플로 방지 산술은 codex 4라운드에서 "대체로 완결됐다"고 확인되어 그대로 유지.
> - 상세 리뷰 원문: 대화 기록 참조(4라운드, codex CLI 자체 리뷰, 판정 no-ship → 위 반영 완료로 5라운드 진행).
> **v6 변경 (2026-08-12, codex 5라운드 반영 — 문구 정정·설정 일관성·문서 내 모순 해소, 새 근본 트레이드오프 없음):**
> - **수용(치명적, 작성자 계산 오류)**: v5 완료 기준의 "5회 소진 후 15분 뒤에도 429"는 틀렸다. `reset-request`(5회/3600초)의 리필 속도는 `capacity/refillPeriod = 5/3600`(토큰/초)이므로 1토큰이 채워지는 데 `3600/5 = 720초(12분)`가 걸린다. 15분(900초) 경과 시 `900/720 ≈ 1.25`토큰이 리필되어 **다음 요청은 실제로 200이어야 한다** — "1시간 정책이니 짧은 시간 내엔 리필 안 될 것"이라는 안일한 가정으로 lazy refill의 연속 리필 특성(계획 곳곳에서 강조해온 바로 그 특성)을 완료 기준에서 스스로 무시한 오류. → 완료 기준을 "소진 후 10분(600초)에는 여전히 429, 12분(720초) 경과 후 정확히 1회만 허용되고 그 직후 요청은 다시 429"로 정정하고, **실제 12분 대기보다 `FakeTicker` 기반 단위 테스트를 주 검증 수단으로 삼는다**(실기 검증에서는 시간이 오래 걸리는 이 케이스를 저비용 대안으로 대체).
> - **수용(높음)**: `expireAfterAccess`를 완전히 제거하면(v5) 메모리 무한 증가는 막히지만, **한 번 `maximumSize`에 도달한 뒤에는 트래픽이 뜸해져도 캐시가 계속 포화 상태로 남는다**(Caffeine은 신규 엔트리가 들어올 때만 기존 것을 밀어내며, 유휴 엔트리를 스스로 청소하지 않는다) — 즉 fail-open 위험이 "공격이 진행 중일 때"만이 아니라 "공격이 끝난 뒤의 정상화 과정"까지 이어진다. → **버킷마다 자기 규칙의 `refillPeriod`를 만료 시간으로 쓰는 Caffeine `Expiry` 커스텀 구현을 도입**한다(쟁점 3 재보강). "마지막 접근 후 그 규칙의 전체 refill 기간만큼 유휴였던 버킷은 이미 완전히 리필된 상태였을 것이므로, 그 시점에 지워도 실제로 뺏기는 토큰이 없다"는 논리로 v4의 버그(고정 TTL이 정책을 우회)를 재발시키지 않으면서도 무기한 포화를 막는다. 이 설계를 위해 `Bucket`이 자기 `refillPeriodNanos`를 알아야 하므로 생성자를 `Bucket(int capacity, long refillPeriodNanos)`로 고정한다(codex 지적 5와 자연스럽게 함께 해소).
> - **수용(높음)**: `enabled=true`인데 `rules`가 비어 있으면 경고만 남기고 기동을 허용하는 v5의 결정이, 빈 `methods`(규칙 하나만 무력화)는 기동 실패시키면서 **모든 방어를 무력화하는 빈 `rules`는 허용**하는 모순이었다 → `enabled=true && rules.isEmpty()`도 기동 실패로 통일(쟁점 11 보강). 명시적 비활성화 스위치(`enabled=false`)가 이미 있으므로 일관성 있음.
> - **수용(중간)**: fail-open 문구가 여전히 "전체적인 요청량 억제 효과는 유지된다"처럼 근거 없이 단정적이었다 — Caffeine의 W-TinyLFU가 신규 후보를 거부하면 다음 요청에서 또 full bucket이 생성되므로 이 억제 효과를 일반적으로 보장할 수 없다. → "포화 시 일부 요청은 계속 제한될 수 있으나, 전체 또는 개별 IP 단위의 억제율을 보장하지 않는다"로 정정. "공격 진행 중"뿐 아니라 "포화 이후 정상화 과정"도 위험 기간에 포함. 한도 표의 "토큰 무차별 대입 차단"을 "토큰 추측 시도율 억제"로 낮춤. "Redis가 유일한 완전한 해법"이라는 표현도 "메모리 상한·무제한 신규 키 수용·IP별 정확성을 동시에 요구하는 경우에만 외부 저장소가 필요하다(로컬 fail-closed 방식도 정확한 상한은 지키지만 신규 사용자를 거절하는 가용성 비용이 있다)"로 조건부화(쟁점 3 문구 재정정).
> - **수용(중간)**: 계획 안에서 `bucket.tryConsume(rule, ticker)`(핵심 설계 결정 코드)와 `tryConsume(Ticker)`(변경 파일 설명) 두 시그니처가 서로 달라 `Bucket`이 리필 주기를 어디서 얻는지 불명확했다 → 위 두 번째 항목(`Expiry` 도입)에서 `Bucket(capacity, refillPeriodNanos)` + `tryConsume(Ticker)`로 고정하며 함께 해소.
> - 상세 리뷰 원문: 대화 기록 참조(5라운드, codex CLI 자체 리뷰, 판정 no-ship → 위 반영 완료로 6라운드 진행).
> **v7 변경 (2026-08-12, codex 6라운드 반영 — 국소적 버그·문서 모순 정정, 근본 트레이드오프 재론 없음):** `Expiry` API 계약 자체는 "정상"으로 확인됨(Spring Boot 3.5.16 BOM이 Caffeine 3.2.4 관리 확인).
> - **수용(치명적, 실제 버그)**: v2~v6에 걸쳐 반복 서술해온 "경과시간이 리필 주기 이상이면 `tokens = capacity`" + "`lastRefillNanos`를 실제로 반영된 만큼만 전진" 두 문구를 그대로 구현하면, 리필 주기를 초과하는 유휴 시간이 "시간 크레딧"으로 남아 **장기 유휴 후 토큰 소비 직후에도 다시 풀버스트가 지급되는 버그**가 된다(예: `capacity=5`, 1시간 규칙에서 10시간 유휴 시 첫 접근에서 5개 채우고 9시간이 크레딧으로 남아 소비 직후 재차 5개 채워짐). → `elapsed >= refillPeriodNanos`일 때도 `lastRefillNanos = now`로 **리셋**해 초과 유휴시간을 폐기한다(부분 토큰은 `double tokens` 필드가 이미 보존하므로 별도 시간 잔액이 필요 없다). 장기 유휴 후에도 정확히 `capacity`개만 허용되는 테스트 추가(쟁점 3b 보강).
> - **수용(높음, 문서 내 모순)**: v6가 쟁점 3에 Caffeine `expireAfter(Expiry)`(명백한 시간 기반 만료)를 도입했는데, 쟁점 7에는 "Caffeine 캐시 자체에는 시간 기반 만료를 쓰지 않으므로 별도 `Ticker`를 넘길 필요가 없다"는 v5 문구가 그대로 남아 있었다 — 반영 시 쟁점 3만 고치고 쟁점 7을 갱신하지 않은 누락. 이대로면 테스트에서 `FakeTicker`를 전진시켜도 Caffeine은 자체 시스템 시계를 쓰므로 **버킷 리필 테스트는 통과해도 v6의 핵심(캐시 만료)은 전혀 검증되지 않는다.** → `Caffeine.newBuilder().ticker(ticker::nanos)`로 **Caffeine builder에도 동일한 `Ticker`를 연결**하고(운영·테스트 모두 하나의 시간원 사용), 만료 경계 테스트(`refillPeriod - 1ns`는 유지, 정확히 `refillPeriod`는 논리적 만료, `cleanUp()` 후 물리 제거, 다음 접근 시 신규 `Bucket` 생성) 추가(쟁점 7 보강).
> - **수용(중간, 표현 정정)**: `maximumSize`·`Expiry`는 즉시 적용되는 정확한 hard cap이 아니라 **유지보수(maintenance) 시점에 정리되는 근사 상한**이다(Caffeine 공식 동작) — "크기 상한"을 "장기적으로 유지되는 근사 엔트리 상한", "메모리 상한"을 "메모리 증가 억제 수단이며 순간적 hard cap은 아님", "만료 후 정리"를 "만료 후 논리적으로 miss 처리, 물리 제거는 유지보수 시 수행"으로 낮춰 표현(쟁점 3 문구 재정정, Caffeine을 기각할 사유는 아니고 운영 위험 문서에만 반영).
> - **수용(낮음, 표현 정정)**: `Cache.get()`의 "동일 키에 대해 mapping function이 정확히 한 번 실행"이라는 표현이 과했다 — eviction·expiration 이후에는 같은 키가 다시 계산될 수 있으므로 "하나의 `Cache.get` 원자 연산 안에서는 at most once, 이후 eviction·expiration이 발생하면 재실행될 수 있다"로 좁힘(쟁점 3 문구 정정).
> - 상세 리뷰 원문: 대화 기록 참조(6라운드, codex CLI 자체 리뷰, 판정 no-ship → 위 반영 완료로 7라운드 진행).
> **v8 변경 (2026-08-12, codex 7라운드 반영 — 새 결함 1건, 이전 4건은 확인 완료):** v7의 Caffeine `Expiry`·동일 ticker 연결·장기 유휴 크레딧 폐기·fail-open 문구·테스트 분리는 "새 실질적 결함 없음"으로 확인됨.
> - **수용(높음)**: `Bucket(int capacity, long refillPeriodNanos)` 생성자에 생성 시각이 없어 `lastRefillNanos` 필드가 기본값 `0`으로 초기화될 수 있는데, `System.nanoTime()`(및 이를 감싸는 `Ticker`)은 **원점이 임의이며 음수를 반환할 수 있다**(Java 공식 문서 명시). 최초 `ticker.nanos()`가 음수인 환경에서는 `elapsed = Math.max(0, now - 0)`이 `now`가 0을 넘기 전까지 계속 0으로 클램프되어 리필이 멈추는 버그가 된다 → **lazy 초기화**로 수정: `Bucket`에 `initialized` 플래그를 두고, 최초 `tryConsume()` 호출 시에만 `lastRefillNanos = now`로 설정하고 리필 계산은 건너뛴다(생성자 계약은 그대로 유지 — 생성자에 시각을 넘기는 대안보다 단순). 회귀 테스트: `FakeTicker`를 충분히 큰 음수값에서 시작해도 최초 `capacity`개 허용 → 리필 주기만큼 전진 시 정확히 1회 추가 허용이 정상 동작함을 확인.
> - 상세 리뷰 원문: 대화 기록 참조(7라운드, codex CLI 자체 리뷰, 판정 no-ship → 위 반영 완료로 8라운드 진행).
> **v9 변경 (2026-08-12, codex 8라운드 반영 — "ship 후보에 매우 가깝다"는 평가, 새 트레이드오프 없음):** Caffeine 채택·버킷별 `Expiry`·동일 `Ticker`·lazy 초기화·장기 유휴 크레딧 폐기·차분 산술·`Cache.get()` 원자성 표현·포화 시 정확성 비보장 수용은 재검토 결과 전부 정상 확인됨.
> - **수용(높음)**: `Bucket` 생성자가 `tokens` 초기값을 명시하지 않아 Java 기본값 `0`으로 구현될 위험 — 그러면 신규 IP의 첫 요청부터 429가 되어 "버스트 상한 N" 계약이 깨진다 → **`this.tokens = capacity`로 명시 초기화**. 회귀 테스트: "신규 버킷은 시간 전진 없이 정확히 `capacity`회 허용, 그다음 요청은 거절"을 독립 테스트 항목으로 추가.
> - **수용(높음)**: 완료 기준의 "Playwright로 429가 정확히 한 번, 중복 헤더·로그 없음"은 필터 컨테이너 이중 등록의 증거가 되지 못한다 — 같은 `OncePerRequestFilter`가 컨테이너와 `SecurityFilterChain`에 모두 등록돼도 already-filtered 속성 때문에 `doFilterInternal()`은 관측상 한 번만 실행될 수 있지만, 실제 실행 위치가 컨테이너 필터 순서로 바뀌어 "`CsrfFilter` 직전"이라는 설계 계약이 조용히 깨질 수 있다 → 검증을 분리: `@SpringBootTest(webEnvironment = RANDOM_PORT)`로 embedded 컨테이너를 실제로 띄운 뒤 `ServletContext#getFilterRegistrations()`에서 `RateLimitFilter`가 컨테이너 필터로 등록돼 있지 않음을 직접 단언하는 테스트를 추가하고, Playwright는 실제 ERROR 디스패치·`error/429.html` 렌더링 확인으로 역할을 좁힌다(쟁점 10 보강).
> - **수용(중간)**: "evict된 버킷을 이미 참조 중이던 스레드가 계속 소비" 문제를 v5~v8이 줄곧 "`maximumSize` 포화·크기 기반 eviction 상황"으로만 서술했는데, **캐시가 포화되지 않아도 expiration(v6에서 도입한 시간 기반 만료)만으로 같은 문제가 재현**된다(스레드 A가 오래된 버킷 참조를 쥔 채 `refillPeriod` 이상 정지해 있는 사이 만료·재생성이 일어나면 A가 구 버킷을 계속 소비) → fail-open 수용 범위를 "크기 eviction뿐 아니라 expiration과 장시간 정지한 in-flight 참조까지" 포함하도록 쟁점 3·범위 밖 섹션의 표현을 넓힌다.
> - **수용(중간)**: 계획한 만료 경계 테스트가 하나의 fixture에서 순차 실행되면, `refillPeriod - 1ns` 시점에 `get()`/`getIfPresent()`로 "유지됨"을 확인하는 조회 행위 자체가 `expireAfterRead()`를 호출해 만료 시각을 다시 `refillPeriod`만큼 연장시킨다(자기 검증이 검증 대상을 오염시킴) — 그 뒤 1ns만 전진해서는 만료되지 않아 다음 단언이 거짓으로 실패하거나 의도와 다르게 통과한다 → **"생성 후 `refillPeriod - 1ns`(유지 확인)"과 "별도로 새로 생성한 뒤 정확히 `refillPeriod`(만료 확인)"를 서로 다른 캐시/fixture로 완전히 분리**한다(쟁점 7 테스트 보강).
> - 상세 리뷰 원문: 대화 기록 참조(8라운드, codex CLI 자체 리뷰, 판정 no-ship, "위 4건 반영 시 재검토 없이도 ship 후보에 매우 가깝다" → 위 반영 완료로 9라운드 진행).
> **v10 변경 (2026-08-12, codex 9라운드 반영 — 실질적 보안 결함 1건, "이 1건 외 새 실질적 결함 없음, 반영 시 ship 후보"):** v9의 4건(초기값·이중 등록 검증·fail-open 범위·만료 경계 fixture 분리)은 전부 정상 확인, Caffeine·`Expiry`·동일 `Ticker`·lazy 초기화·장기 유휴 크레딧 폐기 설계도 재확인 결과 새 결함 없음.
> - **수용(높음, 실질적 보안 결함)**: `addFilterBefore(rateLimitFilter, CsrfFilter.class)`(v1부터 유지해온 배치)는 **CSRF 검증에 실패해 컨트롤러까지 도달하지 못하는 상태 변경 요청도 이미 레이트리밋 quota를 소비**시킨다 — 레이트리밋 필터가 경로·메서드·IP만 보고 판정하기 때문. 공격자가 외부 페이지에서 피해자 브라우저로 CSRF 토큰 없는 `POST /admin/api/password-reset-requests` form을 반복 전송하면, 그 요청들은 컨트롤러에 닿지 못하고 CSRF 검증에서 거부되지만 **피해자의 IP를 기준으로 한 quota(5회/시간)는 이미 소진**되어, 피해자(또는 같은 NAT 사용자) 자신의 정상적인 비밀번호 재설정 요청이 1시간 동안 429로 막힌다 — CSRF가 원래 막아야 할 교차 사이트 요청이 "레이트리밋 서버 상태 변경"이라는 부작용을 여전히 일으키는 구조. → **`http.addFilterAfter(rateLimitFilter, CsrfFilter.class)`로 변경**(쟁점 4 재작성) — "CSRF보다 먼저 값싸게 거절한다"는 원래 근거도 재검토 결과 이득이 크지 않다(`CsrfFilter` 뒤에 둬도 DB 조회·토큰 검증·메일 발송·컨트롤러 로직보다는 여전히 먼저 실행된다). GET·HEAD는 기본적으로 CSRF 검증 대상이 아니므로 공개 공지 목록·첨부 다운로드 규칙에는 이 변경이 영향을 주지 않는다.
>   - `RateLimitFilterOrderTest`의 필터 순서 계약을 `RateLimitFilter < CsrfFilter`에서 **`CsrfFilter < RateLimitFilter`**로 변경.
>   - 신규 테스트: 같은 IP에서 CSRF 토큰 없는(또는 잘못된) `POST`를 `capacity`회 이상 반복해도 버킷이 소진되지 않음 확인 → 그 뒤 유효한 CSRF 토큰을 포함한 요청이 최초 `capacity`회 정상 통과함을 확인.
>   - 완료 기준의 `reset-request` curl 예시에 **유효한 세션·CSRF 토큰 사용을 명시**(현재 기준은 CSRF 없는 요청도 6번째에 429가 되므로 "레이트리밋이 작동한다"는 거짓 양성으로 통과할 수 있었다).
> - 상세 리뷰 원문: 대화 기록 참조(9라운드, codex CLI 자체 리뷰, 판정 no-ship, "이 1건 외 새 실질적 결함 없음, 반영 시 ship 후보" → 위 반영 완료로 10라운드 진행).
> **v11 변경 (2026-08-12, codex 10라운드 반영 — 응답 코드 사실 정정 1건, 보안 결론은 불변, "이 1건 정정 시 ship 판정 가능한 수준"):** `addFilterAfter(CsrfFilter.class)` 전환(v10)의 유효성은 재확인됨, Caffeine·버킷·만료·동시성 설계에서도 새 결함 없음.
> - **수용(중간, 사실 오류)**: v10이 "CSRF 토큰 없는(또는 잘못된) POST는 403"이라고 서술했으나, **현재 `SecurityConfig`는 `/admin/api/**`에서 익명 사용자의 CSRF 실패를 `ApiAuthenticationEntryPoint`로 넘겨 JSON `401`(`code=UNAUTHORIZED`)로 변환**한다(`SecurityConfig.java:100` 부근 — "CsrfFilter가 인증 체크보다 먼저 동작하므로 미인증 요청의 CSRF 실패는 accessDeniedHandler로 도달, 이 경우 403 대신 401을 반환해야 한다"). 기존 `PasswordResetControllerTest.java:181`("CSRF 토큰 없는 미인증 POST는 401")이 이미 이 계약을 테스트로 고정하고 있다 — 이전에 이 파일을 읽고도 v10 작성 시 반영을 놓친 제 실수. → 계획·완료 기준의 "403" 표현을 **"401(JSON `code=UNAUTHORIZED`)"**로 전부 정정한다. **`addFilterAfter`가 quota를 소비시키지 않는다는 보안 결론 자체는 변하지 않는다** — 상태 코드만 사실과 다르게 서술했을 뿐.
> - 상세 리뷰 원문: 대화 기록 참조(10라운드, codex CLI 자체 리뷰, 판정 no-ship, "이 1건 정정 시 ship 판정 가능한 수준" → 위 반영 완료로 11라운드 진행).
> **v12 변경 (2026-08-12, codex 11라운드 반영 — 새 설계·보안 결함 없음, 이전 라운드에서 이미 결정된 정정의 본문 반영 누락 2건만):** Caffeine의 원자적 `Cache.get`·가변 만료·동일 `Ticker`·유지보수 기반 제거 설계는 공식 문서와 대조해 재확인 완료.
> - **수용(문서 정리)**: 완료 기준(당시 297행)에 v10 작성 당시의 "CSRF 403" 표현이 v11에서 401로 정정한 뒤에도 다른 위치에 그대로 남아 있었다 → 401로 통일.
> - **수용(문서 정리)**: 범위 밖 섹션의 "Redis가 유일한 완전한 해법"이 쟁점 3에서 v6에 이미 조건부 표현으로 정정했던 것과 달리 그대로 남아 문서 내 모순이었다 → 동일한 조건부 표현으로 통일.
> - 상세 리뷰 원문: 대화 기록 참조(11라운드, codex CLI 자체 리뷰, 판정 no-ship — "두 항목 모두 새 트레이드오프나 구현 변경은 아니다. 이 문구만 바로잡으면 추가 재검토 없이 ship 판정 가능한 수준" → 위 반영 완료로 12라운드 진행).
> **v13 변경 (2026-08-12, codex 12라운드 반영 — "새로운 설계·보안 결함 없음", 잔존 표현 3곳 최종 정리, 사용자 결정으로 리뷰 루프 종료 후 구현 착수):** Caffeine 가변 만료·동일 `Ticker`·유지보수 기반 정리·`Cache.get` 원자적 적재·`Bucket` 초기화·lazy 시각·장기 유휴 크레딧 폐기·`CsrfFilter` 뒤 배치·컨테이너 등록 비활성화·`RANDOM_PORT` 등록 검사·MockMvc/Playwright 역할 분리 — 전부 재검토 결과 이상 없음("그 밖의 핵심 설계는 재검토 결과 이상 없다").
> - **수용(문서 정리)**: 쟁점 3의 "메모리 상한과 개별 IP의 완벽한 정확성은 외부 원자적 저장소 없이 동시 달성 불가능하다"(단정)와 쟁점 3의 fail-open 문단 말미 "Redis 같은 외부 원자적 저장소 없이는 근본적으로 완전히 막을 수 없다"(단정) 두 곳이, 같은 쟁점 3 안에서 이미 확정한 조건부 결론("메모리 상한·무제한 신규 키 수용·IP별 완벽한 정확성을 동시에 요구하는 경우에만 외부 저장소가 필요하다")과 서로 모순됐다 → 두 단정문을 조건부 표현으로 통일.
> - **수용(문서 정리)**: Context 섹션의 "목표 재확인" 문단이 fail-open을 "극단적인 IP 회전 공격으로 캐시가 포화되는 시나리오"로만 서술해, 이후 쟁점 3·범위 밖 섹션에서 v9에 이미 확대한 범위("eviction 또는 expiration이 in-flight 참조와 겹치는 상황 전반")보다 좁게 남아 있었다 → 동일 범위로 통일.
> - **수용(문서 정리)**: 쟁점 10의 필터 이중 등록 설명이 "`CsrfFilter` 직전이라는 설계 계약"이라는 v1~v9 시절 표현을 그대로 남겨, v10에서 확정한 "`CsrfFilter` 다음"과 모순됐다 → "`CsrfFilter` 다음이라는 현재 설계 계약"으로 통일.
> - **사용자 결정(2026-08-12)**: 12라운드에 걸쳐 핵심 설계 결함은 8~9라운드째부터 재확인만 반복되고 있고, 최근 3라운드는 전부 "새 설계·보안 결함 없음, 문서 자기 일관성 문제만" — 이 시점에서 리뷰 루프를 종료하고 위 3곳 반영을 마지막으로 **구현 착수**로 넘어간다.
> - 상세 리뷰 원문: 대화 기록 참조(12라운드, codex CLI 자체 리뷰, 판정 no-ship — "그 밖의 핵심 설계는 재검토 결과 이상 없다. 위 3곳을 v13 문서 정리로 고치면 ship 판정 가능" → 위 반영 완료. 사용자 결정으로 13라운드 리뷰는 생략하고 구현 착수).

## Context

로드맵(`adversarial-review/project-direction-roadmap.md`)의 실행 로드맵 Top 3·Top 5는 전 항목 완료됐다. 남은 미완료 항목 중 실배포 인프라(nginx·TLS·호스팅)는 호스트·도메인 확정이라는 사용자 결정 대기 상태라 지금 착수할 수 없다. 그 전에 코드 레벨에서 닫을 수 있는 항목이 무인증 경로 방어다.

현재 무인증(비로그인)으로 열려 있는 경로는 아래 4종이고, **요청 수 제한이 전혀 없다**(`RateLimit`·`Bucket4j`·커스텀 레이트리밋 필터 소스 검색 결과 0건 — 2026-08-12 실측):

| 경로 | 비용 | 현재 방어 |
|------|------|-----------|
| `GET/HEAD /notices/{id}/attachments/{attachmentId}` | 파일을 `byte[]`로 전량 로딩(최대 10MB) — **HEAD도 GET 핸들러를 그대로 거쳐 동일 비용**(`PublicNoticeControllerTest.java:396` 실측) | 없음 |
| `GET/HEAD /notices`, `GET/HEAD /notices/{id}` | DB 조회 + 렌더링 | `MAX_PAGE=1000` OFFSET 상한만 (`PublicNoticeService:46`) |
| `POST /admin/api/password-reset-requests` | 메일 발송 트리거 | 계정별 60초 쿨다운 — IP 축 제한은 없음 |
| `POST /admin/api/password-resets` | 토큰 검증(BCrypt 아님, SHA-256 해시 비교) | 없음 — 토큰 무차별 대입 무제한 |

로드맵에 미완료로 남아 있는 두 항목을 이 계획 하나로 닫는다:
- "후속 과제 — ② 공개 첨부 다운로드 완료 시 기록"의 **"무인증 다운로드 경로의 자원 고갈 위험(명시적 수용)"**
- "선정에서 탈락한 후보"의 **"비밀번호 재설정 공개 API의 IP 기반 rate limit"**

**이 계획의 목표 재확인(v5, 범위는 v13에서 통일)**: 정확한 유량 계약을 제공하는 API 게이트웨이가 아니라, **무제한 요청을 값싸게 차단하는 최소 방어**다. Caffeine의 eviction 또는 expiration이 in-flight 참조와 겹치는 상황(캐시 포화 시의 크기 기반 eviction뿐 아니라, 포화되지 않아도 장시간 정지한 참조와 시간 기반 만료가 겹치는 경우도 포함 — 쟁점 3)에서는 개별 IP의 정확한 누적치 보장이 흐트러질 수 있음을(fail-open) 아래에서 명시적으로 수용한다.

## 범위

- **레이트리밋만.** 첨부 다운로드의 `InputStreamResource` 스트리밍 전환은 별개 작업으로 제외(1계획=1PR). 파일당 10MB 상한이 이미 건당 비용을 제한한다.
- **`POST /admin/login`(로그인)은 범위 밖.** 연속 5회 실패 자동 잠금(`LoginFailureService`)이 이미 있고, IP 레이트리밋 추가는 CLAUDE.md가 사전 협의 대상으로 지정한 **로그인 정책 변경**에 해당한다.
- **기존 IP 추출 로직 4곳의 통합·리팩터링은 하지 않는다.** 신규 필터는 `getRemoteAddr()`만 자체적으로 읽는다.
- **신규 의존성: Caffeine 1건만.** v4에서 사용자가 명시적으로 재승인. 그 외 새 라이브러리는 추가하지 않는다.

## 쟁점별 설계 결정

### 쟁점 1 — 레이트리밋 키·매칭 대상: `getRemoteAddr()` + 경로·메서드 조합

- **키: `request.getRemoteAddr()` 고정.** TCP peer 주소는 애플리케이션 레벨에서 위조 불가능하다. **현재 상태(v5 정정)**: `docker-compose.prod.yml`은 `127.0.0.1:8080` 루프백 바인딩이라 외부에서 이 애플리케이션에 직접 접근할 수 없는 로컬 검증 전용 구성이다 — "이 값이 곧 실 클라이언트 IP"라는 v1~v4의 표현은 부정확했다("실 클라이언트"라는 개념 자체가 성립하려면 외부 트래픽이 존재해야 한다). 정확히는 "현재 외부 서비스가 미개방된 상태이므로 `getRemoteAddr()`가 관측하는 것은 로컬 peer 주소"다.
- 향후 nginx 리버스 프록시가 도입되면 Spring Boot의 `server.forward-headers-strategy=native` 설정과 nginx의 헤더 재작성이 함께 갖춰져야 `getRemoteAddr()`가 실 클라이언트 IP를 반환한다 — forward-header 처리가 없으면 peer는 nginx 자신이 된다. 이 코드(`getRemoteAddr()` 그대로 사용)는 그 설정이 갖춰지는 시점까지 변경할 필요가 없다는 결정 자체는 안전하다 — nginx 신뢰 프록시 체인 구성(신뢰 CIDR·헤더 재작성 설정·8080 직접 접근 차단)은 로드맵 "실배포 인프라" 항목으로 이월한다(범위 밖).
- 기각: `X-Forwarded-For` 신뢰. 기존 4곳이 이 헤더를 우선하지만 전부 "로그 전용, 위조 가능"이라 주석에 명시돼 있다 — 방어 수단의 키로 쓰면 공격자가 헤더 값만 바꿔 우회한다.
- **매칭 대상(HTTP 메서드 명시)**: `RateLimitRule`은 경로 패턴(`PathPatternRequestMatcher`, 메서드 무관 매칭)과 **별도로 `Set<HttpMethod> methods`를 보유**한다. 필터는 `matcher.matches(request) && methods.contains(HttpMethod.valueOf(request.getMethod()))`로 판정한다. `attachment`·`public-notice` 규칙 모두 `methods = {GET, HEAD}`로 설정해, HEAD가 GET과 동일 비용(`PublicNoticeControllerTest.java:396` 실측)인데도 레이트리밋을 우회하는 v1의 결함을 막는다.

### 쟁점 2 — 알고리즘: 토큰 버킷(lazy refill) + 표기 정정

- **채택: 토큰 버킷, lazy refill.** 별도 스케줄러 없이 접근 시점에 경과시간만큼 토큰을 채운다 — 이 프로젝트의 "배치 없이 접근 시점 lazy 처리" 관용구(`LoginFailureService`, `PasswordExpiryService`)와 일치.
- 기각: 슬라이딩 윈도우 로그, GCRA — 이번 "최소 방어" 목적에 비해 복잡도 과함.
- **표기(사용자 확정)**: "N회/기간"은 "**버스트 상한 N + 평균 N/기간**"을 뜻한다. 임의로 겹치는 두 기간에 걸치면 이론상 최대 약 2N에 근접하는 요청이 통과할 수 있음을 계획·완료 기준·운영 문서 전체에서 이 표기로 통일한다.

### 쟁점 3 — 저장소 설계: Caffeine + fail-open 명시 수용 (v5 전면 재작성)

v1(전체 순회 정리)·v2(`computeIfAbsent` 오용)·v3(`AtomicInteger` CAS + 유계 sweep, retired 상태 부재)·v4(Caffeine 도입했으나 W-TinyLFU 특성 오해)를 거쳐, v5는 **"메모리 상한·무제한 신규 키 수용·IP별 완벽한 정확성을 동시에 요구하면 외부 원자적 저장소 없이는 달성할 수 없다"는 근본 사실을 명시적으로 인정**(v13: 무조건적 "불가능"이 아니라 세 요구가 동시에 있을 때만 — 아래 fail-open 문단의 조건부 표현과 통일)하고 그 위에서 최소 방어 목적에 맞는 가장 단순한 설계를 채택한다.

- **채택(v6)**:
  ```java
  Cache<BucketKey, Bucket> buckets = Caffeine.newBuilder()
          .ticker(ticker::nanos)  // v7 — Bucket 리필과 캐시 만료가 동일 시간원을 쓰도록 고정(FakeTicker로 양쪽 다 제어 가능해야 함)
          .maximumSize(properties.getMaxKeys())  // 기본 10,000 — 장기적으로 유지되는 근사 엔트리 상한(v7: "정확한 즉시 hard cap"은 아님)
          .expireAfter(new Expiry<BucketKey, Bucket>() {
              public long expireAfterCreate(BucketKey key, Bucket bucket, long currentTime) {
                  return bucket.refillPeriodNanos(); // 버킷 자신의 규칙 리필 주기
              }
              public long expireAfterUpdate(BucketKey key, Bucket bucket, long currentTime, long currentDuration) {
                  return bucket.refillPeriodNanos(); // 매 접근(소비 시도)마다 리필 주기만큼 갱신 — expireAfterAccess와 동일 시맨틱
              }
              public long expireAfterRead(BucketKey key, Bucket bucket, long currentTime, long currentDuration) {
                  return bucket.refillPeriodNanos();
              }
          })
          .build();

  Bucket bucket = buckets.get(new BucketKey(rule.id(), remoteAddr), k -> new Bucket(rule.capacity(), rule.refillPeriod().toNanos()));
  RateLimitDecision decision = bucket.tryConsume(ticker);
  ```
- `Cache.get(key, mappingFunction)`은 동일 키에 대해 원자적이다 — **(v7 표현 정정)** 하나의 `Cache.get` 원자 연산 안에서는 mapping function이 at most once 실행된다. 다만 이후 eviction·expiration이 발생하면 같은 키라도 mapping function이 다시 실행될 수 있다("영구히 한 번만"이 아니다).
- **v6: 버킷별 규칙 리필 주기를 만료 시간으로 사용(Caffeine `Expiry` 커스텀 구현)**. v4는 전역 고정 TTL(10분)을 뒀다가 1시간 규칙(`reset-request`/`reset-confirm`)을 직접 우회시키는 버그를 만들었고(사실상 "10분마다 전체 버스트 재지급"), v5는 그 버그를 피하려고 시간 기반 만료를 아예 제거했다가 "한 번 포화되면 트래픽이 줄어도 캐시가 계속 포화 상태로 남는" 새 문제를 만들었다(codex 5라운드 지적). v6는 **각 버킷이 자기 규칙의 `refillPeriod`를 알고, 그 시간만큼 접근이 없으면 만료**시키는 것으로 둘 다 해결한다 — "마지막 접근 후 자기 규칙의 전체 리필 기간만큼 유휴였던 버킷은 이미 완전히 리필된 상태였을 것"이므로, 그 시점에 지워도 실제로 뺏기는 토큰이 없다(안전한 만료). 이를 위해 `Bucket`이 `capacity`뿐 아니라 `refillPeriodNanos`도 생성자에서 받도록 고정한다(쟁점 3b와 연동, codex 5라운드 지적 5 해소).
- **(v7 표현 정정)** `maximumSize`·`Expiry`는 즉시 반영되는 정확한 hard cap/타이머가 아니다 — Caffeine은 만료 시각이 지난 엔트리를 즉시 물리적으로 삭제하지 않고 "논리적으로 miss 처리"한 뒤 이후 쓰기·간헐적 읽기에 동반되는 유지보수(maintenance) 시점에 실제로 정리한다. `Cache.get()` 관점에서는 만료된 엔트리를 다시 계산해 반환하므로 정책적으로는 문제가 없지만, "캐시 크기가 항상 정확히 `maxKeys` 이하"라거나 "만료 즉시 메모리에서 사라진다"는 식의 표현은 부정확하다 — "메모리 증가를 억제하는 근사 메커니즘"으로 이해한다.
- **fail-open 명시 수용(사용자 확정, 2026-08-12, v5→v6→v9에서 문구 재정정)**: Caffeine은 순수 LRU가 아니라 W-TinyLFU다. `maximumSize` 도달 시 (a) 신규 엔트리가 admission window에서 거부될 수 있고, (b) 기존 엔트리가 evict된 직후에도 그 버킷을 이미 참조 중이던 스레드는 evict된 객체를 계속 소비할 수 있다(evict와 in-flight 참조 사이의 pinning 보장이 없다). **(v9 확대)** 이 문제는 **캐시가 포화되지 않아도 발생할 수 있다** — 스레드 A가 `Cache.get()`으로 기존 버킷 참조를 쥔 채 `refillPeriod` 이상 정지해 있는 사이(예: 매우 느린 클라이언트·GC 일시정지) 그 엔트리가 쟁점 3의 `Expiry`로 정상 만료되고 스레드 B가 같은 키의 신규 버킷을 생성하면, A는 캐시에서 이미 제거된 구 버킷을 계속 소비한다 — **크기 기반 eviction뿐 아니라 시간 기반 expiration + 장시간 정지한 in-flight 참조의 조합에서도 동일한 이중 버킷 문제가 재현**된다(codex 8라운드 지적). 즉 **"캐시가 포화된 상황"이 아니라 "eviction 또는 expiration이 in-flight 참조와 겹치는 상황" 전반**에서 개별 IP의 정확한 누적치가 흐트러질 수 있다 — (v13: 표현 통일) 이를 근본적으로 완전히 막으려면 pinning/lifecycle 관리 같은 자체 구현 보강이나 Redis 같은 외부 원자적 저장소가 필요한데, 전자는 v3에서 3라운드에 걸쳐 정확한 구현이 실패했음이 증명됐고 후자는 이번 범위·인프라 제약을 벗어난다(재검토 후 계속 기각, 아래 참조).
  - **이 잔여 위험을 명시적으로 운영 위험으로 수용한다.** 이 계획의 목표가 애초에 "무제한 요청을 값싸게 차단하는 최소 방어"이지 정확한 유량 계약을 보장하는 게이트웨이가 아니기 때문이다.
  - **(v6 정정)** "포화 상태에서도 전체적인 요청량 억제 효과는 유지된다"는 표현은 근거 없는 단정이었다 — W-TinyLFU가 신규 후보를 거부하면 다음 요청에서 또 full bucket이 생성되므로 이 억제 효과를 일반적으로 보장할 수 없다. **정정된 표현**: "캐시 포화 시 일부 요청은 계속 제한될 수 있으나, 전체 또는 개별 IP 단위의 억제율을 보장하지 않는다." 위험 기간도 "공격 진행 중"뿐 아니라 **"공격 종료 후 캐시가 포화 상태에서 서서히 정상화되는 과정"**까지 포함한다(v6에서 도입한 `Expiry`가 이 회복을 자연스럽게 돕지만, 완전한 즉시 회복을 보장하지는 않는다).
  - **완료 기준·운영 문서에서 "무차별 대입 완전 차단" 같은 절대적 표현을 쓰지 않는다** — "값싸게 차단하는 최소 방어" 또는 "시도율 억제"로 표현한다.
  - **(v6 정정)** "Redis가 유일한 완전한 해법"이라는 표현도 과하다 — 정확히는 "메모리 상한·무제한 신규 키 수용·IP별 완벽한 정확성을 동시에 요구하는 경우에만 외부 원자적 저장소가 필요하다. 로컬 fail-closed 방식(신규 키를 명시적으로 거절)도 정확한 상한은 지킬 수 있지만, 그 대가로 신규 사용자를 거절하는 가용성 비용을 진다."
  - **재검토 후 계속 기각**: overflow 안전망(v3의 CAS admission·폴백 샤딩 등 자체 구현 재도입 — 3라운드에 걸쳐 정확한 구현이 실패했음이 증명됨), Redis 등 외부 저장소(이번 범위·인프라 제약을 벗어남), 로컬 fail-closed(신규 사용자 거절이라는 가용성 비용이 이번 "최소 방어" 목적에 비해 과함).
- 폴백 버킷 개념 없음 — fail-open 수용이 그 자리를 대체한다.

### 쟁점 3b — 버킷 내부 동시성: 버킷 단위 `synchronized` + 자기 규칙 인지 (v6)

- `Bucket(int capacity, long refillPeriodNanos)` — 가변 필드(`double tokens`, `long lastRefillNanos`)에 더해 **자기 규칙의 `refillPeriodNanos`를 불변 필드로 보유**한다(v6, 쟁점 3의 `Expiry` 구현이 `bucket.refillPeriodNanos()`를 조회할 수 있어야 함). `tryConsume(Ticker)`를 `synchronized` 메서드로 선언해 같은 버킷에 대한 모든 리필·소비 연산을 선형화한다. 단일 IP의 요청 동시성은 낮으므로 락 경합 비용은 무시할 수준이다. 이 부분은 Caffeine이 대신해주지 않는 "정책 로직 자체의 동시성"이라 자체 구현으로 남는다.
- **(v8 수정)** `lastRefillNanos`의 최초값은 `boolean initialized` 플래그로 lazy 초기화한다 — 생성자에서 시각을 받지 않고, 최초 `tryConsume()` 호출 시 `lastRefillNanos = now`로 설정한 뒤 리필 계산은 건너뛴다. `System.nanoTime()`(및 이를 감싸는 `Ticker`)은 원점이 임의이며 음수를 반환할 수 있어, 필드 기본값 `0`을 "생성 시각"으로 암묵 전제하면 음수 시간원 환경에서 리필이 멈추는 버그가 된다(codex 7라운드 지적).
- **(v9 수정)** 생성자는 `this.tokens = capacity`로 **명시 초기화**한다 — `double tokens` 필드를 Java 기본값 `0`으로 남겨두면 신규 IP의 첫 요청부터 429가 되어 "버스트 상한 N을 즉시 허용한다"는 계약 자체가 깨진다(codex 8라운드 지적). 회귀 테스트: 신규 버킷은 시간 전진 없이 정확히 `capacity`회 허용, 그다음 요청은 거절.
- 부분 토큰(리필 계산의 나머지 시간)은 버려지지 않는다 — `lastRefillNanos`를 "지금"으로 매번 리셋하지 않고 "실제로 리필이 반영된 만큼"만 전진시킨다.
- **(v7 치명적 버그 수정)** 오버플로 방지 산술(쟁점 7과 연동): 경과 나노초가 리필 주기 이상이면 곱셈 없이 즉시 `tokens = capacity`로 설정하되, **이때 반드시 `lastRefillNanos = now`로 리셋**한다(v2~v6에 걸쳐 "지금으로 리셋하지 않고 실제 반영된 만큼만 전진"이라고만 서술했던 것이 실제로는 리필 주기를 초과하는 유휴 시간을 "시간 크레딧"으로 남겨, 장기 유휴 후 토큰 소비 직후에도 다시 풀버스트가 지급되는 버그였다 — 예: `capacity=5`, 1시간 규칙에서 10시간 유휴 시 최초 접근에서 5개로 채워지고 9시간이 크레딧으로 남아 소비 직후 재차 5개로 채워짐). 부분 토큰은 `double tokens` 필드 자체가 이미 보존하므로 별도의 시간 잔액이 필요 없다. 의사코드:
  ```java
  long elapsed = Math.max(0, now - lastRefillNanos);
  if (elapsed >= refillPeriodNanos) {
      tokens = capacity;
      lastRefillNanos = now; // 초과 유휴시간 폐기 — 핵심 수정
  } else if (elapsed > 0) {
      tokens = Math.min(capacity, tokens + (double) elapsed * capacity / refillPeriodNanos);
      lastRefillNanos = now; // 짧은 경과는 소수 토큰으로 정확히 반영, 나머지 손실 없음
  }
  ```
  장기 유휴 후에도(예: `FakeTicker`를 리필 주기의 10배로 전진) 정확히 `capacity`개만 허용되는 테스트를 반드시 추가한다.

### 쟁점 4 — 필터 위치

- **채택(v10): `http.addFilterAfter(rateLimitFilter, CsrfFilter.class)`.** v1~v9는 `addFilterBefore(CsrfFilter.class)`(CSRF 검증보다 먼저 값싸게 거절)를 채택했으나, 이 순서는 **CSRF 검증에 실패해 컨트롤러까지 도달하지 못하는 상태 변경 요청도 이미 레이트리밋 quota를 소비**시키는 보안 결함이었다(codex 9라운드 지적) — 레이트리밋 필터가 경로·메서드·IP만으로 판정하므로, 공격자가 외부 페이지에서 피해자 브라우저로 CSRF 토큰 없는 form POST를 반복 전송하면 그 요청들이 컨트롤러에 닿지 못해도 피해자 IP 기준 quota는 소진되어 피해자 자신의 정상 요청까지 429로 막힌다. **`CsrfFilter` 뒤로 옮겨 CSRF를 통과한 요청만 quota를 소비**하도록 수정한다 — "CSRF보다 먼저 값싸게 거절한다"는 원래 이점도 크지 않다(`CsrfFilter` 뒤에 둬도 DB 조회·토큰 검증·메일 발송·컨트롤러 로직보다는 여전히 먼저 실행된다). GET·HEAD는 기본적으로 CSRF 검증 대상이 아니므로(Spring Security 기본 정책) 공개 공지 목록·첨부 다운로드 규칙에는 이 변경이 영향을 주지 않는다.
- 필터는 매칭되지 않으면 즉시 통과시키고, 거절 시에만 응답을 직접 쓰고 체인을 끊는다(예외를 던지지 않는다).
- `/actuator/health`는 규칙에 없으므로 자동 무제한(로드밸런서 헬스체크 보호).

### 쟁점 5 — 초과 응답 포맷

- **채택: 경로별 분기.** `/admin/api/**`는 `GlobalApiExceptionHandler.API_MATCHER`와 동일한 `PathPatternRequestMatcher`로 판정해 기존 `ApiErrorResponse` 포맷 JSON 429(**`code="RATE_LIMITED"`** — `ApiErrorResponse.java:8` 실측: 레코드 필드는 `code`)를 필터 안에서 직접 작성한다(`ApiAuthenticationEntryPoint`와 동일 패턴). 그 외(`/notices/**`)는 `sendError(429)`로 `CustomErrorController` → `error/429.html`을 렌더링한다.
- 둘 다 `Retry-After` 헤더(초 단위, 최소 1초로 올림)를 포함한다.

### 쟁점 6 — `CustomErrorController`가 429를 모른다

`statusCode == 404`만 분기하는 현재 구조에 `429` 분기를 추가하고 `error/429.html`(신규, `error/404.html` 구조 미러)을 반환하도록 확장한다. admin 전용 429는 만들지 않는다 — 429가 걸리는 경로는 전부 무인증 공개 경로다.

### 쟁점 7 — 경과시간 측정용 시계: 전용 `Ticker`

- `AppConfig`의 기존 `Clock`(`Clock.system(Asia/Seoul)`, wall-clock)은 NTP 조정에 취약해 리필 계산에 부적합하다.
- **채택**: 레이트리밋 전용 `Ticker` 인터페이스(`long nanos()`)를 신설하고 운영 구현체는 `System.nanoTime()`을 감싼다. 테스트는 수동 전진 가능한 `FakeTicker`를 주입한다. `AppConfig`의 기존 `Clock` 빈은 건드리지 않는다.
- `Retry-After`는 초 단위 올림, 최소 1초 보장. 경과 나노초가 음수로 계산되는 이론적 엣지는 0으로 클램프.
- **(v7 정정 — v5 문구는 더 이상 유효하지 않음)** v6에서 Caffeine `expireAfter(Expiry)`(쟁점 3)를 도입해 캐시 자체도 시간 기반 만료를 쓰게 됐으므로, **이 `Ticker`를 Caffeine builder에도 `.ticker(ticker::nanos)`로 반드시 연결**해야 한다. 연결하지 않으면 `Bucket.tryConsume()`은 주입된 `FakeTicker`로 시간이 전진하지만 Caffeine은 자체 시스템 시계를 쓰는 두 시간원 불일치가 생겨, 테스트에서 버킷 리필은 검증되어도 캐시 만료(쟁점 3의 핵심)는 전혀 검증되지 않는다. 운영·테스트 모두 하나의 `Ticker` 인스턴스를 공유한다.

### 쟁점 8 — 규칙 우선순위

`attachment` 규칙을 `public-notice`보다 먼저 선언해 첫 매칭에서 종료하는 방식으로 더 엄격한 한도가 우선 적용되게 한다. 한 요청에 여러 규칙이 동시에 소비되지 않는다.

### 쟁점 9 — 버킷 키: `BucketKey` record

- `record BucketKey(String ruleId, String remoteAddr)`를 캐시 키로 사용한다(`record`는 `equals`/`hashCode`를 자동 생성해 값 기반 동등성이 보장되고, 문자열 연결·구분자 충돌 문제 자체가 없다).
- 같은 IP가 서로 다른 규칙에 접근해도 `ruleId`가 다르므로 별도 버킷을 받는다 — v2가 놓쳤던 "규칙 간 설정 충돌" 문제가 구조적으로 방지된다.

### 쟁점 10 — 필터의 서블릿 컨테이너 이중 등록 방지 + 테스트 전략 (v5: 테스트 방식 재분리)

- `RateLimitFilter`를 `@Component`로 선언하면 Spring Boot가 서블릿 컨테이너에도 자동 등록해 `SecurityConfig`의 필터 순서 계약이 깨질 수 있다.
- **채택**: `RateLimitFilter`는 `@Component`를 부착하지 않는다. `RateLimitFilterConfig`가 `RateLimitFilter` Bean과 `FilterRegistrationBean<RateLimitFilter>`(`setEnabled(false)`)를 함께 정의해 컨테이너 자동 등록을 차단하고, `SecurityConfig`는 `RateLimitFilter` Bean을 직접 `addFilterAfter(rateLimitFilter, CsrfFilter.class)`에 넘긴다(v10 — 필터 순서 변경, 쟁점 4 참조).
- **v5 보강(테스트 전략 재분리)**: v4는 "`@SpringBootTest(webEnvironment = MOCK)`급으로 컨테이너 이중 등록·`sendError(429)`의 실제 ERROR 디스패치·`CustomErrorController` 렌더링까지 전부 검증"한다고 계획했으나, **`MOCK` webEnvironment는 embedded server를 시작하지 않고 `MockMvc`도 실제 HTTP 서버 없이 mock servlet 환경에서 동작**한다 — 컨테이너 레벨 필터 이중 등록이나 실제 ERROR 재디스패치를 증명하지 못한다. 이 프로젝트의 기존 `SecurityConfigTest.java:310` 주석이 이미 이 한계를 "MockMvc는 컨테이너 ERROR 디스패치를 수행하지 않으므로... Playwright로 검증한다"고 명시하고 있다 — 429 처리도 동일한 기존 관례를 따른다.
  - **`RateLimitFilterOrderTest`(MockMvc)**: 필터가 `SecurityFilterChain` 안에서 **`CsrfFilter` 다음에** 실행됨(v10 — 필터 체인 내부 순서, 쟁점 4의 CSRF 우회 방지 결정과 일치), 요청당 정확히 한 번만 실행됨(테스트 전용 실행 카운터), `RateLimitProperties`가 실제로 바인딩됨, `RateLimitFilterConfig`의 Bean들이 정상 배선됨 — 이 정도까지만 MockMvc로 검증한다.
  - **(v9 정정, v13 표현 통일)** "429가 정확히 한 번, 중복 헤더·로그 없음"을 Playwright로 관찰하는 것은 **컨테이너 이중 등록의 증거가 되지 못한다** — 같은 `OncePerRequestFilter`가 컨테이너 필터 체인과 `SecurityFilterChain`에 모두 등록돼도, already-filtered 요청 속성 때문에 `doFilterInternal()`은 겉보기엔 한 번만 실행된 것처럼 관측될 수 있다. 하지만 그 경우 실제 실행 위치가 컨테이너 필터 순서로 결정되어 "`CsrfFilter` 다음"(v10에서 확정한 현재 설계 계약)이라는 위치 보장이 조용히 깨질 수 있다(codex 8라운드 지적). → **`RateLimitFilterContainerRegistrationTest`(신규, `@SpringBootTest(webEnvironment = RANDOM_PORT)`)**를 추가해 embedded 컨테이너를 실제로 띄운 뒤 `ServletContext#getFilterRegistrations()`로 `RateLimitFilter`가 컨테이너 필터로 등록돼 있지 않음을 직접 단언한다. **Playwright의 역할은 실제 ERROR 재디스패치·`error/429.html` 렌더링 확인으로 좁힌다**(완료 기준에 명시).

### 쟁점 11 — 설정값 검증·실패 정책 + 오버플로 방지 상한 (v5: `methods` 빈 값 방어 추가)

- `RateLimitProperties`와 규칙 항목에 Bean Validation을 적용하고 `@Validated`로 활성화한다. `rules` 필드 자체에도 `@Valid`를 명시해 중첩 검증이 실제로 실행되게 한다.
- 각 값에 구체적 상한(오버플로·과도한 캐시 크기 방지):
  - `maxKeys`(→ Caffeine `maximumSize`): `@Positive @Max(1_000_000)`
  - 규칙 `capacity`: `@Positive @Max(100_000)`
  - 규칙 `refillPeriodSeconds`: `@Positive @Max(86_400)`(하루)
  - **규칙 `id`·`pattern`**: `@NotBlank`
  - **규칙 `methods`(v5, codex 지적 5)**: `@NotEmpty` — 검증이 허용 값 목록만 확인하면 빈 집합이 조용히 통과해 그 규칙이 완전히 무력화되는 fail-open 오설정을 놓친다.
- 추가로 `RateLimitConfigValidator`(Bean으로 명시 등록 — 쟁점 12)가 기동 시 검증하는 것:
  - `methods`는 `GET`/`HEAD`/`POST`/`PUT`/`PATCH`/`DELETE` 중에서만 허용
  - 규칙 `id` 중복 금지(쟁점 9)
  - **(v6 수정)** `enabled=true`인데 `rules`가 비어 있으면 **기동 실패**시킨다 — 빈 `methods`(규칙 하나만 무력화)는 기동 실패시키면서 모든 방어를 무력화하는 빈 `rules`는 경고만 남기고 허용하는 것은 일관성이 없다는 codex 5라운드 지적을 반영. 명시적 비활성화가 필요하면 `enabled=false`를 쓰면 되므로, `enabled=true`인 상태의 빈 `rules`는 항상 설정 실수로 취급한다.
  - 위반 시 `IllegalStateException`으로 컨텍스트 기동 실패

### 쟁점 12 — Spring Bean 배선 전체 명시

- `CmsApplication.java:11` 실측: `@SpringBootApplication` + `@EnableConfigurationProperties(FileStorageProperties.class)`뿐이고 `@ConfigurationPropertiesScan`은 쓰지 않는다 — `@ConfigurationProperties`만 붙인 새 클래스는 자동으로 Bean이 되지 않는다.
- **채택**: `RateLimitFilterConfig`에 다음을 전부 명시(codex 4라운드에서 "대체로 완결됐다"고 확인된 구조 유지):
  ```java
  @Configuration
  @EnableConfigurationProperties(RateLimitProperties.class)
  public class RateLimitFilterConfig {
      @Bean Ticker ticker() { return new SystemTicker(); }
      @Bean RateLimitConfigValidator rateLimitConfigValidator(RateLimitProperties props) { ... }
      @Bean TokenBucketRateLimiter tokenBucketRateLimiter(RateLimitProperties props, Ticker ticker) { ... }
      @Bean RateLimitFilter rateLimitFilter(RateLimitProperties props, TokenBucketRateLimiter limiter) { ... }
      @Bean FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
          FilterRegistrationBean<RateLimitFilter> reg = new FilterRegistrationBean<>(filter);
          reg.setEnabled(false); // 서블릿 컨테이너 자동 등록 차단 — SecurityConfig가 addFilterAfter(CsrfFilter.class)로만 등록(v10)
          return reg;
      }
  }
  ```
- `RateLimitConfigValidator`는 생성자에서(또는 `@PostConstruct`) 검증을 수행해 Bean 생성 자체가 실패하도록 만든다.
- 기존 `@WebMvcTest` 슬라이스 테스트들(예: `PublicNoticeControllerTest`, `SecurityConfigTest`)은 새 `RateLimitFilter`/`RateLimitFilterConfig` 의존성을 컨텍스트에 추가로 `@Import`하거나 목으로 대체해야 컴파일·기동이 유지된다 — 구현 단계에서 영향받는 테스트 목록을 먼저 확인한다.

## 변경 파일

### 신규 의존성

- `build.gradle` — `implementation 'com.github.ben-manes.caffeine:caffeine'`. 버전은 Spring Boot BOM이 관리 — 구현 착수 시 `./gradlew dependencies --configuration compileClasspath | grep caffeine`로 실측 확인하고, 없으면 명시 버전을 지정한다.

### 신규 — `src/main/java/com/cms/config/ratelimit/`

- `RateLimitProperties.java` — `@ConfigurationProperties(prefix = "cms.rate-limit")` + `@Validated`. `enabled`, `maxKeys`(`@Positive @Max(1_000_000)`), `rules`(`@Valid List<RuleConfig>` — `id`(`@NotBlank`), `pattern`(`@NotBlank`), `methods`(`Set<String>`, `@NotEmpty`), `capacity`(`@Positive @Max(100_000)`), `refillPeriodSeconds`(`@Positive @Max(86_400)`)).
- `RateLimitRule.java` — 해석된 규칙(record: `String id`, `PathPatternRequestMatcher matcher`, `Set<HttpMethod> methods`, `int capacity`, `Duration refillPeriod`).
- `RateLimitConfigValidator.java` — 기동 시 규칙 `id` 중복·`methods` 값 허용 목록 검증, 위반 시 `IllegalStateException`.
- `Ticker.java` / `SystemTicker.java` — 경과시간 측정 전용 단조시계(`System.nanoTime()` 래핑).
- `BucketKey.java` — 구조화된 캐시 키(record, 쟁점 9).
- `Bucket.java` — 생성자 `Bucket(int capacity, long refillPeriodNanos)`(v6). 가변 상태(`tokens`, `lastRefillNanos`) + 불변 `refillPeriodNanos` + `synchronized tryConsume(Ticker)`(쟁점 3b). 오버플로 방지 리필 산술.
- `TokenBucketRateLimiter.java` — Caffeine `Cache<BucketKey, Bucket>` 래퍼, `maximumSize` + 버킷별 `Expiry`(v6, 쟁점 3). `tryConsume(String remoteAddr, RateLimitRule rule)` → `RateLimitDecision(boolean allowed, long retryAfterSeconds)`.
- `RateLimitFilter.java` — `OncePerRequestFilter`(`@Component` 미부착 — 쟁점 10). `enabled=false`면 즉시 통과. 규칙 순회 매칭(경로 + `methods.contains()`, 쟁점 1) → 매치 시 `getRemoteAddr()` 키로 소비 시도 → 거절 시 429 작성(쟁점 5) 후 체인 중단.
- `RateLimitFilterConfig.java` — 쟁점 12의 전체 Bean 배선.

### 수정

- `src/main/java/com/cms/config/SecurityConfig.java` — `addFilterAfter(rateLimitFilter, CsrfFilter.class)` 추가(v10 — CSRF를 통과한 요청만 quota 소비, 쟁점 4). `authorizeHttpRequests` 인가 규칙은 변경하지 않는다.
- `src/main/java/com/cms/error/CustomErrorController.java` — `statusCode == 429` 분기 추가(쟁점 6).
- `src/main/resources/application.yml` — `cms.rate-limit` 기본값 블록(`id`·`methods: [GET, HEAD]` 등 포함, 아래 표).
- `build.gradle` — `tasks.named('test')`에 `environment 'CMS_RATE_LIMIT_ENABLED', 'false'` 추가. **누락 시 같은 IP로 반복 요청하는 기존 MockMvc 테스트 다수가 429로 회귀 — 최대 리스크.** IDE 개별 실행은 이 env가 적용되지 않는 한계가 있음을 `docs/troubleshooting.md`에 기록.
- `CLAUDE.md` — "보안 규칙" 섹션에 레이트리밋 정책 표 추가("버스트 상한 N + 평균 N/기간" 표기, fail-open 명시) + Caffeine 신규 의존성 도입 사실 기록.
- `docs/deployment.md` — 운영 튜닝 + 다중 인스턴스 한도 배가 한계 + nginx 신뢰 프록시 전제 명시 + fail-open 잔여 위험 기록.
- `docs/troubleshooting.md` — IDE 개별 테스트 실행 시 429 실패 가능성 기록.

### 신규 — 템플릿

- `src/main/resources/templates/error/429.html` — `error/404.html` 구조 미러.

### 수정 — 기존 테스트 (v5 신설 항목)

- 기존 `@WebMvcTest` 슬라이스(`SecurityConfigTest`, `PublicNoticeControllerTest`, `ApiSecurityConfigTest` 등 `SecurityConfig`가 관여하는 슬라이스 전반)가 새 `RateLimitFilterConfig`/`RateLimitFilter` 의존성 때문에 컨텍스트 로드에 실패하지 않는지 구현 착수 시 먼저 확인하고, 필요하면 `@Import`나 목 Bean으로 보강한다(쟁점 12).

### 신규 — 테스트

- `TokenBucketRateLimiterTest`(단위, `FakeTicker` — **Caffeine builder에도 동일 인스턴스 연결**, v7) — **신규 버킷은 시간 전진 없이 정확히 `capacity`회 허용, 그다음은 거절**(v9, `tokens` 초기화 회귀 방지) / 소진 후 거절 / 부분 토큰 보존 리필 / **장기 유휴(리필 주기의 여러 배) 후에도 정확히 `capacity`개만 허용**(v7 치명적 버그 회귀 방지, 쟁점 3b) / **`FakeTicker`를 충분히 큰 음수값에서 시작해도 정상 동작**(v8, 초기화 lazy 처리 회귀 방지) / 규칙 간 키 독립성(쟁점 9) / **같은 키 동시 소비 시 정확히 `capacity`개만 허용**(멀티스레드 테스트, 쟁점 3b) / **만료 경계 — v9: 두 케이스를 서로 다른 캐시/fixture로 완전히 분리**(하나의 fixture에서 `get()`/`getIfPresent()`로 "유지됨"을 조회하는 행위 자체가 `expireAfterRead()`를 호출해 만료 시각을 다시 연장시켜 테스트가 스스로를 오염시키므로 — codex 8라운드 지적): (a) 생성 후 `refillPeriod - 1ns` 시점에 별도 캐시에서 유지 확인, (b) 별도로 새로 생성한 캐시에서 정확히 `refillPeriod` 경과 시 논리적 만료 + `cleanUp()` 후 물리 제거 + 다음 접근 시 신규 `Bucket` 생성 확인 / `maximumSize` 도달 시에도 예외 없이 정상 동작(evict된 뒤 재요청 시 새 버킷을 정상적으로 받음 — fail-open 시나리오의 "크래시하지 않는다"만 확인, 완전한 정확성은 검증 대상이 아님을 테스트 주석에 명시).
- `RateLimitFilterTest`(`MockMvc`) — 규칙 매칭 경로 초과 시 차단(GET·HEAD 각각), 규칙 밖 경로 무제한, `/actuator/health` 무제한, `Retry-After` 헤더 검증, 규칙 우선순위(쟁점 8).
- `RateLimitResponseTest`(`MockMvc`) — `/admin/api/**` 초과 시 `code=RATE_LIMITED` JSON 429 본문 검증. `/notices` 초과 시에는 `sendError(429)` 호출까지만 MockMvc로 확인하고, 실제 `error/429.html` 렌더링은 Playwright로 검증(쟁점 10 v5).
- `RateLimitPropertiesValidationTest` — 잘못된 설정값(음수·상한 초과·빈 id·중복 id·**빈 `methods`** 등) 각각에 대해 기동 실패 확인(쟁점 11, 12).
- `RateLimitFilterOrderTest`(`MockMvc`, 쟁점 10 v5, 순서는 v10에서 정정) — 필터가 `SecurityFilterChain` 안에서 **`CsrfFilter` 다음에** 실행되고 요청당 정확히 한 번만 실행됨을 확인(컨테이너 이중 등록 자체의 최종 확인은 `RateLimitFilterContainerRegistrationTest`로 이관 — 쟁점 10 v9).
- `RateLimitCsrfOrderingTest`(신규, `MockMvc`, v10, 쟁점 4) — 같은 IP에서 CSRF 토큰 없는(또는 잘못된) `POST /admin/api/password-reset-requests`를 `capacity`(5)회보다 많이 반복해도 레이트리밋 버킷이 소진되지 않음(**(v11 정정)** 익명 요청이라 전부 JSON **401**`code=UNAUTHORIZED`로 CSRF 단계에서 거부됨 — `PasswordResetControllerTest.java:181`의 기존 계약과 일치, 429가 아님) 확인 → 그 뒤 유효한 CSRF 토큰을 포함한 요청이 최초 `capacity`회 정상 통과하고 그다음 429가 됨을 확인.
- 기존 `SecurityConfigTest`류에 회귀 스모크 케이스 1~2개 추가.

## 기본 한도 (`application.yml`, 환경변수 오버라이드 가능)

> **표기 원칙**: "N회/기간"은 "**버스트 상한 N + 평균 N/기간**"을 뜻한다(쟁점 2). **fail-open 명시**: 캐시(`maximumSize`, 기본 10,000)가 포화되는 극단적 상황에서는 개별 IP의 정확한 누적치 보장이 흐트러질 수 있다(쟁점 3) — 이 표는 정상 상황(캐시 포화 이전)의 정책을 기술한다.

| id | 경로 | methods | 한도(버스트 + 평균) | 근거 |
|------|-------------|---------|------|------|
| `attachment` | `/notices/*/attachments/*` | `GET, HEAD` | 20회 버스트, 평균 20회/60초 | 건당 최대 10MB 로딩 — 가장 비싼 경로. HEAD도 GET과 동일 비용(쟁점 1) |
| `public-notice` | `/notices`, `/notices/**` | `GET, HEAD` | 120회 버스트, 평균 120회/60초 | 목록·상세 정상 열람을 방해하지 않는 선 |
| `reset-request` | `/admin/api/password-reset-requests` | `POST` | 5회 버스트, 평균 5회/3600초 | 메일 발송 트리거 — 계정별 60초 쿨다운의 IP 축 보강 |
| `reset-confirm` | `/admin/api/password-resets` | `POST` | 20회 버스트, 평균 20회/3600초 | 토큰 추측 시도율 억제(v6 표현 정정 — "완전 차단" 아님) |

`rules` 선언 순서 = `attachment` → `public-notice` → `reset-request` → `reset-confirm`(쟁점 8). Caffeine `maximumSize`(=`maxKeys`) 기본 10,000 + 버킷별 `Expiry`(자기 규칙의 `refillPeriod`, v6 — 쟁점 3).

## 범위 밖 (명시)

- 첨부 다운로드 스트리밍 전환.
- 로그인 레이트리밋 — 별도 협의 대상.
- 기존 IP 추출 로직 4곳 통합.
- 다중 인스턴스 간 레이트리밋 상태 공유(Redis 등) — **(v12 정정, 11라운드에서 잔존 확인)** "유일한 완전한 해법"이 아니라 "다중 인스턴스 간 상태 공유 및 무제한 신규 키 수용·IP별 완벽한 정확성을 함께 요구할 경우에 필요한 외부 원자적 저장소"(쟁점 3의 v6 정정과 동일 표현) — 이번 범위·인프라 제약(단일 인스턴스 전제)을 벗어나 기각.
- WAF·nginx `limit_req`.
- nginx 신뢰 프록시 체인 구성 자체 — 로드맵 "실배포 인프라" 항목으로 이월.
- Caffeine의 eviction·expiration이 in-flight 참조와 겹치는 상황에서의 개별 IP 정확성 완전 보장(fail-open으로 명시 수용, v9에서 범위를 "캐시 포화 시"에서 "eviction 또는 expiration 전반"으로 확대 — 쟁점 3).
- v3 스타일의 overflow 안전망(CAS admission·폴백 샤딩) 재도입 — 3라운드에 걸쳐 정확한 자체 구현이 실패했음이 증명됨(재검토 후 기각 유지).

## 완료 기준

- [ ] `./gradlew test` 전체 통과(레이트리밋 비활성화 상태에서 기존 테스트 429 회귀 없음, 기존 `@WebMvcTest` 슬라이스가 새 필터 의존성으로 깨지지 않음)
- [ ] 신규 테스트(단위·동시성·필터·응답 포맷·설정 검증) 전부 통과
- [ ] 첨부 다운로드 **GET 21회** 연속 요청 시 21번째 429 + `Retry-After`, **Playwright로 `error/429.html` 실제 렌더링 확인**
- [ ] 첨부 다운로드 **HEAD 21회** 연속 요청에도 동일하게 21번째 429(curl)
- [ ] 1분 경과 후 재요청 시 200으로 복구
- [ ] `POST /admin/api/password-reset-requests` **유효한 세션 쿠키·CSRF 토큰을 포함해** 6회째 `code=RATE_LIMITED` JSON 429(`curl -i` — v10/v12: CSRF 토큰 없이 반복하면 매번 JSON **401**(`code=UNAUTHORIZED`)로 거부될 뿐 레이트리밋이 작동하는지는 검증되지 않으므로, 반드시 유효한 토큰으로 확인)
- [ ] **(v10 신규, v11 응답코드 정정)** 같은 IP에서 CSRF 토큰 없는 `POST /admin/api/password-reset-requests`를 5회보다 많이 반복해도 레이트리밋 버킷이 소진되지 않음(전부 JSON **401** `code=UNAUTHORIZED` — `SecurityConfig`가 익명 사용자의 CSRF 실패를 401로 변환하는 기존 계약, 429 아님) — 그 뒤 유효한 CSRF 토큰 요청이 최초 5회 정상 통과함을 확인(쟁점 4 실증, 교차 사이트 quota 고갈 방지 회귀 테스트)
- [ ] `X-Forwarded-For`를 매 요청 다른 값으로 바꿔도 레이트리밋 우회 안 됨
- [ ] `/actuator/health` 100회 연속 호출에도 200 유지
- [ ] 같은 IP로 규칙 A(첨부)를 소진시켜도 규칙 B(공개 목록)는 별도로 정상 동작(쟁점 9 실증)
- [ ] 동시 다발 요청(병렬 curl)으로 정상 범위(캐시 미포화 상태)에서 `capacity`를 초과하는 허용이 관측되지 않음(쟁점 3b 실증)
- [ ] **(v6 계산 수정)** `reset-request`(5회/3600초, 12분당 1토큰 리필) 소진 직후 재요청 시 429, **10분(600초) 경과 시점에도 여전히 429**, **12분(720초) 경과 시점에는 정확히 1회만 200이고 그 직후 요청은 다시 429** — `FakeTicker` 단위 테스트로 주로 검증(실제 12분 대기는 선택적 실기 확인). v5의 "15분 뒤에도 429"는 계산 오류였다(15분 경과 시 1.25토큰 리필되어 실제로는 200이 정상).
- [ ] **(v9 정정)** `RateLimitFilterContainerRegistrationTest`(`@SpringBootTest(webEnvironment = RANDOM_PORT)`)가 `ServletContext#getFilterRegistrations()`로 `RateLimitFilter`가 컨테이너 필터로 등록되지 않았음을 직접 확인 — Playwright는 실제 ERROR 재디스패치·`error/429.html` 렌더링만 확인(Playwright 관찰만으로는 컨테이너 이중 등록을 증명할 수 없음, codex 8라운드 지적)
- [ ] `RateLimitConfigValidator`·`TokenBucketRateLimiter`·`Ticker`가 전부 Bean으로 등록되어 실제로 동작함(쟁점 12)
- [ ] 잘못된 `cms.rate-limit` 설정(예: `capacity: -1`, 빈 `methods`)으로는 기동이 실패함(쟁점 11)
- [ ] 관리자 로그인 → 대시보드·공지 관리·회원 관리 골든 패스 회귀 없음(Playwright)
- [ ] `/deploy-check` 재실행 시 신규 no-ship 항목 없음

## 착수 게이트

**신규 의존성 1건(Caffeine) — v4에서 사용자 재승인 완료(2026-08-12).** 스키마 변경 없음 / `authorizeHttpRequests` 인가 규칙 변경 없음. **fail-open 잔여 위험을 사용자가 명시적으로 수용(2026-08-12, v5)** — 구현 단계에서 이 결정을 코드 주석·`docs/deployment.md`에 명확히 남긴다. `SecurityConfig`에 필터가 추가되므로 구현 단계에서 필터 순서·이중 등록·버킷 동시성(쟁점 3b)·Bean 배선 완결성(쟁점 12)을 집중 검증하고, 기존 `@WebMvcTest` 슬라이스 영향 범위를 먼저 확인한다.

## 구현·검증 결과 (2026-08-14, `feat/public-endpoint-rate-limit` 브랜치)

v13(12라운드 리뷰 종료, 사용자 결정으로 13라운드 생략) 계획대로 구현 완료. `Caffeine.newBuilder().ticker().maximumSize().expireAfter(Expiry)` + 버킷별 `refillPeriodNanos` 인지 + lazy 초기화 + `addFilterAfter(CsrfFilter.class)` 전부 계획 그대로 반영됐다.

### 구현 파일

- 신규 `src/main/java/com/cms/config/ratelimit/`: `Ticker`·`SystemTicker`·`BucketKey`·`RateLimitDecision`·`Bucket`(package-private, 토큰 버킷 알고리즘)·`RateLimitRule`·`RateLimitProperties`·`RateLimitConfigValidator`·`TokenBucketRateLimiter`(Caffeine 래퍼)·`RateLimitFilter`·`RateLimitFilterConfig`(Bean 배선 전부 명시).
- 수정: `SecurityConfig.java`(`addFilterAfter(rateLimitFilter, CsrfFilter.class)`), `CustomErrorController.java`(429 분기), `application.yml`(`cms.rate-limit.*`), `build.gradle`(Caffeine 의존성 + test 태스크 `CMS_RATE_LIMIT_ENABLED=false`).
- 신규 템플릿: `error/429.html`.
- 신규 테스트: `TokenBucketRateLimiterTest`(단위, `FakeTicker`) — 신규 버킷 초기 tokens=capacity, 장기 유휴 크레딧 미누적, 음수 `nanoTime` 안전, 규칙 간 독립성, 동시 소비 정확히 capacity개, 만료 경계(분리 fixture), `maximumSize` 도달 시 무예외. `RateLimitFilterTest`·`RateLimitResponseTest`(MockMvc, `@TestPropertySource`로 활성화) — 규칙 우선순위, HEAD 우회 방지, `/actuator/health` 무제한, JSON/HTML 429 분기. `RateLimitPropertiesValidationTest`(`ApplicationContextRunner`) — 설정 검증 7케이스. `RateLimitFilterContainerRegistrationTest`(`@SpringBootTest RANDOM_PORT`) — 컨테이너 이중 등록 안 됨. `PasswordResetControllerTest`에 CSRF-레이트리밋 순서 테스트 2건 추가(운영 설정 그대로 사용).
- 기존 `SecurityConfigTest`·`ApiSecurityConfigTest`·`PasswordResetControllerTest`에 `RateLimitFilterConfig.class` `@Import` 추가(필터 체인 의존성 보강, 쟁점 12).

### 테스트 결과

`./gradlew test --rerun` 전체 656개 테스트 통과(실패 0, 에러 0, 스킵 1 — 기존 스킵과 무관). 레이트리밋 비활성화 상태(`CMS_RATE_LIMIT_ENABLED=false`)에서 기존 테스트 429 회귀 없음.

### 실기 검증 (Playwright + curl, `bootRun` 포트 18080, 실측 완료 기준 대조)

- [x] 첨부 다운로드 GET 반복 요청 시 정확한 경계(capacity)에서 429 + `Retry-After` 헤더 확인(curl), **Playwright로 `error/429.html` 실제 렌더링 확인**(제목 "429 - 요청이 너무 많습니다", 오류 발생 시간 표시)
- [x] HEAD 요청도 GET과 동일 버킷 소비(curl로 확인 — 우회 안 됨)
- [x] `POST /admin/api/password-reset-requests` 운영 설정(capacity=5) 그대로 5회 통과 후 6회째 JSON 429(`code=RATE_LIMITED`) — curl 실측
- [x] 서로 다른 `X-Forwarded-For`로 25회 연속 요청해도 우회 안 됨(`getRemoteAddr()` 고정 실증)
- [x] `/actuator/health`는 첨부 규칙이 429인 상태에서도 15회 연속 200 유지
- [x] 같은 IP로 첨부 규칙을 소진시켜도 공개 목록(`/notices`, 별도 규칙)은 독립적으로 정상 200
- [x] 컨테이너 필터 이중 등록 안 됨 — `RateLimitFilterContainerRegistrationTest`(`ServletContext#getFilterRegistrations()`) 통과 + `bootRun` 로그에서 `"Filter rateLimitFilterRegistration was not registered (disabled)"` 직접 확인
- [x] 관리자 로그인(`admin`/`1234`) → 대시보드(방문자 통계·차트 정상) → 공지사항 관리(목록·검색 정상) → 회원 관리(관리자 3명 목록 정상) 골든 패스 회귀 없음

**검증 중 발견한 비자명한 이슈 3건**은 `docs/troubleshooting.md`에 기록:
1. `@WebMvcTest` 슬라이스에서 여러 테스트 메서드가 `TokenBucketRateLimiter` 싱글턴을 공유해 실행 순서에 따라 실패 — `RequestPostProcessor`로 테스트별 IP 격리.
2. `build.gradle`의 전역 `CMS_RATE_LIMIT_ENABLED=false`가 레이트리밋을 검증하려는 슬라이스에도 적용돼 필터가 항상 통과만 함 — `@TestPropertySource`로 개별 오버라이드 필요.
3. (실기 검증 중, 문서 미기록 — 재현 조건이 특정적) `localhost`가 IPv6(`::1`)로, `127.0.0.1`이 IPv4로 resolve되어 curl/Playwright 검증 시 서로 다른 버킷을 참조하는 혼란이 있었음(레이트리밋 코드 자체의 결함 아님, `getRemoteAddr()`는 두 경우 모두 정확한 실제 peer 주소를 반환) — 실기 검증 시 하나의 호스트 표기로 통일해야 함.
4. Windows 동적 포트 예약 범위(Hyper-V/WSL)가 8080을 포함해 `docker-compose`·로컬 `bootRun` 둘 다 최초 시도에서 포트 바인딩 실패 — `SERVER_PORT` 환경변수로 임시 포트 사용해 우회(기존 함정과 별개 증상이라 이번엔 코드 변경 없이 검증 환경만 조정, 프로젝트 코드에 영향 없음).

### 남은 이슈

없음 — 완료 기준 전 항목(1분 경과 후 200 복구·`reset-request` 10분 이상 생존은 `TokenBucketRateLimiterTest`의 `FakeTicker` 기반 단위 테스트로 결정적 검증 완료, 실기에서는 시간 관계상 생략) 충족.

### 다음 단계

`/code-review-loop`로 코드 리뷰 게이트를 거친 뒤 `/commitPR`로 커밋·PR 생성 예정. 로드맵(`adversarial-review/project-direction-roadmap.md`)의 "후속 과제 — ② 공개 첨부 다운로드 완료 시 기록"과 "선정에서 탈락한 후보"의 관련 항목은 `/updateRoadmap`으로 완료 반영 필요.
