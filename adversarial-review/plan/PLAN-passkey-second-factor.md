# PLAN — 패스키(WebAuthn)를 2단계 수단으로 추가 (이슈 #259 PR1)

- 상태: v4 (2026-11-19) — 계획 리뷰 4라운드 ship, 사용자 승인(2026-11-19), 구현 완료(미커밋)
- 이슈: #259 패스키(WebAuthn) 로그인. PR 2개로 나눈다(2026-11-19 사용자 결정, GitHub 방식이 목표).
  - **PR1(이 계획)**: 패스키 등록·저장소·스키마 + 2단계 화면에서 TOTP 코드 대신 패스키로 통과.
  - PR2(별도 계획, PR1 머지 후 진행 여부를 사용자에게 다시 묻는다): 로그인 화면 "패스키로 로그인"(비밀번호·2단계 생략).
- 위험 변경: **인증(로그인 2단계 수단 추가 — 로그인 정책 변경)**, **스키마(V18 새 테이블 2개)**, **동시성(회원 행 잠금·세션 뮤텍스 경로 재사용)**, **새 의존성(`spring-security-webauthn` → `webauthn4j-core`)**, **설정 키 추가(`cms.passkey.*`)**
- URL 인가 규칙 변경: **없음**(아래 결정 4·5 — 기존 `/admin/login/totp`와 `/admin/api/members/me/**`·`/admin/**` 규칙 안). 로그인 정책 변경이라 승인은 필요하다.

## 1. Context

#251 실기 검증 중 iPhone 암호 앱 흐름에서 패스키 요구가 나왔다. 패스키는 TOTP와 별개 기술(공개키 서명, 도메인 결속)이라 서버 지원이 따로 필요하다.
현재 로그인은 비밀번호 → (TOTP 사용 회원) `TOTP_PENDING` 중간 상태 → 코드 화면 → 승격이다(`com.cms.config`의 `CLAUDE.md` "2단계 인증(TOTP)").

### 정찰에서 확인한 사실

| # | 사실 | 출처 |
|---|---|---|
| S1 | Spring Security 7.0.7의 WebAuthn 구현은 별도 모듈 `org.springframework.security:spring-security-webauthn:7.0.7`이다. 컴파일 의존: `spring-security-core/web 7.0.7`, `spring-core/beans/web 7.0.9`, `com.webauthn4j:webauthn4j-core:0.31.6.RELEASE`. 현재 클래스패스에는 DSL(`WebAuthnConfigurer`)과 `spring-security-webauthn.js`만 있다. | Maven Central pom 실측, `spring-security-config-7.0.7.jar`·`spring-security-web-7.0.7.jar` 목록 |
| S2 | Boot 4.0.8 BOM(`spring-security.version` 7.0.7)과 Security BOM 7.0.7이 이 모듈 버전을 관리한다 — 버전 리터럴 없이 선언 가능. | `spring-boot-dependencies-4.0.8.pom` 199행, `spring-security-bom-7.0.7.pom` |
| S3 | Boot 4.0.8 `spring-boot-autoconfigure`·`spring-boot-security`에 WebAuthn 자동 구성이 없다 — 모듈을 추가해도 `http.webAuthn()`을 부르지 않으면 필터·엔드포인트가 생기지 않는다(**추가 후 시험으로 재확인**, 실패 모드 F1). | jar 목록 실측(webauthn 0건) |
| S4 | `Webauthn4JRelyingPartyOperations`(공개 생성자: user entity 저장소·credential 저장소·RP 엔터티·허용 origin 집합)를 필터 없이 직접 쓸 수 있다. 메서드: `createPublicKeyCredentialCreationOptions`·`registerCredential`·`createCredentialRequestOptions`·`authenticate`. | javap 7.0.7, 소스 태그 7.0.7 |
| S5 | `createPublicKeyCredentialCreationOptions`: 인증된 `Authentication` 필수, `findByUsername(name)` 없으면 무작위 user handle로 user entity를 **만들어 저장**, `residentKey=REQUIRED`·`userVerification=PREFERRED`·`attestation=NONE`·타임아웃 5분·기존 자격 `excludeCredentials`. | 7.0.7 소스 |
| S6 | `createCredentialRequestOptions`: 인증된 요청이면 `findByUsername` → 그 회원 자격만 `allowCredentials`, `userVerification=PREFERRED`, 5분, rpId. | 7.0.7 소스 |
| S7 | `authenticate`: credential ID로 자격을 찾고 webauthn4j로 challenge·origin·rpId·서명·`allowCredentials`(비어 있지 않으면) 검증, UV는 요청 옵션이 `REQUIRED`일 때만 요구. 성공하면 `lastUsed=Instant.now()`·서명 횟수를 **저장**하고 user entity를 돌려준다. 실패는 `IllegalArgumentException`·webauthn4j 검증 예외. | 7.0.7 소스 |
| S8 | `authenticate`의 서명 횟수 역행 검사는 **등록 당시 attestation object의 횟수**로 만든 `CredentialRecordImpl`을 쓴다 — 저장한 최신 횟수로 복제 감지를 하지 않는다. | 7.0.7 소스 `authenticate` |
| S9 | Jackson 3 모듈 `org.springframework.security.web.webauthn.jackson.WebauthnJacksonModule`(`tools.jackson.databind`)이 있다(Jackson 2용 `WebauthnJackson2Module`도 별도). | javap 7.0.7 |
| S10 | `AuthenticationProvider` 빈은 정확히 하나여야 한다(`TotpAwareAuthenticationProvider`). `http.webAuthn()`은 자체 `WebAuthnAuthenticationProvider`와 `/login/webauthn` 필터를 만든다 — 성공 처리기 재확인·중간 상태·잠금을 거치지 않는다. | `com.cms.config`의 `CLAUDE.md`, Spring 문서 passkeys.adoc |
| S11 | `/admin/api/members/me/**`는 ADMIN·MANAGER 상시 허용 게이트(`AdminFeature` MY_INFO), `/admin/login/totp`는 `TOTP_PENDING` 전용 규칙이고 `LoginSessionMutexFilter` 대상이다. | `AdminFeature.java:38`, `SecurityConfig.java:101` |
| S12 | `Member.userId`는 빌더에서만 정해지고 바꾸는 메서드가 없다. | `Member.java` |
| S13 | 브라우저 시험은 Playwright Java 1.65 + Chromium, `RANDOM_PORT` 실서버. Chromium CDP `WebAuthn.addVirtualAuthenticator`로 가상 인증기를 쓸 수 있다(**Playwright Java `newCDPSession` 사용 — 구현 첫 단계에서 실측**). | `BrowserTestSupport.java`, `build.gradle:84` |
| S14 | WebAuthn은 secure context(HTTPS 또는 `localhost`)에서만 동작하고 rpId는 origin 호스트의 등록 가능 도메인 접미사여야 한다. prod 도메인은 미정(#238 blocked). | WebAuthn Level 3 §5.1.3·§13.4.9(출처 확인: 사양), 이슈 #238 |

## 2. 핵심 설계 결정

### 결정 1 — PR1의 패스키는 "TOTP를 켠 회원의 대체 2단계 수단"이다 (불변식: 패스키 ⇒ TOTP 사용 중)
- 선택지: (a) TOTP 사용 회원만 등록, 코드 화면에서 대체 수단 / (b) TOTP 없이도 패스키만으로 2단계 사용 / (c) 패스키 단독 로그인(PR2).
- **(a) 채택**. 이유: 중간 상태 결정(`TotpAwareAuthenticationProvider`)·ADMIN 필수 판정(`TotpAuthorities.isRequired`)·성공 처리기 3분기 대조를 **한 줄도 바꾸지 않는다** — "2단계 사용 = `member_totp` 행 있음"이 그대로다. 분실 복구는 기존 TOTP·복구 코드가 맡는다(새 복구 흐름 없음 — 완료 기준 ②의 "분실 복구"). (b)는 2단계 사용 판정·ADMIN 필수 해석을 바꿔야 해 PR2에서 함께 다룬다.
- 따름 규칙: **유효한 패스키 = `member_passkey.created_at >= member_totp.enabled_at`인 행**(현재 TOTP 등록 이후에 만든 것). 옵션의 `allowCredentials`·검증·목록이 모두 이 조건을 쓴다 — 앱 코드가 지우지 못한 경우(이전 앱으로 롤백한 동안의 TOTP 해제·리셋·재등록)에도 옛 패스키가 되살아나지 않는다(리뷰 1-1). 정리: TOTP 본인 해제(`disable`)·ADMIN의 타인 해제(`resetByAdmin`)는 같은 트랜잭션에서 그 회원의 패스키를 모두 지우고, 기동 시 `PasskeyCleanupRunner`가 조건을 어긴 행(TOTP 없음 포함)을 지운다(예외는 밖으로 내지 않고 WARN — `TotpSecretRewrapRunner`와 같은 규칙). 두 시각은 같은 KST `Clock`이 만든다. TOTP가 없는 회원의 등록 요청은 409.
- ADMIN 필수 정책(#256)과의 관계: 변화 없음 — ADMIN은 여전히 TOTP를 등록해야 하고, 패스키는 그 위의 편의 수단이다.

### 결정 2 — 라이브러리: `spring-security-webauthn`의 `Webauthn4JRelyingPartyOperations`를 필터 없이 직접 쓴다
- 선택지: (a) `http.webAuthn()` DSL / (b) 모듈의 RP 연산만 직접 호출 / (c) webauthn4j 직접.
- **(b) 채택**. (a) 기각: 자체 프로바이더·`/login/webauthn`·`/webauthn/register` 필터가 생겨 S10 계약을 우회한다(PR2에서도 로그인 입구는 직접 만든다). (c) 기각: 옵션 생성·JSON 형식·자격 변환을 다시 짜야 한다 — 공식 모듈이 같은 일을 하고 Boot BOM이 버전을 관리한다(S2).
- 의존성: `implementation 'org.springframework.security:spring-security-webauthn'`(버전 리터럴 없음). 전이로 `webauthn4j-core 0.31.6`(+ 그 전이 — 추가 후 `dependencies` 출력으로 확인, Trivy가 CI에서 본다).
- 빈: `PasskeyConfig`가 `WebAuthnRelyingPartyOperations` 빈 하나를 만든다(**`AuthenticationProvider`가 아니다**). `http.webAuthn()`은 부르지 않는다.
- JSON: 전용 `JsonMapper`(Jackson 3, `WebauthnJacksonModule` 등록)를 `PasskeyJson` 안에 두고 WebAuthn 타입 직렬화·역직렬화는 이것만 쓴다. **전역 매퍼 격리는 보장하지 않는다** — Boot 4.0.8은 기본으로 모듈 자동 발견(`findAndAddModules`)을 하고 이 모듈은 SPI 등록을 한다(리뷰 1-4, 출처: Boot `JacksonProperties` v4.0.8·모듈 소스 7.0.7). 의존성 추가 직후 전역 매퍼의 등록 모듈을 실측하고, 등록돼도 영향이 WebAuthn 타입에 한정되는지(앱의 다른 응답 JSON 무변화 — 기존 API 시험 통과) 확인해 결과를 이 문서에 기록한다. 컨트롤러 경계는 `tools.jackson.databind.JsonNode`다: 응답 `options`는 전용 매퍼로 쓴 문자열을 `JsonNode`로 읽어 싣고, 요청 `credential`은 `JsonNode`로 받아 문자열로 쓴 뒤 전용 매퍼로 읽는다.

### 결정 3 — 저장: 새 테이블 2개 + Spring 저장소 인터페이스의 JPA 어댑터 (V18)
- 선택지: (a) Spring 제공 `JdbcUserCredentialRepository`·`JdbcPublicKeyCredentialUserEntityRepository`와 그 스키마(`user_entities`·`user_credentials`) / (b) 자체 테이블 + 어댑터.
- **(b) 채택**. (a) 기각: 사용자 키가 이름 문자열(`varchar(1000)` PK)이라 `member` FK·`ON DELETE CASCADE`를 걸 수 없고, 회원 행 잠금 트랜잭션과 다른 JDBC 경로라 잠금 순서 계약 밖이다.
- `member_passkey_user`: `member_id` PK·FK cascade, `user_handle varbinary(64)` UNIQUE(무작위 — 개인정보 없음), `created_at`.
- `member_passkey`: `id` PK, `member_id` FK cascade + 인덱스, `credential_id varbinary(1023)` UNIQUE(WebAuthn 상한 1023바이트), `public_key blob`, `signature_count bigint`, `uv_initialized`·`backup_eligible`·`backup_state` bit, `transports varchar(255)`, `attestation_object blob`(S7 — `authenticate`가 필수로 읽는다), `attestation_client_data_json blob`, `label varchar(50)`, `created_at`, `last_used_at datetime(6) null`.
- `Member`에 컬럼을 두지 않는다(V17과 같은 이유 — principal에 실리지 않게).
- 어댑터: `PublicKeyCredentialUserEntityRepository.findByUsername(name)` = `member.userId`로 회원 → 핸들 행(S12 — 아이디 불변). `save(entity)` = 그 이름의 회원에 핸들 행 생성(이미 있으면 무시). `UserCredentialRepository.save(record)` = credential ID로 있으면 서명 횟수·`last_used_at`만 갱신, 없으면 insert(`label`·시각은 KST `Clock` — 라이브러리의 `Instant.now()`는 저장하지 않고 `LocalDateTime.now(clock)`로 기록). 어댑터는 호출자 트랜잭션에 참여한다(서비스가 연다).
- 회원당 상한 10개(서비스에서 검사, 등록 옵션 발급 때와 확정 때).
- **잠금 계약**: 어댑터 `save`·`delete`를 부르는 모든 경로(옵션 발급의 핸들 생성, 등록 확정, 인증의 서명 횟수 갱신, 삭제, TOTP 해제·리셋 연동, 기동 정리)는 **그 회원 행 잠금(`findByIdForUpdate`) 트랜잭션 안**에서만 부른다. 그래서 인증 중 삭제가 끼어 `save`가 지운 자격을 다시 넣는 경합(리뷰 1-3)과 첫 옵션 동시 발급의 핸들 이중 생성(리뷰 1-2)이 생기지 않는다. 핸들 행 `member_id` PK가 두 번째 방어다.

### 결정 4 — 로그인: 기존 `GET`·`POST /admin/login/totp`를 그대로 쓴다 (새 URL 없음)
- 선택지: (a) 새 JSON 엔드포인트 `/admin/login/totp/passkey-*` / (b) 같은 화면·같은 POST에 `passkeyCredential` 필드.
- **(b) 채택**. 이유: URL 규칙(`TOTP_PENDING`)·`@PreAuthorize`·`LoginSessionMutexFilter`·`AdminEndpointAuthorizationConventionTest` 짝이 그대로 적용된다. (a)는 규칙 2개·뮤텍스 대상·컨벤션 목록 추가가 필요하다.
- GET: principal 회원에게 패스키가 있고 기능이 켜져 있으면 요청 옵션을 만들어 세션에 `PasskeyAssertionState{assertionId, memberId, optionsJson, issuedAt}`로 두고(GET마다 대체, `assertionId`는 무작위 — 화면의 패스키 폼 숨은 필드에도 싣는다), 옵션 JSON을 화면의 `data-` 속성으로 그린다. 버튼 "패스키로 인증" → 외부 스크립트 `/js/admin/passkey-login.js`가 `navigator.credentials.get` → 결과 JSON을 숨은 필드에 넣고 같은 폼을 제출. 사용자가 브라우저 창을 취소하면 제출하지 않는다.
- POST: `passkeyCredential`이 비어 있지 않으면 패스키 분기. 기존 순서(대기 표지 5분 → 레지스트리) 다음에 세션의 어설션 상태를 **꺼내며 지운다**(1회용) → 회원 일치·**폼의 `assertionId` 일치**·발급 5분 이내가 아니면 `?passkeyExpired`(카운트 없음 — 다른 탭이 GET으로 챌린지를 바꾼 뒤 옛 탭의 정상 서명이 오답으로 쌓이지 않게, 리뷰 2-1) → `TotpLoginService.verifyPasskey`(아래) → 결과별 분기는 코드와 같다(`SUCCESS` → 같은 `promoter.promote` → `recordCompletedLogin` → `/admin`).
- `verifyPasskey`(같은 `@Transactional`, 회원 행 잠금): `matchesLogin` 재확인 → `member_totp` 행 존재(불변식) → 기능 꺼짐·패스키 0개면 `REJECTED` → JSON 파싱 → `operations.authenticate` → 돌려받은 user handle이 이 회원의 핸들과 같은지 **직접 대조**(S7의 `allowCredentials` 검사에 더한 이중 방어) → 성공: `totp.resetFailures` → **명시적 flush**(`member_totp`와 어댑터가 바꾼 `member_passkey` 서명 횟수·`last_used_at`) → `resetFailedLoginCountIfActive`(`clearAutomatically` 벌크 쿼리라 flush 없이는 앞 변경이 사라진다 — 기존 TOTP 경로 `TotpLoginService.java:85`와 같은 순서, 리뷰 2-2). 파싱 실패·검증 예외·핸들 불일치는 **오답과 같이** `member_totp.failed_attempts`를 올리고 5회면 `lockForSecondFactorFailures`(→ `WRONG_PASSKEY` 또는 `LOCKED`). 기능 꺼짐·패스키 없음·`member_totp` 없음은 `REJECTED`(카운트 없음).
- 사용자 확인(UV): **`PREFERRED` 그대로**(S6). 이유: 이미 비밀번호를 통과한 2단계라 "기기 소유"만으로 충분하다. PR2(단독 로그인)는 `REQUIRED`로 별도 옵션을 쓴다.

### 결정 5 — 등록·목록·삭제 API (보안 설정 화면)
`api-conventions` 규칙으로 설계한다(구현 전 스킬 문서 재확인).
- `GET /admin/api/members/me/passkeys` → 200 `{enabled(기능), items:[{id,label,createdAt,lastUsedAt}]}`
- `POST /admin/api/members/me/passkey-registrations` → 201 `{registrationId, options}`. 세션 뮤텍스 → **회원 행 잠금 트랜잭션**(상태 재확인 409 → TOTP·상한 확인 → 핸들 조회·생성 → 옵션 생성, 커밋) → `PasskeyRegistration{registrationId, memberId, totpEnabledAt, optionsJson, issuedAt}`을 세션에 둔다(`totpEnabledAt` = 발급 때 잠금 아래 읽은 `member_totp.enabled_at`)(부를 때마다 대체, 5분 — 옵션 타임아웃과 같게). 409: TOTP 미사용·상한 10개. 404: 기능 꺼짐.
- `POST /admin/api/members/me/passkeys` body `{registrationId, credential(JSON 객체), label, currentPassword}` → 201 `{id,label,createdAt}`. 세션 뮤텍스 → 회원 행 잠금(TOTP 확정과 같은 순서) → 상태 재확인(409) → 등록 상태 대조(회원·ID·5분, 아니면 409) → **현재 `member_totp.enabled_at`이 대기의 `totpEnabledAt`과 같은지(아니면 409 — 리셋·재등록 전에 시작한 등록이 새 세대에 패스키를 만들지 않게, 리뷰 3-1)** → 현재 비밀번호(틀리면 `member_totp.failed_attempts` — "2단계·본인 재인증" 계열, 5회 잠금, `TransactionTemplate`으로 커밋 뒤 400) → 상한·TOTP 재확인 → `operations.registerCredential`(검증 실패 400, 카운트 없음 — 인증기 오류이지 대입이 아니다) → 감사 이벤트. 상태는 성공·실패 모두 세션에서 지운다.
  - 비밀번호를 다시 받는 이유: TOTP 확정과 같다 — 세션 쿠키만 훔친 사람이 자기 패스키를 영구 2단계 수단으로 심지 못하게.
- `DELETE /admin/api/members/me/passkeys/{passkeyId}` → 204(내 것이 아니면 404, 회원 행 잠금 아래). **재인증 없음** — 지워도 TOTP 기준선 아래로 약해지지 않는다(결정 1의 불변식). 감사 기록.
- `label`: trim 후 1~50 코드포인트, 평문(출력은 Thymeleaf/`textContent` — HTML 아님).
- 감사: 기존 `MemberTotpAuditEvent`·리스너에 `PASSKEY_REGISTER`·`PASSKEY_DELETE`(대상 상수 목록 116행에 추가). TOTP 해제·리셋으로 함께 지운 경우는 기존 `TOTP_DISABLE`·`TOTP_RESET` 상세에 "패스키 N개 삭제"를 덧붙인다.
- ADMIN의 타인 상태 조회 `GET /admin/api/members/{id}/totp` 응답에 `passkeyCount`를 더한다(관리 화면 안내용 — 응답 필드 추가, 하위 호환).

### 결정 6 — 설정: `cms.passkey.rp-id`가 비어 있으면 기능 꺼짐
- 키: `cms.passkey.rp-id`, `cms.passkey.rp-name`(기본 `CMS 관리자`), `cms.passkey.allowed-origins`(쉼표 목록).
- dev: `rp-id: localhost`, `allowed-origins: http://localhost:8080`.
- 시험: origin 비교는 **scheme·host·port 정확 일치**다(webauthn4j 0.31.6 `Origin.equals` — http/https는 세 값 비교, `OriginVerifierImpl`이 `ServerProperty`의 origin 집합 판정을 쓴다. Spring은 생성자에서 받은 `Set<String>`을 그대로 넘긴다 — S4). 그래서 와일드카드 설정을 만들지 않고, `PasskeyBrowserTest`만 **기동 전에 빈 포트를 골라 `DEFINED_PORT` + `@DynamicPropertySource`로 `server.port`·`cms.passkey.allowed-origins`를 넣는다**(별도 컨텍스트 1개 — 시험 시간 증가 수용). 그 밖의 시험은 `WebAuthnRelyingPartyOperations`를 목으로 바꾸거나 기능 꺼짐으로 돈다(시험 기본 설정은 `rp-id: localhost`·`allowed-origins: http://localhost`).
- prod: `CMS_PASSKEY_RP_ID`·`CMS_PASSKEY_ALLOWED_ORIGINS`, **기본 빈 값 = 꺼짐**(필수 아님 — #238 전에는 도메인이 없다). 켜면 기동 때 검증: origin 1개 이상, `https://`(호스트가 `localhost`면 `http` 허용), 각 origin 호스트가 rp-id와 같거나 `.rp-id`로 끝남 — 어기면 기동 실패.
- 꺼짐일 때: 코드 화면에 버튼 없음, 보안 설정의 패스키 구역 숨김(`enabled=false`), 등록 API 404, 로그인 패스키 분기는 `REJECTED`. 저장된 패스키는 지우지 않는다(다시 켜면 복귀).

### 결정 7 — 화면
- `login-totp.html`: 패스키가 있으면 코드 입력 위에 "패스키로 인증" 버튼과 오류 문구(`?passkeyError`·`?passkeyExpired`). JS 미지원·`PublicKeyCredential` 없음이면 버튼을 숨긴다. **패스키 제출은 별도 폼**(같은 action `/admin/login/totp`, 숨은 `passkeyCredential` + CSRF만) — 코드 폼의 `required` 입력이 제출을 막지 않게(리뷰 1-6).
- `admin-settings.html`: TOTP 구역 아래 "패스키" 구역 — 목록(이름·등록일·마지막 사용), "패스키 추가"(이름 + 현재 비밀번호 → 옵션 → `navigator.credentials.create` → 확정), 삭제. TOTP 미사용이면 "2단계 인증을 먼저 켜세요" 안내만. 스크립트는 외부 파일 `/js/admin/passkey-settings.js`, CSRF 헤더 포함.
- base64url ↔ `ArrayBuffer` 변환은 두 스크립트가 공유하는 `/js/admin/passkey-codec.js`(브라우저 `PublicKeyCredential.parseRequestOptionsFromJSON`은 쓰지 않는다 — 지원 범위가 고르지 않다, 미확인).

### 설계 제약
- `AuthenticationProvider` 빈 추가 금지, `http.webAuthn()` 호출 금지.
- 시각은 KST `Clock`(`ClockUsageConventionTest`). 대소문자 변환은 `Locale.ROOT`.
- 잠금 순서: 세션 뮤텍스 → 회원 행(로그인·등록 모두).
- 엔티티에 `@Setter` 금지, DTO 경계 유지.

## 3. 성공 기준
1. TOTP를 켠 MANAGER·ADMIN이 보안 설정에서 패스키를 등록·목록·삭제할 수 있다(가상 인증기 브라우저 시험).
2. 비밀번호 통과 후 코드 화면에서 패스키로 통과하면 원래 역할 권한으로 `/admin`에 들어가고 방문 기록이 1건 남는다.
3. TOTP 해제·ADMIN 리셋 뒤 그 회원의 패스키 행이 0개다.
4. 기능 꺼짐(`rp-id` 빈 값)에서 기존 로그인·TOTP 흐름이 그대로다(기존 시험 전부 통과, prod-smoke 통과).
5. `AuthenticationProvider` 빈 1개, `/webauthn/**`·`/login/webauthn`은 핸들러·필터 없음(기본 거부 응답).

## 4. 실패 모드와 고정 시험 (최대 5)
| # | 실패 모드 | 고정 시험 |
|---|---|---|
| F1 | 모듈 추가로 WebAuthn 필터·엔드포인트·두 번째 프로바이더가 생겨 2단계 계약이 우회된다 | `PasskeyModuleWiringIntegrationTest`: 실제 `SecurityFilterChain`의 필터 클래스 목록에 `org.springframework.security.web.webauthn` 패키지 필터가 없음, 폼 로그인 필터의 `AuthenticationManager`(`ProviderManager`, 부모 포함)의 프로바이더가 `TotpAwareAuthenticationProvider` 하나뿐, MVC 핸들러 매핑에 `/webauthn/**`·`/login/webauthn` 없음, 기존 `TotpPendingAuthorizationIntegrationTest` 통과(리뷰 1-5) |
| F2 | 다른 회원(또는 다른 회원의 자격)의 어설션으로 2단계를 통과한다 | `TotpLoginServicePasskeyIntegrationTest`(실 DB, `WebAuthnRelyingPartyOperations`만 목): 다른 핸들을 돌려주면 실패 카운트 + 통과 없음, 옵션의 `allowCredentials`가 그 회원 자격만 담는지 |
| F3 | 어설션 상태(챌린지) 재사용·만료·회원 불일치로 통과한다 / 다른 탭의 GET이 바꾼 챌린지 때문에 정상 서명이 오답으로 쌓인다 | `TotpLoginControllerPasskeyTest`(MockMvc): 같은 상태로 두 번째 제출 → `?passkeyExpired`, 5분 초과, 다른 회원 상태, **GET 두 번 뒤 첫 `assertionId`로 제출 → `?passkeyExpired`·`failed_attempts` 불변** |
| F4 | TOTP 해제·리셋(또는 롤백 중 앱 밖의 해제·재등록) 뒤 옛 패스키가 쓰인다 | `MemberTotpServicePasskeyCleanupIntegrationTest`: `disable`·`resetByAdmin` 뒤 `member_passkey` 0행(`member_passkey_user`는 유지 — 재등록 때 같은 핸들). **롤백 시나리오**: 패스키 행을 남긴 채 `member_totp`를 SQL로 지우고 더 늦은 `enabled_at`으로 다시 넣으면 → 옵션 `allowCredentials`에서 빠지고, 그 자격의 어설션은 실패, 기동 정리가 행을 지운다(리뷰 1-1) |
| F5 | 패스키 검증 실패가 세지지 않아 대입 제한이 없다 / 기능 꺼짐·인증기 취소가 잠금을 만든다 / 성공 처리의 벌크 쿼리가 앞 변경을 버린다 | 같은 서비스 시험: 실패 5회 → `LOCKED`, 꺼짐·패스키 없음 → `REJECTED` 카운트 0, **`failed_attempts`=4·`failed_login_count`>0 회원의 성공 뒤 새 트랜잭션에서 둘 다 0·`signature_count`·`last_used_at` 갱신 확인** |

추가: TOTP 리셋·재등록 뒤 옛 등록 대기로 확정 → 409·패스키 0행(리뷰 3-1), 첫 옵션 동시 발급(두 세션, 실 DB — 핸들 행 1개·두 옵션의 `user.id`가 그 핸들과 같음, 리뷰 1-2), 삭제·인증 경합(삭제가 회원 행 잠금에서 기다리고 인증 커밋 뒤 행 0개 — `LockWaitObserver` 방식, 리뷰 1-3), 등록 API 시험(409·400·404·상한·비밀번호 실패 카운트), V18 마이그레이션 시험(빈 DB·기존 데이터 위 적용 — `docs/migration-guide.md`), 브라우저 시험 `PasskeyBrowserTest`(가상 인증기: 등록 → 로그아웃 → 비밀번호 → 패스키 → `/admin`, 삭제).

## 5. 작업 단계
1. 의존성 추가 → `./gradlew dependencies`로 전이 확인 → F1 시험 작성·통과. webauthn4j origin 비교·Playwright CDP 가상 인증기 실측(결정 6 미확인 해소).
2. V18 + 엔티티 `MemberPasskey`·`MemberPasskeyUser` + 리포지토리 + 어댑터 2종.
3. `PasskeyProperties`·`PasskeyConfig`(기동 검증)·`PasskeyJson`.
4. `PasskeyService`(등록 옵션·확정·목록·삭제) + `MemberTotpService` 해제·리셋 연동 + 감사 유형.
5. `TotpLoginService.verifyPasskey` + `TotpLoginController` 분기.
6. API 컨트롤러 `MemberPasskeyApiController`.
7. 템플릿·스크립트(`login-totp.html`, `admin-settings.html`, JS 3개).
8. 브라우저 시험·실기 검증(dev, Chrome 가상 인증기 + 가능하면 실기기).
9. 문서: `com.cms.config`·`com.cms.admin.member` `CLAUDE.md`, `docs/deployment.md`(환경변수 2개·꺼짐 기본), `.env.example`, 계획 인덱스.

## 6. 리스크
- R1 서명 횟수 복제 감지 없음(S8) — 동기화 패스키는 횟수가 늘 0이라 실효도 작다. 수용, 문서화.
- R2 운영 실사용은 #238(도메인·TLS) 이후다. 기본 꺼짐이라 배포 영향 없음.
- R3 새 의존성 webauthn4j의 취약점 표면 — Trivy·Dependabot이 추적.
- R4 롤백: 이전 앱은 V18 테이블을 모른다. `ddl-auto: validate`가 매핑되지 않은 테이블을 문제 삼지 않는지는 **미확인** — 구현 중 `docs/migration-guide.md`의 기존 사례(V17 추가 뒤 이전 앱 기동)를 확인하고 없으면 실측한다. 롤백해도 TOTP로 로그인 가능하고, 롤백 중의 TOTP 변경은 결정 1의 유효 조건이 재업그레이드 때 막는다.
- R5 Chrome 가상 인증기와 실제 iPhone 암호 앱의 차이 — 실기 검증에서 가능하면 실기기로 한 번 확인(dev localhost는 휴대폰에서 접근 불가 → 같은 PC의 Windows Hello/Chrome 프로필 패스키로 대체 가능성).

## 7. 범위 밖 (PR2 이후)
- 패스키 단독 로그인·조건부 UI(자동 완성)·UV `REQUIRED`, 패스키만으로 ADMIN 필수 충족, 패스키 이름 변경, ADMIN의 타인 패스키 개별 삭제(리셋으로 전부 삭제는 있음).

## 구현 결과 (2026-11-19)

### 핵심 확정 사항
- 결정 1~7을 그대로 구현했다. 계획과 달라진 점은 아래 "이슈" 1·2(CBOR 변환기 제거 추가, 감사 상세 문구 생략)뿐이다.

### 구현 파일
- 의존성: `build.gradle`(`spring-security-webauthn`, 전이 `webauthn4j-core 0.31.6`·`jackson-dataformat-cbor 3.1.7`).
- 스키마: `V18__member_passkey.sql`, 엔티티 `MemberPasskey`·`MemberPasskeyUser`, 리포지토리 `MemberPasskeyRepository`·`MemberPasskeyUserRepository`.
- 패스키 패키지 `admin/member/passkey`: `PasskeyProperties`·`PasskeyConfig`(기동 검증)·`PasskeyRelyingParty`(검증 연산 감싸개)·`PasskeyUserEntityRepository`·`PasskeyCredentialRepository`(JPA 어댑터)·`PasskeyJson`(전용 매퍼).
- 서비스: `MemberPasskeyService`·`PasskeyRegistration`·`PasskeyCleanupRunner`, `MemberTotpService`(해제·리셋 연동), `TotpLoginService`(`startPasskeyAssertion`·`verifyPasskey`), `config/auth/PasskeyAssertionState`.
- 웹: `MemberPasskeyApiController`·`PasskeyRegisterRequest`, `TotpLoginController`(패스키 분기), `MemberTotpApiController`(`passkeyCount`), `config/HttpMessageConverterConfig`(CBOR 제거), `AdminActionTypes`(`PASSKEY_REGISTER`·`PASSKEY_DELETE`)·감사 화면 라벨.
- 화면: `login-totp.html`, `admin-settings.html`, `static/js/admin/passkey-codec.js`·`passkey-login.js`·`passkey-settings.js`.
- 설정·문서: `application.yml`·`application-dev.yml`(`cms.passkey.*`), `docker-compose.prod.yml`·`.env.example`(선택 변수 2개), `docs/deployment.md` "패스키", `docs/migration-guide.md` V18, `docs/development-workflow.md` 위험 표, `CLAUDE.md`·`com.cms.config`·`com.cms.admin.member` `CLAUDE.md`.

### 검증 결과
- **전체 시험(브라우저 제외)** `./gradlew test -PbrowserTests=exclude`: 3,204건 통과(실패 0, 건너뜀 12, 6분 11초). **브라우저 시험 전체** `-PbrowserTests=only`: 117건 통과. 그 뒤 템플릿 한 줄(`passkeyForm`에 `class="user"`)·어댑터 미사용 필드 정리 후 영향 범위(`admin.member.*`·`config.*`·`*ConventionTest`·`BaselineSchemaTest`)와 `PasskeyBrowserTest`·`TotpLoginBrowserTest` 재통과.
- **새 시험**: `PasskeyModuleWiringIntegrationTest`(F1 — 필터 체인·인증 관리자·핸들러 매핑·CBOR 변환기·전역 매퍼 5건), `BaselineSchemaTest#passkeyTables`, `PasskeyConfigTest`(설정 검증 11건), `MemberPasskeyServiceIntegrationTest`(F4·등록·삭제·동시성 13건), `TotpPasskeyLoginIntegrationTest`(F2·F3·F5 10건), `MemberPasskeyApiIntegrationTest`(6건), `PasskeyBrowserTest`(Chromium 가상 인증기, 목 없는 실제 공개키 서명·검증 2건).
- **변이 확인**: ① TOTP 해제의 패스키 삭제 줄 제거 → F4 실패 ② 등록 확정의 TOTP 세대 대조 제거 → 리뷰 3-1 시험 실패 ③ 핸들 대조 제거 → F2 실패 ④ `assertionId` 대조 제거 → F3(리뷰 2-1) 실패 ⑤ CBOR 제거 설정 없음 → 연결 시험 실패(구현 전 실측). 모두 원복 후 통과.
- **실기 검증(dev `bootRun`, Playwright MCP + CDP 가상 인증기)**: 검증 전용 MANAGER를 SQL로 만들어(사용자의 dev ADMIN·인증 앱은 건드리지 않음) ① TOTP 미사용 → 패스키 카드에 "먼저 2단계 인증을 켜세요"(`docs/screenshots/member/passkey-01-totp-off-needs-totp.png`) ② 화면에서 TOTP 등록(설정 키로 코드 계산) → 재로그인 시 패스키가 없으면 코드 화면에 버튼 없음 ③ 틀린 비밀번호로 등록 → "현재 비밀번호가 올바르지 않습니다."·`failed_attempts` 증가(`passkey-02-wrong-password-rejected.png`) ④ 정상 등록 → DB `member_passkey` 1행(자격 32바이트·`internal`)·핸들 32바이트·감사 `PASSKEY_REGISTER`(`passkey-03-registered.png`) ⑤ 쿠키 삭제 → 비밀번호 → 코드 화면의 [패스키로 인증](`passkey-04-totp-page-with-passkey.png`) → 코드 칸 빈 채로 `/admin`(`passkey-05-dashboard-after-passkey.png`) ⑥ 삭제(확인 창) → 감사 `PASSKEY_DELETE` → 재등록 ⑦ TOTP 끄기 → 패스키 0행·핸들 유지·카드 안내 복귀(`passkey-06-totp-off-passkeys-removed.png`) ⑧ 경계: 익명 `GET /admin/api/members/me/passkeys` 401, 익명·로그인 `POST /webauthn/register/options`·`/login/webauthn` 403(기본 거부), `GET /webauthn/register` 302, 로그인 세션의 CBOR 본문 415·`Accept: application/cbor` 406. ⑨ 원복: 검증 계정·감사·방문 행 삭제(패스키·TOTP 행 CASCADE 0). dev DB에는 V18이 적용된 채 남는다.
- **실기 검증에서 고친 것**: 코드 화면의 패스키 버튼만 모서리가 각졌다(폼에 SB Admin `user` 클래스 없음) → `class="user"` 추가 후 `border-radius` 160px 확인. 처음 재확인 때 값이 그대로였던 원인은 실행 중 `bootRun`이 `build/resources`를 읽는데 원본만 고쳤기 때문(환경) — `processResources` 뒤 반영.
- **실기기(iPhone 암호 앱·Windows Hello)는 확인하지 못했다** — dev는 `localhost`라 휴대폰에서 접근할 수 없고 운영 도메인이 없다(#238). Chromium 가상 인증기는 플랫폼 인증기(`internal`, 상주 키·사용자 확인)를 흉내 낸 것이다.

### 이슈
1. **CBOR 변환기(계획 밖 발견)**: 전이 의존성 `jackson-dataformat-cbor`가 클래스패스에 들어오자 Spring MVC가 `JacksonCborHttpMessageConverter`를 기본 변환기에 더했다(실측 — 변환기 목록). 그러면 모든 `@RequestBody` API가 앱 JSON 설정을 거치지 않는 CBOR 본문을 받는다. `HttpMessageConverterConfig`로 지우고(→ 415) `PasskeyModuleWiringIntegrationTest`·`MemberPasskeyApiIntegrationTest`로 고정했다. 시험이 수정 전에 실패함을 확인했다.
2. **감사 상세 생략**: 결정 5의 "TOTP 해제·리셋 감사 상세에 패스키 N개 삭제"는 `MemberTotpAuditEvent`에 상세 필드가 없어 넣지 않았다(필드 추가는 범위 밖 변경 — 별도 감사 행도 남기지 않는다).
3. **`@Lob` 매핑**: `@Lob byte[]`는 Hibernate가 `tinyblob`을 기대해 `validate`가 실패했다(`blob (LONGVARBINARY)` 발견). `length = 65535`로 매핑했다.
4. **전역 매퍼(리뷰 1-4)**: 실측 결과 Boot 4.0.8 전역 `JsonMapper`에 `WebauthnJacksonModule`이 등록되지 **않았다**(등록 모듈: `GeoJacksonModule`·`PageModule`·`JacksonMixinModule`·`JacksonComponentModule`). 전용 매퍼만 쓴다.
5. **코드 리뷰(code-review-loop 3라운드)**: 1라운드 기본 리뷰 지적 0건 → 2라운드 적대적 리뷰 1건 수용 — 어설션 응답의 `userHandle`을 대조하지 않았다(Spring Security 7.0.7은 rawId로 찾은 저장 자격의 사용자 엔티티를 돌려주고 응답 `userHandle`은 검증기에 넘기지 않으며, `userHandle`은 서명 대상이 아니다). 기존 `otherHandleRejected`는 목 반환값만 바꿔 이 누락을 가렸다. `TotpLoginService.passkeyMatches`가 검증기 호출 전에 응답 `userHandle`(있으면)을 본인 핸들과 대조하도록 고치고 `responseUserHandleOfOtherMemberRejected`·`responseUserHandleOfSelfAccepted`로 고정(대조 제거 변이 → 실패 확인). 3라운드 approve.
6. **R4 롤백**: `docs/migration-guide.md` V17 선례(이전 앱이 새 테이블을 무시)와 같다 — 매핑되지 않은 테이블은 `validate` 대상이 아니다.

### 후속
- 이슈 #280 등록(2026-11-19, 사용자 승인): 패스키 단독 로그인(비밀번호·2단계 생략) — PR2, `security`. PR1 머지 뒤 진행 여부를 사용자에게 다시 묻는다.
- 이슈 #281 등록(2026-11-19, 사용자 승인): 운영 도메인에서 패스키 실기기 확인 — `security`·`blocked`(선행 #238), 마일스톤 "실배포 준비".
- PR1은 `Closes #259`(완료 기준 ①정책 승인 ②등록·로그인·분실 복구 ③스키마·문서를 2단계 수단 범위로 충족), 패스키 단독 로그인은 #280이 이어받는다.

## 개정 이력
- v1 (2026-11-19): 최초 작성.
- v4 변경 (3라운드 codex): 등록 대기에 발급 시점 `member_totp.enabled_at`을 담고 확정 때 잠금 아래 대조(다르면 409) + 시험.
- v3 변경 (2라운드 codex): ① 어설션 상태에 `assertionId`, 폼 값과 다르면 검증 전 `?passkeyExpired`(카운트 없음) ② 성공 처리에서 벌크 실패 횟수 리셋 전 명시적 flush, F5 시험 보강. 기각: 코드 화면 GET을 세션 뮤텍스에 넣기 — `assertionId` 대조로 교체 경합이 만료 안내로 끝나 불필요.
- v2 변경 (1라운드 codex): ① 유효 패스키를 `created_at >= member_totp.enabled_at`로 정의 + 기동 정리 러너(롤백 중 TOTP 재등록 시 부활 방지) ② 옵션 발급을 회원 행 잠금 트랜잭션으로(핸들 이중 생성) ③ 어댑터 `save`·`delete` 호출 경로 전부 회원 행 잠금 계약, 삭제도 잠금 ④ 전역 Jackson 매퍼 자동 등록을 전제로 실측·기록, 컨트롤러 경계 `JsonNode` ⑤ F1을 필터 체인·`ProviderManager` 직접 단언으로 ⑥ 패스키 제출용 별도 폼. 기각: 등록 삽입·인증 갱신을 어댑터에서 구분하는 장치 — 회원 행 잠금 직렬화로 경합이 없고, 같은 `save` 호출을 구분하려면 스레드 상태 같은 우회가 필요해 과설계.
