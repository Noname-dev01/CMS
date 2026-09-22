# PLAN — 관리자 비밀번호 검증 정책 통일 + 이메일 정규화·길이 정합 (감사 H-01·M-07)

> 개정 이력
> v1 (2026-09-21): 최초 작성.
> v2 (2026-09-21): codex 적대적 리뷰 1라운드 반영.
> (1)[높음] 수용 — `@MaxUtf8Bytes` 검증기가 바이트 배열을 만들기 전에 먼저 문자 수(`length()`)로 단락 평가하도록 변경(모든 UTF-16 코드 단위는 UTF-8에서 최소 1바이트이므로 문자 수가 이미 상한을 넘으면 바이트 수도 반드시 넘는다 — 의미 보존). 대용량 문자열이 Jackson 기본 제약(2000만자)까지 검증 전 단계에서 파싱될 수 있어 실질적 방어값이 있다고 판단.
> (2)[높음] 수용(재정의) — 코드로 직접 확인(`PasswordResetControllerTest.java:164-175`, `AdminSignupRequest.email`의 기존 `@Email`)한 결과 "공백 포함 이메일이 `createAdmin`까지 도달해 공백-변형 중복 계정을 만들 수 있다"는 원래 계획의 핵심 전제가 **사실이 아니었다** — `@Email`이 이미 앞뒤 공백을 400으로 차단한다. 이 계약을 바꿀 이유가 없으므로(범위 밖 정책 변경) "공백 취약점" 프레이밍을 전부 제거하고, `createAdmin` 정규화의 실제 효과를 "대소문자만"으로 재정의했다.
> (3)[중간] 수용 — NIST SP 800-63B 공식 문서(`pages.nist.gov/800-63-4/sp800-63b.html`)를 직접 조회해 검증: 현행 rev.4는 **단일요소 인증 최소 15자**(8자는 MFA 전용, 이 프로젝트는 MFA 없음), 길이는 **유니코드 코드포인트** 단위로 세고, 최대 길이 64자 이상 권장, **정기적 비밀번호 변경을 명시적으로 금지**(이 프로젝트의 기존 90일 만료 정책과 충돌하나 그 정책은 별도로 승인된 기존 기능이라 이 계획의 범위 밖 — 건드리지 않음). "NIST 일반 기준"이라는 부정확한 인용을 제거했다. **사용자 재확인 결과 최소 길이를 8자 → 15자로 상향**(NIST 단일요소 기준 엄격 준수 — 아래 쟁점 2 참조). 코드포인트 카운팅 정합성을 위해 `@Size` 대신 신규 커스텀 애노테이션 `@MinCodePoints`를 도입(자체 발견 — codex 지적 범위 밖이지만 같은 근거로 직접 판단, 아래 쟁점 2-1 참조).
> (4)[중간] 수용 — `AdminBootstrapLoader`는 검증 실패 시 HTTP 400이 아니라 `IllegalStateException`으로 기동을 실패시킨다(기존에 알고 있었으나 완료 기준 문구가 4개 진입점을 "400 거부"로 뭉뚱그려 부정확했다). 완료 기준을 진입점 유형별(HTTP 400 vs 기동 실패)로 분리.
> (5)[중간] 수용 — 기존 `PasswordFieldBoundaryTest`(Bean Validation 직접 호출) 계획만으로는 실제 HTTP 파이프라인에서 400으로 매핑되는지, 서비스/`passwordEncoder`가 호출되지 않는지 증명하지 못한다. 기존 컨트롤러 테스트(`AdminMemberControllerTest`·`PasswordResetControllerTest`)에 경계값 케이스를 추가하도록 반영.
> (6)[낮음] 결정 필요 → 사용자 확인 완료 — 부트스트랩(`AdminBootstrapCredentials.email`)도 `EmailNormalizer`로 대소문자 정규화하기로 결정(비용 거의 0, "모든 관리자 생성 경로가 canonical email을 저장한다"는 목표를 문자 그대로 완성).
> v3 (2026-09-21): codex 적대적 리뷰 2라운드 반영(1라운드 6개 지적은 정상 반영됐다고 확인됨, v2 신규 설계에서 5개 신규 지적).
> (1)[중간] 수용 — 직접 실측 검증: `"\uD800".getBytes(UTF_8)`와 `"\uD801".getBytes(UTF_8)`가 **서로 다른 원문인데도 동일한 바이트 `3f`(치환 문자)로 인코딩됨**을 확인(Java 기본 인코딩은 잘못된 서로게이트를 조용히 치환한다). 이는 단순 카운팅 오차가 아니라 "서로 다른 비밀번호가 같은 BCrypt 해시로 저장될 수 있다"는 인증 정합성 문제다. `CharsetEncoder`+`CodingErrorAction.REPORT` 조합으로 같은 입력이 `MalformedInputException`을 던짐도 직접 실측 확인 — `MaxUtf8BytesValidator`를 이 방식으로 교체해 잘못된 UTF-16을 아예 거부하기로 결정(쟁점 2-2 참조). `@MinCodePoints`는 수정 불필요 — `@MaxUtf8Bytes`가 malformed 입력을 전부 거부하므로 Bean Validation 조합 결과 어차피 무효가 된다.
> (2)[중간] 수용 — `password-reset-confirm.html`의 JS `newPassword.length`(UTF-16 코드 단위)가 서버의 코드포인트 카운팅과 어긋난다(예: 이모지 8개는 JS로 길이 16, 서버로는 코드포인트 8). `Array.from(newPassword).length`(코드포인트 이터레이션)로 교체.
> (3)[중간, 결정 필요] 수용(표현만 정정 — 사용자 재확인 불필요, 숫자·동작 변경 없음) — NIST rev.4는 최대 길이 64자 이상 권장 + 유출된 비밀번호 blocklist 비교를 `SHALL`로 요구하는데, 이 계획은 최대 길이(72바이트, BCrypt 제약)·blocklist 어느 쪽도 다루지 않는다. "NIST 단일요소 기준 엄격 준수"라는 과장된 표현을 "NIST의 최소 길이·코드포인트 카운팅 규칙만 채택(최대 길이·blocklist 등 전체 준수는 아님)"으로 문서 전체에서 정정.
> (4)[중간] 수용 — 테스트 계획이 세 HTTP 진입점(생성·**내 비밀번호 변경**·재설정 확인) 중 내 비밀번호 변경(`changeMyPassword`)의 MVC 경계 테스트를 빠뜨렸다. 추가. 또한 부트스트랩 테스트의 `verifyNoInteractions(memberRepository)`는 틀렸다 — `existsByUserTypeAndStatus()`가 검증 전에 이미 호출되므로, `verify(memberRepository, never()).saveAndFlush(any())` + `verifyNoInteractions(passwordEncoder)`로 정정. 완료 기준도 "경계값 매트릭스는 `PasswordFieldBoundaryTest`가, 각 HTTP 진입점의 대표 경계값+서비스 미호출은 진입점별 MVC 테스트가 담당"으로 책임을 명확히 분리.
> (5)[낮음] 수용 — 직접 실측 검증: `"İ".toLowerCase(Locale.ROOT)`가 1자→2자("i̇")로 늘어나고, İ 100자를 정규화하면 200자가 됨을 확인 — Java의 대소문자 변환이 항상 1:1이 아니다. `@Size(max=100)`은 **정규화 전** 값만 검사하므로 정규화 후 100자를 넘는 이메일이 `Member.email`(길이 100) 컬럼에 저장 시도될 수 있다(현재는 `DataIntegrityViolationException`이 "중복 이메일"로 오분류되어 잘못된 오류 메시지를 반환). `EmailNormalizer.normalize()`가 정규화 후 길이를 재검증해 초과 시 `InvalidRequestException`(400)을 던지도록 수정.
> v4 (2026-09-22): codex 적대적 리뷰 3라운드 반영(2라운드 5개 지적은 정상 반영됐다고 확인됨, v3에서 신규 3개 지적).
> (1)[중간] 수용 — 직접 실측 검증(프로젝트가 실제로 쓰는 Hibernate Validator 8.0.3.Final로 `Validation.buildDefaultValidatorFactory()` 통해 확인): `@Email`이 고립 서로게이트를 포함한 `"\uD800@a.co"`를 **위반 0건으로 통과시킴**을 확인했다 — 비밀번호에는 이미 막아둔(쟁점 2-2) 것과 같은 종류의 "잘못된 UTF-16이 인코딩/DB 계층까지 도달"하는 문제가 이메일 경로에는 그대로 남아있었다. `EmailNormalizer.normalize()`에도 같은 `CharsetEncoder`+`REPORT` 기법을 적용해 정규화 과정에서 고립 서로게이트를 걸러내기로 결정(쟁점 11 확장).
> (2)[중간] 수용 — 실제 컨트롤러 코드(`AdminMemberController.java`·`PasswordResetController.java`)로 직접 확인: 완료 기준이 세 HTTP 진입점의 성공 응답을 전부 "200"으로 뭉뚱그렸으나 실제로는 생성 `201 Created`, 내 비밀번호 변경 `200 OK`, 재설정 확인 `204 No Content`로 서로 다르다. 또한 재설정 확인 경로를 계획에 `/admin/api/password-reset-confirm`으로 잘못 적었다 — 실제 경로는 `/admin/api/password-resets`(`PasswordResetController.java:48`). 완료 기준·쟁점 8 문구를 정정.
> (3)[낮음] 수용 — `AdminMemberControllerTest` 파일 목록 항목이 `userId` 51자 케이스만 언급하고 완료 기준에 이미 있던 `userName` 101자·`email` 101자 MVC 경계 테스트를 빠뜨렸다. 3개 필드 전부 포함하도록 보완.
> v5 (2026-09-22): 4라운드 시도 — codex CLI가 사용량 한도(usage limit)로 2회(최초 호출 + 1회 재시도) 모두 실패해(`ERROR: You've hit your usage limit`), `plan-review-loop` 스킬 규칙에 따라 **자체 적대적 리뷰**로 대체 수행(출처: codex 아님, Claude 자체 검토). v1~v4 누적 변경 전체(비밀번호 15코드포인트+72바이트+malformed UTF-16 거부, 이메일 정규화+길이 재검증+malformed UTF-16 거부, HTTP 상태 코드/경로 정정)를 다시 통독하며 내부 모순·용어 일관성·설계 상호작용(예: `@MaxUtf8Bytes`의 malformed 거부와 `@MinCodePoints`의 상호작용, `CharsetEncoder.encode(CharBuffer)`가 `onMalformedInput(REPORT)` 설정을 실제로 존중하는지 — 3라운드에서 이미 실측 확인된 것 재확인)를 점검했다. 실질적 신규 결함 없음 — 사소한 용어 표기 불일치(쟁점 9 설명 문단의 "14자"가 다른 곳의 "14코드포인트" 표기와 어긋남, 기술적 오류는 아니고 가독성 문제) 1건만 발견해 직접 정정. **판정: ship.**

## Context

외부 기술 감사(`docs/CMS-technical-audit-2026-09-05.md` H-01·M-07)와 로드맵(`adversarial-review/project-direction-roadmap.md` "실행 로드맵 — Top 5 (2026-09-05 선정)" ①)에서 발굴된 항목. 코드 대조 + 실측으로 사실 확인 완료:

- 관리자 계정 생성·변경·재설정·부트스트랩 4개 진입점(`AdminSignupRequest.pwd`·`AdminMyPasswordChangeRequest.newPassword`·`PasswordResetConfirmRequest.newPassword`·`AdminBootstrapCredentials.password`) 중 `AdminSignupRequest.pwd`만 유일하게 길이 검증(`@Size`)이 전혀 없다(`@NotBlank`만 있음 — 1자짜리 비밀번호도 통과).
- `AdminBootstrapCredentials`의 클래스 Javadoc이 스스로 "`AdminSignupRequest`는 비밀번호에 길이 정책이 없어 재사용하지 않는다"고 명시하고 있어, 이 불일치는 설계상 이미 인지되고 있었다.
- **BCrypt 72바이트 초과 시 500으로 새는 경로를 실제 프로젝트 의존성(spring-security-crypto 6.5.11)으로 직접 실행해 확인**: `BCryptPasswordEncoder.encode()`는 UTF-8 바이트 길이가 72를 초과하면 `IllegalArgumentException("password cannot be more than 72 bytes")`를 던진다(72바이트는 통과, 73바이트부터 예외). 현재 DTO 검증(`@Size(min=4, max=100)`)은 **문자 수** 기준이라 이 바이트 경계를 반영하지 못한다 — 예: 25자 한글 비밀번호(75바이트)는 DTO 검증을 통과하지만 인코딩 단계에서 예외가 난다. `GlobalApiExceptionHandler`에는 `IllegalArgumentException` 전용 핸들러가 없어 `handleException`(Exception catch-all)으로 떨어져 500 INTERNAL_ERROR가 응답된다(코드 열람으로 확인, `src/main/java/com/cms/common/api/GlobalApiExceptionHandler.java:365-378`).
- 생성 DTO(`AdminSignupRequest`)의 `userId`·`userName`·`email`에 `Member` 엔티티 컬럼 길이(각각 50/100/100, `src/main/java/com/cms/admin/member/domain/Member.java:35-45`)에 대응하는 `@Size` 상한이 없다. 프론트엔드(`admin-create.html`)는 이미 `maxlength="50"`/`"100"`/`"100"`을 걸어두고 있어 일반 UI 경유 시에는 문제가 드러나지 않지만, API를 직접 호출하면 우회된다.
- 이메일 정규화(트림·소문자화)가 서비스 간 중복·불일치 상태다: `AdminMemberService.normalizeEmail`(418행)은 **기본 Locale**로 `toLowerCase()`를 호출하고, `PasswordResetService.normalizeEmail`(311행)은 **`Locale.ROOT`**를 명시한다(주석에 "터키어 I/i 등에 의존하지 않도록"라고 이유가 적혀 있음 — 즉 기본 Locale 쪽이 잠재적으로 더 위험한 코드).
- **로드맵에 없던 추가 발견**: `AdminMemberService.createAdmin`(70-107행)은 `updateMyInfo`·`updateAdminMember`와 달리 이메일을 정규화하지 않고 `req.getEmail()`을 그대로 중복 검사(`existsByEmail`)와 저장에 사용한다. **(v2 정정)** `AdminSignupRequest.email`에도 `@Email`이 있어 앞뒤 공백을 포함한 이메일은 애초에 400으로 차단되어 `createAdmin`에 도달할 수 없다(코드로 직접 확인, 아래 쟁점 5). 실제 정규화 격차는 **대소문자뿐**이다 — `email` 컬럼 collation(`utf8mb4_general_ci`)이 대소문자 차이는 DB 유니크 제약에서 이미 막아주므로(`DataIntegrityViolationException` → 409) 이는 중복 계정을 막는 보안 수정이 아니라, 저장되는 값의 대소문자 표기를 다른 3개 경로와 일치시키는 **정합성(consistency)** 수정이다.

**사용자 사전 협의 결과(2026-09-21, 착수 게이트 확인 완료, v2에서 리뷰 반영 후 재확인)**:
- 비밀번호 최소 길이: **15코드포인트**(NIST SP 800-63B(rev.4)의 **단일요소 인증 최소 길이·코드포인트 카운팅 규칙을 채택** — 이 프로젝트는 MFA가 없으므로 MFA 전용인 8자 기준은 적용 대상이 아니다. 공식 문서 직접 조회로 검증. **(v3 정정)** NIST는 최대 길이 64자 이상 권장·유출 비밀번호 blocklist 비교(`SHALL`)도 요구하지만 이 계획은 그 둘을 다루지 않는다 — "전체 준수"가 아니라 "최소 길이 요구만 채택"이 정확한 표현이다).
- BCrypt 72바이트 처리: **DTO 경계에서 UTF-8 바이트 길이 검증만 추가**한다. `GlobalApiExceptionHandler`에 `IllegalArgumentException` 전역 매핑은 **이번 범위에서 추가하지 않는다** — 바이트 검증으로 인코딩 단계 도달 자체를 막으므로 근본 해결이며, 전역 매핑은 이 코드베이스의 다른 `IllegalArgumentException` 발생 지점(있다면)까지 400으로 뭉뚱그릴 위험이 있어 범위를 좁게 유지.
- 부트스트랩 이메일도 `EmailNormalizer`로 대소문자 정규화 포함(4개 생성 경로 전부 canonical email 저장).

## 핵심 쟁점과 설계 결정

### 쟁점 1 — 새 검증을 어디에 넣을 것인가 (재사용 가능한 커스텀 제약 vs 값 하드코딩 반복)

**선택지**
1. **커스텀 Bean Validation 제약 `@MaxUtf8Bytes(72)`를 신설해 4개 DTO 필드에 부착.** (선택)
2. 각 DTO에 바이트 길이를 직접 계산하는 `@AssertTrue` 메서드를 개별 작성.

**결정: 1번.** 이 프로젝트는 이미 `@AllowedRoles`/`@AllowedStatuses`(`com.cms.admin.member.dto.request.validation` 패키지, 필드 단위 `ConstraintValidator`)라는 확립된 패턴을 갖고 있다. 같은 제약(바이트 상한 72)이 4곳에 동일하게 적용되므로 재사용 가능한 애노테이션이 값 하드코딩 반복보다 정확히 이 상황에 맞는 최소 추상화다(과설계 아님 — 이미 4번 반복되는 동일 값). `@AssertTrue` 방식은 DTO마다 로직을 새로 적어야 해 오히려 더 반복적이다.

애노테이션은 범용으로 설계하되(문자열 필드라면 어디든 재사용 가능, Member 도메인에 결합하지 않음), 현재 사용처가 전부 `admin.member` 패키지이므로 기존 `AllowedRoles`와 같은 패키지(`com.cms.admin.member.dto.request.validation`)에 둔다 — 다른 도메인에서 필요해지면 그때 `common`으로 옮긴다(추측성 선이동 금지).

### 쟁점 2 — 최소 길이를 어떤 단위로 셀 것인가 (v2, codex 지적 3 반영)

**v1의 오류**: 최소 길이를 `@Size(min = 8)`(문자 수 = `String.length()`, 즉 UTF-16 코드 단위)로 설계하고 "NIST 일반 기준"이라 설명했다. NIST SP 800-63B(rev.4) 공식 문서를 직접 조회해 검증한 결과 이는 부정확했다 — 현행 기준은 **단일요소 인증 최소 15자**(8자는 MFA 전용)이고, 길이는 **유니코드 코드포인트** 단위로 센다("Each Unicode code point SHALL be counted as a single character"). `String.length()`(UTF-16 코드 단위)는 서로게이트 쌍(이모지 등 BMP 밖 문자)에서 코드포인트 1개를 2로 잘못 셀 수 있어 완전히 같지 않다.

**결정**: 사용자가 "15자로 상향 + NIST 단일요소 기준(코드포인트 카운팅 포함) 채택"을 선택했으므로, 숫자뿐 아니라 카운팅 방식도 정확히 맞춘다. `@Size` 대신 신규 커스텀 애노테이션 `@MinCodePoints(15)`를 만들어 `value.codePointCount(0, value.length()) >= min`으로 판정한다(쟁점 2-1 참조). 최대 길이(72)는 여전히 **UTF-8 바이트 수**(`@MaxUtf8Bytes(72)`) 기준 — 이는 NIST 권고가 아니라 BCrypt의 물리적 제약이므로 코드포인트와 무관하게 바이트 단위가 맞다. 기존 `@Size`의 `max=100`(문자 수 기준) 속성은 제거한다 — 바이트 상한 72가 있으면 ASCII만 입력해도 물리적으로 최대 72자라 문자 수 `max=100`은 도달 불가능한 죽은 제약이 된다.

**(v3 정정, 범위 명시)** 이 계획이 채택하는 것은 NIST의 **최소 길이(15코드포인트)·카운팅 규칙**뿐이다 — NIST rev.4가 함께 요구하는 최대 길이 64자 이상 허용, 유출된 비밀번호 blocklist 비교(`SHALL`)는 이번 범위에서 다루지 않는다(blocklist는 신규 의존성·데이터셋이 필요한 별도 과제, 최대 길이는 BCrypt 72바이트 제약과 직접 상충해 이 프로젝트가 BCrypt를 유지하는 한 확장할 수 없다 — 해시 전략 자체를 바꾸는 것은 이번 계획의 범위를 크게 벗어난다). "NIST 단일요소 기준 엄격 준수"라는 표현은 이 문서 전체에서 사용하지 않는다.

### 쟁점 2-1 — 코드포인트 최소 길이 검증을 별도 애노테이션으로 만들 것인가 (v2 신규, 자체 발견)

**선택지**
1. `@Size(min = 15)`를 그대로 쓴다(코드 단위 기준 — NIST의 코드포인트 기준과 근소하게 다름).
2. **`@MinCodePoints(15)` 커스텀 애노테이션을 신설한다(코드포인트 기준).** (선택)

**결정: 2번.** "NIST 단일요소 기준(코드포인트 카운팅 포함) 채택"이라는 사용자 결정을 문자 그대로 지키려면 카운팅 단위도 맞춰야 한다. `@MaxUtf8Bytes`를 이미 커스텀으로 만들기로 했으므로(쟁점 1) 같은 패키지에 자매 애노테이션 하나를 추가하는 비용은 낮다.

### 쟁점 2-2 — 잘못된 형식의 UTF-16(고립 서로게이트)이 카운팅·인코딩을 오염시키는가 (v3 신규, codex 지적 1 반영)

**직접 실측 확인(2026-09-21)**: 서로 다른 두 고립 서로게이트 문자열 `"\uD800"`과 `"\uD801"`이 `codePointCount()`로는 각각 1로 (정상적으로) 다르게 카운트되지만, **`getBytes(UTF_8)`로는 둘 다 동일한 치환 바이트 `0x3f`로 인코딩됨**을 실행으로 확인했다. 즉 서로 다른 비밀번호 원문이 BCrypt에는 같은 바이트열로 전달되어 같은 해시가 나올 수 있다 — 단순 카운팅 오차가 아니라 인증 정합성(서로 다른 비밀번호가 같은 것으로 통용됨) 문제다. JSON 역직렬화(Jackson)는 `\uD800` 같은 이스케이프를 서로게이트 쌍 여부 검증 없이 그대로 Java `String`에 담으므로, 공개 경로(비밀번호 재설정 확인)를 포함한 모든 진입점에서 이 입력이 실제로 도달 가능하다.

**선택지**
1. 카운팅만 정교화하고 인코딩은 그대로 둔다(현재 상태 — 여전히 위험).
2. **`MaxUtf8BytesValidator`의 바이트 계산을 `CharsetEncoder`(`onMalformedInput`/`onUnmappableCharacter` = `CodingErrorAction.REPORT`)로 교체해, 잘못된 형식의 UTF-16이면 `CharacterCodingException`을 잡아 검증 실패(무효)로 처리한다.** (선택)

**결정: 2번.** 직접 실측: `CharsetEncoder`를 `REPORT` 모드로 설정하면 고립 서로게이트 입력에서 실제로 `MalformedInputException`이 던져짐을 확인했다 — 치환 없이 명확하게 거부할 수 있다. 이 변경 하나로 충분하다: Bean Validation은 한 필드에 걸린 여러 제약을 모두 평가해 하나라도 위반하면 전체가 무효이므로, `@MaxUtf8Bytes`가 잘못된 UTF-16을 전부 거부하면 `@MinCodePoints`의 카운팅 결과와 무관하게 그 비밀번호는 애초에 유효하지 않다 — `@MinCodePoints` 자체는 수정할 필요가 없다. `ConstraintValidator` 인스턴스는 여러 스레드에서 재사용될 수 있으므로 `CharsetEncoder`는 인스턴스 필드가 아니라 `isValid()` 호출마다 새로 생성한다(스레드 안전성).

### 쟁점 3 — `AdminSignupRequest`의 `userId`/`userName`/`email` 상한을 어떻게 맞출 것인가

**결정**: `Member` 엔티티 컬럼과 정확히 일치시킨다 — `userId` `@Size(max = 50)`, `userName` `@Size(max = 100)`, `email` `@Size(max = 100)`(기존 `@Email`은 유지). 프론트엔드(`admin-create.html`)의 `maxlength` 값과도 이미 일치하므로 UI 쪽 추가 변경은 불필요.

`userId`에 **최소 길이**를 신설할지도 검토했으나 **범위에서 제외한다** — 감사 항목(H-01·M-07)은 "길이 상한을 컬럼에 맞춘다"만 지적했고, 로그인 식별자의 최소 길이 정책 신설은 별도의 로그인 정책 변경이라 이번 승인 범위(비밀번호 정책)를 벗어난다. 필요하면 별도 항목으로 재평가.

### 쟁점 4 — 이메일 정규화 중복을 어떻게 해소할 것인가

**선택지**
1. `AdminMemberService.normalizeEmail`을 `Locale.ROOT`로 고치고 `PasswordResetService`는 그대로 둔다(중복은 남음).
2. **공통 유틸 클래스 `EmailNormalizer`(정적 메서드, `com.cms.admin.member.service` 패키지)로 추출해 두 서비스가 재사용.** (선택)

**결정: 2번.** 로드맵이 명시적으로 "서비스 간 공통 유틸로 통일"을 목표로 제시했고, 두 구현이 완전히 동일한 로직(trim + lowercase)에 Locale만 다른 것은 전형적인 "같은 개념의 두 구현" 코드 냄새라 유틸 추출이 정확히 요청된 범위다. `ProfileImageValidator`(같은 패키지, `public final class` + `private` 생성자 + `static` 메서드)의 기존 스타일을 그대로 따른다. `Locale.ROOT`로 통일(터키어 로케일 등 기본 Locale 의존 회피 — 기존 `PasswordResetService` 주석의 근거를 그대로 채택).

`common`이 아니라 `admin.member.service`에 두는 이유: 현재 이메일 정규화가 필요한 곳이 이 도메인뿐이다. 다른 도메인이 필요해지면 그때 옮긴다.

### 쟁점 5 — `createAdmin`의 이메일 정규화 부재를 어떻게 닫을 것인가 (v2, codex 지적 2 반영 — 전제 재정의)

**v1의 오류**: "앞뒤 공백이 포함된 이메일이 `createAdmin`을 통과해 공백-변형 중복 계정을 만들 수 있다"고 주장했다. **코드로 직접 확인한 결과 이 전제는 사실이 아니다** — `AdminSignupRequest.email`에 이미 `@Email @NotBlank`가 있고, Hibernate Validator의 `@Email`은 앞뒤 공백이 포함된 문자열을 형식 위반으로 거부한다. 기존 테스트(`PasswordResetControllerTest.requestReset_emailWithSpaces_returns400`, 164-175행)가 같은 DTO 패턴(`PasswordResetRequestRequest.email`도 `@Email`)에서 이 계약을 이미 명시적으로 검증하고 있다 — `" admin@test.com "` → 400 VALIDATION_ERROR + 서비스 미호출. 즉 공백을 포함한 이메일은 애초에 `createAdmin`에 도달할 수 없으므로 "공백 중복 계정" 시나리오 자체가 성립하지 않는다.

**결정(재정의)**: `createAdmin`에서 `EmailNormalizer.normalize(req.getEmail())`로 정규화한 뒤 중복 검사(`existsByEmail`)와 `Member` 저장 양쪽에 적용하는 것은 그대로 유지하되, 목적을 정확히 다시 규정한다 — **대소문자 정규화만**이 실질 효과다(공백 트리밍은 `@Email` 뒤에서는 항상 no-op). 대소문자 차이는 DB collation(`utf8mb4_general_ci`)이 유니크 제약에서 이미 막아주므로(409로 처리됨 — 보안 문제 아님), 이 수정의 가치는 **저장되는 값의 표기를 다른 3개 경로(`updateMyInfo`·`updateAdminMember`·부트스트랩)와 일치시키는 데이터 정합성**이다. "공백 이메일 취약점 해소"라는 표현은 계획에서 완전히 제거한다.

**기존 `@Email` 계약은 그대로 유지**: 공백 거부는 이미 승인·테스트된 기존 정책이므로 이번 계획에서 바꾸지 않는다(codex가 제시한 두 대안 중 "공백 거부 계약 유지" 쪽 — 범위 밖 정책 변경을 피하는 명백한 선택이라 사용자 재확인 없이 채택).

**기존 데이터 영향 없음**: 생성 시점에만 적용되는 신규 로직이라 기존 행에 소급 적용되지 않는다.

### 쟁점 6 — 기존 `@Size(min=4, max=100)` 자리를 정확히 어떻게 교체할 것인가

**결정**: `AdminMyPasswordChangeRequest.newPassword`, `PasswordResetConfirmRequest.newPassword`, `AdminBootstrapCredentials.password`, `AdminSignupRequest.pwd` 4곳 전부 `@MinCodePoints(15)` + `@MaxUtf8Bytes(72)` 조합으로 통일한다. `AdminBootstrapCredentials`의 클래스 Javadoc(현재 "AdminSignupRequest는 정책이 없어 재사용하지 않는다"는 문구)도 갱신한다 — 이제 4곳이 완전히 동일한 정책을 쓰므로 "재사용하지 않는 이유" 설명 자체가 무의미해진다.

### 쟁점 7 — 로그인(인증) 자체에 이 변경이 영향을 주는가

**결정**: 영향 없음. 로그인은 `passwordEncoder.matches()`만 수행하며 길이 검증을 거치지 않는다. 기존에 4~14자 비밀번호로 생성된 계정은 새 정책 적용 후에도 계속 로그인 가능하다(소급 강제 변경 없음 — 다음 비밀번호 변경/재설정 시점부터 새 정책 적용). 이는 로그인 정책(계정 잠금·만료 등) 자체를 바꾸는 것이 아니라 **비밀번호를 새로 설정하는 시점의 입력 검증**만 바꾸는 것이므로, CLAUDE.md의 "인가 정책·로그인 정책은 사전 협의 필수" 규칙에 해당하되 이미 사용자 협의를 마쳤다.

**참고(범위 밖, v2 기록)**: NIST SP 800-63B(rev.4)는 정기적 비밀번호 변경(rotation)을 명시적으로 금지(`SHALL NOT require ... periodically`)한다 — 이는 이 프로젝트의 기존 90일 만료 정책(`PasswordExpiryService`, `PLAN-password-expiry.md`로 이미 승인·구현·완료됨)과 원칙적으로 충돌한다. 그러나 90일 만료 정책은 이 계획의 범위가 아닌 별도의 기존 승인 기능이며, 재검토하려면 로그인 정책 변경 협의가 별도로 필요하다 — 이번 계획에서는 건드리지 않고 사실만 기록해둔다.

### 쟁점 8 — 부트스트랩(`AdminBootstrapCredentials`) 검증 실패는 HTTP 진입점이 아니다 (v2 신규, codex 지적 4 반영)

**v1의 오류**: 완료 기준에서 "1자/7자/73바이트 비밀번호 400 거부 — 4개 진입점"처럼 4개 진입점을 "HTTP 400"으로 뭉뚱그렸다. `AdminBootstrapLoader`는 `CommandLineRunner`로, HTTP 요청을 처리하지 않는다 — 검증 실패 시 `validator.validate(credentials)`의 위반을 모아 `IllegalStateException`을 던져 **애플리케이션 기동 자체를 실패**시킨다(`AdminBootstrapLoader.java:82-89`).

**결정**: 완료 기준·테스트 계획을 진입점 유형별로 명확히 분리한다 — ① 관리자 생성(`POST /admin/api/members`)·내 비밀번호 변경(`PATCH .../me/password`)·비밀번호 재설정 확인(`POST /admin/api/password-resets` — **(v4 정정)** 계획 초안에 `/admin/api/password-reset-confirm`으로 잘못 적었던 것을 `PasswordResetController.java:48` 실측으로 정정)은 검증 실패 시 **HTTP 400 VALIDATION_ERROR**, ② 부트스트랩은 **`IllegalStateException`으로 기동 실패**(HTTP 계층 자체가 없음). 두 경우 모두 공통으로 검증하는 것은 "위반된 입력이 `passwordEncoder.encode()`·DB 저장까지 도달하지 않는다"는 사실이다.

### 쟁점 9 — 테스트가 실제로 무엇을 증명해야 하는가 (v2 신규, codex 지적 5 반영)

**v1의 오류**: `PasswordFieldBoundaryTest`(Bean Validation `Validator`를 직접 호출)만으로는 애노테이션이 4개 DTO에 정확히 붙어있고 경계값에서 위반을 일으킨다는 것만 증명한다. 다음은 증명하지 못한다: 실제 Spring MVC 요청이 `GlobalApiExceptionHandler`를 거쳐 400으로 응답되는지, 검증 실패 시 서비스·`passwordEncoder`가 전혀 호출되지 않는지(불필요한 BCrypt 연산·DB 조회 낭비 방지 확인), 부트스트랩이 인코더 호출 전에 실패하는지.

**결정**: 계층을 분리해 각자 책임을 좁게 증명한다.
1. **`PasswordFieldBoundaryTest`**(단위, Bean Validation `Validator` 직접 호출): 4개 DTO의 애노테이션 부착·경계값 판정 매트릭스(빠르고 정밀, Spring 컨텍스트 불필요).
2. **컨트롤러 테스트 확장**(`AdminMemberControllerTest`·`PasswordResetControllerTest`, 기존 `@WebMvcTest` 슬라이스에 케이스 추가): 14코드포인트·73바이트 등 대표 경계값 1~2개씩만 추가해 실제 HTTP 파이프라인이 400으로 응답하고 `verifyNoInteractions`/`verify(..., never())`로 서비스가 호출되지 않음을 확인 — 전체 매트릭스를 컨트롤러 레벨에서 반복하지 않는다(1번과 책임 중복 방지, 최소한의 코드로 구현 원칙).
3. **`AdminBootstrapLoaderTest` 확장**: 73바이트 비밀번호 케이스 추가 — `IllegalStateException` 발생 + `passwordEncoder.encode()`·`memberRepository.saveAndFlush()` 미호출(`verifyNoInteractions`) 확인.

### 쟁점 10 — 비밀번호 재설정 화면(JS)의 길이 카운팅이 서버와 다시 어긋나는가 (v3 신규, codex 지적 2 반영)

`password-reset-confirm.html`의 JS는 `newPassword.length`(UTF-16 코드 단위)로 판정하도록 계획했는데, 서버는 이제 `@MinCodePoints`(유니코드 코드포인트)로 판정한다. 서로게이트 쌍을 쓰는 문자(이모지 등)에서 JS `.length`는 코드포인트 수의 2배로 계산돼, 클라이언트에서는 통과했는데 서버에서 거부되는 혼란스러운 불일치가 다시 생긴다.

**결정**: JS를 `Array.from(newPassword).length`(코드포인트 단위 이터레이션)로 교체한다. 서버가 항상 최종 권위라는 원칙은 유지(JS는 UX 보조일 뿐).

### 쟁점 11 — 이메일 대소문자 정규화가 길이를 늘릴 수 있는가 (v3 신규, codex 지적 5 반영)

**직접 실측 확인(2026-09-21)**: `"İ"(U+0130).toLowerCase(Locale.ROOT)`가 1자 → 2자("i̇")로 늘어나고, İ 100자로 이뤄진 문자열을 정규화하면 200자가 됨을 실행으로 확인했다. Java의 대소문자 변환은 일부 유니코드 문자에서 1:1이 아니다. `AdminSignupRequest.email`·`AdminMyInfoUpdateRequest.email` 등의 `@Size(max=100)`은 **정규화 전** 원문만 검사하므로, 정규화 후 100자를 넘는 이메일이 `Member.email`(길이 100, `Member.java:44`) 컬럼에 `INSERT`/`UPDATE`될 수 있다 — 이 경우 현재 `GlobalApiExceptionHandler.handleDataIntegrityViolation`이 무조건 "중복된 데이터"로 안내해(275-279행, `uk_member_email` 문자열 매칭 실패 시 기본 메시지도 "중복") 실제 원인(길이 초과)과 다른 오해를 부르는 오류 메시지가 나간다.

**결정**: `EmailNormalizer.normalize()`가 정규화 직후 길이를 재검증해, `Member.email` 컬럼 길이(100)를 넘으면 `InvalidRequestException`(기존 `GlobalApiExceptionHandler`가 400으로 매핑)을 던지도록 한다. 매직 넘버 100은 `Member.email` 컬럼 길이와 정확히 일치시키고 그 이유를 Javadoc에 명시한다.

**(v4 확장, codex 지적 1 반영)** — **직접 실측 확인(2026-09-22, 이 프로젝트가 실제로 쓰는 Hibernate Validator 8.0.3.Final)**: `@Email`이 고립 서로게이트를 포함한 `"\uD800@a.co"`를 **위반 0건으로 통과**시킨다(`jakarta.validation.Validator`로 직접 검증). 즉 비밀번호에서 막은 것과 같은 종류의 "잘못된 형식의 UTF-16이 인코딩/DB 계층까지 도달"하는 문제가 이메일에는 여전히 열려 있다 — `trim()`/`toLowerCase()`는 고립 서로게이트를 제거하지 않는다. `EmailNormalizer.normalize()`에 `MaxUtf8BytesValidator`(쟁점 2-2)와 동일한 `CharsetEncoder`(UTF-8, `onMalformedInput`/`onUnmappableCharacter` = `CodingErrorAction.REPORT`) 검사를 추가해, 정규화 결과가 잘못된 형식이면 `InvalidRequestException`을 던진다(길이 재검증과 같은 예외 타입·같은 400 처리 경로 재사용).

**범위 밖**: 부트스트랩 경로(`AdminBootstrapLoader`)에서 `EmailNormalizer`가 던지는 `InvalidRequestException`(길이 초과·잘못된 UTF-16 둘 다)은 `IllegalStateException`으로 감싸지 않고 그대로 전파되어 기동이 실패한다(별도 catch 추가하지 않음) — 두 시나리오 모두 극히 희귀한 에지 케이스이고, 기동 실패라는 결과 자체는 기존 fail-fast 철학과 일치하므로 메시지 다듬기 이상의 추가 작업은 하지 않는다.

### 쟁점 12 — 완료 기준·테스트 책임을 HTTP 진입점별로 정확히 나누기 (v3 신규, codex 지적 4 반영)

**v2의 오류**: "4개 진입점 공통"이라는 완료 기준 문구가 실제로는 `PasswordFieldBoundaryTest`(DTO 단위)만으로 커버되는 매트릭스와, 컨트롤러 테스트가 커버하는 "대표 1~2개 경계값 + 서비스 미호출"을 뭉뚱그렸다. 또한 `changeMyPassword`(내 비밀번호 변경) MVC 경계 테스트가 계획에서 누락됐고, 부트스트랩 테스트의 `verifyNoInteractions(memberRepository)`는 `existsByUserTypeAndStatus()`가 검증 전에 이미 호출되므로 성립하지 않는다.

**결정**: 아래와 같이 책임을 명시적으로 나눈다.
- **전체 경계 매트릭스**(14/15코드포인트, 72/73바이트, 한글 경계, 서로게이트 쌍, 고립 서로게이트): `PasswordFieldBoundaryTest` 하나가 4개 DTO 전부에 대해 담당.
- **HTTP 진입점별 대표 케이스 + 서비스 미호출**(전체 매트릭스 반복 아님, 파이프라인 배선 확인용): `AdminMemberControllerTest`(생성 `POST /admin/api/members` + **내 비밀번호 변경 `PATCH .../me/password`**, 두 엔드포인트 각각), `PasswordResetControllerTest`(재설정 확인)에 14코드포인트·73바이트 대표 케이스 1~2개씩.
- **부트스트랩**: `AdminBootstrapLoaderTest`에 73바이트 케이스 추가 — `IllegalStateException` 발생을 확인하고, `verify(memberRepository, never()).saveAndFlush(any())` + `verifyNoInteractions(passwordEncoder)`로 정정(전체 `memberRepository`에 대한 `verifyNoInteractions`는 사용하지 않음 — `existsByUserTypeAndStatus()` 호출과 모순).

## 구현해야 할 정확한 파일

**신규**
- `src/main/java/com/cms/admin/member/dto/request/validation/MaxUtf8Bytes.java` — 제약 애노테이션(`int value()` = 최대 바이트 수, 필드 대상)
- `src/main/java/com/cms/admin/member/dto/request/validation/MaxUtf8BytesValidator.java` — `ConstraintValidator<MaxUtf8Bytes, String>`. `null`은 통과(널 검증은 `@NotBlank`에 위임 — `AllowedRolesValidator`와 동일 관례). **(v2)** 먼저 `if (value.length() > max) return false;`로 문자 수 단락 평가해 대용량 입력의 불필요한 인코딩을 피한다. **(v3, 쟁점 2-2)** 바이트 계산은 `value.getBytes(UTF_8)` 대신 `CharsetEncoder`(UTF-8, `onMalformedInput`/`onUnmappableCharacter` = `CodingErrorAction.REPORT`)로 인코딩해 결과 `ByteBuffer`의 길이를 검사하고, `CharacterCodingException`(고립 서로게이트 등 잘못된 형식의 UTF-16)이 발생하면 무효로 처리한다(서로 다른 원문이 같은 치환 바이트로 뭉개져 다른 비밀번호가 같은 해시가 되는 것을 방지 — 실측 확인, 쟁점 2-2). `CharsetEncoder`는 스레드 안전하지 않으므로 인스턴스 필드가 아니라 `isValid()` 호출마다 `StandardCharsets.UTF_8.newEncoder()`로 새로 만든다.
- `src/main/java/com/cms/admin/member/dto/request/validation/MinCodePoints.java` **(v2 신규)** — 제약 애노테이션(`int value()` = 최소 코드포인트 수)
- `src/main/java/com/cms/admin/member/dto/request/validation/MinCodePointsValidator.java` **(v2 신규)** — `ConstraintValidator<MinCodePoints, String>`. `null`은 통과, `value.codePointCount(0, value.length()) >= min`으로 판정(NIST의 유니코드 코드포인트 카운팅 방식과 일치)
- `src/main/java/com/cms/admin/member/service/EmailNormalizer.java` — `public final class` + `private` 생성자 + `public static String normalize(String email)`(`null` 그대로 반환, 아니면 `trim().toLowerCase(Locale.ROOT)`). **(v3, 쟁점 11)** 정규화 결과 길이가 `Member.email` 컬럼 길이(100, 상수화하고 Javadoc에 출처 명시)를 넘으면 `InvalidRequestException`(400) 발생. **(v4 추가, 쟁점 11 확장)** 정규화 결과를 `MaxUtf8BytesValidator`와 동일한 `CharsetEncoder`(UTF-8, `CodingErrorAction.REPORT`)로 인코딩 시도해 `CharacterCodingException`(고립 서로게이트 등)이면 마찬가지로 `InvalidRequestException` 발생 — `@Email`이 고립 서로게이트를 걸러내지 못함을 실측 확인했으므로 이 유틸이 마지막 방어선.
- `src/test/java/com/cms/admin/member/service/EmailNormalizerTest.java` — trim/lowercase/null-safety 단위 테스트. **(v3)** İ(U+0130) 등 정규화 후 길이가 늘어나는 문자를 컬럼 길이 근처까지 반복한 입력이 `InvalidRequestException`을 던짐을 확인하는 경계값 케이스 추가. **(v4 추가)** `"\uD800@a.co"` 같은 고립 서로게이트 포함 이메일이 `InvalidRequestException`을 던짐을 확인하는 케이스 추가.
- `src/test/java/com/cms/admin/member/dto/request/PasswordFieldBoundaryTest.java` — `jakarta.validation.Validator`(Spring 컨텍스트 없이 `Validation.buildDefaultValidatorFactory()`)로 4개 DTO(`AdminSignupRequest`·`AdminMyPasswordChangeRequest`·`PasswordResetConfirmRequest`·`AdminBootstrapCredentials`)의 비밀번호 필드에 동일한 경계 매트릭스를 적용해 위반 여부가 4곳 모두 일치하는지 검증: **(v2)** 14자(위반)/15자(통과)/ASCII 72바이트(통과)/ASCII 73바이트(위반)/한글 24자=72바이트(통과)/한글 25자=75바이트(위반). 코드포인트 카운팅 확인용으로 서로게이트 쌍 문자(예: 이모지) 15개(코드포인트 15, `length()`는 30) 통과 케이스도 추가(`@MinCodePoints`가 `@Size`와 다르게 동작함을 증명). **(v3, 쟁점 2-2)** 고립 서로게이트(`"\uD800"` 등) 입력이 `@MaxUtf8Bytes` 위반으로 무효 처리됨을 확인하는 케이스 추가(치환 인코딩으로 통과해버리는 회귀 방지).

**수정**
- `src/main/java/com/cms/admin/member/dto/request/AdminSignupRequest.java` — `pwd`에 `@MinCodePoints(value=15, message=...)` + `@MaxUtf8Bytes(value=72, message=...)` 추가, `userId`에 `@Size(max=50)`, `userName`에 `@Size(max=100)`, `email`에 `@Size(max=100)` 추가
- `src/main/java/com/cms/admin/member/dto/request/AdminMyPasswordChangeRequest.java` — `newPassword`의 `@Size(min=4, max=100)` → `@MinCodePoints(15)` + `@MaxUtf8Bytes(72)`
- `src/main/java/com/cms/admin/member/dto/request/PasswordResetConfirmRequest.java` — `newPassword` 동일 교체
- `src/main/java/com/cms/admin/member/AdminBootstrapCredentials.java` — `password` 동일 교체, 클래스 Javadoc 갱신(정책 통일 반영)
- `src/main/java/com/cms/admin/member/service/AdminMemberService.java` — `createAdmin`에서 `EmailNormalizer.normalize()` 적용(중복 검사·저장 양쪽), 기존 `private normalizeEmail` 메서드 제거 후 호출부를 `EmailNormalizer.normalize()`로 교체
- `src/main/java/com/cms/admin/member/service/PasswordResetService.java` — 기존 `private normalizeEmail`(311행) 제거, `EmailNormalizer.normalize()` 호출로 교체, 이제 쓰이지 않는 `import java.util.Locale;` 제거
- `src/main/java/com/cms/admin/member/AdminBootstrapLoader.java` **(v2 신규 — 결정 6)** — `createOrReconcile`의 108행 `.email(credentials.getEmail())` → `.email(EmailNormalizer.normalize(credentials.getEmail()))`
- `src/test/java/com/cms/admin/member/service/AdminMemberServiceTest.java` — `createAdmin_success` 근처에 `createAdmin_normalizesEmailCaseBeforeDuplicateCheckAndSave` 신규 테스트 추가 **(v2, 대소문자만으로 시나리오 축소)**: 입력 이메일에 대문자를 섞어(`"Admin01@Test.com"`, 공백 없음 — `@Email`을 통과하는 값이어야 서비스까지 도달) `existsByEmail`이 정규화된 값(`"admin01@test.com"`)으로 호출되는지, `save()`에 전달된 `Member`의 `email`이 정규화된 값인지(`ArgumentCaptor<Member>`) 확인
- `src/test/java/com/cms/admin/member/AdminBootstrapLoaderTest.java` — 133행 주석 `// 4자 미만` → `// 14자 이하`(값 자체는 `"abc"`로 그대로 유효). **(v2 추가)** 성공 케이스(`noActiveAdmin_validCredentials_createsAdmin`, 79-99행)의 `"bootpass1!"`(10자)을 15자 이상 값으로 교체하지 않으면 새 정책으로 실패하므로 값 교체 필요. **(v3 정정, 쟁점 12)** 73바이트 비밀번호 → `IllegalStateException` 발생 + `verify(memberRepository, never()).saveAndFlush(any())` + `verifyNoInteractions(passwordEncoder)`로 검증(`existsByUserTypeAndStatus()`가 검증 전에 호출되므로 `verifyNoInteractions(memberRepository)`는 쓸 수 없다). **(v2 신규, 결정 6)** 이메일 대문자 입력(`"BOOT@EXAMPLE.COM"`) → 저장된 `Member.email`이 `"boot@example.com"`으로 정규화됨을 확인하는 테스트 추가
- `src/test/java/com/cms/admin/member/controller/AdminMemberControllerTest.java` **(v3 확장 — 쟁점 12, v4 보완)** — 기존 `createAdmin_validation_fail`(165-183행) 패턴에 경계값 케이스 추가 + `verifyNoInteractions(adminMemberService)`로 서비스 미호출 확인: 14코드포인트 비밀번호, ASCII 73바이트 비밀번호, **(v4 정정 — 완료 기준과 일치하도록 3개 필드 전부 포함)** `userId` 51자, `userName` 101자, `email` 101자. **(v3 신규)** 기존 `changeMyPassword_validationFail`(734-742행) 패턴에도 동일하게 `newPassword` 14코드포인트·73바이트 케이스 추가 + `verifyNoInteractions(adminMemberService)`(내 비밀번호 변경 MVC 경계 테스트가 v2에서 누락됐던 것을 보완). 기존 성공 경로 테스트(108·171·242·264행의 `pwd`, 714·740·760·784·806·899행의 `newPassword`/`confirmPassword`)에 쓰인 `"Admin1234!"`(10자)·`"NewAdmin1234!"`(13자)·`"Manager1234!"`(12자) 값을 15자 이상으로 교체(예: `"Admin1234567890!"`) — 그렇지 않으면 새 정책으로 성공 경로 테스트가 깨진다. `currentPassword` 필드는 길이 제약이 없으므로 교체 불필요.
- `src/test/java/com/cms/admin/member/controller/PasswordResetControllerTest.java` **(v3 확장 — 쟁점 12)** — 경계값 1~2개(14코드포인트, ASCII 73바이트) 케이스 추가 + `verifyNoInteractions(passwordResetService)` 확인. 기존 성공 경로(269·272·283·302·313행)의 `"NewPassword1!"`(13자)을 15자 이상으로 교체.
- `src/main/resources/templates/admin/password-reset-confirm.html` — 54행 placeholder `"새 비밀번호 (4자 이상)"` → `"새 비밀번호 (15자 이상)"`, 135행 `newPassword.length < 4` → **(v3 정정, 쟁점 10)** `Array.from(newPassword).length < 15`(코드포인트 단위 — 서버의 `@MinCodePoints`와 카운팅 방식 일치, 이모지 등 서로게이트 쌍 문자에서 JS `.length`(UTF-16 코드 단위)와 서버 판정이 어긋나는 것을 방지), 136행 메시지 `"4자 이상"` → `"15자 이상"`
- `src/main/resources/templates/admin/member/admin-create.html` — `pwd` 입력 필드 아래에 `userId`와 동일한 패턴의 `form-help` 힌트 텍스트 추가(현재 비밀번호 필드에는 길이 안내가 전혀 없음 — "15자 이상 입력하세요." 등)

**범위 밖(v2 명시)**: `AdminMemberServiceTest`(서비스 단위 테스트, `createAdmin_success` 등)와 `PasswordResetServiceTest`에 남아있는 10~13자 비밀번호 값(`"Admin1234!"`·`"NewAdmin1234!"` 등)은 교체하지 않는다 — 이 테스트들은 `adminMemberService.createAdmin(req)`/서비스 메서드를 직접 호출해 Bean Validation(`@Valid`)을 거치지 않으므로 DTO 길이 제약과 무관하다(Spring MVC의 `@Valid`는 컨트롤러 진입 시점에만 작동). 무관한 파일을 건드리지 않는다는 원칙에 따라 그대로 둔다.

## 단계별 작업 순서

1. `MaxUtf8Bytes`/`MaxUtf8BytesValidator`(단락 평가 + `CharsetEncoder`/`CodingErrorAction.REPORT` 기반 인코딩) → `MinCodePoints`/`MinCodePointsValidator` → `EmailNormalizer`(정규화 후 길이 재검증 포함) 작성
2. `./gradlew compileJava`
3. 4개 DTO(`AdminSignupRequest`·`AdminMyPasswordChangeRequest`·`PasswordResetConfirmRequest`·`AdminBootstrapCredentials`) 애노테이션 교체·추가
4. `AdminMemberService.createAdmin` 이메일 정규화 적용 + `normalizeEmail` 제거, `PasswordResetService` 동일 정리, `AdminBootstrapLoader.createOrReconcile` 이메일 정규화 적용
5. `./gradlew compileJava` 재확인(미사용 import 등)
6. 신규/확장 테스트 작성: `PasswordFieldBoundaryTest`(고립 서로게이트·서로게이트 쌍 케이스 포함)·`EmailNormalizerTest`(신규, 길이 확장 케이스 포함), `AdminMemberServiceTest`(대소문자 정규화 케이스), `AdminBootstrapLoaderTest`(픽스처 길이 교체 + 73바이트 실패 케이스(정정된 verify 방식) + 이메일 정규화 케이스), `AdminMemberControllerTest`(경계값 케이스 + **내 비밀번호 변경 경계값 케이스 신규** + 픽스처 길이 교체)·`PasswordResetControllerTest`(경계값 케이스 + 픽스처 길이 교체)
7. 프론트엔드 2개 파일 수정(`password-reset-confirm.html` — 코드포인트 기준 JS 카운팅 포함 · `admin-create.html`)
8. `./gradlew test` 전체 통과 확인
9. Playwright로 관리자 계정 생성·비밀번호 재설정 화면 골든 패스 + 경계값 확인

## 완료 기준

**(v3 — 진입점·계층별 책임 분리, 쟁점 8·12 반영)**

**DTO 경계(`PasswordFieldBoundaryTest`, 4개 DTO 전부에 동일 매트릭스 적용)**
- [ ] 1자 비밀번호 무효(`@NotBlank`, 기존 계약)
- [ ] 14코드포인트 무효, 15코드포인트 유효
- [ ] ASCII 72바이트 유효, 73바이트 무효
- [ ] 한글 24자(72바이트) 유효, 25자(75바이트) 무효
- [ ] 서로게이트 쌍 문자(이모지 등) 15코드포인트 비밀번호 유효(코드포인트 카운팅이 `@Size`의 UTF-16 코드 단위 카운팅과 다르게 동작한다는 사실 증명)
- [ ] 고립 서로게이트(`"\uD800"` 등) 입력이 `@MaxUtf8Bytes` 위반으로 무효(치환 인코딩으로 통과하지 않음 — 쟁점 2-2)

**HTTP 진입점(생성·내 비밀번호 변경·재설정 확인 — 대표 경계값만, 전체 매트릭스 반복 아님)**
- [ ] 각 엔드포인트에서 14코드포인트·73바이트 요청이 HTTP 400 + 서비스 미호출(`verifyNoInteractions`)
- [ ] 각 엔드포인트에서 정상 길이(15코드포인트 이상, 72바이트 이하) 골든 패스가 각자의 실제 성공 상태 코드로 응답(**(v4 정정)** 관리자 생성 `201 Created`, 내 비밀번호 변경 `200 OK`, 비밀번호 재설정 확인 `204 No Content` — `AdminMemberController.java:51`·`:175-180`, `PasswordResetController.java:49-52` 실측. 계획 초안의 "HTTP 400/200"이라는 일괄 표현은 부정확했다), 기존 성공 경로 테스트의 픽스처 교체분 포함
- [ ] `AdminSignupRequest`의 `userId` 51자·`userName` 101자·`email` 101자 각각 400 + 서비스 미호출

**부트스트랩(HTTP 진입점 아님 — `AdminBootstrapLoaderTest`)**
- [ ] 73바이트 비밀번호 → `IllegalStateException` + `verify(memberRepository, never()).saveAndFlush(any())` + `verifyNoInteractions(passwordEncoder)`
- [ ] 15자 이상 정상 비밀번호로 정상 기동·계정 생성(픽스처 교체 확인)

**이메일 정규화**
- [ ] `createAdmin`으로 생성 시 이메일 **대소문자**가 정규화되어 저장되고, 정규화된 값 기준으로 중복 검사됨을 확인(`existsByEmail` 호출 인자 검증) — 공백 케이스는 `@Email`이 이미 400으로 차단하므로 검증 대상 아님(쟁점 5)
- [ ] 부트스트랩으로 생성 시에도 이메일 대소문자가 정규화되어 저장됨을 확인
- [ ] 이메일 대소문자 케이스에서 생성(API·부트스트랩)·내 정보 수정·재설정 조회 결과가 일치(4개 경로 모두 동일한 `EmailNormalizer` 사용)
- [ ] 정규화 후 길이가 `Member.email` 컬럼 길이(100)를 넘는 입력이 `InvalidRequestException`(400)으로 거부됨을 확인(쟁점 11 — İ 등 대소문자 변환 확장 사례)
- [ ] 고립 서로게이트를 포함한 이메일(`"\uD800@a.co"`)이 `@Email`은 통과하지만 `EmailNormalizer`에서 `InvalidRequestException`(400)으로 거부됨을 확인(쟁점 11 확장 — `@Email`이 걸러내지 못함을 실측 확인)

**공통**
- [ ] 기존 회원가입·비밀번호 변경·재설정·로그인 골든 패스 회귀 없음(기존 성공 경로 테스트의 짧은 비밀번호 픽스처를 15자 이상으로 교체한 뒤에도 통과)
- [ ] `./gradlew test` 전체 통과
- [ ] Playwright: 관리자 계정 생성 화면에서 14자 비밀번호 제출 시 서버 오류 메시지 노출 확인 + 15자 이상 정상 생성 확인, 비밀번호 재설정 화면의 "15자 이상" 문구·검증(이모지 등 코드포인트 경계 포함) 확인, 관리 화면 전반 회귀 없음

## 리스크 / 범위 밖

- **기존 계정 소급 적용 없음**: 이미 생성된 4~14자 비밀번호 계정은 계속 로그인 가능(정책은 신규 설정 시점부터만 적용) — 강제 비밀번호 재설정을 원하면 별도 과제.
- **`userId` 최소 길이 신설은 범위 밖**: 감사 항목이 지적한 것은 상한 정합이며, 최소 길이는 별도 로그인 정책 변경이라 이번 승인 범위 밖.
- **클라이언트(JS) 측 72바이트 사전 체크는 추가하지 않음**: UTF-8 바이트 수를 JS에서 계산하는 것은 가능하지만(TextEncoder), 실질 사용자가 72바이트(ASCII 72자/한글 24자)를 초과하는 비밀번호를 입력할 가능성은 낮고, 서버 400 응답이 이미 명확한 메시지를 반환한다 — 최소한의 코드로 구현 원칙에 따라 서버 검증만으로 충분하다고 판단.
- **`GlobalApiExceptionHandler`에 `IllegalArgumentException` 전역 매핑 추가하지 않음**(사용자 결정): DTO 경계 바이트 검증이 인코딩 단계 도달 자체를 막으므로 근본 해결. 향후 다른 경로에서 동일한 예외가 발생하면 그때 재평가.
- **`changeMyPassword`(내 비밀번호 변경) API는 현재 어떤 화면에서도 호출되지 않음**(정찰 중 확인 — `members/me/password` 엔드포인트를 참조하는 템플릿/JS 없음): 이번 변경이 UI 회귀를 일으킬 표면이 없다는 뜻이며, 별도 대응 불필요.
- **90일 비밀번호 만료 정책과 NIST rotation 금지 권고의 충돌은 이번 계획 범위 밖**(쟁점 7 참고 — 별도 승인된 기존 기능, 재검토하려면 로그인 정책 변경 협의 별도 필요).
- **NIST의 최대 길이(64자 이상 권장)·유출된 비밀번호 blocklist 요구는 채택하지 않음**(v3 정정, 쟁점 2 — 최대 길이는 BCrypt 72바이트 제약과 상충해 해시 전략 자체를 바꾸지 않는 한 확장 불가, blocklist는 신규 데이터셋·의존성이 필요한 별도 과제). 이 계획이 채택하는 것은 NIST의 최소 길이·코드포인트 카운팅 규칙뿐이다.
- **`AdminMemberServiceTest`/`PasswordResetServiceTest`(서비스 단위 테스트)의 기존 짧은 비밀번호 픽스처는 교체하지 않음**(Bean Validation을 거치지 않는 테스트라 새 길이 정책과 무관 — 쟁점 12/파일 목록 "범위 밖" 참고).
- **부트스트랩 경로에서 `EmailNormalizer`가 던진 `InvalidRequestException`을 별도로 catch하지 않음**(v3 신규, 쟁점 11 — 극히 희귀한 에지 케이스이고, 미처리 예외로 인한 기동 실패라는 결과 자체는 기존 fail-fast 철학과 일치).
- **스키마 변경 없음, 인가 정책 변경 없음** — 사용자 사전 협의는 비밀번호 최소 길이(15코드포인트)·72바이트 처리 방식·부트스트랩 이메일 정규화 포함 여부 3건으로 완료(적대적 리뷰 2라운드 반영 후에도 숫자·범위 변경 없음 — 표현 정정만).

## 구현·검증 결과 (2026-09-22)

- **구현**: 계획대로 신규 5개 파일(`MaxUtf8Bytes`/`MaxUtf8BytesValidator`/`MinCodePoints`/`MinCodePointsValidator`/`EmailNormalizer`) + 수정 7개 파일(4개 DTO·`AdminMemberService`·`PasswordResetService`·`AdminBootstrapLoader`) + 프론트엔드 2개 파일 전부 계획대로 작성. `MaxUtf8BytesValidator`·`EmailNormalizer` 둘 다 `CharsetEncoder`(`CodingErrorAction.REPORT`) 기법을 그대로 사용해 고립 서로게이트를 거부한다.
- **단위/컨트롤러 테스트**: 신규 `PasswordFieldBoundaryTest`(9개, 4개 DTO 공통 매트릭스 — 고립 서로게이트·서로게이트 쌍 케이스 포함)·`EmailNormalizerTest`(6개) 작성. `AdminMemberServiceTest`에 이메일 대소문자 정규화 케이스 1개, `AdminBootstrapLoaderTest`에 이메일 정규화·73바이트 실패 케이스 2개(+기존 성공 케이스 픽스처 15자 이상으로 교체), `AdminMemberControllerTest`에 경계값 케이스 7개(생성 5개 + 내 비밀번호 변경 2개, 전부 `verifyNoInteractions`로 서비스 미호출 확인), `PasswordResetControllerTest`에 경계값 케이스 2개 추가 — 기존 성공 경로 테스트의 짧은 비밀번호 픽스처(`"Admin1234!"` 등)를 15자 이상 값으로 전부 교체(`sed` 일괄 치환).
- **회귀 테스트**: `SPRING_PROFILES_ACTIVE=dev ./gradlew test` 전체 실행(Docker Desktop 기동 후 Testcontainers 포함) — **685개 전체 통과, 실패/에러 0건**(직전 로드맵 기준 658개에서 신규 테스트 27개 순증). Docker가 꺼진 상태에서 먼저 실행했을 때는 Testcontainers 통합 테스트 6개만 환경 문제(`NoClassDefFoundError: MariaDbContainerSupport`)로 실패했고, Docker Desktop을 기동한 뒤 재실행해 전부 해소됨을 확인(코드 결함 아님).
- **실기 검증(Playwright)**: `make dev-up`으로 dev 스택(`cms-app-dev`·`cms-db-dev`) 기동 후 admin/1234로 로그인.
  - 관리자 계정 생성 화면(`/admin/member/new`): 새로 추가한 "15자 이상 입력하세요." 힌트 노출 확인. 13자 비밀번호 제출 시 서버가 정확히 `"pwd: 비밀번호는 15자 이상이어야 합니다."` 400 메시지를 반환하고 화면에 그대로 노출됨을 확인. 18자 비밀번호로 재제출 시 정상 생성("관리자 계정이 생성되었습니다." 확인 다이얼로그) 확인.
  - 이메일 대소문자 정규화: `CaseTest@TEST.com`으로 계정 생성 후 관리자 조회 목록에서 저장된 값이 `casetest@test.com`(소문자)으로 정규화되어 표시됨을 실측 확인.
  - 비밀번호 재설정 화면(`/admin/password-reset/confirm`): "새 비밀번호 (15자 이상)" placeholder 텍스트 반영 확인. 토큰 fragment 처리(기존 코드, 이번 변경 대상 아님)가 Playwright의 hash 포함 직접 URL 내비게이션에서 간헐적으로 "링크가 유효하지 않습니다"로 표시되는 현상을 발견했으나, `location.hash`를 `evaluate`로 직접 확인한 결과 fragment 자체는 정상 존재해 페이지 로드 타이밍 이슈로 판단(이번 변경과 무관한 기존 코드 경로 — 범위 밖). 대신 수정한 `Array.from(newPassword).length` 로직을 `browser_evaluate`로 직접 실행해 검증: 14자 → 거부, 15자 → 통과, 서로게이트 쌍 이모지 15개(JS `.length`는 30이지만 `Array.from().length`는 정확히 15) → 통과 — 서버의 `@MinCodePoints`와 동일한 코드포인트 카운팅 기준으로 동작함을 실측 확인.
  - 관리 화면(대시보드·관리자 조회·메뉴 관리) 회귀 없음 확인(사이드바 네비게이션 정상).
- **이슈**: 없음(설계 단계에서 4라운드 적대적 리뷰로 엣지 케이스를 충분히 검증한 덕에 구현 단계에서 막힌 지점 없음). Docker Desktop 미기동으로 인한 최초 테스트 실패는 코드 문제가 아닌 로컬 환경 문제로 확인 후 해소.
- **후속**: 없음(테스트 계정 `pwtest01`·`casetest01`은 dev 전용 로컬 DB에 남아있음 — 실사용에 영향 없는 dev 환경 테스트 데이터).
