# PLAN-password-reset — 비밀번호 재설정 (메일 발송 + 토큰 검증)

## 구현 결과 (2026-07-13, v15 기준 구현 완료)

**Context**: plan-review-loop 총 14라운드(v2~v15) 통과 후 공개 경로 4개 인가 정책 변경 사용자 승인을 받아 `feat/password-reset` 브랜치에 구현.

**핵심 확정 사항**: v15 설계 결정 표를 그대로 구현. 계획과 달라진 점 1건 —
`GlobalApiExceptionHandler`가 `PessimisticLockingFailureException`을 이미 409로 매핑하고 있어(핸들러 주석: "flush/커밋 시점 락 실패는 서비스 try-catch로 안정적으로 못 잡는다"), **커밋 시점 락 실패는 여전히 409가 될 수 있다**. 주 잠금 지점(`findByIdForUpdate` 호출)은 서비스 catch로 400 통일했으므로 잔여 노출 창은 커밋 경합뿐 (수용 — 동시성 통합 테스트에서도 이 경로를 거부로 집계).

**구현 파일**:
- 신규: `PasswordResetService`(명시적 생성자 + Clock + TransactionTemplate + IssueResult toString 오버라이드), `PasswordResetController`, `PasswordResetRequestRequest`, `PasswordResetConfirmRequest`, `password-reset.html`, `password-reset-confirm.html`
- 수정: `Member`(changePassword 토큰 클리어 + issueResetToken/clearResetToken), `MemberRepository`(findByEmailForUpdate·findIdsByResetToken·clearResetTokenIfMatches), `SecurityConfig`(공개 경로 4개), `AdminMainController`(페이지 매핑 2개), `login.html`(재설정 링크)
- 테스트: `PasswordResetServiceTest`(23), `PasswordResetControllerTest`(10, 실제 SecurityConfig로 공개 경로·CSRF 401 검증), `PasswordResetConcurrencyIntegrationTest`(2, 실제 DB), `AdminMemberServiceTest`+1

**검증 결과**: `./gradlew test` 전체 통과 (신규 36건 포함, 실패 0). MailHog + playwright 실기 검증 완료 —
골든 패스(요청→메일→재설정→새 비밀번호 로그인), 공백·대문자 이메일 정규화, DB 해시 저장(평문 아님), fragment URL 즉시 제거, 토큰 DOM/외부 리소스/앱 로그 미노출, 사용된 토큰 400, 토큰 부재 시 제출 비활성, **재설정 직후 로그인 중이던 기존 세션 만료(리다이렉트)**, ADMIN/MANAGER 로그인 회귀, manager01 비밀번호 원복까지 확인. 스크린샷: `.playwright-mcp/01~09-*.png`

**이슈**: 없음 (favicon.ico 500은 기존 무관 이슈).

**후속**: ① `AdminMemberService.normalizeEmail()`의 기본 Locale 의존 정리(v12에서 범위 외 판정), ② 커밋 시점 락 실패 409 잔여 창(위 확정 사항), ③ 로드맵 ③(비밀번호 90일 만료)이 이 기능을 선행 조건으로 사용 가능해짐.

## 개정 이력

- **v15 (2026-07-13, 적대적 리뷰 라운드 14 반영 — codex, 5건 전부 수용)**
  - 변경: `PasswordResetService`를 **명시적 생성자**로 전환 — `@RequiredArgsConstructor` + `final @Value` 필드 조합은 Lombok 생성자 파라미터에 `@Value`가 복사되지 않아 `String` 빈 주입 실패가 남. `@Value("${app.base-url}")`는 생성자 파라미터에 부착하고 base-url 검증도 생성자 안에서 수행
  - 변경: **`requestReset()` 발급 트랜잭션의 락 실패도 200으로 삼킴** — `findByEmailForUpdate()`의 락 타임아웃·데드락이 500으로 새면 "해당 이메일 행 존재 + 경합 중" 신호가 됨. `PessimisticLockingFailureException` 계열 catch 후 발급 없이 조용히 종료 (마스킹 이메일/memberId + 예외 클래스명만 로그)
  - 변경: **base-url 검증 구체화** — URI 파싱 가능만으로는 부족. `http`/`https` scheme + host 존재 + userinfo 없음 + query/fragment 없음을 명시 검증 (경로(context path)는 허용 — 프록시 하위 경로 배포 대비). 위반 시 기동 실패
  - 변경: `resetPassword()` 락 실패 400 변환 **직전에 서버 로그** — memberId 후보 + 예외 클래스명만 (토큰·해시·throwable 금지, 기존 로그 원칙과 동일 형식). 사용자에게 숨긴 장애의 운영 관측성 확보
  - 변경: **`Clock` 주입** — TTL·쿨다운·만료 등 시간 분기가 촘촘해 `LocalDateTime.now()` 직접 호출은 경계값 테스트를 불안정하게 함. `Clock` 빈(운영: `Clock.systemDefaultZone()`)을 주입하고 서비스 내 시각은 전부 이 Clock 기준, 테스트는 고정 Clock 사용
- **v14 (2026-07-13, 적대적 리뷰 라운드 13 반영 — codex, 6건 전부 수용·1건은 안내 문구 정정해 수용)**
  - 변경: `IssueResult` record의 **자동 `toString()` 토큰 노출 차단** — v13에서 도입한 record가 "토큰 문자열화 금지" 원칙과 자기모순이었음. `toString()`을 오버라이드해 memberId만 노출 + **토큰·비밀번호를 담는 모든 타입은 자동 문자열화(record 기본 toString, `@ToString`, `@Data`) 금지** 일반 원칙화
  - 변경: **커밋 후 실패 처리 범위 확장** — catch 대상을 `MailException`만이 아니라 커밋 후 전 구간(링크 생성 + 메시지 구성 + send)의 `Exception`으로 확장. 조건부 클리어 자체가 실패해도(락 타임아웃·DB 오류) 삼키고 **200 유지** (memberId + 예외 클래스명만 로그) — 균일 응답 계약이 어떤 경로로도 깨지지 않게. `app.base-url`은 서비스 생성자에서 URI 파싱 검증
  - 변경: hex 인코딩을 **`HexFormat.of().formatHex(bytes)`로 고정** — `BigInteger.toString(16)`류는 선행 0 소실로 64자 미만 토큰을 만들 수 있음 (클라이언트 형식 검사와 엇갈림)
  - 변경: `resetPassword()`의 **잠금 실패(`PessimisticLockingFailureException`)를 동일 메시지 400으로 감쌈** — 기존 핸들러는 409로 매핑하는데, 락 경합 발생 자체가 "유효 토큰 존재 + 사용 중"을 간접 노출하므로 사유 비구분 400 정책과 통일
  - 변경: confirm 페이지에 **401/403 분기 추가** — 세션/CSRF 만료 시 400과 구분해 "세션이 만료되었습니다. 메일의 링크를 다시 클릭해 다시 시도해 주세요." 표시. 리뷰어가 제안한 "새로고침 후 재시도"는 기각 — `replaceState`로 fragment가 이미 제거돼 새로고침하면 토큰이 사라진다. 메일 링크 재클릭이 올바른 복구 경로(토큰은 미소비라 유효)
- **v13 (2026-07-13, 적대적 리뷰 라운드 12 반영 — codex, 5건 전부 수용)**
  - 변경: **토큰 위협 모델 정정** — fragment는 메일 링크 진입(GET) 단계의 서버·프록시 로그 유출만 막고, 재설정 제출 시 토큰은 `POST /admin/api/password-resets` **요청 본문으로 서버에 도달한다**. 구체 지시 추가: `PasswordResetConfirmRequest`에 `@ToString`/`@Data` 금지, DTO 자체 로깅 금지, 검증 실패 응답·로그에 rejected value(토큰) 미포함 확인, 향후 요청 바디 로깅/observability 도입 시 이 경로 마스킹 필요
  - 변경: **발급 결과 모델 `IssueResult` 명시** — `requestReset()`의 트랜잭션 람다("미존재/무자격/쿨다운이면 조용히 return" 3갈래)와 "발급된 경우에만 커밋 후 발송"의 연결을 `Optional<IssueResult>` 반환으로 고정 (empty = 발송 생략) — null 토큰/memberId 처리 실수·NPE 방지
  - 변경: `clearResetTokenIfMatches`에 **`@Modifying(clearAutomatically = true, flushAutomatically = true)`** — JPA 벌크 UPDATE는 영속성 컨텍스트를 우회하므로, 향후 엔티티 로드 후 재사용될 때의 stale entity 재발을 방어적 기본값으로 차단
  - 변경: **메일 수신자는 DB 저장값(`member.getEmail()`)** — 저장 이메일은 정규화되어 있지 않으므로(v9) 정규화 입력값으로 발송하면 저장값과 불일치. `IssueResult.email`은 DB 값으로 채운다
  - 변경: 세션 만료 완료 기준을 **ACTIVE 계정 기준으로 검증** — `PASSWORD_EXPIRED` 계정은 로그인 자체가 불가(ACTIVE만 로그인 가능)해 기존 세션이 없을 가능성이 높아, ACTIVE 계정으로 세션을 만든 뒤 재설정 성공 → 세션 무효화를 확인해야 검증에 의미가 있음
- **v12 (2026-07-13, 적대적 리뷰 라운드 11 반영 — codex, 3건 전부 수용)**
  - 변경: **발급 트랜잭션에 계정 행 잠금 추가 — 쿨다운 원자성 확보**. `findByEmail()` 일반 조회 후 쿨다운 검사·저장은 check-then-act 경합이라 동시 요청 2건이 모두 발급·발송될 수 있었음(완료 기준 "60초 이내 재요청 시 미발급"과 직접 충돌 — v10의 메일 순서 역전과는 별개 결함). `findByEmailForUpdate()`(PESSIMISTIC_WRITE, 신규)로 잠근 뒤 쿨다운 검사 — 이 트랜잭션의 최초 엔티티 로드라 1차 캐시 문제 없음. 동시 발급 케이스를 동시성 통합 테스트에 추가
  - 변경: **발송 실패 로그에서 throwable 통째 로깅 금지** — `MailSendException`의 메시지·failedMessages에 메일 본문(fragment 토큰 포함)이 들어갈 수 있어 `log.error("...", e)` 관성이 토큰 로그 금지 원칙을 우회로 깨뜨림. 실패 로그는 `memberId` + 예외 클래스명(`e.getClass().getSimpleName()`)만 기록
  - 변경: 이메일 정규화를 **`toLowerCase(Locale.ROOT)`로 명시** — 기본 Locale 의존은 터키어 `I/i` 등에서 조회 실패 유발 가능. 기존 `AdminMemberService.normalizeEmail()`의 동일 문제는 무관 변경이라 이번 범위 외(후속 정리 대상으로만 기재)
- **v11 (2026-07-13, 적대적 리뷰 라운드 10 반영 — codex, 2건 모두 코드 확인으로 종결)**
  - 변경: `changePassword()` 토큰 클리어의 부작용 범위를 **호출부 전수 확인으로 종결** — 프로덕션 호출부는 `AdminMemberService.changeMyPassword()`(현재 비밀번호 검증 통과 후 실변경) **단 1곳**임을 확인. "비밀번호가 안 바뀌는데 호출되는 경로"는 존재하지 않으므로 클리어 부작용 없음. 구현 후 호출부가 실변경 경로(기존 1곳 + 신규 재설정 경로)뿐인지 재확인하는 항목을 완료 기준에 추가. 메서드 분리(`changePasswordAndClearResetToken()`)는 호출부 1곳에 과한 대응이라 기각
  - 변경: 세션 만료 리스너의 AFTER_COMMIT 계약을 **코드 근거로 고정** — `AdminSessionRevokeListener.onRevoke()`가 `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`임을 확인(`AdminSessionRevokeListener.java:23`), 롤백 시 미소비는 이벤트 record javadoc에 명문화된 기존 계약. "실패 트랜잭션에서 세션 만료 미실행" 별도 테스트는 기각 — 기존 리스너의 프레임워크 계약이며, `resetPassword()`의 모든 예외는 이벤트 발행(마지막 단계) 이전에 발생
- **v10 (2026-07-13, 적대적 리뷰 라운드 9 반영 — codex, 4건 수용 + 1건 부분 수용)**
  - 변경: **동시 재설정 요청의 메일 순서 역전을 수용 리스크로 명문화** — 커밋 순서와 SMTP 도착 순서는 독립이라, 나중에 도착한 메일의 링크가 이미 덮어쓰여 400이 될 수 있다(엣지 케이스 7). 행 잠금은 발급 순서만 직렬화할 뿐 메일 도착 순서를 통제하지 못하고, 같은 토큰 재발송은 해시 저장 설계상 불가, 다중 유효 토큰은 스키마 변경이라 범위 외 — confirm 페이지의 "다시 요청" 안내가 복구 경로이고 쿨다운 60초가 발생 창을 좁힌다
  - 변경: `AdminMainController` 페이지 추가 근거를 검증된 사실로 정정 — `@AdminPage`가 붙어 있어 `AdminSidebarAdvice`·`AdminViewAdvice`가 공개 페이지에도 적용되지만, `AdminSidebarAdvice`는 익명이면 DB 조회 없이 빈 리스트를 반환하고 `/admin/login`이 이미 같은 경로로 정상 서빙 중(선례). MockMvc 테스트에 두 공개 페이지의 **익명 렌더링 200 + advice 예외 없음** 검증 추가. 전용 공개 컨트롤러 분리는 기각(`AdminPageAnnotationConventionTest` 예외 규칙 추가라는 무관 변경 유발, login 선례와 불일치)
  - 변경: **요청 IP는 참고 로그 전용**임을 명시 — 공개 엔드포인트에서 `X-Forwarded-For`는 클라이언트가 위조 가능하므로 보안 판단·차단 근거로 사용 금지 (향후 rate limit 설계 시에도 이 한계 전제)
  - 변경: confirm 페이지에 **토큰 부재/형식 오류 분기** 추가 — 직접 진입·fragment 제거 후 새로고침 시 토큰이 없거나 64-hex가 아니면 제출 버튼 비활성 + "링크가 유효하지 않습니다. 다시 요청해 주세요" 표시 (불필요한 API 400 호출·로그 방지)
  - 변경: **같은 토큰 동시 제출 통합 테스트 추가** — `PESSIMISTIC_WRITE` 직렬화·1차 캐시 회피는 Mockito로 검증 불가. 기존 `AdminMemberUpdateConcurrencyIntegrationTest` 패턴대로 실제 DB 기반 통합 테스트 1개(`PasswordResetConcurrencyIntegrationTest`)로 "동시 제출 2건 중 1건만 성공" 검증
- **v9 (2026-07-12, 적대적 리뷰 라운드 8 반영 — codex, 2건 수용)**
  - 변경: 이메일 공백 처리 경로 정리 — `@Email` 검증은 서비스 trim보다 먼저 실행되므로 공백 포함 입력은 서비스에 도달하지 못하고 400 `VALIDATION_ERROR`가 된다(v5 테스트 기대와 모순이었음). 해결: **페이지 JS가 제출 전 `value.trim()`** + API 직접 호출의 공백 입력은 **400이 정상 동작**임을 명시(조용한 미발송이 아니라 가시적 검증 피드백 — v5의 원래 우려 해소). 서비스 레벨 trim은 심층 방어로 유지, 테스트를 경로별로 분리(서비스: 대문자 lowercase 발송 / MockMvc: 공백 포함 400)
  - 변경: 저장 이메일 정규화 가정 명시 — 기존 생성 경로는 이메일을 정규화하지 않고 저장하므로, `findByEmail` 매칭은 **현재 MariaDB collation(`utf8mb4_general_ci`, 대소문자 무시)에 의존**한다는 사실을 설계 결정에 기재. 생성 경로 정규화·기존 데이터 보정은 무관 변경이라 범위 외 (테스트·CI 모두 MariaDB라 collation 전제 일관)
- **v8 (2026-07-12, 적대적 리뷰 라운드 7 반영 — codex, 1건 수용)**
  - 변경: 발급 단계의 역할 검사를 **denylist → allowlist**로 정정 — "`ROLE_USER`면 return"이 아니라 "`ROLE_ADMIN`/`ROLE_MANAGER`가 **아니면** return". 정책(설계 결정 표·재설정 재검증 단계)은 이미 allowlist인데 발급 단계만 denylist라, 새 역할 추가·데이터 오염 시 재설정 메일 발송 대상이 의도보다 넓어지는 불일치 제거. 서비스 테스트도 "대상 외 역할 미발송"을 allowlist 기준으로 표현
- **v7 (2026-07-12, 적대적 리뷰 라운드 6 반영 — codex, 2건 전부 수용)**
  - 변경: 신규 리포지토리 메서드 시그니처에 **`@Param` 명시** — JPQL named parameter(`:resetToken`, `:id`, `:hashedToken`)는 `-parameters` 컴파일 옵션에 의존하지 않도록 기존 `findByIdForUpdate(@Param("id") ...)` 컨벤션을 따른다
  - 변경: **요청 IP를 시그니처로 연결** — 감사 로그 기준(요청 수신 = 마스킹 이메일 + IP)이 `requestReset(String email)`만으로는 구현 중 누락되기 쉬움. `PasswordResetController`가 `HttpServletRequest`에서 IP를 추출(X-FORWARDED-FOR 마지막 홉 → X-Real-IP → RemoteAddr — `AdminActionLogAspect.getClientIp()`·`VisitLoggingAuthenticationSuccessHandler.extractClientIp()`와 동일 로직, 둘 다 private이라 복제. 공통 유틸 추출은 무관 리팩터링이므로 범위 외)해 `requestReset(email, clientIp)`로 전달
- **v6 (2026-07-12, 적대적 리뷰 라운드 5 반영 — codex, 2건 전부 수용)**
  - 변경: **토큰 조회를 스칼라 id 조회로 전환** — v5의 "엔티티 조회 → 잠금 재조회 → 재검증" 구조는 첫 조회가 엔티티를 영속성 컨텍스트에 올려, 잠금 재조회가 **1차 캐시의 낡은 `resetToken`을 돌려줄 수 있다** (리포지토리 자체가 `findActiveAdminIdsForUpdate()` 주석에 기록해 둔 함정). `List<Long> findIdsByResetToken(hash)` 스칼라 조회로 id만 확보한 뒤 `findByIdForUpdate(id)`가 **최초 엔티티 로드**가 되게 수정 — 잠금 후 재검증이 항상 DB 최신 값을 본다. 중복 탐지는 id 리스트 크기로 동일하게 수행
  - 변경: 이메일 정규화 문구 정정 — `AdminMemberService.normalizeEmail()`은 **private라 직접 재사용 불가**. "동일 규칙(trim + lowercase)의 로직을 `PasswordResetService`에 복제"로 명시 (한 줄 로직이라 공통 유틸 추출은 과함)
- **v5 (2026-07-12, 적대적 리뷰 라운드 4 반영 — codex, 4건 전부 수용)**
  - 변경: **토큰 동시 사용 차단** — `Member`에 `@Version`이 없어 일반 조회 후 수정은 같은 토큰 2건 동시 제출을 막지 못함. `findAllByResetToken`으로 id 확보 후 기존 `findByIdForUpdate()`(PESSIMISTIC_WRITE)로 행 잠금 재조회하고, **잠긴 엔티티에서 토큰 해시·만료를 재검증** — 먼저 커밋한 요청이 토큰을 클리어하므로 두 번째는 재검증에서 400
  - 변경: **`Member.changePassword()`가 reset 토큰을 함께 클리어**하도록 수정 — 재설정 메일 요청 후 기존 비밀번호로 로그인해 변경하면 메일함의 reset 링크가 잔여 TTL 동안 계속 유효한 구멍 차단. `resetPassword()` 도메인 메서드는 `changePassword()` 위임으로 정리, `changeMyPassword()` 후 토큰 무효화 테스트 추가
  - 변경: 세션 만료를 `AdminSessionService` 직접 호출에서 **`AdminSessionRevokeEvent` 발행(AFTER_COMMIT)**으로 교체 — 기존 세션 강제 만료 계약과 일관, 커밋 실패 시 "비밀번호는 안 바뀌고 세션만 만료"되는 불일치 제거. 서비스 의존성에서 `AdminSessionService` 제거, `ApplicationEventPublisher` 추가
  - 변경: `requestReset()` 입력 이메일에 `trim().toLowerCase()` 정규화 명시 — `AdminMemberService.normalizeEmail()`과 동일 규칙 (앞뒤 공백 입력 시 정상 계정이 조용히 미발송되는 실패 모드 방지)
- **v4 (2026-07-12, 적대적 리뷰 라운드 3 반영 — codex, 6건 전부 수용)**
  - 변경: **fragment 방식을 단계별 지시·완료 기준까지 전파** — 단계 5의 메일 링크를 `confirm#token=`으로, 단계 8을 `location.hash` 읽기 + fragment 제거로, 완료 기준을 "fragment 제거"로 수정 (v3 설계 결정과 구현 지시의 내부 모순 해소)
  - 변경: **트랜잭션 경계를 `TransactionTemplate`으로 명시** — 같은 클래스 내부 메서드 호출은 `@Transactional` 프록시를 타지 않으므로(self-invocation 제약), `requestReset()`의 토큰 발급 구간과 발송 실패 클리어 구간은 주입받은 `TransactionTemplate`으로 감싼다. "커밋 후 발송" 계약이 조용히 깨지는 것을 방지
  - 변경: 발송 실패 조건부 클리어를 **조건부 벌크 UPDATE**로 구체화 — `MemberRepository`에 `@Modifying` 쿼리(`... where m.id = :id and m.resetToken = :hash`) 추가. stale entity로 최신 토큰을 덮어쓰는 문제와 검사-후-행동 경합을 원자적으로 제거 (수정 파일에 `MemberRepository.java` 추가)
  - 변경: 토큰 조회를 `findAllByResetToken(hash)` List 조회로 전환 — 결과가 정확히 1건이 아니면(중복 = 데이터 오염 징후) 400 + `log.error`. 유니크 제약 없는 설계에서 `IncorrectResultSizeDataAccessException` 500 경로 차단
  - 변경: dev 골든 패스 검증을 **MailHog(로컬 SMTP 캡처, 일회성 docker)** 기반으로 교체 — "로그에서 재설정 URL 확인"은 토큰 로그 금지 원칙과 모순이고, SMTP 미설정 시 토큰이 클리어되어 링크가 무효이므로 검증 자체가 불가능했음
  - 변경: 쿨다운 역산의 한계 명시 — "TTL 변경에도 안전" 문구 삭제. TTL 변경 배포 직후에는 기존 토큰의 발급 시각 역산이 어긋날 수 있음(영향: 쿨다운 오판, 보안 영향 경미)을 수용 리스크로 기재
- **v3 (2026-07-12, 적대적 리뷰 라운드 2 반영)**
  - 변경: **토큰 전달을 URL fragment(`#token=`) 방식으로 전환** — 서버·프록시 access log와 Referer에 토큰이 아예 도달하지 않음 (fragment는 서버로 전송되지 않음). confirm 페이지 JS가 `location.hash`에서 읽고 `history.replaceState()`로 즉시 제거
  - 변경: 발송 실패 시 토큰 클리어를 **조건부**로 — 내가 발급한 해시와 현재 DB 값이 일치할 때만 클리어 (동시 요청이 갱신한 최신 토큰을 지우는 정합성 버그 방지)
  - 변경: 쿨다운·TTL을 `TOKEN_TTL`(30분)·`REISSUE_COOLDOWN`(60초) 상수로 명시, 발급 시각은 `expiryAt.minus(TOKEN_TTL)`로 계산 (TTL 변경 시 쿨다운이 조용히 깨지지 않도록)
  - 변경: 메일 링크 base URL 정규화 (trailing slash 제거 또는 `UriComponentsBuilder` 사용) + 링크 생성 테스트 추가
  - 변경: 로그 기준 세분화 — 요청 수신은 마스킹 이메일+IP(info), 발송 성공/실패는 memberId 기준(info/error), 미존재·무자격은 debug
  - 변경: 상태·역할 재검증 거부 시 **토큰은 유지** 정책 명문화 (예외 롤백과 일관, TTL 30분이 위험 창 상한) + `PASSWORD_EXPIRED만 ACTIVE로 복귀`(다른 상태는 전이 없음) 테스트 명시
  - 변경: 토큰 충돌 설계 판단 문서화 — `reset_token` 유니크 제약 없음, SHA-256 64-hex 충돌은 사실상 불가능으로 간주하고 `Optional` 단건 조회 유지
  - 변경: 테스트 추가 — 공개 페이지 HTML에 CSRF meta 렌더링 검증(MockMvc), 조건부 클리어 동시성 케이스, base-url trailing slash, 토큰이 DOM 텍스트·console에 남지 않는지(playwright)
  - 기각: "CSRF 실패 401 기대값은 프로젝트 종속이라 위험" — `SecurityConfig.java:79-89`가 미인증 CSRF 실패를 API 경로에서 401로 변환함을 코드로 확인, 계획 기술이 정확함
  - 기각: 토큰 오류 전용 에러 코드(`INVALID_PASSWORD_RESET_TOKEN`) 신설 — 기존 `GlobalApiExceptionHandler`가 `InvalidRequestException`을 400 `INVALID_REQUEST`로 일관 처리하며, 프론트는 상태코드(400)만으로 분기하므로 코드 신설 불필요. 계획에 응답 코드 `INVALID_REQUEST` 사실만 명시
  - 기각: 운영 프로파일에서 `APP_BASE_URL` 기본값 검증 — prod 프로파일 자체가 제거된 상태(#3)라 이번 범위 외. prod 부활 시(로드맵 3단계) 체크리스트로 이관
- **v2 (2026-07-12, 적대적 리뷰 라운드 1 반영)**
  - 변경: confirm 페이지 토큰 유출 방지(외부 리소스 금지 + `Referrer-Policy` 메타 + `history.replaceState()`) 추가
  - 변경: `resetPassword()` 절차에 상태(`ACTIVE`/`PASSWORD_EXPIRED`)·역할(`ROLE_ADMIN`/`ROLE_MANAGER`) 재검증 명시
  - 변경: 이메일 기준 재발급 쿨다운 60초 추가 (스키마 변경 없이 `resetTokenExpiryAt`에서 발급 시각 역산, 응답은 동일 200)
  - 변경: 새 페이지는 `head.html` 미사용 — CSRF meta 태그를 페이지에 직접 추가 (login.html에는 CSRF meta 없음을 확인)
  - 변경: `PasswordResetConfirmRequest`에 `confirmPassword` 추가 + 서비스 일치 검증 (기존 `AdminMyPasswordChangeRequest` 정책 일관)
  - 변경: 메일 발송을 트랜잭션 밖으로 — 토큰 저장 커밋 후 발송, 실패 시 별도 트랜잭션으로 토큰 클리어(best-effort)
  - 변경: 보안 이벤트 로그 기준 명시 (토큰 로그 절대 금지, 이메일 마스킹, IP 포함)
  - 변경: 토큰 DTO에 `@Size(min=64, max=64)` + hex 패턴 검증 추가
  - 변경: `reset_token varchar(255)`·인덱스 없음(소규모 테이블이라 허용) 사실 기재, 204 응답과 JS 처리 일치 명시
  - 기각: 타이밍 부차널(응답 시간으로 계정 존재 추정) 완전 제거 요구 — 이미 명시된 수용 리스크이며 쿨다운 도입으로 반복 측정이 완화됨. 비동기 발송은 이번 범위(미완성 기능 마감)를 초과. IP 기준 rate limit·일일 한도도 같은 이유로 범위 제외.

## 목표

로그인할 수 없는 관리자가 이메일로 비밀번호 재설정 링크를 받아 스스로 비밀번호를 변경할 수 있게 한다.
현재 `Member.resetToken`·`resetTokenExpiryAt` 필드, `MemberRepository.findByResetToken()`, SMTP 설정(`application-dev.yml`의 `spring.mail`)까지 준비되어 있으나 **발송·검증·재설정 로직이 전부 미구현**이다 (CLAUDE.md "핵심 도메인 모델 > Member"에 명시된 미완성 기능).

## ⚠️ 착수 전 필수 확인 (인가 정책 변경 승인)

이 기능은 **비로그인 사용자가 접근하는 공개 경로 4개를 새로 추가**한다. CLAUDE.md 보안 규칙상 "인가 정책(URL 권한, 로그인 정책)은 사전 협의 없이 변경하지 않는다". 구현 시작 전에 아래 경로 목록을 사용자에게 제시하고 승인받아야 한다:

- `GET /admin/password-reset` (요청 페이지)
- `GET /admin/password-reset/confirm` (새 비밀번호 입력 페이지)
- `POST /admin/api/password-reset-requests` (재설정 메일 발송 API)
- `POST /admin/api/password-resets` (토큰으로 비밀번호 재설정 API)

## 설계 결정 (구현 시 그대로 따를 것 — 재질문 불필요)

| 항목 | 결정 |
|------|------|
| 토큰 생성 | `SecureRandom` 32바이트 → hex 문자열 (64자). `UUID.randomUUID()` 사용 금지(엔트로피 낮음). **hex 변환은 `HexFormat.of().formatHex(bytes)`(JDK 17)로 고정 (v14)** — `BigInteger.toString(16)`류는 선행 0 소실로 64자 미만이 될 수 있음. SHA-256 해시의 hex 변환도 동일 |
| 토큰 저장 | **SHA-256 해시 hex(64자)** 를 `reset_token` 컬럼에 저장. 평문 토큰은 메일 링크에만 존재. DB 유출 시에도 토큰 재사용 불가 |
| 토큰 유효 시간 | 발급 시점 + **30분** (`resetTokenExpiryAt`). `TOKEN_TTL` 상수로 정의 (v3) |
| 토큰 전달 방식 | 메일 링크는 **URL fragment** — `{baseUrl}/admin/password-reset/confirm#token={평문토큰}`. fragment는 서버로 전송되지 않아 access log·프록시 로그·Referer에 토큰이 남지 않는다 (v3). **위협 모델 정확화 (v13)**: 이 보호는 **링크 진입(GET) 단계까지만**이다 — 재설정 제출 시 토큰은 `POST /admin/api/password-resets` 요청 본문으로 서버에 도달한다. 따라서 ① `PasswordResetConfirmRequest`에 `@ToString`/`@Data` 금지, ② 컨트롤러·서비스에서 DTO 자체 로깅 금지, ③ 검증 실패 응답·로그가 rejected value(토큰)를 노출하지 않는지 확인, ④ 향후 요청 바디 로깅 필터·access log·observability 도입 시 이 경로의 body 마스킹 필수 |
| 토큰 충돌·중복 | `reset_token`에 유니크 제약 없음. SHA-256 hex 64자 충돌은 사실상 불가능으로 간주. 단, 데이터 오염·수동 수정·테스트 픽스처 중복으로 같은 해시가 2행 이상 존재할 수 있으므로 조회는 **`findIdsByResetToken(hash)` 스칼라 id List**로 하고 (v6 — 엔티티 조회 금지, 1차 캐시 오염 방지), 결과가 정확히 1건이 아니면 `InvalidRequestException`(400) + `log.error`(이상 징후 탐지) — `IncorrectResultSizeDataAccessException` 500 경로 차단 (v3, v4 중복 처리 구체화) |
| 재검증 거부 시 토큰 | **유지** (클리어하지 않음) — 예외 발생 시 트랜잭션 롤백과 일관되고, TTL 30분이 위험 창의 상한. 잠금 해제 후 기존 링크가 잔여 TTL 동안 다시 유효해질 수 있음은 수용 (v3) |
| 계정 존재 노출 방지 | 요청 API는 이메일 존재 여부·계정 상태와 무관하게 **항상 200 OK 동일 응답** |
| 재설정 허용 상태 | `ACTIVE`, `PASSWORD_EXPIRED`만 메일 발송. `LOCKED`/`DISABLED`/`DELETED`는 발송하지 않되 응답은 동일하게 200 |
| 대상 역할 | `ROLE_ADMIN`, `ROLE_MANAGER`만. `ROLE_USER`는 발송 대상 아님 |
| 토큰 사용 후 | 즉시 `resetToken`·`resetTokenExpiryAt`을 null로 클리어 (일회용) |
| 토큰 동시 사용 차단 | `Member`에 `@Version` 없음 → 일반 조회 후 수정은 동시 제출 2건을 모두 성공시킬 수 있다. **`findIdsByResetToken(hash)` 스칼라 조회**로 id만 확보 (엔티티를 영속성 컨텍스트에 올리지 않음, v6) → 기존 `findByIdForUpdate(id)`(PESSIMISTIC_WRITE)가 **최초 엔티티 로드** → 잠긴 최신 엔티티에서 토큰 해시·만료 재검증 후 진행. 먼저 커밋한 쪽이 토큰을 클리어하므로 나중 요청은 재검증에서 400 (v5, v6에서 1차 캐시 함정 제거) |
| 타 경로 비밀번호 변경 시 토큰 무효화 | `Member.changePassword()`가 `pwd` 교체와 함께 `resetToken`·`resetTokenExpiryAt`을 null로 클리어하도록 수정 — 모든 비밀번호 변경 성공 경로(본인 변경 포함)에서 outstanding reset 링크가 즉시 무효화된다 (v5). **호출부 전수 확인 완료 (v11)**: 프로덕션 호출부는 `AdminMemberService.changeMyPassword()` 1곳뿐(실변경 경로) — 비밀번호가 바뀌지 않는데 호출되는 경로 없음, 의도치 않은 토큰 클리어 부작용 없음 |
| 재설정 시 재검증 | `resetPassword()`에서 상태(`ACTIVE`/`PASSWORD_EXPIRED`)와 역할(`ROLE_ADMIN`/`ROLE_MANAGER`)을 **반드시 다시 검증** — 토큰 발급 후 계정이 잠기거나 역할이 바뀐 경우 400 (v2) |
| 재발급 쿨다운 | 동일 계정에 유효 토큰이 있고 발급 후 **60초 미만**이면 재발급·재발송 없이 조용히 return (응답은 동일 200). `REISSUE_COOLDOWN` 상수(60초)로 정의하고 발급 시각은 `resetTokenExpiryAt.minus(TOKEN_TTL)`로 계산 — 새 컬럼 불필요. **한계 (v4)**: 이 역산은 기존 토큰이 현재 코드와 같은 TTL로 발급됐다고 가정한다. TTL 상수를 바꿔 배포한 직후에는 기존 토큰의 발급 시각 추정이 어긋나 쿨다운이 오판될 수 있음(보안 영향 경미)을 수용 리스크로 인지 (v2, v3 상수화). **원자성 (v12)**: 발급 트랜잭션은 `findByEmailForUpdate()`(PESSIMISTIC_WRITE)로 계정 행을 잠근 뒤 쿨다운 검사·토큰 저장을 수행 — 동시 요청 2건이 모두 "유효 토큰 없음"으로 판단해 중복 발급·중복 발송하는 check-then-act 경합 제거 (잠금 조회가 이 트랜잭션의 최초 엔티티 로드이므로 1차 캐시 문제 없음) |
| 재설정 성공 시 | 해당 계정의 기존 세션 전부 만료 — `AdminSessionService` 직접 호출이 아니라 **`AdminSessionRevokeEvent(memberId)` 발행** (기존 AFTER_COMMIT 리스너가 커밋 후 만료 처리, v5 — 커밋 실패 시 세션만 만료되는 불일치 방지 + 기존 계약 일관). **코드 확인 완료 (v11)**: `AdminSessionRevokeListener.onRevoke()`는 `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`(`AdminSessionRevokeListener.java:23`) — 가정 아닌 확인된 사실 |
| 요청 이메일 정규화 | `requestReset()` 진입 시 `trim().toLowerCase(Locale.ROOT)` (v12 — 기본 Locale 의존 금지: 터키어 `I/i` 등에서 조회 불일치 유발) — `AdminMemberService.normalizeEmail()`과 동일 규칙. 단 해당 메서드는 **private라 직접 재사용 불가** → 동일 로직을 `PasswordResetService`에 private로 복제한다 (v5, v6 문구 정정). 기존 `normalizeEmail()`의 기본 Locale 의존은 무관 변경이라 이번 범위 외 — 후속 정리 대상 (v12). **공백 처리의 실제 1차 방어는 페이지 JS의 `trim()`** — `@Email` 검증이 서비스보다 먼저 실행되므로 공백 포함 API 직접 호출은 400 `VALIDATION_ERROR`가 되며 이는 정상 동작(가시적 피드백)이다. 서비스 trim은 심층 방어 (v9) |
| 저장 이메일 대소문자 가정 | 기존 생성 경로는 이메일을 정규화 없이 저장하므로 `findByEmail` 매칭은 **현재 MariaDB collation(`utf8mb4_general_ci`, 대소문자 무시)에 의존**한다. 생성 경로 정규화·데이터 보정은 무관 변경이라 이번 범위 외. collation이 다른 DB로 테스트를 옮기면(예: Testcontainers 전환 시 설정 변경) 이 가정을 재점검할 것 (v9) |
| 비밀번호 검증 규칙 | 기존 `AdminMyPasswordChangeRequest`의 새 비밀번호 필드와 **동일한 Bean Validation 제약**을 복사해 적용. `confirmPassword` 필드도 포함하고 서비스에서 일치 검증 (기존 정책 일관, v2) |
| 토큰 입력 검증 | `PasswordResetConfirmRequest.token`에 `@Size(min=64, max=64)` + `@Pattern(regexp = "^[0-9a-f]{64}$")` (v2) |
| 메일 링크 base URL | `app.base-url` 프로퍼티(`application.yml`에 이미 존재, `${APP_BASE_URL:http://localhost:8080}`) 사용. **trailing slash 제거 등 정규화 후 결합** (또는 `UriComponentsBuilder`) — `//admin/...` 링크 방지 (v3) |
| 메일 발송 시점 | **토큰 저장 트랜잭션 커밋 후** 발송 (SMTP 지연이 DB 커넥션을 점유하지 않도록). 발송 실패 시 별도 트랜잭션으로 **조건부** 토큰 클리어(best-effort) + `log.error` (v2) — 클리어는 `MemberRepository`의 조건부 벌크 UPDATE(`where m.id = :id and m.resetToken = :hash`)로 수행해 **내가 발급한 해시와 일치할 때만** 원자적으로 지운다. 그 사이 다른 요청이 새 토큰을 발급했다면 건드리지 않는다 (v3 stale clear 방지, v4 벌크 UPDATE로 구체화) |
| 트랜잭션 경계 구현 | 같은 클래스 내부 메서드 호출은 `@Transactional` 프록시를 타지 않는다(self-invocation 제약). `requestReset()`의 토큰 발급 구간·발송 실패 클리어 구간은 주입받은 **`TransactionTemplate`**으로 감싸 경계를 코드에 명시한다 — 별도 컴포넌트 분리 없이 "커밋 후 발송" 계약을 보장 (v4) |
| 스키마 변경 | **불필요** — `reset_token varchar(255)`, `reset_token_expiry_at datetime(6)` 컬럼이 V1 스키마에 이미 존재 (해시 hex 64자 수용 충분). `reset_token` 인덱스 없음 — 관리자 테이블은 소규모라 풀스캔 허용 (v2) |
| 감사 로그 | `@AdminActionLogged`는 인증 컨텍스트 기준이므로 **사용하지 않는다**(비로그인 흐름). 대신 서비스 로그로 남기되 **토큰(평문·해시 모두) 로그 절대 금지** (v2). 세분화 기준 (v3): 요청 수신 = 마스킹 이메일(`ab***@domain`) + 요청 IP, `info` / 발송 성공·실패 = `memberId` 기준, `info`/`error` / 미존재 이메일·무자격(역할/상태) = `debug` (계정 열거 흔적으로 로그가 불필요하게 쌓이지 않도록) |
| confirm 페이지 토큰 보호 | 외부 리소스(Google Fonts 등) 로드 금지 + `<meta name="referrer" content="no-referrer">` (심층 방어) + 페이지 로드 직후 `location.hash`에서 토큰을 JS 변수로 옮기고 `history.replaceState()`로 fragment 제거 (v2, v3에서 fragment 방식으로 전환). 토큰을 DOM 텍스트·`console.log`에 출력하지 않는다 (v3) |
| 오류 응답 코드 | 토큰 무효/만료/사용됨/상태 거부 모두 `InvalidRequestException` → 기존 `GlobalApiExceptionHandler`가 400 `INVALID_REQUEST`로 반환. 전용 코드 신설 없음 — 프론트는 HTTP 400 여부로만 분기 (v3) |

## 수정해야 할 정확한 파일

### 신규 생성
| 파일 | 내용 |
|------|------|
| `src/main/java/com/cms/admin/member/service/PasswordResetService.java` | 토큰 발급·메일 발송·토큰 검증·비밀번호 재설정 |
| `src/main/java/com/cms/admin/member/controller/PasswordResetController.java` | `@RestController`, 공개 API 2개 |
| `src/main/java/com/cms/admin/member/dto/request/PasswordResetRequestRequest.java` | `{ "email": "..." }`, `@NotBlank @Email` |
| `src/main/java/com/cms/admin/member/dto/request/PasswordResetConfirmRequest.java` | `{ "token": "...", "newPassword": "...", "confirmPassword": "..." }` — token은 64자 hex 패턴 검증 (v2). **`@ToString`/`@Data` 금지** — 토큰·비밀번호가 로그에 실릴 수 있는 표현 생성 금지 (v13) |
| `src/main/resources/templates/admin/password-reset.html` | 이메일 입력 폼 (login.html 레이아웃 복사 기반) |
| `src/main/resources/templates/admin/password-reset-confirm.html` | 새 비밀번호 입력 폼 |
| `src/test/java/com/cms/admin/member/service/PasswordResetServiceTest.java` | 서비스 단위 테스트 |
| `src/test/java/com/cms/admin/member/controller/PasswordResetControllerTest.java` | MockMvc 테스트 |

### 수정
| 파일 | 변경 내용 |
|------|-----------|
| `src/main/java/com/cms/config/SecurityConfig.java` | 43행 `requestMatchers("/admin/login", "/admin/login-error").permitAll()`에 위 공개 경로 4개 추가 |
| `src/main/java/com/cms/admin/member/domain/Member.java` | 도메인 메서드 2개 추가: `issueResetToken(String hashedToken, LocalDateTime expiryAt)`, `clearResetToken()`. **기존 `changePassword(String encodedPwd)` 수정 (v5)**: `pwd` 교체 + `updateDate` 갱신에 더해 `resetToken`·`resetTokenExpiryAt` null 클리어 — 모든 비밀번호 변경 경로에서 outstanding reset 링크 무효화. 별도 `resetPassword()` 도메인 메서드는 두지 않고 `changePassword()`를 그대로 사용 |
| `src/main/java/com/cms/admin/member/repository/MemberRepository.java` | 메서드 3개 추가 (v4, v6, v7, v12): `@Query("select m.id from Member m where m.resetToken = :resetToken") List<Long> findIdsByResetToken(@Param("resetToken") String resetToken)` (**스칼라 id 조회** — 엔티티를 1차 캐시에 올리지 않아 이후 잠금 조회가 최신 값을 봄. 중복 행 탐지 겸용. 기존 `findByResetToken` Optional 조회는 사용하지 않음), `@Modifying(clearAutomatically = true, flushAutomatically = true) @Query` 조건부 토큰 클리어 `int clearResetTokenIfMatches(@Param("id") Long id, @Param("hashedToken") String hashedToken)` (`update Member m set m.resetToken = null, m.resetTokenExpiryAt = null where m.id = :id and m.resetToken = :hashedToken` — 벌크 UPDATE는 영속성 컨텍스트를 우회하므로 clear/flush 옵션으로 stale entity 재발 방어, v13), `@Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select m from Member m where m.email = :email") Optional<Member> findByEmailForUpdate(@Param("email") String email)` (v12 — 발급 트랜잭션의 쿨다운 원자성. `uk_member_email` 유니크라 단건 보장) — **`@Param`은 기존 `findByIdForUpdate` 컨벤션대로 반드시 명시** (v7) |
| `src/main/java/com/cms/admin/AdminMainController.java` | 페이지 서빙 메서드 2개 추가: `GET /admin/password-reset`, `GET /admin/password-reset/confirm` — 기존 `/admin/login` 서빙과 동일 패턴. **주의 (v10)**: 이 컨트롤러에는 `@AdminPage`가 붙어 있어 `AdminSidebarAdvice`·`AdminViewAdvice`가 공개 페이지에도 적용된다. `AdminSidebarAdvice`는 익명 요청이면 DB 조회 없이 빈 리스트를 반환하므로(코드 확인, login 페이지 선례) 안전하지만, MockMvc 테스트로 두 페이지의 익명 렌더링(200, advice 예외 없음)을 반드시 검증한다. 전용 공개 컨트롤러 분리는 컨벤션 테스트 예외 규칙이 필요한 무관 변경이라 하지 않는다 |
| `src/main/resources/templates/admin/login.html` | 비밀번호 입력 아래에 "비밀번호를 잊으셨나요?" 링크 추가 (`/admin/password-reset`) |

## 단계별 작업 순서

1. **브랜치 생성**: `git checkout -b feat/password-reset`
2. **의존성 확인**: `build.gradle`에 `spring-boot-starter-mail`이 있는지 확인한다. 없으면 추가한다 (CLAUDE.md 기준 이미 존재할 것).
3. **Member 도메인 메서드 작업** (`Member.java`, v5): `issueResetToken(hash, expiryAt)`·`clearResetToken()` 2개를 새로 만들고, **기존 `changePassword(encodedPwd)`를 수정**해 `pwd` 교체 + `updateDate` 갱신 + `resetToken`/`resetTokenExpiryAt` null 클리어를 한 번에 수행하게 한다 (재설정 경로·본인 비밀번호 변경 경로 공용 — 기존 호출부는 시그니처 변경 없음).
4. **DTO 2개 작성**: 검증 어노테이션 포함. `PasswordResetConfirmRequest.newPassword`에는 `AdminMyPasswordChangeRequest`의 새 비밀번호 필드와 동일한 제약을 복사하고, `confirmPassword`(`@NotBlank`)와 `token`(64자 hex `@Pattern`) 필드를 함께 둔다 (v2).
5. **PasswordResetService 작성**:
   - `@Service` + **명시적 생성자** (v15 — `@RequiredArgsConstructor` 금지: `final @Value` 필드 조합은 Lombok 생성자 파라미터에 `@Value`가 복사되지 않아 주입 실패. 이 클래스만 예외적으로 생성자를 직접 작성하고 `@Value("${app.base-url}")`는 생성자 파라미터에 부착). 의존성: `MemberRepository`, `PasswordEncoder`, `JavaMailSender`, `ApplicationEventPublisher` (v5 — `AdminSessionService` 직접 호출 금지), `TransactionTemplate` (v4), `Clock` (v15 — 시간 분기 전부 이 Clock 기준: 운영은 `Clock.systemDefaultZone()` 빈, 테스트는 고정 Clock. `LocalDateTime.now(clock)` 사용), `String baseUrl`. **생성자에서 `baseUrl` 검증 (v14, v15 구체화)**: URI 파싱 + `http`/`https` scheme + host 존재 + userinfo 없음 + query/fragment 없음 (경로는 허용 — 프록시 하위 경로 배포 대비). 위반 시 예외로 기동 실패 — 커밋 후 링크 생성 단계에서 처음 터지는 것 방지.
   - `requestReset(String email, String clientIp)` (v7 — IP는 요청 수신 로그 전용, 그 외 용도 없음) — **트랜잭션 분리 구조** (v2): 토큰 발급과 메일 발송(트랜잭션 밖)을 분리한다. **주의 (v4)**: 같은 클래스 내부 호출은 `@Transactional` 프록시를 타지 않으므로, 공개 진입 메서드 `requestReset()`에는 `@Transactional`을 붙이지 않고 아래 `[트랜잭션]` 구간(1~4)을 주입받은 `TransactionTemplate.execute()` 람다 하나로 감싼다. **발급 결과 모델 (v13)**: 람다의 반환 타입을 `Optional<IssueResult>`로 고정한다 — `private record IssueResult(Long memberId, String email, String plainToken, String hashedToken) {}` (서비스 내부 record, `email`은 **DB 저장값 `member.getEmail()`** — 정규화 입력값 아님). **`toString()`을 반드시 오버라이드해 memberId만 노출한다 (v14)** — record 기본 `toString()`은 평문 토큰·해시를 그대로 포함하므로 우발적 로깅 한 줄로 토큰이 유출된다. 일반 원칙: 토큰·비밀번호를 담는 모든 타입은 자동 문자열화(record 기본 toString, `@ToString`, `@Data`) 금지. 미존재/무자격/쿨다운의 "조용히 return"은 전부 `Optional.empty()` 반환이고, **empty면 커밋 후 발송 단계(5~6)를 실행하지 않는다**:
     1. `[트랜잭션]` 이메일을 `trim().toLowerCase(Locale.ROOT)` 정규화(v12 — 기본 Locale 금지. `AdminMemberService.normalizeEmail()`과 동일 규칙 — private이므로 로직 복제, v5·v6) 후 `memberRepository.findByEmailForUpdate(정규화된 email)` **잠금 조회** (v12 — 쿨다운 검사~토큰 저장의 check-then-act 경합 차단. 이 트랜잭션의 최초 엔티티 로드라 1차 캐시 문제 없음). 없으면 **조용히 return** (예외 던지지 않음). **잠금 실패도 조용히 200 (v15)**: `PessimisticLockingFailureException` 계열(락 타임아웃·데드락)이 500으로 새면 "해당 이메일 행 존재 + 경합 중" 신호가 됨 — catch 후 발급 없이 종료, 마스킹 이메일(또는 memberId) + 예외 클래스명만 로그.
     2. `[트랜잭션]` 상태가 `ACTIVE`/`PASSWORD_EXPIRED`가 아니거나 `userType`이 **`ROLE_ADMIN`/`ROLE_MANAGER`가 아니면** 조용히 return (v8 — allowlist. denylist 금지: 새 역할 추가·데이터 오염 시 발송 대상이 넓어진다).
     3. `[트랜잭션]` **쿨다운 검사** (v2): 유효 토큰이 존재하고 발급 후 60초 미만(`resetTokenExpiryAt > now + 29분`)이면 재발급 없이 조용히 return.
     4. `[트랜잭션]` `SecureRandom`으로 32바이트 → hex 평문 토큰 생성, SHA-256 해시를 `member.issueResetToken(hash, now.plusMinutes(30))`으로 저장하고 `Optional.of(new IssueResult(member.getId(), member.getEmail(), 평문토큰, 해시))` 반환 (`TransactionTemplate` 람다 종료 = 커밋, v13).
     5. `[커밋 후]` `IssueResult`가 empty면 여기서 종료 (v13). 존재하면 메일 발송: 수신자는 **`IssueResult.email`(DB 저장값)** — 정규화 입력값으로 보내지 않는다 (v13). 제목 "[CMS] 비밀번호 재설정 안내", 본문에 `{baseUrl}/admin/password-reset/confirm#token={평문토큰}` 링크(**fragment — 쿼리스트링 아님**, v4)와 30분 유효 안내. `SimpleMailMessage` 사용.
     6. `[커밋 후]` 발송 구간 실패 시: **catch 범위는 `MailException`만이 아니라 커밋 후 전 구간(링크 생성 + 메시지 구성 + send)의 `Exception`** (v14 — `baseUrl` 이상값·URI 조립 예외·레거시 이메일 비정상 값 등 발송 전 예외도 토큰이 이미 커밋된 상태라 동일 정리 필요). catch 시 **별도 `TransactionTemplate` 실행**으로 `memberRepository.clearResetTokenIfMatches(memberId, 발급한 해시)` 조건부 벌크 UPDATE 호출(best-effort, v4 — stale entity 저장 금지) 후 `log.error`만 남기고 정상 return (응답 균일성 유지). **클리어 자체가 실패해도(락 타임아웃·DB 오류) 예외를 삼키고 200을 유지한다 (v14)** — 토큰은 30분 뒤 자연 만료. 토큰·이메일 원문을 로그에 남기지 않는다. **로그 형식 주의 (v12)**: `MailSendException`의 메시지·failedMessages에는 메일 본문(fragment 토큰 포함)이 들어갈 수 있으므로 **throwable·`e.getMessage()`를 그대로 로깅하지 않는다** — `log.error("비밀번호 재설정 메일 발송 실패 memberId={}, exceptionType={}", memberId, e.getClass().getSimpleName())` 형태로 memberId + 예외 클래스명만 기록 (`log.error("...", e)` 금지, 클리어 실패 로그도 동일 형식).
   - `resetPassword(String token, String newPassword, String confirmPassword)` — `@Transactional` (컨트롤러가 직접 호출하는 진입 메서드이므로 프록시 적용됨):
     1. `newPassword`와 `confirmPassword` 불일치 시 `InvalidRequestException` (400) — 기존 `AdminMemberService`의 비밀번호 변경 검증과 동일 패턴.
     2. 입력 토큰을 SHA-256 해시 후 `findIdsByResetToken(hash)` **스칼라 id List** 조회 (v4, v6 — 엔티티 조회 금지: 여기서 엔티티를 로드하면 다음 단계의 잠금 조회가 1차 캐시의 낡은 인스턴스를 돌려준다). 결과가 0건이면 `InvalidRequestException("유효하지 않은 재설정 토큰입니다.")` (400). 2건 이상이면 **동일 메시지의 400** + `log.error`(중복 토큰 행 = 데이터 오염 징후, memberId 목록 기재 — 토큰 값은 로그 금지).
     3. **행 잠금 + 재검증** (v5, v6): 확보한 id로 `findByIdForUpdate(id)`(PESSIMISTIC_WRITE) 조회 — 이 시점이 **최초 엔티티 로드**라 DB 최신 값이 보장된다. 잠긴 엔티티의 `resetToken`이 입력 해시와 일치하는지 다시 확인한다 — 불일치(그 사이 다른 요청이 사용·클리어)면 동일 메시지의 400. 같은 토큰 동시 제출 시 먼저 커밋한 쪽만 성공한다. **잠금 실패 처리 (v14)**: `PessimisticLockingFailureException`(락 타임아웃·데드락)은 기존 `GlobalApiExceptionHandler`가 409로 매핑하는데, 락 경합 발생 자체가 "유효 토큰 존재 + 사용 중"을 간접 노출하므로 **서비스에서 catch해 동일 메시지의 `InvalidRequestException`(400)으로 감싼다** — 사유 비구분 400 정책과 통일. **변환 직전에 서버 로그 필수 (v15)**: memberId 후보 + 예외 클래스명만 (토큰·해시·throwable 금지) — 사용자에게 숨긴 실제 DB 장애의 운영 관측성 확보.
     4. `resetTokenExpiryAt`이 null이거나 현재 시각 이전이면 동일 메시지의 `InvalidRequestException` (만료/무효를 구분해 노출하지 않는다). 이하 검사도 전부 잠긴 엔티티 기준.
     5. **상태·역할 재검증** (v2): 상태가 `ACTIVE`/`PASSWORD_EXPIRED`가 아니거나 역할이 `ROLE_ADMIN`/`ROLE_MANAGER`가 아니면 동일 메시지의 400 — 토큰 발급 후 잠긴/삭제된/강등된 계정 차단.
     6. `member.changePassword(passwordEncoder.encode(newPassword))` 호출 (v5 — 토큰 클리어 포함). 상태가 `PASSWORD_EXPIRED`였다면 `member.changeStatus(MemberStatus.ACTIVE)`로 복귀시킨다.
     7. `eventPublisher.publishEvent(new AdminSessionRevokeEvent(member.getId()))` 발행 (v5) — 기존 AFTER_COMMIT 리스너가 커밋 성공 후 대상 세션을 만료한다. 롤백 시 이벤트는 소비되지 않는다.
6. **PasswordResetController 작성**: `@RestController` + `@RequestMapping("/admin/api")`.
   - `POST /password-reset-requests` → `@Valid @RequestBody PasswordResetRequestRequest` + `HttpServletRequest` → 클라이언트 IP 추출 후 `requestReset(email, clientIp)` → `200 OK` 빈 본문. **IP 추출** (v7): X-FORWARDED-FOR 마지막 홉 → X-Real-IP → `getRemoteAddr()` 순 — `AdminActionLogAspect.getClientIp()`와 동일 로직을 복제한다 (기존 2곳 모두 private, `VisitLoggingAuthenticationSuccessHandler`도 같은 이유로 복제한 선례 있음. 공통 유틸 추출은 무관 리팩터링이라 이번 범위 외). **이 IP는 참고 로그 전용이다 (v10)** — 공개 엔드포인트에서 이 헤더들은 클라이언트가 위조할 수 있으므로 보안 판단·차단·rate limit의 근거로 사용하지 않는다.
   - `POST /password-resets` → `@Valid @RequestBody PasswordResetConfirmRequest` → `resetPassword()` → `204 No Content`.
7. **SecurityConfig 수정**: 43행의 permitAll 목록에 `"/admin/password-reset", "/admin/password-reset/confirm", "/admin/api/password-reset-requests", "/admin/api/password-resets"` 추가. **다른 규칙 순서는 건드리지 않는다.**
8. **페이지 2개 작성**: `login.html`을 복사해 레이아웃 유지. 단, **`login.html`은 `head.html` 프래그먼트를 쓰지 않고 CSRF meta도 없음을 확인했으므로** (v2) 두 페이지 모두 Thymeleaf로 CSRF meta 태그(`<meta name="_csrf" th:content="${_csrf.token}">`, `<meta name="_csrf_header" th:content="${_csrf.headerName}">`)를 직접 추가한다.
   - `password-reset.html`: 이메일 입력 → **제출 전 JS에서 `value.trim()`** (v9 — `@Email`이 공백 포함 값을 400으로 거부하므로 클라이언트가 1차 정리) → fetch로 `POST /admin/api/password-reset-requests`. CSRF 토큰은 위 meta에서 읽어 `X-CSRF-TOKEN` 헤더로 전송. 성공 시 "입력하신 이메일로 안내를 보냈습니다" 고정 문구 표시.
   - `password-reset-confirm.html`: **토큰 유출 방지 3종** (v2, v4에서 fragment 기준으로 정정) — ① 외부 리소스(Google Fonts CDN `<link>` 등) 제거(로컬 vendor 리소스만 사용), ② `<meta name="referrer" content="no-referrer">`, ③ 페이지 로드 직후 **`location.hash`에서 fragment의 `token`을 JS 변수로 옮기고 `history.replaceState()`로 fragment를 URL에서 제거** (쿼리스트링 아님 — 메일 링크가 `#token=` 형식). **토큰 부재/형식 오류 분기 (v10)**: 옮긴 토큰이 없거나 64자 hex 형식이 아니면(직접 진입, fragment 제거 후 새로고침 등) 제출 버튼을 비활성화하고 "링크가 유효하지 않습니다. 다시 요청해 주세요." + 요청 페이지 링크를 표시 — API 호출까지 가지 않는다. 새 비밀번호 + 비밀번호 확인 입력 → `POST /admin/api/password-resets` (204는 `response.ok === true`로 처리됨 — 성공 판정에 본문 파싱 금지, v2). 성공 시 `/admin/login`으로 이동 버튼 표시, 400이면 "링크가 유효하지 않거나 만료되었습니다. 다시 요청해 주세요." 표시. **401/403 분기 (v14)**: 페이지를 오래 열어 두어 세션/CSRF 토큰이 만료된 경우 — "세션이 만료되었습니다. 메일의 링크를 다시 클릭해 다시 시도해 주세요." 표시 (reset 토큰은 미소비라 아직 유효. 새로고침 안내는 금지 — `replaceState`로 fragment가 이미 제거돼 새로고침하면 토큰이 사라진다).
9. **AdminMainController에 페이지 매핑 추가**, **login.html에 링크 추가**.
10. **테스트 작성**:
    - 서비스: 정상 발급/발송, 미존재 이메일 무반응, **대문자 이메일 입력도 lowercase 정규화되어 발송** (v5, v9 — 공백 케이스는 서비스가 아니라 MockMvc 검증으로 이동), LOCKED 계정 미발송, **allowlist 외 역할 미발송(`ROLE_ADMIN`/`ROLE_MANAGER`가 아닌 역할은 발송 안 됨, v8)**, 만료 토큰 거부, 사용 후 토큰 클리어, PASSWORD_EXPIRED → ACTIVE 복귀, **세션 만료 이벤트 발행 검증(`AdminSessionRevokeEvent` 발행 여부 — publisher mock verify 또는 `@RecordApplicationEvents`, v5)**, **쿨다운(60초 내 재요청 시 토큰 미변경·메일 미발송), 재설정 시점 상태·역할 재검증 거부, confirmPassword 불일치 400, 발송 실패 시 조건부 클리어**(내 해시일 때만 클리어 — 다른 해시로 바뀌어 있으면 미클리어, v4), **중복 토큰 행(2건 이상) 시 400 + error 로그** (v4), **잠금 후 재검증 거부(락 획득 시점에 토큰이 이미 클리어/교체된 경우 400 — 동시 제출 직렬화 로직 검증, v5)**, **`changePassword()` 호출 시 reset 토큰 클리어(본인 비밀번호 변경 후 기존 reset 링크 무효, v5 — `AdminMemberServiceTest`에 추가)**. `JavaMailSender`는 mock.
    - 컨트롤러(MockMvc): 미인증 접근 허용(200/204), 검증 실패 400, **공백 포함 이메일(`" admin@test.com "`) 요청이 400 `VALIDATION_ERROR`로 거부됨** (v9 — `@Email`이 서비스 진입 전에 차단하는 것이 명세임을 고정), CSRF 누락 시 401(미인증 CSRF 실패는 `SecurityConfig`가 401로 변환함에 유의), **공개 페이지 2개(`GET /admin/password-reset`, `GET /admin/password-reset/confirm`)의 익명 렌더링 200 — `@AdminPage` advice(`AdminSidebarAdvice`·`AdminViewAdvice`)가 익명 요청에서 예외 없이 동작하는지 확인** (v10).
    - 동시성 통합 테스트 (v10): `PasswordResetConcurrencyIntegrationTest` — 기존 `AdminMemberUpdateConcurrencyIntegrationTest` 패턴(실제 DB + `CountDownLatch`/`ExecutorService`)대로, **같은 토큰으로 동시 재설정 제출 2건 중 정확히 1건만 성공하고 나머지는 400**을 검증한다 (`PESSIMISTIC_WRITE` 직렬화·스칼라 조회의 1차 캐시 회피는 Mockito로 검증 불가). **동시 발급 케이스 추가 (v12)**: 같은 이메일 동시 요청 2건 중 **1건만 토큰을 발급·발송**(다른 1건은 쿨다운 return)을 같은 테스트 클래스에서 검증 — `findByEmailForUpdate` 직렬화 확인.
11. **검증**: `./gradlew test` 전체 통과 확인. **메일 확인은 MailHog 사용** (v4 — 로그 URL 확인은 토큰 로그 금지 원칙과 모순이며, SMTP 발송 실패 시 토큰이 클리어되어 링크가 무효이므로 불가): `docker run -d --name mailhog -p 1025:1025 -p 8025:8025 mailhog/mailhog` 기동 후 dev 프로파일의 `spring.mail`을 `host: localhost, port: 1025`(인증 없음)로 지정해 앱 실행. playwright로 로그인 페이지 링크 → 요청 페이지 → MailHog 웹 UI(`http://localhost:8025`)에서 메일 링크 확인 → confirm 페이지 → 재설정 → 새 비밀번호 로그인 골든 패스 확인. 검증 종료 후 컨테이너 제거.
12. **커밋·PR**: 한국어 커밋 메시지, Squash merge 대상 PR 생성.

## 엣지 케이스

1. **존재하지 않는 이메일**: 200 동일 응답. 응답 시간 차이로 존재 여부가 새는 것은 이 단계에서 허용(추후 비동기 발송으로 개선 가능 — 이번 범위 아님. 쿨다운 도입으로 반복 측정은 완화됨 — v2 리뷰에서 기각 사유와 함께 재확인).
2. **재요청**: 발급 후 60초 이내면 조용히 무시(쿨다운, v2). 60초 경과 후에는 기존 토큰이 있어도 새 토큰으로 **덮어쓴다** (이전 링크는 즉시 무효).
3. **만료된 토큰 / 이미 사용된 토큰 / 위조 토큰**: 모두 동일한 400 메시지. 어떤 사유인지 구분해 노출하지 않는다.
4. **토큰 검증 직전에 계정이 LOCKED/DISABLED/DELETED로 변경되거나 역할이 대상 외로 바뀐 경우**: `resetPassword()`에서 상태·역할을 다시 확인해 `ACTIVE`/`PASSWORD_EXPIRED` + `ROLE_ADMIN`/`ROLE_MANAGER`가 아니면 400으로 거부한다 (v2 — 단계 5에도 명시).
5. **메일 발송 실패** (SMTP 미설정 dev 환경 포함): 500을 반환하지 않고 200 유지, 커밋 후 별도 트랜잭션에서 조건부 벌크 UPDATE(`clearResetTokenIfMatches`)로 토큰 클리어(best-effort) + `log.error` (v2, v4). 클리어 자체가 실패해도 토큰은 30분 뒤 자연 만료.
5-1. **같은 해시의 토큰 행이 2건 이상 발견** (데이터 오염·픽스처 중복 등, v4): `findIdsByResetToken` 결과 크기 검사로 동일 메시지 400 + `log.error` — 500이 발생하지 않는다 (v6 스칼라 조회 기준).
6. **CSRF**: 프로젝트는 모든 경로에 CSRF 활성. 공개 API 2개도 CSRF 토큰이 필요하므로 페이지 JS가 반드시 `X-CSRF-TOKEN` 헤더를 보낸다. 페이지 없이 API만 직접 호출하는 시나리오는 지원하지 않는다.
7. **동시 재설정 요청 2건**: `findByEmailForUpdate` 행 잠금으로 발급이 직렬화된다 (v12) — 동시 요청 중 먼저 잠근 쪽이 발급하고, 나중 요청은 쿨다운 검사에서 조용히 return (중복 발급·중복 발송 없음). 쿨다운(60초) 경과 후의 순차 재요청은 마지막 커밋이 이긴다(토큰 덮어쓰기). **수용 리스크 (v10, v12 범위 축소)**: 커밋 순서와 SMTP 도착 순서는 독립이므로, 60초 이상 간격을 둔 재요청에서 늦게 도착한 첫 메일의 링크가 이미 덮어쓰인 토큰일 수 있다(클릭 시 400). 행 잠금은 발급 순서만 직렬화할 뿐 메일 도착 순서를 통제하지 못하고, 해시 저장 설계상 같은 토큰 재발송도 불가 — confirm 페이지의 "다시 요청해 주세요" 안내가 복구 경로. 별도 대응 없이 수용.
8. **같은 토큰으로 동시 재설정 제출 2건** (v5): `findByIdForUpdate` 행 잠금 + 잠금 후 토큰 재검증으로 직렬화 — 먼저 커밋한 쪽만 성공하고 나중 요청은 400.
9. **재설정 요청 후 기존 비밀번호로 로그인해 본인 비밀번호를 변경** (v5): `changePassword()`가 reset 토큰을 함께 클리어하므로 메일함의 기존 링크는 즉시 무효.
10. **비밀번호 재설정 성공 시 로그인 중이던 세션**: `AdminSessionRevokeEvent` 발행 → AFTER_COMMIT 리스너가 만료 (best-effort — CLAUDE.md 세션 만료 계약과 동일, v5에서 직접 호출 대신 이벤트로 정렬).

## 완료 기준

- [ ] 공개 경로 4개 추가에 대해 사용자 승인을 받았다 (착수 전).
- [ ] `./gradlew test` 전체 통과 (기존 `SecurityConfigTest`·`ApiSecurityConfigTest` 포함 — 공개 경로 추가로 깨지면 해당 테스트에 새 경로 기대값을 반영).
- [ ] 비로그인 상태에서 `GET /admin/password-reset`·`GET /admin/password-reset/confirm`이 로그인 리다이렉트 없이 200으로 열린다 (`@AdminPage` advice 익명 동작 포함 — MockMvc로 검증, v10).
- [ ] 요청 → MailHog에서 수신한 메일 링크(fragment `#token=` 형식) → 새 비밀번호 설정 → 새 비밀번호로 로그인 성공까지 골든 패스가 playwright로 확인되었다 (v4 — 로그에서 URL을 확인하는 방식 금지).
- [ ] 사용한 토큰으로 두 번째 재설정 시도 시 400이 반환된다.
- [ ] 만료(30분 경과) 토큰이 400으로 거부된다 (서비스 테스트로 검증).
- [ ] 존재하지 않는 이메일 요청도 200을 반환한다.
- [ ] 재설정 성공 직후 해당 계정의 기존 세션으로 보낸 요청이 401(API) 또는 로그인 리다이렉트(페이지)를 받는다 — **ACTIVE 계정으로 로그인 세션을 만든 뒤 검증** (v13: `PASSWORD_EXPIRED` 계정은 로그인 불가라 기존 세션이 없을 가능성이 높아 검증 무의미).
- [ ] 검증 실패(400 `VALIDATION_ERROR`) 응답 본문·로그에 rejected value(토큰·비밀번호)가 포함되지 않는다 (v13 — MockMvc 응답 확인 + 코드 리뷰).
- [ ] 평문 토큰이 DB에 저장되지 않는다 (저장값은 SHA-256 해시).
- [ ] 발급 60초 이내 재요청 시 토큰이 변경되지 않고 메일이 재발송되지 않는다 (서비스 테스트로 검증, v2).
- [ ] 애플리케이션 로그 어디에도 토큰(평문·해시)이 출력되지 않는다 (v2).
- [ ] confirm 페이지가 외부 도메인 리소스를 로드하지 않고, 로드 직후 주소창 URL에서 `#token=` fragment가 제거된다 (playwright로 확인, v2, v4 fragment 기준으로 정정).
- [ ] 같은 해시의 토큰 행이 2건 이상일 때 500이 아닌 400이 반환된다 (서비스 테스트로 검증, v4).
- [ ] 행 잠금 후 토큰 재검증이 동작한다 — 락 획득 시점에 토큰이 이미 클리어/교체된 경우 400 (서비스 테스트로 검증, v5).
- [ ] 같은 토큰 동시 제출 2건 중 정확히 1건만 성공한다 (실제 DB 기반 통합 테스트로 검증, v10).
- [ ] 같은 이메일 동시 발급 요청 2건 중 1건만 토큰을 발급·발송한다 (실제 DB 기반 통합 테스트로 검증, v12 — 쿨다운 원자성).
- [ ] 발송 실패 로그에 throwable·예외 메시지가 포함되지 않는다 — memberId + 예외 클래스명만 (코드 리뷰로 확인, v12).
- [ ] confirm 페이지에 토큰 없이(fragment 없이) 진입하면 제출 버튼이 비활성화되고 재요청 안내가 표시된다 (playwright로 확인, v10).
- [ ] 본인 비밀번호 변경(`changeMyPassword`) 성공 시 발급돼 있던 reset 토큰이 클리어된다 (테스트로 검증, v5).
- [ ] 구현 완료 후 `Member.changePassword()` 호출부가 실제 비밀번호 변경 경로(기존 `changeMyPassword` + 신규 `resetPassword`)뿐임을 재확인했다 (v11 — 비밀번호가 바뀌지 않는 경로에서 호출되면 reset 링크가 무관 작업으로 무효화되는 부작용 발생).
