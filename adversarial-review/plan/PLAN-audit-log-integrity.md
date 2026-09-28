# PLAN — 감사 로그 신뢰성 강화: IP 위조 차단 + 커밋 순서 보장

> 작성일: 2026-09-28
> 로드맵 근거: `adversarial-review/project-direction-roadmap.md` "실행 로드맵 — Top 5 (2026-09-05 선정)" ③ (감사 H-03·M-01)
> 감사 원본: `docs/CMS-technical-audit-2026-09-05.md` H-03(감사 로그 IP 위조 가능)·M-01(REQUIRES_NEW 커밋 순서)

## 개정 이력

- v1 (2026-09-28): 최초 작성(정찰·설계 결과). `AdminActionLogAspect`·`AdminActionLogService`·`VisitLoggingAuthenticationSuccessHandler`·`AdminActionTypeSyncTest`·`AdminSessionRevokeListener`(기존 AFTER_COMMIT 관례) 실측. `/plan-review-loop` 리뷰 대상으로 제출.
- v2 (2026-09-28, codex 리뷰 1차 반영 — needs-attention, 5개 지적 전부 수용):
  - **수용(높음1, IP 위조 경로 누락)**: `LockingAuthenticationFailureHandler.extractClientIp()`가 `AdminAccountAutoLockListener.onAutoLock()` → `AdminActionLogService.log()` 경로로 **동일한 `admin_action_log` 테이블**에 `ACCOUNT_AUTO_LOCK` 항목의 `requestIp`를 기록한다는 지적 — 코드 대조로 확인(`AdminAccountAutoLockListener.java` 직접 열람). 이 파일을 고치지 않으면 이번 작업의 목표(감사 IP 위조 차단) 자체가 절반만 달성된다. **추가로 직접 재확인한 사실**: `X-FORWARDED-FOR: ,` 같은 값은 `",".split(",")`가 빈 배열을 반환해 `ips[ips.length - 1]`이 `ArrayIndexOutOfBoundsException`을 던지고, `LockingAuthenticationFailureHandler.tryRecordFailure()`의 try-catch가 이 예외를 삼키면서 **`loginFailureService.recordFailure()` 호출 자체를 건너뛴다** — 즉 이 헤더 하나로 "연속 5회 실패 시 자동 잠금" 무차별 대입 방어를 매 요청마다 무력화할 수 있는 별도의 실질 보안 결함을 코드로 확인했다. 변경 대상에 추가(아래 결정 2·파일 목록 갱신).
  - **수용(높음1 부속, PasswordResetController 부분)**: `PasswordResetController.extractClientIp()`도 IP 정책 영향 조사에서 누락됐다는 지적 — 타당. 다만 이 값은 `PasswordResetService.requestReset()`에서 `log.info()` 한 줄에만 쓰이고 어떤 테이블에도 저장되지 않음을 코드로 확인(H-03 감사 대상 `admin_action_log`와 무관, 기존 주석도 "위조 가능한 참고 로그"로 명시). 그러나 같은 파싱 버그(`X-FORWARDED-FOR: ,`)가 이 컨트롤러에서는 try-catch 없이 그대로 전파되어, "이메일 존재 여부와 무관하게 항상 200 반환"이라는 계정 열거 방지 계약을 깨고 500을 반환하게 만든다는 것을 코드로 확인(`requestReset()` 메서드에 try-catch 없음) — 감사 정합성과는 별개 근거로 수용, 같은 근본 원인이라 함께 수정.
  - **수용(높음2, `@Order`는 참여 트랜잭션의 실제 커밋까지 보장하지 않음)**: `@AdminActionLogged`가 붙은 메서드가 이미 열린 외부 트랜잭션에 `REQUIRED`로 참여하는 경우, 그 메서드의 반환은 물리 커밋과 무관하므로 `@Order`로 순서를 고정해도 "반환 = 커밋 완료"가 성립하지 않는다는 지적 — Spring의 트랜잭션 참여·전파 계약상 타당하다. **직접 재확인**: 현재 이 애노테이션이 붙은 4개 프로덕션 메서드(`AdminMemberService.createAdmin/updateAdminMember/changeMyPassword`·`MenuService.createMenu/updateMenu/deactivateMenu`·`NoticeService.createNotice/updateNotice/deleteNotice`·`NoticeAttachmentService.upload/delete`) 전부가 대응하는 Controller 메서드에서 직접 호출되며, 서로 다른 `@Transactional` 메서드 내부에서 체이닝 호출되는 사례는 없음을 grep+코드 열람으로 확인 — **오늘 기준으로는 전부 최상위 트랜잭션 진입점**이다. 이 전제를 계획에 명시적으로 못박고, 중첩 참여 시 보장이 깨진다는 사실을 별도 통합 테스트로 실증한다(해결이 아니라 명시적으로 테스트된 한계로 남김 — codex도 "업무 메서드를 REQUIRES_NEW로 바꾸는 것은 부적절"이라 명시). 완료 기준 문구를 이 범위로 좁힌다(아래 결정 5 신설·완료 기준 수정).
  - **부분 수용(중간3, 정상 반환/커밋·롤백이 1:1이 아닌 반례 3종)**: (a) `setRollbackOnly()` 후 정상 반환 — **직접 재확인**: 코드베이스 전체에 `setRollbackOnly()` 호출 0건(grep 확인), 현재는 발생 불가능한 경로. (b) 직접 등록한 `afterCommit()`에서 예외 전파 — **직접 재확인**: `NoticeAttachmentService.registerFileDeleteAfterCommit()`의 `afterCommit()` 콜백은 `try-catch`로 예외를 삼키고 로그만 남길 뿐 전파하지 않음(코드 확인) — codex도 "당장 재현된다고 주장하지는 않는다"고 인정한 항목. (c) 물리 커밋 중 통신 장애로 완료 상태가 `UNKNOWN` — 이 모호성은 `AdminActionLogService.log()`의 REQUIRES_NEW FAIL 기록 자체도 이미 갖고 있던 근원적 한계(분산 트랜잭션 없이는 원칙적으로 해결 불가)이며, 이번 변경이 새로 만들거나 악화시키지 않는다. 세 반례 모두 "리스크 / 범위 밖" 섹션에 근거와 함께 명시(아래 리스크 섹션 갱신) — 코드 수정은 하지 않는다.
  - **수용(중간4, `getRemoteAddr()`도 상위 계층에서 재작성될 수 있음)**: `ForwardedHeaderFilter`/`RemoteIpValve`/`server.forward-headers-strategy`가 앞단에 있으면 `getRemoteAddr()` 자체가 이미 헤더 영향을 받을 수 있다는 지적 — 이론적으로 타당. **직접 재확인**: `src/main/resources`·`src/main/java` 전체에 `forward-headers-strategy`·`ForwardedHeaderFilter`·`RemoteIpValve`·`use-forward-headers` 전부 grep 0건 — 현재는 해당 없음. 이 확인 사실 자체를 완료 기준에 명시적으로 추가한다(아래 완료 기준 갱신).
  - **수용(낮음5, `@EnableMethodSecurity` 기본 순서 서술 오류)**: "메서드 보안 인터셉터 전체가 `LOWEST_PRECEDENCE - 100`"이라는 v1 서술이 부정확함을 인정 — 실제로는 `@PreFilter`=100, `@PreAuthorize`=200 등 작은 개별 순서값을 쓴다(Spring Security 문서 근거). `Ordered.LOWEST_PRECEDENCE - 1`이 이 값들보다 확실히 안쪽(큰 값)이라는 결론 자체는 영향 없어 `@Order` 선택은 유지 — 정찰 섹션의 서술만 정정.
  - **설계 변경(파생, v1 결정 2 철회)**: v1에서 "공통 유틸 추출 기각, 각 파일에서 2줄씩 제거"로 결정했던 것을 철회한다 — 당시엔 중복이 2곳(`AdminActionLogAspect`·`VisitLoggingAuthenticationSuccessHandler`)뿐이라고 판단했으나 이번 리뷰로 실제로는 4곳(`LockingAuthenticationFailureHandler`·`PasswordResetController` 추가)임이 드러났고, 이 중 하나를 놓치면 이번 작업의 목적 자체가 무효화된다는 것이 실증됐다 — 이 정도 중복 규모·반복 발견 이력이면 공유 유틸 추출이 "무관한 리팩터링"이 아니라 이번 수정의 정확성을 보장하는 최소 수단이라고 재판단한다. `com.cms.common.web.ClientIpResolver` 정적 유틸로 4곳을 통일(아래 결정 2 재작성).
- v3 (2026-09-28, codex 리뷰 2차 반영 — needs-attention, 4개 지적 + 부속 3건 전부 수용):
  - **수용(중간1, 시나리오 C가 실제 서비스 회귀를 감지하지 못함)**: 테스트 전용 `probeService`만 호출하는 시나리오 C는 실제 `MenuService` 등이 향후 참여 호출로 바뀌어도 계속 통과하므로 "회귀 감지" 주장이 성립하지 않는다는 지적, "SUCCESS 기록 여부와 무관하게 관찰"은 너무 약하고 예상 결과(업무 행 롤백 + SUCCESS 감사 행 잔존)를 명시적으로 단언해야 한다는 지적 — 둘 다 타당. 시나리오 C를 "알려진 한계 재현 테스트"로 재명명하고 정확한 결과를 단언하도록 테스트 계획·리스크 섹션을 수정한다(아래 테스트 계획 5·완료 기준·리스크 섹션 갱신) — "향후 회귀 감지" 주장은 제거하고 "이 경계가 존재함을 고정 기록"으로 범위를 낮춘다.
  - **수용(중간2, grep 0건은 실행 환경 검증이 아님)**: 저장소 소스 검색 결과와 "실제 실행 환경(클라우드 플랫폼 자동 감지 등)에서도 비활성임을 확인했다"는 서로 다른 주장이라는 지적 — 타당(Spring Boot 3.5는 지원 클라우드 환경에서 `forward-headers-strategy` 기본값을 `NATIVE`로 자동 설정할 수 있음). 완료 기준의 표현을 "저장소 내 명시적 설정 없음(grep 확인)"으로 낮추고, 실행 환경 자체의 검증은 실제 ingress가 생기는 시점(로드맵 Gate H)의 범위로 명시적으로 이관한다(아래 정찰·완료 기준·리스크 섹션 갱신).
  - **수용(중간3, `ClientIpResolver`의 null 계약 불명확)**: 기존 `AdminActionLogAspect.getClientIp()`는 요청 컨텍스트가 없으면(비HTTP 호출 등) `null`을 반환하며 감사 저장을 계속하는데, `resolve()`의 정의가 "request 자체가 null인 경우"를 명시하지 않아 NPE로 감사 행 전체가 누락될 위험이 있다는 지적 — 타당. `ClientIpResolver.resolve(HttpServletRequest request)`의 계약을 `request == null → null 반환`, `request.getRemoteAddr() == null → null 반환`으로 명시하고, 요청 컨텍스트 없이도 성공/실패 감사 저장이 계속됨을 확인하는 기존 테스트를 유지하도록 테스트 계획에 추가한다(아래 결정 2·테스트 계획 갱신).
  - **수용(중간4, UNKNOWN 논거가 서로 다른 실패를 혼동)**: "감사 로그 저장 자체의 REQUIRES_NEW 커밋 불확실성"과 "업무 트랜잭션이 실제로는 커밋됐으나 응답 유실로 예외가 관측돼 FAIL이 잘못 기록되는 것"은 서로 다른 문제이며, 후자를 전자와 동일시해 범위 밖으로 뭉뚱그리면 안 된다는 지적, 업무 커밋과 감사 커밋 사이 프로세스 종료 시 감사 행이 아예 없을 수 있는 비원자성도 명시해야 한다는 지적 — 둘 다 타당. 보장 문구를 "최상위 진입점에서 정상 커밋 및 커밋 전 확정적 실패에 대한 순서·분류"로 좁히고, "커밋 후 응답 유실로 인한 오분류(FAIL이 실제 성공을 잘못 나타낼 수 있음, 반대 방향인 v1의 원 문제 — SUCCESS가 실제 실패를 잘못 나타내는 것 — 보다는 감사 신뢰성 관점에서 덜 위험하지만 별개의 잔여 위험)"와 "두 물리 커밋 사이 비원자성"을 리스크 섹션에 각각 별도 항목으로 명시한다(아래 결정 5·리스크 섹션 갱신).
  - **수용(부속1, `FileStorageTransactionSupport.java:53` 누락)**: `NoticeAttachmentService` 외에 `FileStorageTransactionSupport.deleteAfterCommit()`도 동일하게 `afterCommit()` 콜백에서 `RuntimeException`을 삼키고 전파하지 않는 패턴임을 코드로 확인(53~64행) — "afterCommit() 예외 미전파" 근거에 추가.
  - **수용(부속2, "유틸 한 파일만 고치면 다섯 곳 모두 반영"은 틀림)**: `RateLimitFilter.java:58`은 이미 `request.getRemoteAddr()`를 직접 사용하고 있어(헤더 신뢰 버그가 없음) 이번 `ClientIpResolver` 통일 대상이 애초에 아니라는 지적 — 코드로 확인, 타당. 리스크 섹션의 "다섯 곳" 서술을 "4곳(`ClientIpResolver` 통일 대상) + 레이트리밋은 이미 올바르므로 별도"로 정정한다.
  - **수용(부속3, "4개 프로덕션 메서드" 표현 오류)**: 실제로는 4개 서비스에 걸친 11개 메서드(`AdminMemberService` 3·`MenuService` 3·`NoticeService` 3·`NoticeAttachmentService` 2)라는 지적 — 정확한 개수로 문서 전체 표현을 정정한다.
  - **수용(문서 오류, 결정 3의 철회된 서술 잔존)**: v2 개정 이력에서는 "`LOWEST_PRECEDENCE - 100`" 서술 오류를 정정했다고 기록했으나 결정 3 본문에는 그 표현이 그대로 남아 있었다는 지적 — 실수 인정, 본문에서 삭제하고 "메서드 보안(100~수백 단위의 작은 순서값)"으로 교체.
  - **3차 리뷰 시도 — codex 사용량 한도 도달(2026-09-28)**: v3를 codex에 3차 제출했으나 "usage limit" 오류로 응답을 받지 못했다(1회 재시도도 동일 오류). `plan-review-loop` 상시 규칙에 따라 codex 없이 직접 재검토를 수행했다 — v2→v3 반영分(시나리오 C 재명명·정밀 assert, forward-headers 주장 범위 축소, `ClientIpResolver` null 계약, UNKNOWN 오분류·비원자성 분리, 사실 오류 정정 5건)이 계획 본문에 실제로 정확히 반영됐는지 전문 재대조로 확인 완료. **추가로 자체 발견**: `com.cms.admin.member/CLAUDE.md`가 이미 `LoginFailureService`·`PasswordResetService`의 동작 계약을 상세 기술하고 있어, 이번 IP 파싱 버그 수정도 이 문서에 반영해야 함(변경 파일 목록에 추가). 이 재검토는 codex 리뷰가 아니라 자체 점검이며, 새로운 3자 관점의 지적은 아니라는 점을 명시한다 — 사용자 판단에 따라 codex 가용 시점(안내된 재시도 시각 이후)에 3차 라운드를 추가로 받을 수도 있다.

## Context

`AdminActionLogAspect.getClientIp()`(95행)가 `X-FORWARDED-FOR`/`X-Real-IP` 헤더를 신뢰 프록시 검증 없이 우선 사용한다 — 이 프로젝트에는 현재 실제 리버스 프록시가 없으므로(로드맵 3단계 "후속 과제 — ① 실배포 인프라", Gate H NOT RUN 확인, 2026-09-28), 클라이언트가 이 헤더를 임의로 보내 감사 로그의 `requestIp`를 완전히 위조할 수 있다.

**같은 로직이 실제로는 4곳에 복제돼 있다(v1은 2곳만 확인 — codex 리뷰로 나머지 2곳 발견, 2026-09-28)**:
1. `AdminActionLogAspect.getClientIp()`(95행) — `admin_action_log`에 저장(H-03 원 대상).
2. `VisitLoggingAuthenticationSuccessHandler.extractClientIp()`(159행) — `visit_log`에 저장. 주석: "AdminActionLogAspect.getClientIp()와 동일 로직".
3. `LockingAuthenticationFailureHandler.extractClientIp()`(58행) — `recordFailure()` → `AdminAccountAutoLockEvent` → `AdminAccountAutoLockListener.onAutoLock()` → `AdminActionLogService.log()` 경로로 **`admin_action_log`에 `ACCOUNT_AUTO_LOCK` 항목**으로 저장된다(1과 **동일 테이블**) — 이 파일을 빠뜨리면 H-03이 절반만 해소된다.
4. `PasswordResetController.extractClientIp()`(60행) — `PasswordResetService.requestReset()`의 `log.info()` 한 줄에만 쓰이고 테이블에는 저장되지 않는다(H-03 대상 아님, 기존 주석도 "위조 가능한 참고 로그"로 명시).

3·4 둘 다 동일한 파싱 버그를 공유한다: `X-FORWARDED-FOR: ,`처럼 콤마만 있는 값은 `",".split(",")`가 빈 배열을 반환해 `ips[ips.length - 1]`이 `ArrayIndexOutOfBoundsException`을 던진다. **3(`LockingAuthenticationFailureHandler`)에서는 이 예외가 `tryRecordFailure()`의 try-catch에 잡혀 `loginFailureService.recordFailure()` 호출 자체를 건너뛰게 만든다** — 즉 이 헤더 하나로 "연속 5회 실패 시 자동 잠금" 무차별 대입 방어를 매 요청마다 무력화할 수 있다(로그인 정책 관련 별도 실질 보안 결함, 이번 리뷰에서 발견). **4(`PasswordResetController`)에서는 try-catch 없이 예외가 그대로 전파**되어, "이메일 존재 여부와 무관하게 항상 200 반환"이라는 계정 열거 방지 계약을 깨고 500을 반환한다.

또한 `AdminActionLogAspect`(성공 시 `@AfterReturning`, 실패 시 `@AfterThrowing`)는 `@Aspect` 순서를 지정하지 않아 기본값 `Ordered.LOWEST_PRECEDENCE`를 갖는다. `@Transactional`의 프록시 어드바이저(`BeanFactoryTransactionAttributeSourceAdvisor`)도 기본값이 동일한 `Ordered.LOWEST_PRECEDENCE`다. 두 어드바이저가 동일한 우선순위를 가지면 어느 쪽이 상대를 감싸는지가 Spring 내부 구현(등록 순서 등)에 의존하는 **비결정적 상태**다. 만약 감사 Aspect가 트랜잭션 어드바이저보다 안쪽에 위치하면, 대상 메서드가 정상 반환되는 즉시(= 원 트랜잭션이 아직 커밋되지 않은 시점에) `@AfterReturning`이 실행돼 `AdminActionLogService.log()`(REQUIRES_NEW)가 SUCCESS를 별도 트랜잭션으로 **먼저 커밋**해버린다. 이후 원 트랜잭션이 커밋 단계에서 실패(예: 지연된 제약 위반, flush 시점 예외)해도 이미 커밋된 SUCCESS 로그는 되돌릴 수 없다 — "감사 SUCCESS = 실제 성공"이라는 전제가 깨진다.

이 프로젝트에는 이미 커밋-후 처리를 위한 관례(`AdminSessionRevokeListener`, `@TransactionalEventListener(phase = AFTER_COMMIT)`)가 있으나, 이번 문제는 그 패턴이 아니라 **AOP 어드바이저 순서 자체를 명시적으로 고정**하는 방식으로 해결하는 것이 더 단순하고 안전하다(아래 핵심 설계 결정 2 참조).

## 스키마 · 인가 정책 영향

- **스키마 변경: 없음.**
- **인가 정책 변경: 없음.** `SecurityConfig.java`는 수정하지 않는다. 감사 로그 IP 판정 로직만 바꾼다 — 어떤 경로를 누가 접근할 수 있는지는 그대로다.
- **신규 의존성: 없음.**

## 정찰로 확인한 사실 (설계 근거)

- `AdminActionLogAspect.getClientIp()`(95~113행): `X-FORWARDED-FOR`(마지막 홉) → `X-Real-IP` → `request.getRemoteAddr()` 순으로 신뢰. 프록시 존재 여부와 무관하게 헤더를 우선한다.
- `VisitLoggingAuthenticationSuccessHandler.extractClientIp()`(159~172행): 완전히 동일한 로직이 복제돼 있고, 이미 `truncateIp()`(178~183행, `MAX_IP_LENGTH=45`)로 과도하게 긴 값에 대한 방어가 있다 — "조작된 헤더나 과도하게 긴 값이 들어와도 DataException으로 인한 무음 누락을 방지"라는 주석이 이미 이번 감사와 같은 문제의식을 담고 있다.
- `AdminActionLog.requestIp` 컬럼 길이는 45(`AdminActionLog.java:40`) — `VisitLog`와 동일 상한. `AdminActionLogAspect`에는 현재 길이 방어가 없어, 비정상적으로 긴 값이 들어오면 `adminActionLogService.log()`가 `DataException`을 던지고 Aspect의 try-catch가 **로그 행 전체**(IP뿐 아니라 성공/실패 사실 자체)를 조용히 삼킨다.
- root `CLAUDE.md`(레이트리밋 규칙)가 이미 동일한 결론을 내려놓았다 — "키는 `request.getRemoteAddr()` 고정(`X-Forwarded-For` 등은 위조 가능해 미사용)". `com.cms.config.ratelimit`는 이미 이 정책을 따르고 있어 수정 불필요.
- `AdminActionLogAspect`에 `@Order`가 없고, `@Transactional` 어드바이저(Spring Boot 자동 구성, `@EnableTransactionManagement` 미override)도 기본 `Ordered.LOWEST_PRECEDENCE` — 두 값이 동률이라 상대적 순서가 명세상 보장되지 않는다(직접 확인: 코드베이스 전체에 `@EnableTransactionManagement`·`AopAutoConfiguration` 관련 순서 override 없음, `grep` 결과 0건).
- `MethodSecurityConfig`는 `@EnableMethodSecurity`만 선언하고 순서를 override하지 않는다. **(v2 정정)** Spring Security의 메서드 보안 인터셉터는 "전체가 `LOWEST_PRECEDENCE - 100`"이 아니라 `@PreFilter`=100·`@PreAuthorize`=200 등 개별적으로 작은 순서값을 쓴다(codex 리뷰로 확인된 v1 서술 오류, Spring Security 6.5 공식 문서 근거) — 다만 `Ordered.LOWEST_PRECEDENCE - 1`이 이 작은 값들보다 확실히 안쪽(큰 값)이라는 결론에는 영향 없다. 현재 `@AdminActionLogged`가 붙은 Service 메서드(`AdminMemberService`·`NoticeService`·`MenuService`·`NoticeAttachmentService`)에는 같은 메서드에 `@PreAuthorize`가 공존하지 않음을 확인(grep 결과 교집합 없음) — 이번 변경이 메서드 보안 어드바이저와의 상대 순서에 영향을 주지 않도록, 메서드 보안 순서(100~수백 단위)보다는 확실히 안쪽(큰 값)이지만 `@Transactional`(`LOWEST_PRECEDENCE`)보다는 바깥쪽(작은 값)인 `Ordered.LOWEST_PRECEDENCE - 1`을 채택한다 — 기존에 어떤 것도 감싸지 않던 관계는 그대로 두고, `@Transactional`과의 관계만 결정론적으로 고정하는 최소 변경.
- **(v2 신규, v3에서 개수 정정 — codex 2차 리뷰 지적)** `@AdminActionLogged`가 붙은 **4개 서비스의 11개 메서드**(`AdminMemberService.createAdmin/updateAdminMember/changeMyPassword` 3개·`MenuService.createMenu/updateMenu/deactivateMenu` 3개·`NoticeService.createNotice/updateNotice/deleteNotice` 3개·`NoticeAttachmentService.upload/delete` 2개 — v2는 이를 "4개 프로덕션 메서드"로 잘못 표기했었다) 전부가 대응 Controller 메서드에서 **직접** 호출됨을 grep+코드 열람으로 확인 — 서로 다른 `@Transactional` 메서드 내부에서 체이닝 호출되는 사례는 없다. 즉 오늘 기준으로는 전부 **해당 HTTP 요청의 최상위 트랜잭션 진입점**이다. `@Order`로 얻는 "반환 시점 = 커밋 완료 시점" 보장은 이 전제가 성립할 때만 유효하다(codex 리뷰 지적 — 참여 트랜잭션 케이스는 반환이 물리 커밋과 무관).
- **(v2 신규, v3에서 근거 보강 — codex 2차 리뷰 지적)** `setRollbackOnly()` 호출은 코드베이스 전체에 0건(grep 확인) — codex가 지적한 "로컬 rollback-only 후 정상 반환" 반례는 현재 발생 불가능한 경로다. `afterCommit()` 콜백을 직접 등록하는 곳은 두 곳: `NoticeAttachmentService.registerFileDeleteAfterCommit()`(173~184행)과 `FileStorageTransactionSupport.deleteAfterCommit()`(53~64행) — **둘 다** `fileStorage.delete()`/`storage.delete()` 실패를 try-catch로 삼키고 로그만 남길 뿐 예외를 전파하지 않음을 코드로 확인(v2는 전자만 확인했었다 — codex 2차 리뷰로 후자 추가 확인). codex가 지적한 "직접 등록한 afterCommit()에서 예외 전파" 반례는 현재 이 두 콜백 어느 쪽에서도 재현되지 않는다.
- **(v2 신규, v3에서 주장 범위 축소 — codex 2차 리뷰 지적)** `src/main/resources`·`src/main/java` 전체에 `forward-headers-strategy`·`ForwardedHeaderFilter`·`RemoteIpValve`·`use-forward-headers` grep 결과 0건. **다만 이것은 "저장소 소스에 명시적 설정이 없다"는 사실일 뿐, "실제 실행 환경에서 전달 헤더 재작성이 비활성"이라는 검증은 아니다** — Spring Boot 3.5는 지원하는 클라우드 플랫폼을 자동 감지하면 `server.forward-headers-strategy`를 `NATIVE`로 기본 설정할 수 있고, 이는 소스 grep으로는 드러나지 않는다(codex 2차 리뷰 지적, Spring Boot 3.5 공식 문서 근거). 실행 환경 자체의 검증(실제 배포된 컨테이너를 통과하는 요청으로 확인)은 이번 계획 범위 밖 — 실제 ingress가 생기는 시점(로드맵 "후속 과제 — ① 실배포 인프라", Gate H)에 함께 검증한다. 이번 계획이 주장할 수 있는 것은 "저장소 코드 검색 기준으로는 발견되지 않았다"까지다.
- `AdminActionTypeSyncTest`(클래스패스 전체 `com.cms` 패키지를 `ClassPathScanningCandidateComponentProvider(true)`로 스캔, `@Component` 계열만 대상)가 `@AdminActionLogged`의 `actionType`이 `AdminActionTypes.ALL`에 있는지 검증한다 — 테스트용 프로브 서비스를 만들 때 `@Service`/`@Component`로 선언하면 이 스캔에 걸려 신규 `actionType` 문자열을 `AdminActionTypes`에 등록해야 하는 불필요한 프로덕션 코드 변경이 생긴다. **`@Component` 계열 애노테이션 없이 `@TestConfiguration`의 `@Bean` 메서드로만 등록**하면 스캔 대상에서 자연히 제외되고(Spring AOP 오토프록시는 스테레오타입과 무관하게 모든 빈 정의에 적용되므로 `@Transactional`·`@AdminActionLogged` 모두 정상 작동), 기존 `actionType`(`AdminActionTypes.MENU_UPDATE`)을 재사용하면 프로덕션 상수도 건드리지 않는다.
- `MenuConcurrencyIntegrationTest`가 이미 `@SpringBootTest(classes = CmsTestApplication.class)` + `MariaDbContainerSupport` + 실제 `MenuRepository`로 실 DB 왕복을 검증하는 선례 — 같은 인프라를 재사용해 커밋 순서 통합 테스트를 작성한다.

## 핵심 설계 결정

### 1. IP는 `request.getRemoteAddr()`만 신뢰한다 (신뢰 프록시 allowlist 도입 기각)

**선택지**:
- (A) 헤더 신뢰를 완전히 제거하고 `getRemoteAddr()`만 사용.
- (B) 신뢰할 프록시 IP를 설정으로 받아, 그 프록시가 보낸 요청에서만 `X-Forwarded-For`를 신뢰(예: Spring의 `ForwardedHeaderFilter` 또는 수동 allowlist).
- (C) 헤더 신뢰 여부를 프로퍼티로 토글 가능하게 만듦(추후 유연성 확보).

**결정: (A).** 이유: 이 프로젝트에는 아직 실제 리버스 프록시가 없다(`docker-compose.prod.yml`은 `127.0.0.1:8080` 루프백 바인딩까지만 다룸, Gate H NOT RUN, 2026-09-28 확인) — 지금 신뢰할 프록시가 존재하지 않는데 (B)의 allowlist를 만들면 실질적으로 "아무 프록시나 신뢰"와 다를 바 없거나(빈 allowlist는 무의미), 미리 만들어둔 설정이 실제 프록시 도입 시점에 재검토 없이 그대로 굳어질 위험이 있다. 이미 레이트리밋 기능이 동일한 딜레마에서 (A)와 같은 결론을 내렸다(CLAUDE.md 명시). (C)는 지금 쓰이지 않을 옵션을 미리 만드는 것이라 "요청받지 않은 설정 옵션을 미리 만들지 않는다"(CLAUDE.md 작업 방식 3번)에 위배된다. 실제 ingress가 생기면(로드맵 "후속 과제 — ① 실배포 인프라") 그 시점에 레이트리밋·감사 로그·방문 로그의 IP 해석 로직을 **한 번에** 재검토하는 편이 안전하다 — `forward-headers-strategy` 항목이 이미 그 시점에 함께 다루기로 로드맵에 기록돼 있다.

### 2. IP 추출 로직 4곳을 `com.cms.common.web.ClientIpResolver` 공유 유틸로 통일한다 (v2, 각 파일 개별 수정 기각)

**v1에서는** "공통 유틸 추출 기각, 각 파일에서 헤더 신뢰 분기 두 줄씩만 제거"로 결정했었다 — 당시 파악한 중복은 `AdminActionLogAspect`·`VisitLoggingAuthenticationSuccessHandler` 2곳뿐이었고, 두 메서드 모두 `private`이라 "재사용 불가"가 기존 주석의 명시적 근거였다.

**v2에서 철회한다.** codex 리뷰로 실제 중복이 4곳(`AdminActionLogAspect`·`VisitLoggingAuthenticationSuccessHandler`·`LockingAuthenticationFailureHandler`·`PasswordResetController`)임이 드러났고, 이 중 `LockingAuthenticationFailureHandler` 하나를 놓치면 이번 작업의 목적(감사 IP 위조 차단, H-03) 자체가 무효화된다는 것이 실제로 확인됐다 — "각자 독립적으로 존재해야 할 이유"가 애초에 없었고(전부 같은 3줄짜리 헤더 우선순위 로직), 4곳으로 늘어난 시점부터는 "각 파일에서 개별 수정"이 오히려 "다섯 번째 복제본을 놓칠 위험"을 키운다. 이번 리뷰 자체가 그 위험이 이미 현실화됐음을 보여준다(v1이 2곳만 찾았다).

**결정**: `com.cms.common.web.ClientIpResolver`(신규, `com.cms.common.storage`·`com.cms.common.api`·`com.cms.common.exception`과 같은 계층의 공통 유틸 패키지)에 `public static String resolve(HttpServletRequest request)` 하나만 두고, `request.getRemoteAddr()`을 45자로 절단해 반환한다(길이 상한은 아래 결정 4 참조 — `admin_action_log`·`visit_log` 양쪽 `request_ip` 컬럼이 이미 동일하게 45). 4개 호출부는 각자의 private `extractClientIp()`/`getClientIp()` 메서드를 삭제하고 `ClientIpResolver.resolve(request)` 호출로 교체한다. 테스트도 `ClientIpResolverTest` 한 곳에서 헤더 무시·길이 절단을 검증하고, 4개 호출부 테스트는 "위임이 실제로 이 유틸을 쓰는지"만 얕게 확인한다(중복 테스트 방지).

**(v3 추가) null 계약을 명시한다(codex 리뷰 지적)**: 기존 `AdminActionLogAspect.getClientIp()`는 `getCurrentRequest()`가 `null`을 반환하면(비HTTP 호출 등 요청 컨텍스트가 없는 경우) 그대로 `null`을 반환하며, 이때도 감사 저장 자체는 계속된다(`requestIp` 컬럼만 `null`). `ClientIpResolver.resolve()`가 이 계약을 깨고 `request`가 `null`일 때 NPE를 던지면, `AdminActionLogAspect`의 try-catch가 이를 삼켜 **로그 행 전체**(IP뿐 아니라 성공/실패 사실 자체)가 조용히 누락된다 — 원래 결함(H-03)보다 더 나쁜 회귀. 계약을 명시적으로 고정한다: `request == null → null 반환`, `request.getRemoteAddr() == null → null 반환`(정상 서블릿 컨테이너에서는 발생하지 않지만 방어적으로 처리), 그 외에는 45자 절단된 `getRemoteAddr()` 값을 반환한다. `AdminActionLogAspectTest`의 기존 "요청 컨텍스트 없음" 테스트 케이스(`RequestContextHolder`에 아무것도 설정하지 않은 상태에서 `logSuccess`/`logFailure` 호출 시 정상적으로 감사 저장이 계속되는지)를 유지해 이 계약을 회귀 감지한다.

### 3. `AdminActionLogAspect`에 `@Order(Ordered.LOWEST_PRECEDENCE - 1)`를 명시해 `@Transactional` 어드바이저보다 바깥에 위치시킨다 (이벤트 기반 재설계 기각)

**선택지**:
- (A) `@Order`로 AOP 어드바이저 순서를 명시적으로 고정 — 감사 Aspect가 `@Transactional` 프록시보다 바깥쪽(먼저 진입, 나중에 반환)에 위치하도록.
- (B) `AdminActionLogAspect`가 직접 로그를 쓰는 대신 이벤트(`AdminActionLoggedEvent`)를 발행하고, `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` 리스너가 실제 저장을 수행(기존 `AdminSessionRevokeListener` 패턴 재사용).
- (C) Aspect 내부에서 `TransactionSynchronizationManager.isSynchronizationActive()`를 확인해 활성 트랜잭션이 있으면 `registerSynchronization(...)`으로 `afterCommit()` 콜백에 로그 저장을 위임하고, 없으면 즉시 저장(폴백).

**결정: (A).** 이유:
- (B)의 함정: `@TransactionalEventListener`는 이벤트 발행 시점에 활성 트랜잭션이 없으면(기본 `fallbackExecution=false`) **이벤트를 조용히 버린다**. `@AdminActionLogged`가 항상 `@Transactional` 메서드에만 붙는다는 보장이 없고, 설령 지금은 그렇더라도 그 전제가 향후 조용히 깨지면 감사 로그 자체가 무음으로 사라진다 — 감사 로그 도메인에서 가장 피해야 할 실패 모드다. 또한 `AFTER_COMMIT` 페이즈는 트랜잭션이 롤백되면 아예 호출되지 않으므로, `@AfterThrowing`(FAIL 로그) 쪽에 이 패턴을 적용하려면 `AFTER_COMPLETION`으로 갈아타 커밋/롤백 분기를 또 처리해야 해 로직이 늘어난다. 지금 확인된 결함은 SUCCESS 경로 하나뿐이므로 FAIL 경로(이미 정상 동작 중인 즉시-REQUIRES_NEW 방식)까지 함께 재설계할 이유가 없다.
- (C)는 SUCCESS 경로의 결함을 고치지만, "커밋 직전 실패가 FAIL로 정확히 귀속되는지"까지는 보장하지 않는다 — Aspect가 여전히 트랜잭션 안쪽에 있다면 `@AfterReturning`이 먼저 실행되어 콜백을 등록한 뒤 일반 반환하고, 콜백은 실제 커밋 실패 시 `afterCompletion(ROLLBACK)`으로만 통지받을 뿐 FAIL 로그를 남기는 별도 코드가 필요하다 — (A)가 공짜로 주는 이점(커밋 실패가 자동으로 `@AfterThrowing`으로 전환됨)을 얻으려면 결국 (C) 안에서도 순서를 손대야 한다.
- (A)는 Spring의 `Ordered` 계약을 있는 그대로 사용하는 한 줄짜리 변경이며, 부작용으로 "커밋 실패 = FAIL 로그"까지 자동으로 얻는다: Aspect가 `@Transactional` 어드바이저보다 바깥에 있으면, 대상 메서드의 반환값이 아니라 **커밋까지 포함한 전체 프록시 체인의 결과**가 `@AfterReturning`/`@AfterThrowing`의 관측 대상이 된다 — 커밋이 실패하면 `TransactionInterceptor`가 예외를 던지고, 그 예외는 (A) 이후 우리 Aspect의 `@AfterThrowing`을 그대로 통과한다. 값은 `Ordered.LOWEST_PRECEDENCE - 1`(메서드 보안 — `@PreFilter`=100·`@PreAuthorize`=200 등 작은 개별 순서값 — 보다는 안쪽, `@Transactional` 기본 순서 `LOWEST_PRECEDENCE`보다는 바깥쪽)을 사용해 현재 존재하지 않는 다른 관계(메서드 보안과의 관계)는 건드리지 않는다.
- `REQUIRES_NEW`(`AdminActionLogService.log()`)는 그대로 유지한다 — 이 결정과 무관하게 필요하다(로그 저장 자체의 원자성·FAIL 로그의 독립 커밋 보장).

### 4. IP 길이 방어는 `ClientIpResolver` 안에 한 번만 둔다 (VisitLog 패턴을 공유 유틸로 승격)

`getRemoteAddr()`만 쓰면 공격자가 임의로 긴 문자열을 주입할 방법은 사라지지만, 컨테이너·프록시 구성에 따라 예상 밖 형식(IPv6 zone id 포함 등)이 45자를 넘을 가능성을 방어적으로 차단한다. 기존 `VisitLoggingAuthenticationSuccessHandler.truncateIp()`(컬럼 길이 45)와 동일한 패턴을 (결정 2에서 신설하는) `ClientIpResolver.resolve()` 안에 한 번만 구현해 4개 호출부 모두에 자동 적용한다 — 길이 초과로 인한 `DataException`이 로그 행 전체(IP뿐 아니라 성공/실패 사실 자체)를 조용히 삼키는 실패 모드를 4곳에서 동시에 막는다. `VisitLoggingAuthenticationSuccessHandler`의 기존 `truncateIp()`는 `ClientIpResolver`로 흡수되어 삭제된다.

### 5. 커밋 순서 보장 범위를 "최상위 트랜잭션 진입점"으로 명시하고, 참여 트랜잭션 시나리오는 해결이 아니라 실증한다 (v2 신설, codex 리뷰 지적 반영)

**문제**: 결정 3의 `@Order`는 `AdminActionLogAspect`가 감싸는 프록시 **안**의 인터셉터 순서만 정한다. `@AdminActionLogged`가 붙은 메서드가 이미 열려 있는 외부 `@Transactional` 메서드 안에서 `REQUIRED`로 참여 호출되면, 그 메서드의 정상 반환은 물리 커밋과 무관하다(커밋은 최상위 메서드가 반환할 때 일어난다) — `@Order`가 이 경우까지 "반환 = 커밋 완료"를 보장하지는 않는다.

**선택지**:
- (A) 아무 조치 없이 완료 기준을 그대로 둔다 — 실제로 성립하지 않는 범위까지 보장한다고 주장하게 되어 기각.
- (B) `@AdminActionLogged`가 붙은 메서드를 전부 최상위 진입점으로 강제하거나 `REQUIRES_NEW`로 바꾼다 — codex도 지적했듯 업무 트랜잭션의 원자성 자체를 바꾸는 과도한 변경이라 기각(감사 로깅이 업무 트랜잭션 전파 정책을 좌우해서는 안 된다).
- (C) 보장 범위를 "최상위 트랜잭션 진입점으로 호출될 때"로 명시적으로 좁히고, 참여 호출 시나리오에서 실제로 무슨 일이 벌어지는지 통합 테스트로 실증해 문서화한다(해결하지 않고 알려진 한계로 남김).

**결정: (C).** 오늘 기준 실제 프로덕션 코드(정찰 섹션에서 확인 — 4개 서비스의 11개 메서드 전부)는 전부 이 조건을 만족하므로 당장 아무것도 깨지지 않는다.

**(v3 수정, codex 2차 리뷰 지적 반영) 시나리오 C가 실제로 보장하는 것과 보장하지 않는 것을 명확히 한다.** 시나리오 C는 테스트 전용 `probeService`만 호출하므로, 실제 `MenuService` 등의 프로덕션 코드가 향후 참여 호출로 리팩터링되는 회귀를 **감지하지 못한다** — "향후 전제가 깨지면 이 테스트가 회귀를 감지한다"는 v2의 주장은 철회한다. 시나리오 C의 정확한 역할은 "이 경계 밖(참여 트랜잭션)에서는 무슨 일이 벌어지는가"를 **알려진 한계 재현 테스트**로 고정 기록하는 것뿐이다. 결과도 막연히 관찰하지 않고 정확히 단언한다: 이 구조·감사 저장 성공을 전제로 하면 **업무 행은 롤백되지만 SUCCESS 감사 행은 남는다** — 이 정확한 결과를 assert한다(아래 테스트 계획 5 갱신). 정찰 섹션에서 확인한 "오늘 기준 11개 메서드 전부가 최상위 진입점"이라는 사실 자체가 현재의 유일한 실질적 방어선이며, 향후 이 전제가 깨지는 리팩터링에 대한 자동 회귀 감지는 이번 계획의 범위 밖이다(별도 운영 호출 경로 검증이 필요하면 후속 과제).

**(v3 추가) "커밋 실패"와 "커밋 후 응답 유실"을 구분한다.** 결정 3이 보장하는 것은 "최상위 진입점에서, 정상 커밋 시 SUCCESS·커밋 전 확정적 실패 시 FAIL"이다. 이것과 별개로, 업무 트랜잭션이 물리적으로는 COMMIT됐으나 그 확인 응답이 유실돼(네트워크 장애 등) 호출자에게 예외로 보이는 경우, 이 계획의 구조에서는 **실제로는 성공한 업무를 FAIL로 잘못 기록**할 수 있다 — 이는 "감사 로그 저장 자체(`AdminActionLogService.log()`)의 REQUIRES_NEW 커밋 불확실성"과는 다른 문제이므로 동일시하지 않는다(v2의 서술은 이 둘을 뭉뚱그렸다는 지적을 수용). 아래 리스크 섹션에 별도 항목으로 명시한다.

## 참고: 유틸 위치와 기존 파일 정리

- `com.cms.common.web.ClientIpResolver`는 `HttpServletRequest`만 인자로 받는 순수 정적 유틸이라 Spring 빈 등록이 불필요하다(테스트도 순수 단위 테스트로 충분).
- `VisitLoggingAuthenticationSuccessHandler`·`LockingAuthenticationFailureHandler`·`PasswordResetController`의 기존 `MAX_IP_LENGTH`/`truncateIp()`(또는 동등 로직)는 `ClientIpResolver`로 흡수되며 각 파일에서 삭제한다.

## 변경 파일

- 신규: `src/main/java/com/cms/common/web/ClientIpResolver.java` — `resolve(HttpServletRequest)` 정적 메서드. `getRemoteAddr()`를 45자로 절단해 반환.
- 수정: `src/main/java/com/cms/admin/log/aspect/AdminActionLogAspect.java`
  - `getClientIp()` 삭제, `ClientIpResolver.resolve(request)` 호출로 교체.
  - 클래스에 `@Order(Ordered.LOWEST_PRECEDENCE - 1)` 추가(순서 결정 이유를 설명하는 주석 포함, `AdminSessionRevokeListener`·`PublicWebExceptionAdvice`의 기존 `@Order` 주석 관례를 따름).
- 수정: `src/main/java/com/cms/config/auth/VisitLoggingAuthenticationSuccessHandler.java` — `extractClientIp()`·`truncateIp()`·`MAX_IP_LENGTH` 삭제, `ClientIpResolver.resolve(request)` 호출로 교체.
- 수정: `src/main/java/com/cms/config/auth/LockingAuthenticationFailureHandler.java` — `extractClientIp()`·`truncate()`(IP 부분)·`MAX_IP_LENGTH` 삭제, `ClientIpResolver.resolve(request)` 호출로 교체. **이 수정이 부수적으로 "`X-FORWARDED-FOR: ,` 헤더로 로그인 실패 카운트 기록을 건너뛸 수 있는" 별도 버그도 함께 닫는다.**
- 수정: `src/main/java/com/cms/admin/member/controller/PasswordResetController.java` — `extractClientIp()` 삭제, `ClientIpResolver.resolve(request)` 호출로 교체. 이 수정이 "`X-FORWARDED-FOR: ,` 헤더로 비밀번호 재설정 요청이 500을 반환하는(200 항상 반환 계약 위반)" 버그도 함께 닫는다.
- 신규 테스트: `src/test/java/com/cms/common/web/ClientIpResolverTest.java` — 헤더 무시·`getRemoteAddr()`만 사용·45자 초과 절단·null 처리 단위 테스트(단일 소스).
- 수정: `src/test/java/com/cms/admin/log/aspect/AdminActionLogAspectTest.java` — `getClientIp()` 자체 테스트는 제거(로직이 `ClientIpResolver`로 이동)하고, `logSuccess`/`logFailure`가 `ClientIpResolver.resolve()`의 결과를 그대로 전달하는지만 얕게 확인.
- 신규 테스트: `src/test/java/com/cms/config/auth/LockingAuthenticationFailureHandlerTest.java`(존재 시 확장, 없으면 신규) — 조작된 `X-FORWARDED-FOR: ,` 헤더를 보내도 `loginFailureService.recordFailure()`가 정상 호출됨(카운트 증가)을 확인하는 회귀 테스트(수정 전에는 실패함을 구현 단계에서 먼저 관찰·기록).
- 수정: `src/test/java/com/cms/admin/member/controller/PasswordResetControllerTest.java`(존재 시 확장) — 조작된 `X-FORWARDED-FOR: ,` 헤더를 보내도 200이 반환됨을 확인하는 회귀 테스트.
- 신규 테스트: `src/test/java/com/cms/admin/log/aspect/AdminActionLogCommitOrderIntegrationTest.java` — 실 MariaDB(Testcontainers) 기반, 커밋 직전 실패 주입으로 SUCCESS/FAIL 귀속이 실제 커밋 결과와 일치하는지 검증(아래 테스트 계획 참조). 내부에 `@TestConfiguration`으로 등록하는 테스트 전용 프로브 빈(`@Component` 미부착, `AdminActionTypes.MENU_UPDATE` 재사용)을 포함.
- 수정: `src/test/java/com/cms/config/auth/VisitLoggingAuthenticationSuccessHandlerTest.java` — `extractClientIp()` 관련 기존 테스트를 `ClientIpResolver` 위임 확인으로 축소.
- 수정: `docs/troubleshooting.md` — (1) AOP 어드바이저 순서 비결정성 이슈, (2) `X-Forwarded-For: ,` 파싱으로 인한 로그인 실패 카운트 누락/비밀번호 재설정 500 버그, 두 건을 해결 기록으로 남긴다(둘 다 비자명한 이슈에 해당).
- 수정: `src/main/java/com/cms/admin/log/CLAUDE.md` — IP 신뢰 정책(신뢰 프록시 없음 전제, `ClientIpResolver` 단일 출처)과 `@Order` 계약(최상위 트랜잭션 진입점 한정) 문서화.
- 수정: `src/main/java/com/cms/admin/member/CLAUDE.md` — **(자체 재검토로 추가 발견, 2026-09-28)** 이 문서가 `LoginFailureService`(로그인 실패 잠금)·`PasswordResetService`(비밀번호 재설정) 동작을 이미 상세히 다루고 있음을 확인 — `LockingAuthenticationFailureHandler`·`PasswordResetController`의 IP 파싱 버그 수정(콤마 헤더로 인한 카운트 누락/500 응답)을 해당 섹션에 추가 기록한다. 물리적으로는 `com.cms.config.auth`·`com.cms.admin.member.controller` 패키지의 파일이지만, 이 CLAUDE.md가 이미 같은 동작 계약의 authoritative 문서다.
- 수정: `adversarial-review/plan/README.md` — 이 계획을 인덱스에 추가.

## 테스트 계획

1. **IP 위조 차단 (단위, 단일화)**: `ClientIpResolverTest`에 `HttpServletRequest` mock으로 `X-FORWARDED-FOR`/`X-Real-IP`를 주입해도 반환값이 mock의 `getRemoteAddr()` 값과 같음을 확인. 45자 초과 `getRemoteAddr()` 값이 절단됨을 확인. `X-FORWARDED-FOR: ,`처럼 조작된 값이 섞여도 예외 없이 `getRemoteAddr()`를 반환함을 확인(파싱 버그 자체가 사라졌음을 증명). **(v3 추가)** `resolve(null) == null`(요청 자체가 없는 경우), `getRemoteAddr()`가 `null`인 mock에서도 `resolve(request) == null`(예외 없이) 확인 — `AdminActionLogAspectTest`의 기존 "요청 컨텍스트 없음" 테스트가 이 계약이 `AdminActionLogAspect`까지 안전하게 이어지는지(감사 저장 자체는 계속됨) 회귀 감지한다.
2. **로그인 실패 카운트 회귀 (신규, 발견된 버그)**: `LockingAuthenticationFailureHandlerTest`에서 `X-FORWARDED-FOR: ,` 헤더를 포함한 실패 로그인 요청이 `loginFailureService.recordFailure()`를 정상 호출함을 확인. **회귀 재현**: 구현 단계에서 수정 전 코드로 먼저 실행해 이 헤더가 실제로 카운트 기록을 건너뛰게 만드는지 관찰·기록한 뒤 수정 후 재실행.
3. **비밀번호 재설정 500 회귀 (신규, 발견된 버그)**: `PasswordResetControllerTest`에서 `X-FORWARDED-FOR: ,` 헤더를 포함한 재설정 요청이 여전히 200을 반환함을 확인.
4. **커밋 순서 보장 (통합, 신규, 최상위 진입점 시나리오)**: `AdminActionLogCommitOrderIntegrationTest`
   - 프로브 빈: `@Transactional` + `@AdminActionLogged(actionType = AdminActionTypes.MENU_UPDATE, ...)`가 붙은 메서드가 Menu 행을 저장하고, `failAtCommit` 플래그가 있으면 `TransactionSynchronizationManager.registerSynchronization`으로 `beforeCommit()`에서 `IllegalStateException`을 던져 "커밋 직전 실패"를 시뮬레이션(테스트 메서드 자체에는 `@Transactional`을 붙이지 않는다 — codex 리뷰 반영).
   - 시나리오 A(정상 커밋): 호출 성공, DB에 Menu 행 영속 확인(새 트랜잭션으로 재조회), `AdminActionLogRepository`에 이번 호출에 해당하는 SUCCESS 행이 정확히 1건 존재 확인(요청별 식별값으로 격리 — codex 리뷰 반영, 기존 `MENU_UPDATE` 로그와 혼동 방지).
   - 시나리오 B(커밋 직전 실패, 최상위 진입점): 호출이 예외를 던짐, DB에 Menu 행이 **영속되지 않음**(롤백) 확인, `AdminActionLogRepository`에 **FAIL** 행이 정확히 1건 존재(에러 메시지에 시뮬레이션 문구 포함)하고 SUCCESS 행은 0건임을 확인.
   - **회귀 재현**: 구현 단계에서 `@Order`를 제거한 상태로 시나리오 B를 먼저 실행해 현재 코드가 실제로 SUCCESS를 잘못 기록하는지(또는 최소한 보장하지 못하는지) 관찰·기록한 뒤 `@Order`를 적용해 재실행 — PR4(M-04)의 "회귀 재현 후 복원" 검증 방식과 동일.
5. **알려진 한계 재현 테스트 (통합, 신규, 참여 트랜잭션 시나리오 — v3, codex 2차 리뷰 반영)**: 같은 `AdminActionLogCommitOrderIntegrationTest`에 시나리오 C를 `knownLimitation_...`류 이름으로 추가 — `TransactionTemplate.execute(status -> { probeService.run(false); throw new RuntimeException("외부 트랜잭션 실패"); })`처럼 프로브 메서드를 **이미 열려 있는 외부 트랜잭션 안에서** 호출한 뒤 외부 트랜잭션을 실패시킨다. **막연히 관찰하지 않고 정확한 결과를 단언한다**: 업무 행(Menu)은 외부 트랜잭션 롤백으로 **영속되지 않음**을 확인하면서, 동시에 `AdminActionLogRepository`에는 이 호출의 **SUCCESS 행이 정확히 1건 존재**함을 단언한다 — 즉 "업무는 실패했지만 감사는 성공으로 남는" 알려진 불일치를 명시적으로 고정한다. 테스트 Javadoc/DisplayName에 "이 테스트는 프로브 전용이며 실제 프로덕션 서비스가 참여 호출로 리팩터링되는 회귀는 감지하지 못한다 — 정찰 섹션에서 확인한 '오늘 기준 11개 메서드 전부가 최상위 진입점'이라는 사실이 현재의 유일한 방어선"임을 명시한다(v2의 "회귀 감지" 주장 철회).
6. **기존 회귀**: `AdminActionLogAspectTest`·`AdminActionLogServiceTest`(REQUIRES_NEW 선언 확인)·`VisitLoggingAuthenticationSuccessHandlerTest`·`MenuServiceTest`/`MenuControllerTest`(프로브가 재사용하는 `AdminActionTypes.MENU_UPDATE`와 무관하지만 회귀 확인 차원) 전체 재실행.
7. **전체 스위트**: `SPRING_PROFILES_ACTIVE=dev ./gradlew test` 통과.
8. **AdminActionTypeSyncTest 무영향 확인**: 프로브 빈이 `@Component` 계열이 아니므로 이 테스트가 프로브를 스캔하지 않음을 실행 결과로 확인(테스트 통과 자체가 증거).

## 완료 기준

- 위조/과도하게 긴 IP 헤더를 보내도 4개 호출부(`AdminActionLogAspect`·`VisitLoggingAuthenticationSuccessHandler`·`LockingAuthenticationFailureHandler`·`PasswordResetController`) 전부에서 기록되는 IP가 `getRemoteAddr()` 값(45자 상한 적용)과 정확히 일치 — `ClientIpResolver` 단일 출처로 확인.
- 조작된 `X-FORWARDED-FOR: ,` 헤더를 보내도 로그인 실패 카운트 기록과 비밀번호 재설정 요청(200 응답)이 정상 동작 — 이번 리뷰에서 발견된 두 부수 버그의 회귀 테스트로 확인.
- 실제 Spring 프록시·트랜잭션 환경에서, **해당 메서드가 요청의 최상위 트랜잭션 진입점일 때, 정상 커밋 또는 커밋 전 확정적 실패**를 주입하면 감사 로그가 각각 SUCCESS/FAIL로 정확히 귀속됨을 통합 테스트로 확인(v3: "커밋 실패"를 "커밋 전 확정적 실패"로 정밀화 — 커밋 후 응답 유실 케이스는 별도 리스크). 참여 트랜잭션 시나리오는 보장 범위 밖임을 "알려진 한계 재현 테스트"로 실증(해결이 아니라 명시, 실제 프로덕션 서비스의 회귀 감지 도구는 아님).
- `ClientIpResolver.resolve()`의 null 계약(`request == null`·`getRemoteAddr() == null` 각각 `null` 반환)이 명시되고, 요청 컨텍스트 없이도 감사 저장 자체는 계속됨을 회귀 테스트로 확인(v3 신규).
- **(v3 정밀화)** 저장소 소스 코드에 `forward-headers-strategy`·`ForwardedHeaderFilter`·`RemoteIpValve` 관련 명시적 설정이 없음을 grep으로 확인(현재 0건) — 단, 이것이 실제 실행 환경(클라우드 플랫폼 자동 감지 등)에서의 검증을 의미하지 않음을 계획 문서에 명시. 실행 환경 자체의 검증은 범위 밖(Gate H 시점으로 이관).
- `AdminActionTypeSyncTest`를 포함한 `./gradlew test` 전체 통과.
- `com.cms.admin.log`의 CLAUDE.md에 새 계약(신뢰 프록시 없음 전제, `ClientIpResolver` 단일 출처와 null 계약, `@Order` 이유와 보장 범위) 반영.
- 기존 감사 로그·로그인·비밀번호 재설정·방문 로그 골든 패스 회귀 없음 — 기존 테스트 스위트로 확인.

## 리스크 / 범위 밖

- **실제 ingress 도입 시 재검토 필요**: 실제 리버스 프록시가 생기면 `X-Forwarded-For` 신뢰가 다시 필요해진다 — 그 시점에 감사 로그·방문 로그·로그인 실패·비밀번호 재설정(`ClientIpResolver` 통일 대상 4곳) **및 레이트리밋(`RateLimitFilter` — 이미 `getRemoteAddr()` 직접 사용, 이번 통일 대상 아님)**의 IP 해석을 함께(로드맵 "후속 과제 — ① 실배포 인프라"의 `forward-headers-strategy` 항목과 통합해) 재검토한다. **(v3 정정, codex 2차 리뷰 지적)** "한 파일만 고치면 다섯 곳 모두 반영"은 부정확했다 — `ClientIpResolver`는 4곳만 통일하고, `RateLimitFilter`는 이 유틸을 호출하지 않으므로 재검토 시 별도로 확인해야 한다.
- **실행 환경의 forward-headers 처리 여부는 미검증(v3 신규, codex 2차 리뷰 지적)**: 소스 grep 0건은 저장소 코드 기준일 뿐, 실제 배포 환경(클라우드 플랫폼 자동 감지로 `NATIVE` 전략이 적용되는 경우 등)까지 검증한 것은 아니다 — 실제 ingress 도입 시 함께 검증.
- **`@Order` 값의 장기 안정성**: `Ordered.LOWEST_PRECEDENCE - 1`은 Spring/Spring Security의 향후 버전이 `@Transactional`이나 메서드 보안 기본 순서를 바꾸면 재검토가 필요할 수 있다 — 통합 테스트(시나리오 B)가 이 계약이 깨지면 실패하도록 설계돼 있어 회귀 감지는 가능하다.
- **커밋 순서 보장은 최상위 트랜잭션 진입점으로 호출될 때로 한정되며, 이 경계를 넘는 실제 프로덕션 코드의 회귀는 이번 계획으로 감지되지 않는다(v3 정밀화, codex 2차 리뷰 지적)**: `@AdminActionLogged`가 붙은 4개 서비스 11개 메서드가 향후 다른 `@Transactional` 메서드 안에서 참여 호출되도록 리팩터링되면 이 보장이 깨질 수 있다 — "알려진 한계 재현 테스트"(시나리오 C)는 그 경계에서 정확히 무슨 일이 벌어지는지(업무 롤백 + SUCCESS 감사 잔존)를 프로브 대상으로 고정할 뿐, 실제 프로덕션 서비스가 그 경계를 넘는 것 자체를 자동으로 감지하지 않는다 — 이번에 실제로 고치지도 않는다(업무 메서드의 트랜잭션 전파 정책을 감사 로깅이 좌우하지 않도록 하는 의도적 선택). 오늘 기준 11개 메서드 전부가 최상위 진입점이라는 사실(정찰 섹션)이 현재의 유일한 방어선이다.
- **`setRollbackOnly()` 후 정상 반환·직접 등록한 `afterCommit()`에서 예외 전파는 범위 밖(codex 리뷰 반영, 현재 코드에 없음을 확인)**: 전자는 코드베이스에 `setRollbackOnly()` 호출이 0건(grep 확인), 후자는 `NoticeAttachmentService.registerFileDeleteAfterCommit()`과 `FileStorageTransactionSupport.deleteAfterCommit()`(v3에서 추가 확인) 둘 다 예외를 삼키고 전파하지 않음(코드 확인)이라 현재는 발생하지 않는 경로다. 향후 이런 패턴이 추가되면 재평가가 필요하다.
- **(v3 분리, codex 2차 리뷰 지적 — 이전에는 아래 항목과 뭉뚱그려져 있었다) 업무 트랜잭션이 실제로는 커밋됐으나 응답이 유실돼 FAIL로 오분류될 수 있다**: 업무 DB가 물리적으로 COMMIT을 적용한 뒤 그 확인 응답이(네트워크 장애 등으로) 유실되면, 최상위 진입점이라도 호출자에게는 예외로 보여 FAIL이 기록될 수 있다 — 실제로는 성공한 업무가 FAIL로 잘못 분류되는 것이다. 이는 "감사 로그 저장 자체의 REQUIRES_NEW 커밋 불확실성"과는 다른 문제이며(v2는 이 둘을 동일시했었다), 분산 트랜잭션(2PC/outbox 등) 없이는 원칙적으로 해결 불가능한 근원적 한계라 이번 범위에서 해결하지 않는다. 다만 이 계획의 제목("신뢰성 강화")이 감사 기록의 완전성 보장으로 오독되지 않도록 명시적으로 잔여 위험에 남긴다 — 방향성 참고: 이 오분류는 "실패를 성공으로" 숨기는 것이 아니라 "성공을 실패로" 보고하는 방향이라, 원래 H-03/M-01이 우려한 방향(실패를 성공으로 오기록)보다는 감사 신뢰성 관점에서 상대적으로 덜 위험하다.
- **(v3 신규, codex 2차 리뷰 지적) 업무 커밋과 감사 커밋 사이의 비원자성**: 업무 트랜잭션 커밋과 감사 로그 커밋(REQUIRES_NEW)은 서로 다른 두 개의 물리 트랜잭션이다 — 그 사이에 프로세스가 종료되면 업무 변경은 커밋됐지만 감사 행 자체가 아예 없을 수 있다. outbox 패턴 등으로 이 원자성을 보장하는 것은 이번 계획의 범위가 아니다(신규 인프라 도입 없이 최소 변경을 유지한다는 원칙과 충돌) — 잔여 위험으로만 명시.
- **VisitLog 다운스트림(대시보드 집계)에는 영향 없음**: `VisitLog.requestIp`는 대시보드 집계(`DashboardService`)에서 IP 값 자체를 사용하지 않음(방문자 수만 집계) — grep으로 확인 완료(`requestIp` 참조 0건).

## 구현·검증 결과 (2026-09-28)

- **Context**: `/feature` 8단계 워크플로로 진행 — 정찰→설계→적대적 리뷰(codex 2라운드 + 3라운드는 사용량 한도로 자체 재검토)→사용자 승인→구현→테스트→기록. `security/audit-log-ip-commit-integrity` 브랜치에서 작업.
- **구현**:
  - 신규 `com.cms.common.web.ClientIpResolver`(정적 유틸) — `resolve(HttpServletRequest)`가 `request.getRemoteAddr()`를 45자로 절단해 반환, `request == null`·`getRemoteAddr() == null` 각각 `null` 반환.
  - `AdminActionLogAspect`: `getClientIp()` 삭제 후 `ClientIpResolver.resolve(getCurrentRequest())`로 교체, 클래스에 `@Order(Ordered.LOWEST_PRECEDENCE - 1)` 추가(순서 근거 주석 포함).
  - `VisitLoggingAuthenticationSuccessHandler`·`LockingAuthenticationFailureHandler`(URI용 `truncate()`/`MAX_URI_LENGTH`는 유지)·`PasswordResetController`: 각각의 헤더 신뢰 로직 삭제 후 `ClientIpResolver.resolve()`로 교체.
  - 스키마·인가 정책·신규 의존성 변경 없음(계획대로).
- **신규 테스트**: `ClientIpResolverTest`(7개, 헤더 무시·null 계약·길이 절단·조작된 헤더에도 예외 없음), `AdminActionLogCommitOrderIntegrationTest`(3개, Testcontainers 실 MariaDB — 정상 커밋 SUCCESS·커밋 직전 실패 FAIL·참여 트랜잭션 알려진 한계).
- **기존 테스트 갱신**: `LockingAuthenticationFailureHandlerTest`(헤더 위조 무시 검증으로 교체 + 조작된 헤더 회귀 테스트 추가, 5→8개), `VisitLoggingAuthenticationSuccessHandlerTest`(헤더 위조 무시 검증으로 교체, 16개 유지), `PasswordResetControllerTest`(헤더 위조 무시 + 조작된 헤더 500 회귀 테스트 추가, 14→16개). `AdminActionLogAspectTest`는 수정 없이 그대로 통과(요청 컨텍스트 없음 시나리오가 `ClientIpResolver`의 null 계약까지 회귀 감지).
- **회귀 재현·수정 증명**: `AdminActionLogCommitOrderIntegrationTest`의 커밋 직전 실패 시나리오를 `@Order` 제거 상태로 먼저 실행해 실제로 `AssertionFailedError: expected: FAIL but was: SUCCESS`를 관측(감사 H-03·M-01 버그 실증) — `@Order` 복원 후 재실행해 3개 시나리오 전부 통과 확인.
- **검증 결과**: `SPRING_PROFILES_ACTIVE=dev ./gradlew test` 전체 778개 통과(신규 15개 순증, 실패·오류 0, 스킵 1건은 기존 Windows symlink 테스트). Docker Desktop 기동 상태에서 Testcontainers 포함 전체 재실행.
- **실기 검증(2026-09-28)**: 사용자의 기존 dev 스택(`cms-app-dev`/`cms-db-dev`)과 별도로 격리 포트(host `bootRun --server.port=8099`, 같은 공유 dev DB, PR4 선례와 동일 방식)에서 실제 서버로 확인 — (1) 비밀번호 재설정 요청에 조작된 `X-Forwarded-For: ,` 헤더를 보내도 여전히 200 반환(수정 전이라면 500 — 회귀 해소 확인), 위조된 정상 형식 헤더(`9.9.9.9`)도 무시됨을 확인. (2) `admin` 계정으로 조작된 `X-Forwarded-For: ,` 헤더를 포함한 실패 로그인 시도 — `failed_login_count`가 0→1로 정상 증가함을 DB 직접 조회로 확인(수정 전이라면 예외로 카운트 자체가 건너뛰어짐 — 브루트포스 방어 무력화 버그 해소 확인). 검증 후 `failed_login_count`를 0으로 원복해 사용자의 dev DB에 잔여 영향 없음 확인. 격리 인스턴스는 검증 직후 종료, 사용자의 `cms-app-dev`/`cms-db-dev` 컨테이너는 재기동·수정 없이 그대로 유지됨을 확인.
- **문서 동기화**: `docs/troubleshooting.md`(애플리케이션/런타임 카테고리에 2건 — 커밋 순서 비결정성, IP 신뢰 4곳 통합), `com.cms.admin.log/CLAUDE.md`(`@Order` 계약·보장 범위·`ClientIpResolver` 단일 출처), `com.cms.admin.member/CLAUDE.md`(로그인 실패 잠금·비밀번호 재설정 섹션에 IP 신뢰 정책 갱신 추가), `adversarial-review/plan/README.md`(14번 행 추가) 전부 완료.
- **이슈**: codex 3차 리뷰가 외부 사용량 한도로 실패(1회 재시도도 동일) — 스킬 규칙에 따라 자체 재검토로 대체, 신규 결함 없음(문서 갱신 누락 1건만 추가 발견·반영). 사용자 승인 하에 자체 검토로 마무리하고 구현 진행.
- **후속**: 실제 리버스 프록시 도입 시 `ClientIpResolver`(및 별도로 `RateLimitFilter`)의 IP 해석 재검토 필요(로드맵 "후속 과제 — ① 실배포 인프라"). 참여 트랜잭션 경계를 넘는 실제 프로덕션 서비스 리팩터링에 대한 자동 회귀 감지는 이번 범위 밖(현재 11개 메서드 전부가 최상위 진입점).
- **커밋·PR**: 이번 세션에서 미진행 — `/code-review-loop` → `/commitPR` 단계 예정.
