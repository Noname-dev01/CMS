# PROJECT TECHNICAL AUDIT

감사일: 2026-09-22 · 기준 커밋: `f89019556a3eff0fe500586255793aae0fff3a85`

판정: **no-ship — 현재 상태의 외부 운영 배포는 보류**.

이 보고서는 기능 구현 계획이 아니라 현재 코드의 기술 감사다. `deploy-check` 절차에 따라 소스·설정·테스트·마이그레이션은 수정하지 않았다. 감사 보고서만 생성했다. 기존 로드맵의 작업 중 변경과 기존 미추적 파일은 보존했다. 실제 운영 서버, 운영 자격 증명, 운영 DB에는 접근하지 않았다.

감사 도중 반영된 `f890195`의 비밀번호 정책·이메일 정규화 수정도 다시 검토했다. 이전 기준선 `002f7c3`에서 발견한 비밀번호 검증 불일치는 **현재 결함으로 재보고하지 않는다**. 아래 항목 ID는 이 보고서 내부 ID이며 과거 감사·로드맵의 같은 번호와 동일한 항목을 뜻하지 않는다.

## 1. Executive Summary

현재 프로젝트는 **소규모 관리자 CMS에 적합한 기능별 모놀리스**다. Spring MVC/Thymeleaf, 세션 인증, JPA/QueryDSL, MariaDB, 로컬 파일 볼륨의 조합은 목적에 맞는다. 이를 마이크로서비스나 SPA로 다시 만들 이유는 발견하지 못했다.

인가 경계, 계정 상태 변경 후 세션 폐기, 최후 활성 ADMIN 보호, 공개 공지 전용 조회, 파일 트랜잭션 보상, Flyway, 실제 MariaDB 동시성 테스트 등 중요한 기반이 이미 있다. 최신 기준 전체 테스트도 성공했다. 단순 CRUD 데모 이상의 안정화 작업이 축적되어 있다.

그러나 다음 두 항목은 배포 전에 해결할 가치가 있다.

| ID | 심각도 / 분류 | 핵심 위험 |
|---|---|---|
| C-01 | Critical / Security Issue | MANAGER가 수정하는 이름이 ADMIN 상세 모달에서 HTML로 실행될 수 있어 ADMIN 권한으로 요청을 실행할 수 있다. |
| H-01 | High / Operational Risk · Architecture Issue | 모든 ADMIN이 잠기거나 비밀번호 만료 상태인 동안 재시작하면 앱 전체가 기동 실패하여 공개 서비스와 비밀번호 복구까지 중단된다. |

그 밖의 주요 개선은 백업 시점 정합성, SMTP 시간 제한, 오류 분류·로그, 메뉴 수정 동시성, 프록시 IP 신뢰 경계, 시간대 일관성이다. 이는 모두 새로운 플랫폼 도입 없이 개선할 수 있다.

운영 구성은 실제로 존재한다. 다만 현재 prod compose는 루프백 바인딩의 운영 후보 검증 구성이며, 저장소만으로 인터넷 배포의 TLS·프록시·백업 원격 보관·장애 통보가 완료되었다고 볼 수 없다. 이 공백은 이미 존재하는 prod 구성을 부정하는 것이 아니라 실제 배포 시 채워야 하는 마지막 운영 계약이다.

**집중 방향: 기능 확대보다 권한 경계의 브라우저 검증, 정상적인 재기동·복구, 장애를 알아낼 수 있는 최소 운영 체계를 먼저 완성한다.**

## 2. Project Architecture Summary

### 구성

- `admin/member`: 관리자 생성·수정, 자기 정보, 프로필 이미지, 비밀번호 변경·재설정, 초기 관리자 생성.
- `admin/menu`: DB 메뉴 트리와 역할별 사이드바. 메뉴 표시 정책과 실제 URL 인가는 별개다.
- `admin/notice`: 공지 CRUD, 소프트 삭제, 첨부 관리.
- `admin/log`: 관리자 작업 로그와 방문 로그 조회·통계.
- `publicweb/notice`: 공개 조건을 강제하는 별도 공지 조회·다운로드 경로.
- `config/auth`, `config/security`, `config/ratelimit`: 인증 상태 전이, 세션 폐기, 보안 응답, 공개 경로의 로컬 토큰 버킷.
- `common/api`, `common/exception`, `common/storage`: 오류 계약, 예외, 파일 저장 및 트랜잭션 보상.

### 주요 실행 흐름

```text
브라우저
  → Spring Security: 세션·CSRF·URL 인가
  → 공개 경로 rate limit (CSRF 다음, 컨트롤러 이전)
  → Controller / DTO 검증 / 메서드 인가
  → Service / 트랜잭션 / 필요한 행 잠금
  → Repository / MariaDB
       ├─ 변경 성공 이후 세션 폐기 이벤트
       ├─ 파일 저장 + 롤백 시 새 파일 정리 / 커밋 후 옛 파일 삭제
       └─ 감사 로그 (독립 트랜잭션; 성공의 커밋 의미는 추가 검증 필요)

비밀번호 재설정: 공개 요청 → 토큰 해시 저장 → 트랜잭션 밖 비동기 메일
공개 다운로드: 공개·미삭제 공지 확인 → 소속 첨부 확인 → 파일 로딩 → 강제 다운로드
```

`SecurityConfig`의 MANAGER 예외 경로가 `/admin/**` ADMIN 규칙보다 앞에 있다. 자기 정보 API는 세션의 사용자 ID를 사용하므로 임의 대상 ID를 받아 수정하지 않는다. 공개 공지는 관리자 조회 서비스를 그대로 재사용하지 않고 공개 조건을 고정한 별도 서비스를 사용한다.

계층은 전반적으로 명확하다. 일부 서비스가 응답 DTO 생성, 저장소 조정, 이벤트 발행을 함께 담당하지만 현재 기능 규모에서는 이해 가능한 수준이다. 별도 use-case 계층이나 범용 domain framework를 추가할 이유는 약하다. 실제 순환 의존으로 기동이 실패하는 증거는 없으며 최신 전체 테스트의 컨텍스트 기동도 성공했다.

## 3. What Is Already Good

1. **역할과 자기 정보 경계가 서버에 있다.** 버튼 숨김에만 의존하지 않는다. ADMIN/MANAGER allowlist와 `@PreAuthorize`, 자기 ID 도출, 수정 DTO의 제한된 필드가 직접적인 IDOR·mass assignment를 줄인다.
2. **세션 무효화가 비즈니스 변경과 연결되어 있다.** 권한·상태·비밀번호 변경, 자동 잠금 후 세션 폐기를 처리하고 로그인 성공 직전 fresh 상태·역할·비밀번호를 재확인한다. 단일 인스턴스에서 타당한 설계다.
3. **재설정 토큰 취급이 신중하다.** 원문 대신 해시 저장, 유효기간, 계정별 발급 간격, 조건부 정리, 단회 사용을 위한 잠금이 있다. URL fragment 사용도 토큰이 일반 요청 경로에 남는 범위를 줄인다.
4. **최신 비밀번호 수정은 실제로 구현되어 있다.** 생성·자기 변경·재설정·부트스트랩 네 경로가 15 코드포인트 최소 길이와 UTF-8 72바이트 상한을 공유한다. 잘못된 UTF-16 거부와 이메일 `Locale.ROOT` 정규화도 구현·경계 테스트가 있다. 기존 로그인에 새 설정 정책을 무작정 적용하지 않은 점도 적절하다.
5. **동시성 제약을 실제 쓰기 경로에서 다룬다.** 최후 활성 ADMIN 보호, 회원 자기 수정 행 잠금, 공지별 첨부 개수 제한과 삭제 직렬화 등은 유지할 가치가 있다. `@DynamicUpdate` 하나만으로 정합성을 해결했다고 간주하지 않는다.
6. **공개 조회가 안전한 기본값을 가진다.** 공개·미삭제 조건, 첨부의 공지 소속, 공개 DTO에서 내부 식별 정보 제외, 없는 자원과 비공개 자원의 같은 404 처리가 좋다.
7. **로컬 파일 저장이 무방비하지 않다.** 원본 파일명을 실제 저장 경로로 사용하지 않고 생성 키를 쓰며, 경로 제한·`CREATE_NEW`·프로필 namespace가 있다. 파일 변경의 롤백/커밋 보상도 구현했다.
8. **프로필 이미지와 일반 첨부의 위협 모델이 구분되어 있다.** 인라인 표시하는 이미지는 ImageIO로 포맷·크기·픽셀 수 등을 검사하고, 일반 첨부는 강제 다운로드·octet-stream·nosniff를 적용한다.
9. **Flyway와 `ddl-auto: validate`가 실제 설정이다.** 기본값으로 운영 스키마를 자동 변경하지 않고 baseline도 상시 활성화하지 않는다. 신규 DB의 V1~V11 적용은 이번 테스트에서 확인했다.
10. **운영 실수 방어가 실용적이다.** dev/prod 프로젝트·볼륨 분리, prod DB 비공개, app 루프백 바인딩, 고정 UID 10001의 비루트 실행, 환경변수 가드, 복구 전 확인·안전 백업은 유지해야 한다.
11. **테스트가 위험 중심이다.** MockMvc 인가/CSRF, 실제 MariaDB 잠금·토큰·마이그레이션, 저장소 실패/보상, 프로파일 검증, rate limiter 시간·동시성 테스트가 있다.

이 기반을 추상적으로 더 정교해 보이는 구조로 전면 교체하면, 이미 확보한 보안·동시성 계약을 다시 증명해야 하는 비용이 더 크다.

## 4. Critical Issues

### C-01 — 관리자 상세 모달의 저장형 XSS가 MANAGER → ADMIN 권한 경계를 넘는다

**분류:** Security Issue · Bug. **검증 수준:** 입력 경로 정적 추적 + 실제 렌더링 함수의 격리 실행. 실제 사용자 계정이나 운영 브라우저에서 공격을 실행하지 않았다.

**Problem**

이름을 일반 문자열이 아닌 HTML 조각으로 삽입한다. 공격자가 자기 이름을 수정할 수 있는 MANAGER이고, 실행 피해자가 ADMIN이라는 점에서 단순 자기 XSS가 아니다.

**Evidence**

- [admin-manage.html](../src/main/resources/templates/admin/member/admin-manage.html) `renderDetail()`, 635~636행: `data.userId`, `data.userName`을 이스케이프 없이 `detailItems.value`에 넣는다.
- 같은 파일 651~654행: `detailContent.innerHTML`에 `item.value`를 그대로 연결한다. 상단 요약의 `escapeHtml(displayName)`은 이 별도 sink를 보호하지 않는다.
- [AdminMemberController.java](../src/main/java/com/cms/admin/member/controller/AdminMemberController.java) 116~120행: `PATCH /admin/api/members/me`를 MANAGER에도 허용한다.
- [AdminMyInfoUpdateRequest.java](../src/main/java/com/cms/admin/member/dto/request/AdminMyInfoUpdateRequest.java) 21~24행: 이름은 `@NotBlank`, `@Size(max=100)`이며 HTML 문자열도 유효하다. `updateMyInfo()`는 trim 후 저장한다.
- [SecurityConfig.java](../src/main/java/com/cms/config/SecurityConfig.java)의 CSRF 유지와 ADMIN 인가는 정상이다. 이것들은 이미 ADMIN 브라우저에서 실행되는 같은 출처 스크립트를 막지 못한다. 저장소의 보안 설정에 이 실행을 막는 CSP는 없다.

**Trigger**

1. MANAGER가 자기 이름에 100자 이내 HTML 이벤트 속성이 있는 문자열을 저장한다.
2. ADMIN이 관리자 관리 화면에서 해당 계정의 상세를 연다.
3. 상세 모달의 `innerHTML` 삽입으로 이벤트 처리기가 실행된다.

격리 검증에는 `<img src=x onerror=alert(1)>`를 사용했다. 현재 파일에서 `renderDetail()`을 직접 추출하여 DOM stub에 실행한 결과 `SUMMARY_ESCAPED=true`, `DETAIL_RAW_EVENT_HANDLER=true`였다. 문자열의 DTO 허용도 확인했다. 이는 위험 sink의 재현이며 실제 브라우저에서의 계정 승격 E2E까지 수행했다는 뜻은 아니다.

**Impact**

ADMIN의 세션으로 API를 호출할 수 있다. 페이지의 CSRF 메타 값을 읽어 공격자 계정의 역할 변경 또는 새 ADMIN 생성을 요청할 수 있으므로, 이름 수정 권한이 계정 관리 권한으로 확대된다. HttpOnly 쿠키여도 쿠키 값을 훔칠 필요 없이 브라우저가 요청에 실어 보낸다.

**Likelihood**

외부 무인증 공격은 아니며 악성·탈취된 MANAGER 계정과 ADMIN의 상세 조회가 필요하다. 그러나 둘 다 현재 제품의 정상 기능이므로 현실적인 권한 상승 경로다. 모든 관리자가 완전히 동일하게 신뢰받는 설치에서는 가능성이 낮아지지만, 코드가 ADMIN과 MANAGER를 구분하는 목적과 충돌한다.

**Current Mitigation**

서버의 역할 제한, CSRF, 다른 화면의 escaping, 이름 길이 제한이 있다. 이 sink와 피해 ADMIN의 same-origin 실행에는 충분하지 않다.

**Recommendation / 대안 비교**

| 대안 | 범위·복잡도 | 효과·주의점 | 의존성 / migration / 운영 영향 |
|---|---|---|---|
| 이름·아이디 등 텍스트 필드를 DOM `textContent`로 렌더링 | 작음, DOM 구성 일부 변경 | 가장 명확한 텍스트/마크업 분리. 이메일 버튼 등 의도된 마크업은 별도 구성 | 없음 / 없음 / UI 회귀 확인 |
| 현재 문자열 방식에서 해당 값을 `escapeHtml()` 처리 | 매우 작음 | 즉시 위험 차단에 적합. 같은 형태의 새 sink가 생기지 않도록 테스트 필요 | 없음 / 없음 / 거의 없음 |
| 서버 HTML 금지 또는 sanitizer 전면 도입 | 더 넓음 | 다른 sink의 올바른 출력 인코딩을 대체하지 못함. 정상 이름 제한·정책 복잡성 증가 | 도입 방식에 따라 필요 / 대체로 없음 / 입력 호환성 위험 |

**균형 잡힌 선택:** 우선 모든 같은 유형의 텍스트 sink를 좁게 수정하고, 핵심 모달부터 텍스트/마크업을 분리한다. 현재 평문 이름에 sanitizer 라이브러리는 필요 없다. MANAGER 이름 저장 → ADMIN 목록/상세 표시에서 이벤트가 실행되지 않는 브라우저 회귀 테스트를 추가한다. CSP는 이후 방어층이지 이 버그의 대체 수정이 아니다.

## 5. High Priority Issues

### H-01 — 일시적인 ADMIN 상태를 앱 전체의 기동 조건으로 사용한다

**분류:** Operational Risk · Architecture Issue. **검증 수준:** 현재 코드의 결정적 분기와 인증 상태 전이 추적. 운영 재시작은 실행하지 않았다.

**Problem / Evidence**

[AdminBootstrapLoader.java](../src/main/java/com/cms/admin/member/AdminBootstrapLoader.java) `run()`, 59행은 `existsByUserTypeAndStatus(ROLE_ADMIN, ACTIVE)`만 정상 기동 조건으로 인정한다. 없으면 63행 이후 부트스트랩 환경변수를 요구하고, 없거나 유효하지 않으면 `CommandLineRunner`가 실패한다. 부트스트랩 성공 후 세 변수를 삭제하라는 [deployment.md](../docs/deployment.md) 41행의 운영 절차와 결합한다.

`LoginFailureService`는 실패 누적으로 `LOCKED`를 저장하고, `PasswordExpiryService`는 만료 시 `PASSWORD_EXPIRED`를 저장한다. 자동 잠금 해제는 로그인·재설정 경로의 lazy 처리이지, 부트스트랩 직전 실행되는 복구가 아니다. 최후 ADMIN 보호는 관리자의 수동 강등·상태 변경을 보호해도 이 자동 상태 전이를 막지 않는다.

**Trigger**

단일 ADMIN 설치 또는 모든 ADMIN이 LOCKED/PASSWORD_EXPIRED인 상태에서 재배포·호스트 재부팅·프로세스 재시작이 발생한다. 부트스트랩 환경변수가 제거되어 있으면 기동 실패한다. 기존 계정과 같은 부트스트랩 ID를 계속 보관하는 것도 해결책이 아니다. 중복 저장 후 재조회가 인정하는 상태 역시 ADMIN+ACTIVE이기 때문이다.

**Impact**

관리자 로그인만이 아니라 공개 공지와 비밀번호 재설정까지 중단된다. 잠금 시간이 지나도 앱이 올라오지 않아 lazy 해제 경로를 실행할 수 없다. 결국 운영자 SQL 개입이나 새 부트스트랩 계정 등의 별도 복구가 필요해진다.

**Likelihood**

소규모 CMS의 ADMIN 1명 운영, 일시 잠금, 재배포는 현실적인 조합이다. 다만 원격 공격자가 로그인 실패만으로 즉시 앱 전체를 종료시키는 것은 아니며 **재시작 조건이 추가로 필요**하므로 Critical로 분류하지 않는다.

**Current Mitigation**

다른 ACTIVE ADMIN이 있으면 정상이다. 잠금 해제·재설정 기능과 수동 복구 문서도 있다. 그러나 앱이 기동하지 않는 동안 자체 복구 경로를 이용할 수 없다.

**Recommendation / 대안 비교**

| 대안 | 범위·복잡도 | 위험 감소·유지보수 | 의존성 / migration / 검증 |
|---|---|---|---|
| 최초 설치와 기존 설치의 일시 계정 상태를 구분 | 부트스트랩 조건과 테스트 변경, 작음~중간 | 기존 계정의 LOCKED/EXPIRED를 그대로 존중하면서 앱·복구 기능을 기동. 새 관리자 자동 생성도 피함 | 없음 / 필수 아님 / 빈 DB·USER만 있음·잠금·만료·삭제 관리자 경계 |
| 부트스트랩을 명시적 1회 운영 명령으로 분리 | 실행 방식·배포 문서 변경, 중간 | 기동과 계정 생성의 결합을 가장 명확히 제거. 최초 운영 절차가 하나 늘어남 | 새 라이브러리 불필요 / 필수 아님 / 명령 재실행·실패·초기 설치 |
| 현재 정책 유지 + 관리자 추가·비상 SQL 절차 | 코드 변경 적음 | 빈도만 낮추고 전체 장애 경로는 남김 | 없음 / 없음 / 정기 복구 훈련 필요 |

**균형 잡힌 선택:** 첫 대안으로 좁게 바꾼다. 단순히 `member count > 0`이면 무조건 통과시키지 말고, 최초 관리자 미구성·기존 관리자 일시 잠금·명시적 폐기 상태를 구분해야 한다. 재시작 시 잠금을 자동 해제하거나 DISABLED 계정을 부활시키는 방식은 권장하지 않는다. 부트스트랩 조건은 의도된 기존 결정이지만 현재 복구 기능과의 상호작용 때문에 재검토 가치가 있다.

## 6. Medium Priority Issues

### M-01 — 기본 온라인 백업은 DB와 파일의 동일 시점을 보장하지 않는다

- **분류 / Problem:** Operational Risk. 정상 완료·checksum 통과 백업도 일부 첨부를 복구하지 못할 수 있다.
- **Evidence:** [prod-backup.sh](../scripts/prod-backup.sh) 101행 DB `--single-transaction` 덤프 이후 114행 파일 볼륨 tar. 앱 쓰기를 멈추지 않는다.
- **Trigger:** DB 스냅샷 이후 파일 tar 이전에 첨부 삭제 또는 프로필 교체가 커밋된다.
- **Impact:** 복구 DB는 옛 storage key를 참조하지만 파일은 백업에 없어 404가 된다. 반대 방향은 orphan 파일 잔존이다.
- **Likelihood:** 백업 중 변경이 있는 경우. 관리자 작업이 드물면 낮지만 자동 백업을 쓰기 시간과 겹치게 실행하면 실제 경로가 있다.
- **Current Mitigation:** 배포 문서가 이를 정확히 인정하고 앱만 정지한 백업 절차를 제공한다. 이 때문에 새로 발견한 무방비 데이터 손실처럼 High로 과장하지 않는다.
- **Recommendation:** 소규모 운영에서는 **정지 상태 백업을 정규 복구 기준**으로 선택하는 것이 가장 단순하다. 앱 정지/재개와 실패 통보의 책임을 명시한다. 무중단 snapshot·버전형 파일 보관은 다운타임이 실제 제약이 된 뒤 고려한다. 의존성·DB migration 없이 절차로 우선 개선할 수 있다. 검증은 checksum 외에 복원 후 DB 참조 파일 전수 존재·샘플 바이트 hash를 포함해야 한다.

### M-02 — SMTP 무응답 시 재설정 메일 작업이 장시간 점유·누적될 수 있다

- **분류 / Problem:** Operational Risk. 비동기화가 시간 제한이나 backpressure를 제공하지는 않는다.
- **Evidence:** [PasswordResetService.java](../src/main/java/com/cms/admin/member/service/PasswordResetService.java) `TaskExecutor` 주입과 193행 `mailSender.send()`. prod/dev SMTP 설정에 connection/read/write timeout이 없으며 메일 전용 bounded executor 설정도 없다. 로컬 Boot 설정 객체의 기본 queue capacity는 `2147483647`이었다.
- **Trigger:** SMTP 연결·응답·쓰기 단계가 멈추거나 장시간 지연된다.
- **Impact:** 메일 작업 스레드가 반환되지 않고 뒤 요청이 누적되며, 재설정 응답은 성공처럼 보여도 메일을 받지 못하거나 토큰 만료 후 받는다. 비밀번호 만료 계정의 복구성이 특히 나빠진다.
- **Likelihood:** 메일 서비스·네트워크 장애는 현실적이다. 소수 관리자 환경에서 곧바로 heap 고갈이 난다고 단정하지 않는다. 우선 영향은 복구 기능 정지다.
- **Current Mitigation:** IP rate limit, 계정별 발급 간격, send/dispatch 실패 처리와 토큰 조건부 정리가 있다. 무응답이 종료되지 않으면 예외 처리에 도달하지 못한다.
- **Recommendation:** 세 종류 SMTP timeout을 유한하게 설정하고, 필요하면 작은 메일 전용 bounded executor와 명시적 거절 처리를 둔다. 새 메시지 브로커는 필요 없다. timeout은 설정 변경, executor는 좁은 코드 변경이며 migration은 없다. 연결 정지·발송 예외·큐 포화·재발급과 옛 실패 정리 경합을 테스트한다.

Spring Boot도 일부 메일 timeout의 기본값이 무한임을 명시한다. [Spring Boot 3.5 메일 설정](https://docs.spring.io/spring-boot/3.5/reference/io/email.html), [작업 실행 설정](https://docs.spring.io/spring-boot/3.5/reference/features/task-execution-and-scheduling.html).

### M-03 — 일반 요청 오류가 500이 되고 예상 밖 API 장애의 로그가 없다

- **분류 / Problem:** Bug · Operational Risk.
- **Evidence:** [GlobalApiExceptionHandler.java](../src/main/java/com/cms/common/api/GlobalApiExceptionHandler.java) 366행 `handleException()`은 모든 나머지 예외를 `INTERNAL_ERROR` 500으로 변환하며 로그를 남기지 않는다. 타입 변환·지원하지 않는 메서드의 전용 처리가 없다.
- **Trigger / 검증:** 실제 컴파일된 advice와 독립적인 Long path-variable 컨트롤러를 연결한 MockMvc에서 잘못된 ID와 PUT 요청이 각각 `500 INTERNAL_ERROR`로 확인됐다. 전체 인증 체인 E2E가 아니라 MVC/advice 경계의 격리 검증이다.
- **Impact:** 클라이언트 실수를 장애로 분류하고 405의 계약도 잃는다. 별도 서비스 로그가 없는 예상 밖 조회·스토리지 예외는 운영자가 원인을 찾기 어렵다.
- **Likelihood:** 잘못된 경로 변수·클라이언트 메서드는 흔한 입력이다. 일반 500이 항상 무로그인 것은 아니지만 catch-all 자체에는 진단 기록이 없다.
- **Current Mitigation:** 검증 400, JSON 파싱 400, 인가 403, 없는 자원 404, 충돌 409 등 주요 명시적 예외는 처리한다. 클라이언트에 DB 상세를 노출하지 않는 점은 좋다.
- **Recommendation:** 프레임워크의 400/405/415 성격을 보존하고, 예상 밖 예외만 request path·오류 식별자와 함께 서버에 기록한다. 비밀번호·토큰·본문을 로그에 넣지 않는다. 작은 handler 추가가 우선이며 advice 전면 재설계는 불필요하다. API JSON과 HTML 화면의 응답 회귀 테스트를 같이 둔다.

### M-04 — 메뉴 일반 수정과 비활성화가 서로 다른 잠금 규칙을 사용한다

- **분류 / Problem:** Bug · Data Integrity risk.
- **Evidence:** [MenuService.java](../src/main/java/com/cms/admin/menu/service/MenuService.java) `updateMenu()`는 `useYn=false` 요청만 `findByIdForUpdate()`, 나머지는 `findById()`를 사용한다. 현재 [Menu.java](../src/main/java/com/cms/admin/menu/Menu.java)에 `@Version`·`@DynamicUpdate`가 없고 조회한 값으로 수정 필드를 채운다.
- **Trigger:** ADMIN A가 활성 메뉴의 이름 수정용 엔티티를 먼저 읽고, ADMIN B의 비활성화가 커밋된 뒤 A가 flush한다.
- **Impact:** A의 오래된 활성 값이 UPDATE에 포함되어 B의 비활성화가 되돌아가거나 다른 필드 변경이 소실될 수 있다.
- **Likelihood:** 같은 메뉴를 여러 관리자가 동시에 수정할 때. 현재 규모에서는 낮음~중간이며 권한 상승이나 대규모 데이터 손상으로 과장하지 않는다.
- **Current Mitigation:** 부모 비활성화와 자식 생성·재활성화 경합에는 행 잠금과 통합 테스트가 있다. 일반 필드 수정 경합은 이 계약 밖이다.
- **Recommendation:** 모든 같은 대상 쓰기에 동일한 행 잠금을 사용하는 좁은 수정이 우선이다. 부모/자식 잠금 순서를 함께 검토하고 실제 MariaDB latch 기반 테스트를 추가한다. 대안인 `@Version`은 충돌을 사용자에게 알려주는 장점이 있지만 컬럼 migration·409 UI 처리가 추가된다. 현재 규모에서는 공통 잠금이 더 작은 선택이다. 이 경합 자체를 이번 감사에서 DB로 별도 재현한 것은 아니다.

### M-05 — 프록시 도입 시 rate limit IP와 감사 IP의 신뢰 기준이 어긋난다

- **분류 / Problem:** Operational Risk · Security Issue(로그 신뢰성). 프록시 연동은 조건부 위험이다.
- **Evidence:** [RateLimitFilter.java](../src/main/java/com/cms/config/ratelimit/RateLimitFilter.java) 58행은 `getRemoteAddr()` 사용. [AdminActionLogAspect.java](../src/main/java/com/cms/admin/log/aspect/AdminActionLogAspect.java) 101~112행은 신뢰 프록시 확인 없이 X-Forwarded-For의 마지막 값 또는 X-Real-IP 사용. 저장소 prod 구성에는 실제 TLS ingress 및 trusted proxy 해석 계약이 없다.
- **Trigger:** 단일 reverse proxy 뒤에 앱을 배치하되 실제 IP 복원이 없거나, ingress가 전달 헤더를 정규화하지 않는다. 직접 접근 가능한 경로에서는 클라이언트가 로그용 헤더를 조작할 수 있다.
- **Impact:** 모든 이용자가 프록시 IP의 reset-request 버킷(5회/시간)을 공유할 수 있고 감사 IP도 위조될 수 있다. 비정상적으로 긴 감사 IP는 컬럼 길이와 충돌해 로그 저장 실패를 유발할 수 있다.
- **Likelihood:** 인터넷 배포에서 프록시 추가 시 반드시 확인할 통합 경계다. 현재 루프백 구성만으로 외부 공격이 이미 가능하다고 주장하지 않는다.
- **Current Mitigation:** limiter가 forwarded 헤더를 직접 믿지 않는 점은 현재 직접 접근 모델에서 오히려 안전하다. 감사 IP가 현재 권한 판단의 근거인 것도 아니다.
- **Recommendation:** ingress가 전달 헤더를 제거/재작성하고 앱은 **신뢰한 프록시만** 해석하도록 정한다. 감사와 limiter가 같은 해석 결과를 사용하며 로그 값은 타입·길이를 제한한다. 서로 다른 두 실제 IP, 위조 헤더, proxy 우회 차단을 배포 환경에서 검증한다. Redis나 distributed limiter는 이 문제의 해결책이 아니다.

### M-06 — KST Clock과 JVM 기본 시각이 혼용된다

- **분류 / Problem:** Technical Debt · Operational Risk.
- **Evidence:** [AppConfig.java](../src/main/java/com/cms/config/AppConfig.java)의 Clock은 `Asia/Seoul`. 반면 `Member` 일부 변경 메서드, `Menu`, `Notice`, `AdminActionLogService` 등에는 `LocalDateTime.now()`가 있다. Dockerfile·prod compose에는 JVM 기본 시간대를 KST로 고정하는 설정이 없다.
- **Trigger:** JVM 기본 시간대가 UTC 등 KST와 다른 배포 환경이다.
- **Impact:** 같은 사건의 생성·수정·감사 기록이 다른 기준으로 저장되어 시계열 추적과 날짜별 해석이 어긋날 수 있다. 비밀번호 만료 계산 전체가 이미 잘못됐다고 단정하지 않는다.
- **Likelihood:** 컨테이너 이전·호스트 변경 시 충분히 가능하다. 실제 운영 JVM/DB 시간대는 미확인이다.
- **Current Mitigation:** 인증 핵심과 일부 통계 경로는 주입된 Clock을 사용하고 테스트 가능하다.
- **Recommendation:** 단기에는 배포 시간대 계약을 명시·검증하고, 후속 변경에서 기존 Clock을 공통으로 사용한다. 새 시간 라이브러리는 필요 없다. 기존 데이터의 일괄 9시간 보정은 각 환경의 저장 기준을 확인하기 전에는 하지 않는다.

## 7. Low Priority / Optional Improvements

| 항목 | 분류 | 판단·가장 작은 개선 |
|---|---|---|
| 회원 정렬 allowlist와 구현 불일치 | Low / Bug | `MemberRepositoryImpl`의 허용 필드에 `userType`, `status`가 있으나 정렬 switch에서 처리하지 않아 기본 ID 정렬로 돌아간다. 지원한다고 노출할 필드만 유지하거나 해당 정렬을 구현한다. |
| 메뉴 깊이 계약 | Low / Architecture Issue | API/트리 조립은 다단계를 수용하지만 사이드바는 2단만 표시한다. 3단 생성 후 안 보이는 혼란을 피하도록 제품 요구가 2단이면 생성 제약·UI 안내를 맞춘다. 범용 트리 엔진은 불필요하다. |
| 순수 텍스트와 HTML 렌더링 유틸 중복 | Low / Technical Debt | C-01 수정은 필수지만, 이후 공통 escape/텍스트 DOM helper 정리는 작은 후속 작업이다. 프런트엔드 프레임워크 교체로 확대하지 않는다. |
| 정렬의 안정적인 보조 키 | Low / Optional Improvement | 이름·날짜가 같은 행의 페이지 경계가 흔들리면 ID 보조 정렬을 추가한다. 현재 관리자 수에서 대규모 pagination 재설계는 불필요하다. |
| CI 로그 과다 출력 | Low / Optional Improvement | `testLogging.showStandardStreams=true`로 정상 테스트의 SQL·예상 예외까지 대량 출력된다. 컨테이너 정보와 실패 로그는 남기되 정상 실행 로그량을 줄이면 실제 장애를 찾기 쉽다. 운영 자격 증명 노출을 이번 출력에서 발견했다는 뜻은 아니다. |

관찰 사항으로 감사 AOP의 커밋 의미, 로컬 파일 신뢰 경계, 바이트 배열 다운로드를 아래에서 다룬다. 모두 즉시 High로 승격할 근거는 없다.

## 8. Security Assessment

### 인증·인가·세션

서버 인가는 대체로 잘 구성되어 있다. `/admin/**`의 ADMIN 기본 규칙과 앞쪽 MANAGER allowlist, 메서드 보안이 함께 있다. ROLE_USER를 관리자 인증 대상으로 취급하지 않는 방어도 유지해야 한다. 메뉴가 숨겨진다고 접근이 차단되는 구조가 아니라 실제 URL 경계가 별도로 존재한다.

세션 레지스트리와 AFTER_COMMIT 폐기는 **단일 앱 인스턴스**라는 현재 전제에 맞는다. 프로세스 재시작에 따른 세션 소멸도 작은 관리자 CMS에서 합리적이다. 다중 인스턴스로 바꾸면서 현재 세션 폐기를 그대로 신뢰하는 것은 허용되지 않지만, 그 미래 문제 때문에 지금 Redis를 도입할 필요는 없다.

5회 로그인 실패 잠금과 30분 lazy 해제는 brute force를 줄인다. 반대로 알려진 관리자 ID에 실패를 누적해 잠그는 가용성 trade-off는 남는다. 로그인 POST는 현재 공개 공지/재설정용 limiter의 대상이 아니다. 외부 노출 시 proxy의 저비용 연결·로그인 시도 제어를 검토하되, account lock을 없애거나 전역 저한도로 정상 관리자를 막는 단순 대체는 피한다.

### 비밀번호와 복구

새 비밀번호 설정 정책은 최신 커밋에서 정합성이 개선됐다. BCrypt의 바이트 상한을 검증하고 DTO 경계 테스트를 갖춘 점을 유지한다. 이 구현이 특정 보안 표준 전체를 준수한다는 의미는 아니다. 기존 약한 비밀번호의 존재 여부는 DB를 읽지 않아 알 수 없으며, 저장된 해시만으로 원문 강도를 검증할 수도 없다.

재설정 토큰·발급 간격·잠금은 좋은 방어다. 현재 더 시급한 문제는 SMTP 장애의 시간 제한(M-02)과 잠금/만료 뒤 앱 재기동(H-01)이다. 90일 주기 만료는 보안상 당연한 진리로 유지할 것이 아니라 실제 조직 요구와 지원 비용을 함께 재평가할 정책이다.

### CSRF·XSS·IDOR·정보 노출

CSRF를 끄지 않았고 fetch 요청도 해당 계약을 사용한다. 공개 재설정 API라고 CSRF가 해제된 것도 아니다. 직접 IDOR나 범용 mass assignment 경로는 검토한 주요 API에서 확인하지 못했다. 다만 C-01은 정상 서버 인가를 피해 ADMIN의 브라우저에서 이용하므로 별도 차단이 필요하다.

공개 DTO가 관리자 ID·storage key를 내보내지 않고, 다운로드가 공지와 첨부의 소속을 함께 검사하는 것은 적절하다. 오류 본문에 원시 SQL·stack trace를 보내지 않는다. 반면 감사 실패 메시지에 원시 `e.getMessage()`를 일부 보관하므로 향후 예외에 PII/SQL이 포함되지 않도록 메시지 분류를 명시하는 편이 좋다. 현재 비밀번호 원문이 이 경로로 저장된다는 증거는 없다.

### 파일 보안

일반 첨부는 확장자와 선언 MIME의 allowlist이지 악성 파일 검사기가 아니다. octet-stream·null MIME 허용도 있으므로 파일 실내용이 안전하다는 보장은 없다. 그러나 업로드 주체는 ADMIN/MANAGER이고 서버에서 파일을 실행·해제하지 않으며 다운로드를 강제한다. 이 조건에서 antivirus 미도입을 인증 우회 수준으로 과장하지 않는다. 불특정 외부 사용자의 업로드를 허용하게 되면 신뢰 경계가 달라진다.

프로필 이미지는 실제 디코더 검사, 헤더 기반 해상도·총 픽셀 제한 이후 디코딩을 사용한다. 별도 namespace와 허용 프리셋 경로도 의미 있는 방어다. 로컬 파일시스템을 공격자가 쓸 수 있다는 전제로 descriptor 수준 TOCTOU 방어까지 확대하기보다, 먼저 앱 전용 UID와 볼륨 쓰기 권한을 유지한다.

### Rate limiting / 자원 고갈

- `Bucket.tryConsume()`는 `synchronized`로 토큰 변경을 직렬화한다. 같은 키의 캐시 생성도 Caffeine에 맡긴다.
- monotonic ticker, 음수 시작 시각, 장기 유휴 후 리필, 최소 1초 `Retry-After`를 고려했다.
- 최대 키 10,000과 규칙별 리필 기간 기반 만료가 있다. 짧은 전역 TTL로 긴 재설정 quota를 무력화하는 설계가 아니다.
- 포화 시 eviction/admission 또는 in-flight 버킷 수명 경합으로 quota가 새로 시작될 수 있다. 이는 개별 IP의 엄밀한 유량 보장보다 메모리 관리와 최소 방어를 택한 명시적 trade-off다. Caffeine의 최대 크기를 매 순간의 전체 heap 사용량 hard cap으로 해석해서는 안 된다.
- 신규 IP는 초기 burst를 받고 IP rotation·프로세스 재시작·다중 인스턴스는 제한 효과를 낮춘다. 현재 limiter는 DDoS 방어나 전역 동시 다운로드 제한이 아니다.
- fail-open이라는 설명은 주로 **캐시 포화 시 quota 정확성**에 관한 것이다. 모든 내부 예외를 무조건 허용한다는 일반적인 장애 정책으로 확대 해석하지 않는다.
- CSRF 다음 배치와 자동 서블릿 필터 중복 등록 방지는 적절하다. 순서를 단순히 가장 앞으로 옮기면 타 사이트 요청으로 피해 IP quota를 소진하는 문제가 생길 수 있다.

**판단:** 현재 프로젝트에는 적절한 최소 방어다. 교체보다 프록시 IP 계약(M-05), 실제 메모리/연결 수 한도, 소규모 부하 측정이 우선이다.

## 9. Data Integrity & Database Assessment

### 현재 강점

서비스 트랜잭션과 비관적 행 잠금, DB unique constraint의 최종 방어가 함께 있다. 이메일·아이디 선조회만으로 중복을 막는 설계가 아니다. 공지의 첨부 개수 제한은 공지 행 잠금을 공유하며, 회원 권한·상태 변경과 재설정의 주요 경합도 명시적으로 다룬다. 소프트 삭제된 공지는 공개 서비스에서 일관되게 제외한다.

Flyway V1~V11을 읽고 최신 테스트의 MariaDB 신규 DB 적용을 확인했다. 비밀번호 변경 시각의 추가→backfill→NOT NULL 분리, 공지·첨부 및 프로필 저장 변경을 버전으로 관리하는 방향은 적절하다. 이번 변경에서 이미 적용된 migration을 고쳐야 할 이유는 없다.

### 남는 위험과 운영 조건

- 메뉴 일반 수정의 잠금 누락은 M-04다. 회원에 해결한 종류의 문제를 모든 엔티티에 해결했다고 일반화하지 말아야 한다.
- DB/Flyway 테스트 성공은 실제 기존 운영 DB의 history·checksum·제약·백필 상태를 확인한 것이 아니다. baseline은 기존 스키마가 V1과 같다는 증거를 확보한 환경에서만 1회 사용한다.
- MariaDB DDL 전체를 애플리케이션 트랜잭션처럼 원자적으로 rollback할 수 있다고 가정하지 않는다. migration 전 검증된 백업, 장애 시 복원/forward-fix 절차가 필요하다.
- V1의 `member.pwd` nullable과 엔티티의 non-null 표현처럼 스키마/모델의 모든 계약이 동일하지 않은 부분이 있다. 정상 애플리케이션은 해시를 저장하므로 현재 데이터 손상 버그로 승격하지 않는다. 실제 NULL·비정상 행 조사 후 작은 후속 migration을 판단한다.
- menu parent가 scalar ID인 것은 단순한 구조 선택이다. 모든 관계에 JPA cascade를 추가하는 것이 안전성 개선은 아니다. 직접 SQL과 복구도 운영 입력이므로 orphan 검증은 별도로 필요하다.
- 첨부 물리 삭제와 공지 soft delete의 복구 가능성은 같은 개념이 아니다. soft delete만으로 파일 복구를 약속해서는 안 된다.
- 공개 조회 후 관리자가 비공개로 바꾸는 순간 이미 시작된 응답이 끝날 수 있다. 현재의 SELECT 시점 공개 보장은 합리적이다. 이를 막으려고 공개 다운로드 전체에 쓰기 잠금을 걸면 오히려 관리 작업을 방해한다.

### DB와 파일의 일관성

`FileStorageTransactionSupport`의 새 파일 롤백 정리와 이전 파일 커밋 후 삭제는 로컬 저장에 맞는 실용적 보상이다. DB+파일의 원자적 분산 트랜잭션은 아니다. 프로세스 강제 종료·삭제 실패에는 orphan이 남을 수 있다. 바로 자동 삭제기를 만들기보다 **참조 누락/고아 파일을 읽기 전용으로 보고하는 검증 절차**부터 두는 것이 안전하다. 복구 대상·보존 기간을 모른 채 orphan을 지우면 사고를 키울 수 있다.

## 10. Production Readiness

### 이미 구현된 준비

prod 전용 DB·메일·URL·저장소 설정, 프로파일 가드, Swagger 비활성화, health만 노출, 비루트 컨테이너, dev/prod 볼륨 분리, 환경변수 가드와 백업·복구 스크립트가 있다. dev 시드가 prod와 무조건 함께 실행되는 구조는 아니다.

### 아직 검증/결정해야 하는 것

1. C-01, H-01을 고치고 재검증해야 한다. 테스트 전체 성공만으로 이 두 동작이 안전해지는 것은 아니다.
2. 실제 ingress의 TLS 종료, HTTP→HTTPS, secure session cookie, forwarded scheme/IP, health 접근 범위를 확인해야 한다. `APP_BASE_URL=https://...`만으로 전송 보안이나 쿠키 설정이 완성되지는 않는다.
3. proxy의 연결/본문/시간 제한과 컨테이너 메모리 예산을 정한다. 지금 Kubernetes를 도입하는 대신 대상 VM에서 측정한 값으로 시작한다.
4. SMTP timeout·실제 재설정 수신·실패 알림을 검증한다. 현재 mail health가 꺼져 있으므로 앱 health 성공은 메일 복구 가능성을 보장하지 않는다.
5. 프로세스가 살아 있어도 장애일 수 있다. compose의 DB health와 `restart: unless-stopped`만으로 앱 hung 상태의 발견·복구까지 보장하지 않는다. 외부의 가벼운 HTTP 감시와 운영자 통보가 필요하다.
6. 로그 보관·회전, 파일/DB 디스크 여유, 백업 성공 시각을 관찰해야 한다. DB에 저장하는 audit/visit log는 별도 무한 보관을 약속하지 않는다.
7. 배포할 artifact/commit, DB schema version, 복구 이미지의 조합을 기록한다. 이미지 태그가 가리키는 내용은 시간이 지나 바뀔 수 있으므로 복구 검증에 사용할 이미지 식별자를 남기는 편이 좋다.

실제 secret 값이나 `.env.prod`를 수집하지 않았다. 따라서 운영 secret의 강도·재사용·권한은 미확인이다. 저장소의 예제 값 또는 dev 전용 계정을 그대로 외부 공개에 사용해서는 안 된다. dev compose는 공개 서비스의 대체 배포 방식이 아니다.

## 11. Backup / Restore Assessment

### 복구 도구로서 좋은 점

[prod-backup.sh](../scripts/prod-backup.sh)는 DB·파일·manifest·checksum을 함께 만들고 gzip/tar 검사와 retention을 수행한다. [prod-restore.sh](../scripts/prod-restore.sh)는 입력 파일·checksum·DB 이름을 확인하고, 공유 잠금과 운영자 확인, 앱 정지, 복구 직전 안전 백업, 파일 staging, 기동/health 재확인을 수행한다. 파괴적 단계 실패 시 앱을 계속 정지시키는 방향도 부분 복구 데이터를 정상 서비스처럼 노출하는 것보다 안전하다.

UID/GID 10001과 numeric-owner 복원 계약은 실질적이다. 환경·볼륨 이름을 고정하고 dev/prod를 구분하는 방어를 단순화한다며 제거해서는 안 된다.

### 복구 가능성의 한계

- 온라인 백업의 시점 불일치(M-01)는 checksum으로 검출할 수 없다.
- 기본 백업은 동일 호스트에 있다. 호스트·디스크 전체 유실에 대한 백업이 아니라 **로컬 논리 복구 수단**이다. 문서도 이 범위를 명시한다.
- safety backup과 복구에는 작동 가능한 Docker/DB·충분한 디스크가 필요하다. DB가 심하게 손상된 상황은 평상시 rollback과 다른 절차가 필요하다.
- 스크립트가 DB를 되돌린 후 파일 복원에서 실패하면 완전한 원상 복귀가 자동으로 끝나는 것이 아니다. 앱 정지를 유지하고 safety backup으로 복구하는 운영 판단이 필요하다.
- 백업에 호스트 설정·secret·인증서·DNS·배포 artifact 전체가 들어있는 것은 아니다. 데이터 외의 재구축 정보를 별도로 확보해야 한다.
- tar/gzip 정상 및 health UP은 공지·첨부·프로필의 업무 무결성 증명이 아니다.

### 현실적인 선택

**소규모 단일 호스트라면 짧은 정지 백업 + 제한된 원격 복사 + 복원 리허설이 가장 균형적이다.**

정지할 수 없다면 immutable 파일/지연 삭제, 스냅샷 등 대안을 비교할 수 있지만 그때 보관 비용·정리 정책·복구 시점 의미가 추가된다. 현재부터 이를 구현하는 것은 과하다. 단, 실제 서비스 데이터의 유실을 허용하지 않는다면 원격 보관 자체는 규모가 커질 때까지 미룰 항목이 아니다.

이번 감사에서는 운영 백업·restore를 실행하지 않았다. 별도 폐기 가능한 환경에서 다음을 재현해야 한다: DB+파일 복원, 사용자/역할 확인, 공지 공개 상태, 첨부 전수 존재와 샘플 hash, 프로필 표시, UID 쓰기 권한, 재기동, 손상 checksum 거부, 복원 중 실패 시 서비스 중지 유지. RPO/RTO와 담당자를 함께 기록한다.

## 12. Testing Assessment

### 이번 실행 결과

```text
.\gradlew.bat compileJava compileTestJava test --console=plain
BUILD SUCCESSFUL
JUnit XML: 71 suites, 685 tests, 0 failures, 0 errors, 1 skipped
실제 통과: 684
```

최신 실행에서 `compileJava`, `compileTestJava`는 UP-TO-DATE였고 `test`가 실제 실행되었다. 따라서 clean rebuild까지 수행했다고 표현하지 않는다. Testcontainers가 Docker Desktop의 MariaDB 10.11을 띄웠고 빈 DB의 11개 Flyway migration 적용도 로그로 확인했다.

건너뛴 1개는 `LocalDiskFileStorageTest`의 심볼릭 링크를 통한 루트 탈출 테스트다. Windows의 링크 생성 조건 때문에 실행되지 않아 이 항목은 Linux CI 또는 배포와 유사한 환경에서 따로 확인해야 한다. 테스트 결과는 [Gradle HTML 보고서](../build/reports/tests/test/index.html)에서 볼 수 있다.

이전 기준선의 첫 실행은 Docker 미탐지로 통합 테스트 초기화에 실패했지만, 이후 최신 커밋의 재실행에서 해소되었다. 최종 판정의 미해결 테스트 실패로 계산하지 않는다.

### 강점

단위·MVC 보안·실제 MariaDB 통합 테스트가 함께 있다. 토큰·계정 상태·행 잠금·최후 ADMIN·파일 저장 실패·마이그레이션처럼 실패 영향이 큰 경로를 검증한다. H2로 DB 차이를 숨기지 않는 선택도 좋다. 최근 비밀번호 수정은 네 진입점에 같은 경계 매트릭스를 적용했다.

### 중요한 빈틈

| 추가 검증 | 이유 / 완료 기준 |
|---|---|
| 브라우저 저장형 XSS 회귀 | MockMvc가 정상 JSON을 검증해도 `innerHTML` 실행은 놓친다. MANAGER 입력을 ADMIN이 보는 실제 화면 경계를 검증한다. |
| 기존 관리자 잠금·만료 상태의 prod 재기동 | 현재 부트스트랩 단위 테스트가 의도된 분기를 검증해도 시스템 가용성 요구는 틀릴 수 있다. 복구 화면까지 살아 있어야 한다. |
| 메뉴 일반 수정 vs 비활성화 | 기존 부모/자식 동시성 테스트와 다른 경합이다. 실제 DB에서 두 커밋 순서를 고정해 확인한다. |
| SMTP 무응답·큐 포화 | 예외 즉시 발생과 영원히 응답하지 않는 호출은 다르다. 시간 제한 내 종료와 토큰 정리 경합을 확인한다. |
| 감사 성공과 실제 commit의 일치 | aspect 메서드 mock 호출이나 REQUIRES_NEW 애너테이션 검사는 실제 advisor 순서·커밋 실패를 증명하지 않는다. |
| 운영 셸 backup/restore 실패 경로 | JUnit 성공과 별개다. 폐기 가능한 환경에서 손상·공간 부족·중간 실패·재기동을 검증한다. |
| trusted proxy 환경 | 두 실제 IP의 quota 분리, 위조 forwarded 헤더 차단, secure cookie를 확인한다. |
| 다운로드 메모리·HEAD 부하 | 파일 전체 byte[] 로딩 비용을 배포 heap에서 소규모로 측정한다. |

기본 test 작업은 rate limit을 꺼서 기존 반복 MockMvc와 간섭을 피하고 전용 테스트에서 켠다. 이 자체는 합리적이다. 다만 일반 테스트 전체 성공을 운영 rate limit 활성 상태의 E2E 성공과 혼동하지 않는다. 커버리지 숫자를 올리기보다 위 위험 경계의 테스트가 더 가치 있다.

## 13. Technical Debt

버그(C-01, M-03, M-04)와 다음 유지보수 부채를 구분한다.

### 감사 로그의 성공 의미 — Observation / Technical Debt

`AdminActionLogAspect`는 `@AfterReturning`으로 SUCCESS를 저장하고, 저장 서비스는 `REQUIRES_NEW`다. aspect에 트랜잭션 advisor와의 순서를 명시하는 `@Order`가 없다. **대상 메서드가 반환한 것**과 **외부 트랜잭션이 실제 commit된 것**은 구분해야 한다.

현재 런타임 advisor 순서와 commit 실패에서의 실제 로그 결과를 이번 감사에서 관측하지 않았다. 따라서 “이미 모든 실패 작업이 SUCCESS로 남는다”고 단정하지 않는다. 확인할 위험은 aspect가 트랜잭션 안쪽에 배치되는 경우 commit 전 SUCCESS가 독립 저장될 수 있다는 점이다. 실패 로그가 별도 트랜잭션으로 살아남는 장점과 성공 로그의 시점 의미를 분리해야 한다.

먼저 실제 Spring 프록시·DB로 commit 실패를 주입하는 테스트를 추가한다. 필요하면 aspect를 바깥으로 명시하거나 성공만 after-commit에 연결한다. 실패 로그의 독립 저장은 유지한다. 이 요구 하나 때문에 outbox·Kafka를 도입할 필요는 없다. reflection으로 응답 getter에서 target ID를 추출하는 방식도 변경에 취약하지만, 지금 전면 이벤트 시스템을 만들 정도의 규모는 아니다.

### 오류 응답 경계의 누적 예외 처리 — Technical Debt

API advice가 전역이고 공개 HTML advice·`CustomErrorController`·Security의 401/403 처리도 별도다. JSON/HTML 분리를 위해 생긴 합리적 부분이 있으나, 새 예외를 추가할 때 여러 경로를 생각해야 한다. M-03의 누락부터 보완하고 공통 matcher/응답 계약 테스트로 정리한다. 공통 matcher의 소유자가 exception handler인 구조는 다소 어색하지만 순환 의존을 피하고 있으므로 파일 이동만을 독립 대형 작업으로 만들지 않는다.

### 레거시 프로필 표현의 수명 — Technical Debt

저장소로 이관한 뒤에도 LEGACY_INLINE과 전환 러너가 남아 있다. 이미 데이터가 있는 설치를 지키는 동안은 필요한 호환성이다. 모든 사용 환경에서 잔여 행이 없고 백업/rollback 호환 기간도 종료되었다는 증거가 생기면 제거 후보가 된다. 현재 DB를 읽지 않았으므로 즉시 삭제를 권고하지 않는다. 레거시 LONGTEXT가 일반 member 로딩에 포함되는 비용은 계정 수·잔여 데이터로 측정해야 한다.

### 문서·시간·표시 로직 — Technical Debt

설계 근거가 코드 주석·여러 CLAUDE·계획·배포 문서에 중복되어 일부만 갱신된다. 시간대 혼용(M-06), 문자열 HTML 렌더링 중복도 변경 시 실수를 만들 수 있다. 해결은 문서의 현재 계약을 한곳으로 줄이고, 기존 Clock·작은 안전 렌더링 도구를 재사용하는 수준이면 된다.

## 14. Over-engineering Candidates

과잉 엔지니어링 후보는 “즉시 삭제할 코드” 목록이 아니다. 현재 이득과 제거 위험까지 비교했다.

### 14.1 90일 주기 비밀번호 만료

- **현재 구조:** `PasswordExpiryService`, 상태 전이, 로그인 전/후 재검증, passwordChangedAt backfill, 복구 경로를 유지한다.
- **복잡성이 생긴 이유:** 주기적 교체 정책을 강제하면서 경합·기존 계정·기존 세션의 의미를 보존하려 했다.
- **실제 이득:** 조직의 명시적인 만료 정책을 일관되게 집행할 수 있다.
- **유지 비용:** 잠금·만료·재설정·부트스트랩의 조합이 늘고, 메일 장애가 로그인 불가로 이어진다. H-01 같은 상호작용이 생긴다.
- **단순화 장점/위험:** 정기 만료를 없애면 상태 전이와 지원 부담이 줄지만 조직 정책과 충돌할 수 있고 기존 EXPIRED 계정 전환 계획이 필요하다.
- **변경 가치:** 준수해야 할 요구가 없다면 재검토할 가치가 높다. 요구가 확인되면 유지하되 복구성을 먼저 고친다. 감사만으로 무조건 제거하거나 “MFA를 추가하면 끝”으로 대체하지 않는다.

### 14.2 감사 AOP의 범용 target ID 추출

- **현재 구조:** annotation 문자열 → 반환 DTO getter reflection → 성공/실패 독립 로그.
- **복잡성이 생긴 이유:** 여러 서비스에서 반복 감사 코드를 줄이기 위해서다.
- **실제 이득:** 변경 지점에 annotation만 붙여 공통 메타데이터를 기록한다.
- **유지 비용:** DTO 이름 변경이 로그 누락으로 조용히 이어질 수 있고, 실패 시 대상 ID를 잃으며 commit 의미가 분명하지 않다.
- **단순화 장점/위험:** 작은 typed audit record 또는 일부 명시적 호출은 컴파일 시 계약을 잡기 쉽다. 대신 반복 코드와 로그 호출 누락 위험이 늘어난다.
- **변경 가치:** 먼저 commit 의미와 target ID 회귀 테스트를 보강한다. 새 감사 요구가 늘 때 좁게 typed 계약으로 옮기는 것이 적절하며, 지금 전체 도메인 이벤트 아키텍처로 바꿀 가치는 낮다.

### 14.3 프로필 이미지 레거시 호환성

- **현재 구조:** preset/uploaded/legacy 표현과 일회성 이관 경로.
- **복잡성이 생긴 이유:** 기존 Base64 데이터를 깨지 않고 실제 파일 저장으로 전환하기 위해서다.
- **실제 이득:** 기존 설치와 예전 데이터의 읽기·복구 호환성을 제공한다.
- **유지 비용:** 분기·검증·엔티티 필드 의미·테스트 행렬이 늘어난다.
- **단순화 장점/위험:** 이관 완료 후 legacy 분기 제거는 코드와 member 로딩을 줄인다. 증거 없이 제거하면 오래된 DB·백업에서 프로필이 사라질 수 있다.
- **변경 가치:** 모든 환경과 복구 보존 기간의 종료가 확인된 뒤 높다. 지금은 제거 일정을 결정할 데이터부터 수집한다.

### 14.4 Rate limiter의 작은 타입들과 수동 알고리즘

- **현재 구조:** Bucket·Key·Rule·Decision·Ticker·설정 검증·필터 등 여러 작은 타입, Caffeine 생명주기 관리.
- **복잡성이 생긴 이유:** 시간 테스트, 설정 검증, 동시성, 캐시 수명과 HTTP 응답을 분리했다.
- **실제 이득:** 핵심 동시성/만료가 검증 가능하고 캐시 구현을 다시 만들지 않는다.
- **유지 비용:** 기능 크기보다 읽어야 할 파일과 설계 주석이 많고 유량 보장의 한계를 이해해야 한다.
- **단순화 장점/위험:** 패키지 내부 타입 묶음·주석 요약은 탐색 비용을 줄일 수 있다. 그러나 단일 Map 구현으로 합치면 수명·동시성 버그를 다시 만든다.
- **변경 가치:** 기능 교체는 낮다. **Caffeine과 시간 주입은 유지**하고 파일 수 자체를 결함으로 보지 않는다. Redis·자체 admission/sweep 추가는 현재 목적보다 복잡하다.

### 14.5 백업·복구의 다수 안전장치

- **현재 구조:** 환경변수 가드, 공유 잠금, checksum/DB 이름 검증, 확인 입력, 안전 백업, staging, 실패 시 정지.
- **복잡성이 생긴 이유:** 실제 파괴적 작업과 Windows/Bash/Docker 경계를 다룬다.
- **실제 이득:** 잘못된 환경·백업·중복 실행·부분 복구를 방지한다.
- **유지 비용:** 운영자가 상태와 실패 후 재시작 방법을 이해해야 하고 실기 테스트가 필요하다.
- **단순화 장점/위험:** 명령 UX와 문서를 단순화할 수 있다. 검증 단계를 없애면 잘못된 대상 덮어쓰기와 복구 실패 위험이 커진다.
- **변경 가치:** **안전장치를 제거할 가치는 낮다.** 절차와 실패 테스트를 보강하는 것이 맞다. 여기의 복잡성을 단순히 과잉 방어로 취급하지 않는다.

### 14.6 계획·리뷰 이력의 다중 현재 상태

- **현재 구조:** roadmap, 상세 PLAN, 개발 규칙, 배포 문서, 과거 감사에 구현 설명이 반복된다.
- **복잡성이 생긴 이유:** 의사결정과 검토 근거를 보존하려 했다.
- **실제 이득:** 왜 그렇게 설계했는지 추적할 수 있다.
- **유지 비용:** 과거 상태를 현재 계약으로 읽거나 완료 표기를 과신하기 쉽다.
- **단순화 장점/위험:** 현재 운영 계약·명령은 한 문서에서 관리하고 과거 문서는 시점 명시와 링크로 보존하면 drift가 줄어든다. 기록 자체를 지우면 결정 근거를 잃는다.
- **변경 가치:** 높지만 문서 정리 범위로 제한한다. 새로운 문서 생성 체계를 개발할 필요는 없다.

## 15. Decisions Worth Reconsidering

| 결정 | 당시 합리적 전제 | 현재 재검토 이유 | 지금의 균형적 판단 |
|---|---|---|---|
| ACTIVE ADMIN이 없으면 앱 기동 실패 | 관리 불가능한 설치를 정상으로 보이지 않게 함 | 잠금·만료 계정은 초기 미설치가 아니고 자체 복구에도 앱이 필요 | 최초 설치와 기존 계정 복구를 분리한다(H-01). |
| 90일 비밀번호 만료 | 조직 보안 정책을 주기적으로 강제 | 지원 비용·메일 의존·상태 조합이 실제로 증가 | 조직 요구를 확인하고 결정. 무조건 유지/삭제하지 않는다. |
| 단일 인스턴스·메모리 세션 | 운영 단순성, 작은 관리자 수 | 수평 확장 시 강제 세션 폐기와 limiter가 분산되지 않음 | 현재 유지. 다중 인스턴스가 실제 필요해질 때 하나의 변경 과제로 다룬다. |
| AOP 성공 반환 시 감사 | 반복 로그 코드를 중앙화 | commit된 성공과 반환의 의미가 다를 수 있음 | 실 프록시 테스트로 계약 고정 후 최소 수정. |
| 로컬 파일 저장 | 작은 파일·단일 호스트에서 저비용 | 복구·디스크 유실·동시 다운로드 책임을 직접 짐 | 당장 S3 전환보다 원격 백업·복원 검증이 우선. |
| 공개 첨부 byte[]와 공개 조건 재조회 | 구현 단순성, 파일당 10MB | HEAD도 전체 로딩, in-flight 메모리의 전역 상한 없음 | 작은 부하 검증과 ingress 한도를 먼저 둔다. streaming은 측정 후 또는 큰 파일 요구 때. |
| 온라인 DB→파일 순차 백업 | 무중단·단순한 도구 | 삭제·교체 경합 시 정합한 복원 불가 | 지금은 정지 백업을 정규 복구 기준으로 삼는다. |
| fail-soft 로컬 limiter | 값싼 최소 남용 방어 | IP 회전·포화·proxy의 한계 | 목적을 유지하고 proxy 계약부터 검증. 엄밀한 gateway 계약을 추가하지 않는다. |
| 역할 2개와 명시적 endpoint 정책 | 좁은 관리자 제품에 충분 | 기능이 늘면 각 matcher 변경이 필요 | 현재 유지. 일반 ACL/ABAC 엔진의 실제 요구는 없다. |

비밀번호 해싱은 최신 입력 정책으로 당면 경계 문제가 해소되었다. 다른 해시 알고리즘이 있다는 이유만으로 기존 BCrypt 전환·재해싱 프로젝트를 지금 우선하지 않는다.

## 16. Documentation Drift

다음은 현재 소스와 교차 확인한 불일치다. 문서가 낡았다는 이유로 코드가 취약하다고 판정하지 않았다.

| 문서 / 위치 | 현재 문서 | 실제 구현 | 영향 / 우선순위 |
|---|---|---|---|
| [deployment.md](../docs/deployment.md) 39행 | 부트스트랩 비밀번호 4자 미만을 실패 예시로 설명 | 최신 네 진입점 정책은 15 코드포인트 이상·UTF-8 72바이트 이하 | 복사한 초기 설치 값이 기동 실패할 수 있어 배포 전 정정. 17행 shell quoting 예제도 현재 최소 길이보다 짧다. 예제 값을 운영 secret으로 사용하지 않아야 한다. |
| [deployment.md](../docs/deployment.md) 167행 | 비활성 Swagger의 핸들러 없는 경로가 500인 기존 결함 설명 | 현재 global advice에 `NoResourceFoundException`/`NoHandlerFoundException`의 404 처리가 있다 | 권한·인증 조건별 응답을 재확인해 낡은 예외 설명 제거. 모든 미인증 요청이 404라는 뜻은 아니다. |
| [migration-guide.md](../docs/migration-guide.md) | 현재 migration 표와 기대 결과가 V1~V3에서 끝남 | 현재 V11까지 있고 이번 신규 DB 적용도 확인 | 신규/기존 DB 확인 절차를 잘못 종료할 수 있어 우선 갱신. baseline 명령도 현재 필수 profile 지정과 함께 설명해야 한다. |
| [README.md](../README.md) 30행 | 낙관적/비관적 락 적용 | 검토한 엔티티에 `@Version` 없음. 실제로 행 잠금·조건부 UPDATE가 핵심 | 동시성 보장 범위를 과대 이해하지 않도록 정정. |
| [config/CLAUDE.md](../src/main/java/com/cms/config/CLAUDE.md) | `/admin/**` ADMIN 일반 규칙 표에서 자기 정보/대시보드 예외 누락 | `/admin`, `/admin/member/info`, `/admin/api/members/me/**`를 MANAGER에도 허용 | 후속 보안 변경 시 오판할 수 있으므로 표 보완. |
| [publicweb/notice/CLAUDE.md](../src/main/java/com/cms/publicweb/notice/CLAUDE.md) | 공개 다운로드 설명에 rate limit 미도입 | 실제 limiter·필터·전용 테스트 도입됨 | 없는 기능을 새로 만들거나 현재 방어를 누락 평가하지 않도록 정정. byte[]/HEAD 비용은 여전히 남는다. |
| [Member.java](../src/main/java/com/cms/admin/member/domain/Member.java) 상단 주석 | 자기 정보·프로필을 잠금 없는 쓰기 예시로 설명 | 현재 해당 쓰기 경로에서 `findByIdForUpdate` 사용 | `@DynamicUpdate`와 잠금의 책임을 잘못 이해하지 않도록 정정. |

과거 PLAN·감사 문서는 그 시점의 기록으로 보존할 수 있다. 다만 최신 계약 문서와 명확히 구분해야 한다. 비밀번호 정책 계획은 이제 실제 코드와 테스트가 따라왔으므로, 이전 감사의 “미구현” 판정을 유지해서는 안 된다.

## 17. Missing Production Capabilities

운영 준비 공백을 코드 결함과 구분한다. 다음은 **실제 외부 서비스 시작 전에** 담당자·설정·검증 결과가 필요하다.

| 능력 | 현재 상태 | 최소 완료 기준 |
|---|---|---|
| TLS ingress와 신뢰 IP/쿠키 계약 | loopback app 구성은 존재, 실제 ingress 미검증 | 실제 도메인 HTTPS, 우회 차단, secure cookie, 두 IP quota 분리·위조 헤더 차단 |
| 재기동 후 계정 복구 | 잠금/만료로 전체 기동 차단 가능 | 기존 계정 상태를 보존하면서 앱과 재설정 기능 기동 |
| 시간 제한 있는 메일 복구 | send 예외 처리는 있으나 무응답 한도 없음 | 유한 timeout, 실제 수신, 실패가 운영자에게 보임 |
| 데이터·파일 일관 백업 | 온라인 및 수동 정지 절차 제공 | 채택한 정지/정합 전략, 최근 성공 기록, 복원 리허설 |
| 호스트 유실 복구 | 로컬 백업 중심 | 접근 제한된 원격 사본, 환경/이미지 재구축 정보, RPO/RTO |
| 최소 장애 감지 | health endpoint와 로그 존재 | 외부 HTTP 확인, 디스크/백업 지연/중요 오류 통보, 담당자 |
| 용량·로그 운영 | 저장 한도·pagination·일부 rate limit 존재 | VM 메모리·연결 예산, 로그 회전, DB/파일 증가 관찰 |

이를 모두 새로운 “기능”으로 구현할 필요는 없다. 작은 운영 설정·문서·정기 확인으로 충분한 항목이 많다. 조직 정책에 따른 MFA·감사 보존·개인정보 삭제 요구는 별도 확인 사항이며, 저장소만 보고 법적 의무나 현재 위반을 단정하지 않는다.

## 18. Things We Should NOT Build Yet

| 기술/기능 | 지금 도입하지 않는 이유 | 검토할 실제 조건 |
|---|---|---|
| Redis 세션·분산 rate limiting | 단일 인스턴스의 현재 계약을 더 복잡하게 만듦. proxy IP 오류를 고치지도 않음 | 다중 앱 인스턴스가 실제 가용성/용량 요구가 될 때 |
| Kafka·범용 event-driven architecture | 현재 메일·감사 처리에 broker 운영 비용이 더 큼 | 외부 연동 다수, 재처리/전달 보장의 구체적 요구가 생길 때 |
| Kubernetes·서비스 메시 | 배포 대상·운영 인력이 작은 상태에서는 추가 장애 지점 | 여러 서비스/팀의 공통 플랫폼 요구가 확인될 때 |
| 마이크로서비스·CQRS·범용 DDD 계층 | 현재 기능 결합보다 데이터·권한 경계의 복잡성을 키움 | 독립 배포·소유권 경계가 실제로 생길 때 |
| 전용 API Gateway | 현재 ingress의 TLS/시간 제한/간단한 제한으로 우선 충분 | 다수 API의 중앙 정책·외부 고객 quota 계약이 필요할 때 |
| 전면 S3 전환·CDN | 작은 로컬 파일 저장의 당면 위험은 백업·복원 계약 | 다중 호스트, 큰 트래픽, 원격 스토리지 운영 이득이 측정될 때 |
| 대형 observability stack | 최소 오류 로그·health·disk·backup 통보가 먼저 | 간단한 기록으로 원인 추적이 안 될 정도로 구성·트래픽이 커질 때 |
| React/Vue 전면 재작성 | C-01은 출력 sink 수정과 브라우저 테스트로 해결 가능 | 현재 UI 방식이 실제 사용자 요구를 반복해서 막을 때 |
| 범용 ACL/ABAC·정책 DSL | ADMIN/MANAGER 및 현재 경로 정책으로 충분 | 고객별·리소스별 권한 요구가 구체화될 때 |
| 파일과 DB의 분산 트랜잭션, 무조건 자동 orphan 삭제 | 보상·백업·정합 점검보다 비용과 삭제 사고 위험이 큼 | 실제 복구/RPO 제약이 현 방식으로 충족되지 않을 때 |

MFA도 “불필요하다”는 결론은 아니다. 외부 노출 관리자 자산의 가치나 조직 요구가 확인되면 우선순위가 올라갈 수 있다. 지금은 확인된 XSS를 남겨둔 채 인증 기능을 더 얹는 순서가 잘못된 것이다.

## 19. Recommended 3-Month Roadmap

아래는 새 기능을 채우기 위한 달력이 아니라 위험을 줄이는 순서다. 기존 `project-direction-roadmap.md`는 수정하지 않았다. 기간은 투입 인력에 따라 조정하며, 일정 때문에 미완료 배포 게이트를 통과시키지 않는다.

### Phase 1 — 반드시 해결

첫 달, 외부 배포 전 완료를 목표로 한다.

1. **C-01 출력 sink 수정과 브라우저 회귀 테스트.** 완료 기준: MANAGER가 HTML 형태의 이름을 저장해도 ADMIN 화면에서 텍스트로만 보이고 이벤트가 실행되지 않는다. 동일 화면 아이디·기존 저장 데이터도 검사한다.
2. **H-01 부트스트랩/계정 복구 분리.** 완료 기준: 빈 DB 최초 설치의 안전성은 유지하면서 기존 ADMIN 잠금·만료 후 재기동 및 재설정이 가능하다. DISABLED/DELETED 자동 부활은 없다.
3. **SMTP timeout과 M-03 오류 처리·진단 기록.** 완료 기준: 무응답 메일 호출이 유한 시간에 끝나고 요청 오류는 400/405, 예기치 않은 오류는 안전한 500과 추적 가능한 서버 기록을 남긴다.
4. **배포 게이트 확정.** TLS·프록시 IP·secure cookie 검증, 정지 백업·원격 사본·폐기 가능한 환경의 복원 리허설, 담당자와 RPO/RTO 결정.
5. **실행 문서의 중요한 drift 수정.** 부트스트랩 길이, 현재 migration 목록, 실제 배포·복구 명령을 먼저 맞춘다.

처음 두 항목은 확인된 no-ship finding이다. 나머지는 작은 수정과 운영 검증으로 실제 첫 배포의 신뢰도를 확보하는 작업이다.

### Phase 2 — 안정화

둘째 달, 제한된 운영에서 피드백을 받을 수 있는 상태를 목표로 한다.

1. 메뉴 쓰기 잠금 계약 통일과 실제 DB 경합 테스트(M-04).
2. 감사 SUCCESS/FAIL의 commit 의미를 실제 프록시 테스트로 확정하고, 필요한 경우에만 좁게 수정.
3. 시간대 계약을 배포와 코드에서 통일하고 기존 데이터 기준을 조사(M-06).
4. health·디스크·중요 오류·백업 지연 통보, 로그 회전과 보관 책임 확정.
5. Linux에서 건너뛴 파일 링크 테스트와 다운로드/HEAD 소규모 부하 측정.
6. 복원 후 파일 참조 검증을 읽기 전용 점검으로 정례화. 파괴적 자동 정리는 하지 않는다.

### Phase 3 — 운영 후 판단

셋째 달, 실제 사용과 장애 데이터를 기준으로 선택한다.

- 로그인 잠금 빈도·메일 실패·복구 문의를 보고 90일 만료 정책과 지원 UX를 재평가한다.
- heap/다운로드 동시성·지연이 문제면 스트리밍이나 HEAD의 파일 전체 로딩 제거를 좁게 진행한다.
- 모든 환경의 legacy 프로필 이관과 복구 호환 기간이 끝났으면 관련 분기를 제거한다.
- 감사/방문 로그 증가량을 보고 보관 기간과 정리 방식을 정한다.
- 사용자 가치가 확인된 CMS 기능을 추가한다. 인프라 추가를 개발 성과의 기본 단위로 삼지 않는다.

## 20. Recommended 6-Month Direction

**기능별 모놀리스와 단일 운영 단위를 유지하면서, 시스템 경계의 안전성과 운영 절차를 제품 수준으로 만든다.**

안정성은 모든 곳에 잠금을 늘리는 것이 아니라 같은 불변식을 수정하는 경로가 같은 계약을 따르도록 확보한다. 보안은 더 많은 정책을 쌓기보다 브라우저 출력·세션·복구·프록시 신뢰 경계의 실제 동작을 검증한다. 단순성은 필요한 보상/복구 방어를 삭제하는 것이 아니라, 이관이 끝난 legacy 분기와 중복된 현재 상태 설명을 걷어내는 방향이다.

6개월 후의 성공 기준은 클래스나 서비스 수가 아니다. 다음을 운영자가 설명·재현할 수 있어야 한다.

- 계정이 잠기거나 만료되어도 안전하게 복구하고 재배포할 수 있다.
- 공지/첨부/프로필이 어떤 백업 시점으로 얼마나 빨리 돌아오는지 안다.
- 중요한 변경은 권한·입력·DB commit·사용자 화면까지 회귀 테스트로 보호된다.
- 서버 장애와 잘못된 클라이언트 요청을 구분하고 원인을 찾을 수 있다.
- 새 기능을 넣을 때 현재 문서·구현·테스트가 같은 계약을 설명한다.

트래픽이나 가용성 요구가 변하지 않는다면 Redis/Kubernetes/마이크로서비스 없이도 충분히 성숙한 CMS로 발전할 수 있다. 늘릴 것은 기술 종류보다 검증된 사용자 가치와 운영 신뢰도다.

## 21. Production Deployment Checklist

아래는 **배포 실행 시 확인할 목록**이다. 이 감사에서 실제 prod 변경·백업·복원을 수행했다는 의미는 아니다.

### 소스·테스트·보안 게이트

- [ ] C-01 수정 후 MANAGER 입력 → ADMIN 목록/상세의 브라우저 XSS 회귀 테스트를 통과한다.
- [ ] H-01 수정 후 잠금·만료 상태 ADMIN만 있는 DB의 재기동 및 재설정을 검증한다.
- [ ] 배포할 commit을 고정하고 작업 트리의 미커밋 변경을 분리한다.
- [ ] Docker 사용 가능한 환경에서 `gradlew.bat clean build` 또는 Linux `./gradlew clean build`를 통과한다.
- [ ] Linux에서 심볼릭 링크 저장소 테스트가 실제 실행됐는지 확인한다.
- [ ] ADMIN/MANAGER/비로그인의 주요 API·페이지, CSRF 없는 쓰기, 세션 폐기 후 요청을 확인한다.
- [ ] 잘못된 숫자 ID·메서드·JSON의 400/405 등 오류 계약과 안전한 500 로그를 확인한다.
- [ ] 해시된 비밀번호·재설정 토큰·메일 자격 증명·요청 본문이 로그/응답에 노출되지 않는지 확인한다.

### 설정·네트워크

- [ ] prod만 활성화하고 dev seed/개발 도구가 실행되지 않음을 확인한다.
- [ ] `.env.prod`는 추적하지 않고 파일 권한을 제한한다. 예제 secret을 사용하지 않는다.
- [ ] DB·파일 볼륨 이름, 대상 DB, 앱 UID 10001, 쓰기 권한을 확인한다.
- [ ] `APP_BASE_URL`이 실제 HTTPS 도메인이며 재설정 링크가 그 주소로 도착한다.
- [ ] 부트스트랩 비밀번호가 최신 길이·UTF-8 정책을 만족한다. 초기 설치 후 secret 제거 및 재기동 절차를 검증한다.
- [ ] 실제 ingress에서 TLS, HTTP redirect, secure/HttpOnly/SameSite 쿠키의 기대값을 확인한다.
- [ ] 앱/DB 직접 외부 접근은 막고, ingress가 forwarded 헤더를 정규화함을 확인한다.
- [ ] 서로 다른 실제 IP가 서로 다른 quota를 사용하고 위조 헤더로 우회·감사 오염을 만들지 못함을 확인한다.
- [ ] `/actuator/health` 외 관리 endpoint가 차단되고 prod Swagger가 활성화되지 않음을 확인한다.
- [ ] SMTP 연결·읽기·쓰기 timeout을 설정하고 실제 발송·실패 경로를 검증한다.
- [ ] JVM/DB/업무 Clock의 시간대와 로그 시각을 확인한다.

### DB·파일·복구

- [ ] 대상 DB의 `flyway_schema_history` 성공 여부·checksum·최신 버전을 확인한다. 현재 코드 기준 마지막 migration은 V11이다.
- [ ] 기존 DB의 baseline 필요 여부를 먼저 판단하고, 필요 시 현재 스키마와 baseline 계약을 대조한다. 상시 baseline을 켜지 않는다.
- [ ] migration 직전 복구 가능한 DB+파일 백업과 배포 artifact 식별자를 확보한다.
- [ ] 정지 백업의 경우 **앱만** 멈추고 DB는 살아 있는 절차를 사용한다. 실패 시 재개·통보 책임을 정한다.
- [ ] 원격 백업 사본의 접근 제한·최근 성공 시각·retention을 확인한다.
- [ ] 폐기 가능한 환경에서 restore하고 공지·첨부·프로필·역할·로그인·파일 쓰기를 확인한다.
- [ ] checksum 손상 거부와 부분 복구 실패 시 앱 정지 유지·safety backup 복구를 연습한다.
- [ ] DB 참조 파일 존재 여부와 샘플 hash를 확인한다. health UP만으로 복원 완료 처리하지 않는다.
- [ ] 호스트 전체 유실 시 이미지·설정·secret·인증서를 재구축할 방법과 RPO/RTO를 기록한다.

### 운영 인수

- [ ] 컨테이너 heap/메모리·동시 연결·파일 크기·디스크 여유의 실제 예산을 정한다.
- [ ] 다운로드/HEAD 소규모 부하에서 메모리와 응답 지연을 확인한다.
- [ ] 외부 HTTP 실패, 디스크 부족, 백업 지연, 반복 SMTP/500 오류가 담당자에게 통보된다.
- [ ] 애플리케이션·Docker 로그 회전과 감사/방문 로그 보관 정책을 정한다.
- [ ] 잠금 복구·메일 장애·파일 누락·migration 실패·restore 실패의 담당자와 순서를 문서화한다.
- [ ] dependency/container image의 현재 취약점 점검을 별도로 수행하고 결과를 기록한다. 이 감사는 전체 CVE 스캔을 대체하지 않는다.

## 22. Final Recommendation

### KEEP

- 기능별 모놀리스, Spring MVC/Thymeleaf, 서버 세션, 명시적 ADMIN/MANAGER 정책.
- CSRF·메서드 인가·self API, 상태 변경 후 세션 폐기, 로그인 직전 fresh 재검증.
- 최신 비밀번호 검증 통일, 토큰 해시와 제한된 재설정 계약.
- 실제 DB 제약·필요한 행 잠금, Flyway와 validate, Testcontainers.
- 공개 공지 전용 조회와 안전한 DTO, 로컬 FileStorage와 커밋/롤백 보상.
- Caffeine 기반 최소 limiter와 백업/복구의 대상·실패 안전장치.

### FIX

- **최우선 C-01: MANAGER 입력이 ADMIN 상세에서 HTML로 실행되는 경로.**
- **H-01: 기존 ADMIN의 잠금/만료를 앱 전체 기동 실패로 확대하는 정책.**
- SMTP timeout, 요청 오류의 500 오분류와 일반 예외 로그, 메뉴 쓰기 잠금 계약.
- 실제 배포의 TLS·trusted proxy·시간대·모니터링 계약과 검증된 정지/원격 백업.
- 운영 명령과 보안 경계에 영향을 주는 문서 drift.

### SIMPLIFY / REMOVE

- 평문 필드와 HTML 문자열을 섞는 UI 렌더링을 분리한다.
- 이관·복구 호환 기간이 끝난 경우에만 legacy 프로필 분기를 제거한다.
- 조직 요구가 없는 90일 만료 정책은 지원·복구 비용을 포함해 다시 결정한다.
- 현재 계약의 중복 문서를 줄이고 과거 계획은 시점이 명확한 이력으로 보존한다.
- 감사 reflection 계약은 필요가 확인되면 작은 typed 계약으로 개선하되 범용 이벤트 플랫폼으로 확대하지 않는다.

### DEFER

- Redis·Kafka·Kubernetes·마이크로서비스·분산 limiter·범용 권한 엔진.
- 측정 없는 streaming/CDN/S3 전면 전환, 전면 SPA 재작성.
- 파괴적 자동 orphan 정리, 무중단 복구를 위한 복잡한 분산 일관성 설계.

**Final Verdict: no-ship.** 확인된 C-01과 H-01을 해결하고 필요한 운영 검증을 마치기 전까지 외부 운영 배포를 권고하지 않는다. 이는 프로젝트 전체 방향이 잘못됐다는 판단이 아니다. **기반은 유지할 가치가 크며, 현재 필요한 것은 더 많은 기술이 아니라 적은 수의 정확한 수정과 검증 가능한 운영 절차다.**

## Audit Coverage

### 충분히 검토한 영역

- 디렉터리·build·프로파일·Security matcher/필터 순서·인증·인가·계정 상태·세션 폐기·재설정의 주요 실행 경로.
- 주요 Controller/Service/Repository/Entity/DTO, 공개 공지와 관리자 조회의 차이, 회원/메뉴/공지 쓰기 트랜잭션.
- 첨부/프로필 validation·저장·조회·삭제·보상, 공개 다운로드 응답 및 소속 확인.
- 토큰 버킷·Caffeine 생명주기·설정 검증·전용 테스트의 핵심 계약.
- API/HTML 오류 처리, 감사 기록, application 설정, Dockerfile, dev/prod compose, 배포·백업·복구 스크립트.
- V1~V11 migration, 핵심 보안·동시성·저장소·프로파일 테스트, CI 구성.
- README·루트/기능별 AGENTS/CLAUDE 규칙·배포/마이그레이션 문서와 관련 계획의 주요 결정.
- 중단 사이 추가된 비밀번호 정책·이메일 정규화 변경. 이전 발견을 현재 코드에 재대조했다.

### 실행으로 확인한 영역

- 최신 커밋에서 Gradle compile/test 작업 성공, MariaDB Testcontainers 및 신규 DB migration 적용.
- 현재 관리자 상세 렌더링 함수에 대한 격리 Node 실행과 raw HTML sink 확인.
- Bean Validation/BCrypt 경계의 초기 진단 및 최신 정책의 전체 회귀 테스트.
- 현재 전역 advice를 사용한 독립 MockMvc의 잘못된 ID/메서드 500 응답 확인.

### 부분적으로 검토한 영역

- 모든 테스트 파일의 모든 assertion을 줄 단위로 검토하지는 않았다. 위험도가 높은 경로를 상세 검토하고 전체 suite 실행으로 보완했다.
- 템플릿/JavaScript는 권한·CSRF·렌더링 sink·다운로드 등 주요 경로 중심이다. 모든 UI 상태를 브라우저에서 클릭 검증하지 않았다.
- 모든 과거 PLAN·감사·portfolio 파일을 동일 깊이로 읽지 않았다. 현재 구현과 관련된 계약·주장만 교차 확인했다.
- CSS 및 외부 제공/vendor 자산은 실행 경로·의존성 관점의 부분 검토다. 전체 소스 및 supply-chain 분석이 아니다.
- 일부 장애·경합은 코드상 실행 순서 분석이며 해당 실패를 이번 감사에서 별도 주입하지 않았다. 각 항목에 검증 수준을 표시했다.

### 검토하지 못했거나 추가 검증이 필요한 영역

- 실제 운영 DB의 비정상 행·인덱스 선택·실데이터 규모·Flyway history·legacy 이미지 잔존.
- 운영 secret 강도·OS/파일 권한·네트워크 방화벽·TLS ingress·쿠키·메일 공급자 설정.
- 실제 prod 이미지 기동·재배포·호스트 유실·백업/restore 실기·RPO/RTO.
- 실제 브라우저에서 C-01의 이벤트 실행부터 계정 승격까지 이어지는 E2E 공격 재현.
- heap/동시 다운로드·장시간 SMTP 장애·대량 신규 IP 트래픽의 실부하 결과.
- 전체 dependency·컨테이너·정적 vendor의 현재 CVE 스캔.
- Windows에서 skipped된 심볼릭 링크 테스트의 Linux 재실행.

### Critical / High 최종 자체 검증

| 질문 | C-01 | H-01 |
|---|---|---|
| 현재 코드에 근거하는가? | 현재 sink·self API 확인, 실제 renderer 격리 실행 | ACTIVE 조건·예외·잠금/만료 전이 확인 |
| 이미 있는 방어가 막는가? | CSRF/HttpOnly/인가로 같은 출처 ADMIN 스크립트 실행을 차단하지 못함 | 다른 ACTIVE ADMIN은 완화하지만 한 명/전원 잠금·만료 경로는 남음 |
| 조건이 현실적인가? | MANAGER 계정 + ADMIN의 일반 상세 조회 | 잠금/만료 + 재시작, 단일 ADMIN 설치에서 현실적 |
| 규모와 무관하게 의미 있는가? | 권한 경계 자체의 문제 | 소규모일수록 단일 관리자 가능성이 높음 |
| best practice 차이인가? | 아니오, 공격자 입력이 privileged HTML sink에 도달 | 아니오, 자체 복구 기능과 공개 서비스까지 기동 실패 |
| 더 작은 수정이 가능한가? | 텍스트 escaping/DOM 분리와 회귀 테스트 | 최초 설치와 기존 계정 상태 구분 |

근거가 불충분한 감사 commit 순서, 파일시스템 로컬 공격, 미래 수평 확장 문제는 Critical/High로 분류하지 않았다. 수정된 비밀번호 검증 결함도 제외했다.
