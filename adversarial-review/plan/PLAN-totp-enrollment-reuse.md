# PLAN — 2단계 인증 강제 등록 화면의 등록 대기 재사용 (이슈 #277)

- 상태: v2 — 계획 리뷰 2라운드 ship, **사용자 승인 완료(2026-11-19)**, 구현·실기 검증 완료(2026-11-19), PR 전
- 브랜치: `fix/totp-enrollment-reuse`
- **위험 변경: 인증(로그인 중간 상태 화면의 세션 상태)** — 접근 규칙·인가 선언·성공 처리기는 바꾸지 않는다
- 스키마·API·설정 키·의존성 변경 없음. 보안 설정 화면의 등록 API(`POST /admin/api/members/me/totp-enrollments`) 동작 무변경

## 개정 이력
- v2 변경(계획 리뷰 1라운드, codex): ① 리스크의 "평문 최대 10분" 서술 정정 — 유효기간 10분·세션 속성은 세션 수명 동안 남음(수용) ② 경계 시험을 실제 시계 통합 시험이 아니라 순수 판정 메서드 `TotpEnrollment.isReusableFor` + 고정 시각 단위 시험으로(수용) ③ `changeSessionId` 속성 유지 전제에 Spring Security 7.0.7 출처(수용) ④ "틈을 만들지 않는다" → "같은 시각의 경계 정의 일치"로 축소(수용) ⑤ 비밀값 20바이트로 정정(수용). 기각 없음.
- 2라운드(codex): ship — 새 실질 지적 없음.

## 1. Context

#256(ADMIN 필수화)의 강제 등록 화면 `GET /admin/login/totp-enrollment`는 요청마다 `MemberTotpService.startEnrollment`로 새 비밀값의 대기를 발급해 세션 속성 `TotpEnrollment.SESSION_ATTRIBUTE`를 덮어쓴다(`TotpEnrollmentLoginController.java:58-79`). 강제 등록 상태에서는 다른 관리 주소가 모두 이 화면으로 302하므로(`config/CLAUDE.md` "중간 상태는 둘이다"), 사용자가 QR을 스캔한 뒤 새로고침하거나 다른 주소를 열면 스캔한 QR이 무효가 되고, 앱 코드로 확정하면 `?expired` 안내와 함께 새 QR을 다시 스캔해야 한다(#256 실기 검증에서 확인).

### 완료 기준 (이슈 #277)
1. 세션의 대기가 같은 회원이고 10분 안이면 재사용, 만료·회원 불일치·없음이면 새로 발급
2. 새로고침해도 같은 QR(`enrollmentId`·설정 키)이 나오는 시험, 만료 뒤에는 새로 발급되는 시험
3. 보안 설정 화면의 등록 API(부를 때마다 대체) 동작은 바꾸지 않는다
4. `config` CLAUDE.md "강제 등록 화면" 문구 갱신

## 2. 정찰 요약 (코드 근거)

- `TotpEnrollment{enrollmentId, memberId, secret, issuedAt}`(세션, `Serializable`, DB 쓰기 없음). TTL `MemberTotpService.ENROLLMENT_TTL = 10분`.
- `startEnrollment`(`MemberTotpService.java:90-97`): `memberTotpRepository.existsById`면 `ConflictException`, 아니면 새 20바이트(`SECRET_BYTES`) 비밀값·UUID·`LocalDateTime.now(clock)`.
- 유효 판정은 이미 두 곳에 같은 식으로 있다: 컨트롤러 POST(`TotpEnrollmentLoginController.java:99-104` — 없음·회원 불일치·`enrollmentId` 불일치·`issuedAt + TTL isBefore now` → `?expired`), 서비스 `confirmEnrollment`(`MemberTotpService.java:108-113` → 409). 만료 경계: `issuedAt + TTL`과 같은 시각은 **유효**.
- GET은 `LoginSessionMutexFilter` 대상이라 같은 세션의 요청이 직렬화된다(`config/CLAUDE.md`). 컨트롤러는 그 안에서 `TotpSessionPromoter.isLiveFor`를 먼저 확인한다.
- 보안 설정 API `MemberTotpApiController.startEnrollment`(81행)는 같은 서비스 메서드 `startEnrollment`를 쓴다 — 바꾸지 않는다.
- 강제 등록 상태(`TOTP_ENROLLMENT_REQUIRED`)는 역할 권한이 없어 `/admin/api/**`가 401이다 → 이 상태에서 API가 세션 대기를 바꿀 수 없다. 반대로 완전 권한 세션에서 API가 둔 대기가 같은 세션 ID 위의 재로그인(세션 고정 방어 기본 전략 `ChangeSessionIdAuthenticationStrategy`가 `request.changeSessionId()`로 같은 세션을 유지한다 — 출처: Spring Security 7.0 레퍼런스 "Session Management", 7.0.7 소스 `ChangeSessionIdAuthenticationStrategy.java`. 앱 재로그인 경로 전체의 속성 유지는 시험하지 않는다 — 아래 논증상 유지돼도 무해)으로 강제 등록 화면에 넘어올 수는 있다. 같은 회원이면 그 대기도 본인에게 보여 준 비밀값이라 재사용해도 노출 범위가 늘지 않고, 다른 회원이면 회원 대조로 버린다.
- 시험: `TotpEnrollmentLoginControllerIntegrationTest`(MockMvc + Testcontainers, 실제 폼 로그인으로 강제 등록 세션을 만든다, `openPage`가 세션 속성을 읽는다). `TotpLoginBrowserTest.unenrolledAdminIsForcedToEnroll`는 `/admin` 302 뒤 설정 키를 **다시 읽으므로** 재사용 여부와 무관하게 통과한다. CI `scripts/ci/prod-smoke.sh`는 302를 따라가지 않고 GET 1회 → POST 1회라 영향 없음.

## 3. 설계 결정

### 결정 1 — 재사용 판정은 서비스의 새 메서드 `resumeOrStartEnrollment(principal, current)`에 둔다

- 동작: ① `existsById`면 `ConflictException`(기존 `startEnrollment`와 같은 검사·같은 예외) ② `current != null && current.isReusableFor(principal.getId(), now)`이면 `current`를 그대로 반환 ③ 아니면 `startEnrollment`와 같은 방식으로 새로 발급.
- 판정 `TotpEnrollment.isReusableFor(Long memberId, LocalDateTime now)` = `memberId` 일치 && `!issuedAt.plus(ENROLLMENT_TTL).isBefore(now)` — 시각을 인자로 받는 순수 메서드라 고정 시각 단위 시험으로 경계를 검증한다(v2).
- 컨트롤러 GET은 세션 속성을 꺼내(`instanceof TotpEnrollment`가 아니면 null) 이 메서드에 넘기고, 반환값을 세션에 다시 둔다(재사용이면 같은 객체 — 무해).
- 선택지: (A) 서비스 새 메서드(채택) / (B) 컨트롤러에서 판정 후 필요할 때만 `startEnrollment` 호출 / (C) `startEnrollment` 자체에 `current` 인자 추가.
- 이유: (B)는 재사용 경로에서 "이미 등록됨" 검사가 빠져 GET의 기존 동작(이미 등록이면 즉시 세션 종료·`/admin/login-error`)이 POST까지 미뤄진다 — 검사를 컨트롤러에 따로 넣으면 저장소 의존이 컨트롤러로 샌다. TTL·`Clock`·존재 검사가 이미 서비스에 있다. (C)는 보안 설정 API 호출부까지 바꿔야 하고 "부를 때마다 대체" 계약이 인자에 기대게 된다(완료 기준 3).
- 판정 식은 기존 두 곳과 **같은 식**(`isBefore`)을 쓴다 — 같은 시각에 대한 경계 정의(정확히 10분은 유효)가 세 곳에서 일치한다. GET 때 유효했던 대기가 POST 전에 만료되는 것은 막지 않는다(결정 2에서 수용한 만료 직전 동작 — `?expired`가 복구). 기존 두 곳을 공용 메서드로 합치는 정리는 하지 않는다(무관한 리팩터링).

### 결정 2 — 만료 직전 대기도 그대로 재사용한다(여유 시간 없음)

- 선택지: (A) 유효하면 재사용(채택) / (B) 남은 시간이 N분 미만이면 새로 발급 / (C) 재사용하면서 `issuedAt`을 갱신(연장).
- 이유: (A)는 이슈 완료 기준 그대로이고, 만료되면 기존 `?expired` → 새 QR 경로가 이미 복구한다. (B)는 "스캔했는데 남은 시간이 짧아 새로 발급"이라는 같은 증상을 경계 근처에서 다시 만든다(사용자는 남은 시간을 모른다). (C)는 비밀값 평문이 세션에 머무는 시간을 무한히 늘린다(`TotpEnrollment` 주석의 "대기 동안뿐" 계약 위반).

### 결정 3 — `?expired` 안내 문구는 그대로 둔다

- POST가 낡은 대기로 `?expired`에 오면, GET은 세션의 대기가 유효할 때(다른 탭이 새로 발급한 경우) 그것을 재사용해 보여 준다. 이 탭이 보던 QR과는 다르므로 "새 QR 코드를 다시 스캔하세요"는 여전히 맞다. 만료로 온 경우는 새로 발급된다.
- 한계(수용): 다른 탭이 이미 대기를 발급했으면 그 탭과 이 탭의 QR은 같아지지만, 처음 보던 QR(이전 대기)은 복구되지 않는다 — 이전 대기는 세션에 하나만 둔다는 기존 설계(`TotpEnrollment` 1개) 그대로다.

## 4. 성공 기준 (검증 가능)
- S1: 같은 세션에서 GET 두 번 → 세션 대기 `enrollmentId`·비밀값 동일, 모델 `enrollmentId`·`secret` 동일.
- S2: 강제 등록 상태에서 `/admin` → 302 → GET 화면이 첫 GET과 같은 `enrollmentId`(이슈의 실제 증상 경로).
- S3: 세션 대기의 `issuedAt`이 10분을 넘겼으면 GET이 새 `enrollmentId`·새 비밀값을 발급.
- S4: 세션 대기가 다른 회원 것이면 GET이 새로 발급(다른 회원의 비밀값을 그리지 않음).
- S5: 재사용한 대기로 POST 확정 성공(기존 확정 경로와 연결).
- S6: 보안 설정 API는 부를 때마다 다른 `enrollmentId`(기존 시험 유지·확인).
- S7: 재사용 경로라도 그 사이 다른 경로로 등록됐으면 GET이 세션 종료·`/admin/login-error`(기존 GET 동작 유지).

## 5. 실패 모드와 고정 시험
| # | 실패 모드 | 고정 시험 |
|---|---|---|
| FM1 | 재사용 판정을 `memberId` 없이 해 같은 세션의 다른 계정 대기(다른 회원 비밀값)를 그린다 | `TotpEnrollmentLoginControllerIntegrationTest` — 세션에 다른 회원 대기를 심은 뒤 GET → 새 `enrollmentId`·대기 `memberId` = 요청 회원 (S4) |
| FM2 | 만료 판정 누락·부호 반전으로 만료된 대기를 재사용(POST가 `?expired`로 되돌리는 순환) | 컨트롤러 시험 — `issuedAt`을 TTL+1분 과거로 바꾼 대기를 심고 GET → 새 발급 (S3). 경계는 새 단위 시험 `TotpEnrollmentTest`(고정 `LocalDateTime`): 발급+10분−1µs·정확히 10분 → 재사용, +10분+1µs → 불가, 다른 회원 → 불가 (v2 — 실제 시계 통합 시험의 경계 흔들림 회피) |
| FM3 | 재사용 경로가 "이미 등록됨" 검사를 건너뜀 | 컨트롤러 시험 — 대기 발급 후 `TotpTestSupport.enroll`, GET → `/admin/login-error`·세션 무효 (S7) |
| FM4 | 보안 설정 API까지 재사용으로 바뀜(완료 기준 3 위반) | `MemberTotpApiIntegrationTest:152` 기존 시험("다른 탭이 새 대기를 발급하면 옛 enrollmentId 확정은 409, 새 것은 200") 그대로 유지 — 같은 세션의 두 번째 `POST …/totp-enrollments`가 대기를 대체함을 이미 고정한다 (S6) |
| FM5 | GET이 대기를 재사용하지만 세션 속성을 다른 객체로 바꿔(예: 직렬화 복사 뒤 재발급) POST의 `enrollmentId` 대조가 틀어진다 | 컨트롤러 시험 — GET 두 번 뒤 첫 GET의 `enrollmentId`·코드로 POST 확정 성공 (S1·S5), `/admin` 302 경유 (S2) |

## 6. 작업 단계
1. (RED) 단위 시험 `TotpEnrollmentTest`(경계) → (GREEN) `TotpEnrollment.isReusableFor`.
2. (RED) `MemberTotpServiceIntegrationTest`에 `resumeOrStartEnrollment` 재사용·만료(1분 이상 여유)·회원 불일치·null·이미 등록 시험 → (GREEN) 구현(새 발급 부분은 `startEnrollment`와 공유하는 private 메서드).
3. (RED) 컨트롤러 시험 FM1·FM2·FM3·FM5.
4. (GREEN) `TotpEnrollmentLoginController.page`가 세션 대기를 넘기게 수정.
5. 영향 시험: `com.cms.admin.member.*`·`com.cms.config.auth.*`·`*ConventionTest`. 화면 무변경이라 브라우저 시험은 PR CI(`browser-test`)에 맡긴다.
6. 실기 검증(dev, playwright): 강제 등록 화면 → 새로고침 → 같은 설정 키 → `/admin` → 같은 설정 키 → 그 키의 코드로 확정 성공. 임시 계정 원복.
7. 기록: `config/CLAUDE.md` "강제 등록 화면", `member/CLAUDE.md` "등록 대기", 이 문서 결과, `plan/README.md` 인덱스.

## 7. 리스크
- 대기의 **유효기간**(확정·재사용 가능)은 발급 후 10분이고 재사용으로 연장되지 않는다. 세션 속성 자체는 만료 판정 때 지워지지 않아 실제 보관 기간은 세션 수명이다(기존 동작 — 이번 변경으로 늘지 않는다, 엄격한 10분 삭제는 범위 밖).
- 교차 사이트 GET이 대기를 바꾸던 효과는 오히려 줄어든다(유효하면 재사용).

## 핵심 확정 사항
- 계획 v2 그대로 구현했다(계획과 달라진 결정 없음): `TotpEnrollment.isReusableFor` + `MemberTotpService.resumeOrStartEnrollment` + 강제 등록 GET이 세션 대기를 넘김. 보안 설정 API는 `startEnrollment` 그대로.

## 구현 파일
- `admin/member/service/TotpEnrollment`(`isReusableFor`), `MemberTotpService`(`resumeOrStartEnrollment`), `admin/member/controller/TotpEnrollmentLoginController`(GET).
- 시험: 신규 `TotpEnrollmentTest`(2 — 경계), 보강 `MemberTotpServiceIntegrationTest`(+3), `TotpEnrollmentLoginControllerIntegrationTest`(+4).
- 문서: `config/CLAUDE.md`("강제 등록 화면"), `admin/member/CLAUDE.md`("등록 대기"), 이 문서, `plan/README.md`.

## 검증 결과
- 시험 먼저(RED 확인): 경계 단위 시험(빈 구현 `false`에서 실패), 서비스 재사용 시험(빈 구현 = 항상 새로 발급에서 실패), 컨트롤러 S1·S2·S5(기존 GET에서 실패). 기존 동작으로도 통과하는 회귀 시험은 변이로 확인 — 재사용 분기의 등록 여부 검사 제거 → FM3 서비스 시험 실패, `isReusableFor`의 회원 대조 제거 → FM1 컨트롤러 시험 실패, 만료 판정 제거 → FM2 컨트롤러 시험 실패(모두 원복).
- 영향 범위 시험(2026-11-19): `./gradlew test -PbrowserTests=exclude --tests 'com.cms.admin.member.*' --tests 'com.cms.config.auth.*' --tests '*ConventionTest'` 620건 통과·실패 0, `-PbrowserTests=only --tests '…TotpLoginBrowserTest'` 3건 통과. 전체 시험(2026-11-19, 코드 리뷰 마무리 — `cleanTest`): `./gradlew test -PbrowserTests=exclude` 3,158건 통과·실패 0·건너뜀 12. 화면 무변경이라 브라우저 전체는 PR CI `browser-test`에 맡긴다.
- 코드 리뷰(2026-11-19, `code-review-loop`): 1라운드 codex `review` 지적 0건 → 위험 변경이라 2라운드 `adversarial-review`(FM1~FM5·리뷰 초점) **approve, 지적 0건**. 수용·기각·보류 0건.
- 실기 검증(dev, playwright, 임시 ADMIN `verify-reuse` — 끝나고 등록·복구 코드·감사·방문 기록·계정 삭제로 원복): ① 비밀번호 → 강제 등록 화면 ② 새로고침 → 같은 설정 키·`enrollmentId` ③ `/admin/member/settings` → 등록 화면 302 → 같은 설정 키·`enrollmentId` ④ 강제 등록 상태의 `/admin/api/members/me/totp` → 401(경계 유지) ⑤ **처음 본 설정 키**로 계산한 코드 + 비밀번호로 확정 → 완료 화면·복구 코드 10개, DB `v2:` 암호문·코드 10행·감사 `TOTP_ENABLE`. 스크린샷 `docs/screenshots/auth/totp-09·10`(로컬 보관, git 무시). 만료 뒤 새 발급은 실기에서 세션 시각을 조작할 수 없어 시험(컨트롤러 FM2)으로만 확인했다.

## 이슈
- 실기 검증용 BCrypt 해시를 Gradle 캐시의 `spring-security-crypto` jar로 만들 때 Git Bash 경로(`/c/...`)를 Windows `java -cp`가 읽지 못했고, `spring-core`·`spring-jcl`도 클래스패스에 필요했다(`cygpath -w`로 해결). 그 전에 해시 없이 계정 행이 먼저 들어가 비밀번호를 뒤에 갱신했다 — 원복으로 함께 삭제.

## 후속
- 없음.
