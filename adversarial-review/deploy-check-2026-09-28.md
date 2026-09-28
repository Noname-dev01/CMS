# INDEPENDENT IMPLEMENTATION VERIFICATION REVIEW

## Executive Summary

**전체 판정: FIX REQUIRED BEFORE GATES.**

제품 변경 대상 **C-01 / H-01 / M-02 / M-03 / M-04는 현재 HEAD에서 종결을 확인했다.** 다만 PR 6의 정규 백업 실행 예시가 정지·백업 실패를 통제하지 않아, M-01의 운영 계약까지 완료됐다고 판단하지 않는다. **PR 6의 런북만 다시 열어 보완**해야 한다. PR 1~5 제품 구현을 되돌리거나 아키텍처를 재설계할 근거는 없다.

- 현재 HEAD: `ec6336cb5ea3aaaa80d94efb88555e9b381da810`.
- 비교 기준: PR 1 이전 `f890195`부터 HEAD까지. #37~#42의 6개 변경을 대조했다.
- 검증 실행일: **2026-09-28, KST**. 이전 문서의 실행 숫자는 이번 결과에 합산하지 않았다.
- Windows / Java 17.0.18 / Spring Boot 3.5.16 / 실제 MariaDB 10.11.16 / Chromium 사용.
- **clean compile + 전체 테스트 + bootJar 성공: 763개 중 762개 통과, 실패·오류 0, skip 1.**
- 실제 브라우저의 저장→DB→ADMIN 상세, 독립 schema별 prod JAR 기동, 실제 HTTP 오류, MariaDB 잠금 대기, 실제 prod mail Bean과 SMTP fixture를 확인했다.
- restore script의 파괴적 실행 및 실제 ingress 인증은 이번에 수행하지 않았다. 과거 drill 기록과 이번 실행을 구분한다.

`deploy-check`의 읽기 전용 검증·증거 구분·보고서 저장 절차를 사용했으며, 범위는 PR 1~6으로 제한했다. 사용자 요청에 따라 테스트용 합성 데이터만 사용했다. 제품 코드·설정·migration·기존 테스트·계획서는 수정하지 않았다.

## 기준 및 범위

현재 코드, 승인된 [remediation-plan.md](C:/Users/user/IdeaProjects/CMS/adversarial-review/remediation-plan.md), [2차 독립 검증](C:/Users/user/IdeaProjects/CMS/adversarial-review/deploy-check-2026-09-23.md), H-01 승인 정책을 대조했다.

최신 계획의 다음 범위 조정을 인정했다. 오래된 요청만 기준으로 새 결함을 만들지 않았다.

- PR 3: 406, 명시적 JSON Content-Type, binding 실패 메시지 제한은 승인된 v9 범위다.
- PR 4: `useYn` 생략/null 수정의 lost update가 대상이다. 오래된 화면이 명시적으로 `useYn=true`를 보내는 문제는 승인된 제외 범위다.
- PR 5: 연결 지연의 결정적 fault 재현 한계는 계획에 기록돼 있다. 이번 변경을 executor 문제로 확대하지 않는다.
- PR 6: 실배포 prod가 없을 때 사전·사후 점검을 거친 로컬 disposable stack을 허용하는 v19~v21 결정을 인정한다. 예전의 전용 VM 요구만으로 과거 drill을 무효화하지 않는다.
- M-06은 REJECTED / NO ACTION이다.

착수 전부터 있던 `adversarial-review/project-direction-roadmap.md`의 수정 및 미추적 `portfolio/`는 사용자 작업으로 보존하고 이번 구현 diff에서 분리했다.

## No-Ship Findings

**이번 범위에서 새로 확인된 Critical/High 제품 결함은 없다.** 기존 C-01/H-01을 현재도 미해결 High라고 반복하지 않는다. 아래의 Gate 전 차단은 PR 6 운영 절차의 미완료 때문이며, 새로운 심각한 제품 보안 취약점 판정이 아니다.

## Needs-Attention Findings

### PR6-R1 — 정규 백업 예시가 실패와 원래 실행 상태를 보존하지 않음

**분류:** 기존 M-01 remediation의 운영 계약 누락. PR 6 재개 필요.

**근거:** [deployment.md:130](C:/Users/user/IdeaProjects/CMS/docs/deployment.md:130)의 예시는 `docker stop` → backup → `docker start`를 무조건 연속 실행한다. 정지 성공 확인, 원래 실행 여부 기록, backup 종료 코드 보존이 없다. 마지막 health 성공은 `OK`로 끝난다. 반면 계획 7절은 정지 확인, 원래 상태 기록, backup 실패 기록과 재개 책임의 분리를 요구한다.

실제 Docker/파일을 건드리지 않는 Bash 대체 명령으로 문서의 실행 흐름을 확인했다.

| 안전한 모의 조건 | 관측 결과 |
|---|---|
| `docker stop`이 1을 반환 | backup과 start가 계속 호출됨 |
| backup이 1을 반환하고 start/health는 성공 | `OK`, 최종 상태 0 |

**실제 영향:** 운영자가 예시를 묶어 실행하면 백업 실패를 health 회복으로 오인할 수 있다. 앱 정지를 확인하지 않고 생성한 산출물은 quiesced 정합 백업으로 인정할 수 없다. 원래 정지돼 있던 앱도 무조건 시작한다. 백업 실패 후 앱을 재개하는 행위 자체를 결함으로 보는 것이 아니라, 그 판단과 백업 성공 여부가 구분되지 않는 것이 문제다.

**현재 방어:** backup script 자체는 실패 코드를 반환하고 불완전 산출물을 정리한다. [restore cleanup](C:/Users/user/IdeaProjects/CMS/scripts/prod-restore.sh:38)은 파괴적 단계 이후 실패 시 앱을 정지시킨다. [deployment.md:246](C:/Users/user/IdeaProjects/CMS/docs/deployment.md:246)도 부분 복원 위험을 경고한다. 이 방어를 무시하거나 backup/restore script를 전면 재작성할 필요는 없다.

**최소 보완:** 런북에 원래 실행 상태, 실제 정지 확인, backup 성공/실패 기록을 명시하고 예시의 분기를 그 계약과 맞춘다. health 회복과 backup 성공은 따로 표시한다. 원래 정지 상태는 보존하고, backup 실패 시 재개 판단을 destructive restore 실패 시 정지 유지와 분리한다. 계획 내 PR 6 상태표와 7절의 서로 다르게 읽히는 실패 설명도 일치시킨다. 단순히 `set -e`만 붙이면 backup 실패 후 앱 재개 책임이 사라지므로 충분하지 않다.

**완료 조건:** 정지 실패, backup 실패, 원래 정지 상태, 정상 backup, start/health 실패를 안전한 대체 명령으로 검증하고 각각의 결과·재개 책임이 명시된다. 제품 코드 변경이나 새 백업 플랫폼은 필요 없다.

### PR4-T1 — 제품 수정은 유효하지만 영구 동시성 테스트의 증명 범위가 좁음

**분류:** Gate 전 작은 테스트·기록 보완. M-04 제품 결함 재개가 아님.

- [barrier 테스트](C:/Users/user/IdeaProjects/CMS/src/test/java/com/cms/admin/menu/service/MenuConcurrencyIntegrationTest.java:246)는 동시에 제출하지만 A-first/B-first를 각각 보장하지 않는다. 계획 실행 기록의 “결정적 순서 강제와 동등한 보증”이라는 설명은 정확하지 않다.
- [잠금 실증 테스트](C:/Users/user/IdeaProjects/CMS/src/test/java/com/cms/admin/menu/service/MenuConcurrencyIntegrationTest.java:178)는 실제 `updateMenu()` 대신 repository lock을 직접 획득한다. 이는 코드 주석에도 정직하게 구분돼 있다.
- 같은 시험은 `Throwable`을 잡아 `assertNotNull`만 수행하므로 예상한 lock timeout과 무관한 예외도 통과시킬 수 있다. 첫 latch 대기가 정리 `finally` 앞에 있고 worker Future를 확인하지 않는 부분도 실패 시 정리를 약하게 만든다.

이번 검토에서는 별도 실제 API 시험으로 **양방향 InnoDB lock wait와 최종 결과를 확인했다.** 그러므로 구현 자체를 NOT VERIFIED/FAIL로 내리지 않는다. 후속은 테스트 하니스에 한정한다: 두 순서를 고정하고 실제 대기를 확인하며, timeout 예외를 좁게 단언하고 모든 실패 분기의 release/worker 종료를 보장한다. 제품 코드에 latch를 심을 필요는 없다.

## PR 1 — C-01 상세 평문 안전 렌더링

### Planned Change

기존 `escapeHtml()`을 재사용하고 평문 값과 내부 이메일 markup을 분리한다. 서버 입력 제한, sanitizer, frontend framework, migration은 추가하지 않는다.

### Actual Implementation

[renderDetail():611](C:/Users/user/IdeaProjects/CMS/src/main/resources/templates/admin/member/admin-manage.html:611)은 상세 `value`를 평문으로 유지한다. 이메일 UI만 내부 `html`이며, [출력 경계:655](C:/Users/user/IdeaProjects/CMS/src/main/resources/templates/admin/member/admin-manage.html:655)에서 `item.html ?? escapeHtml(item.value)`를 사용한다. 이메일 값은 별도로 escape되고 기존 목록·요약 경계도 유지된다.

### Plan Match

**Exact.**

### Evidence

Static Code Trace + Unit/MVC + **Browser/UI Regression Test**.

현재 prod JAR, 합성 ADMIN/MANAGER, 격리 MariaDB, 서로 다른 Chromium 세션으로 다음을 이번에 실행했다.

- MANAGER가 `<span id="audit-name-marker">검증 이름</span>` 저장. API 응답과 DB `HEX(user_name)`으로 원문 왕복 확인.
- ADMIN 상세 이름의 `textContent`는 원문과 같고 `#audit-name-marker` 개수는 **0**.
- `홍길동 Alice`, `홍길동 & Alice ' " &lt;`도 원문 그대로 표시. 이중 escape 없음.
- 목록·요약·상세 일치. 이메일 버튼의 내부 아이콘 클릭 → 복사 안내 → 실제 clipboard 값 일치.
- 프로필 fallback/preset, 편집 저장·취소·모달 닫기 통과.
- 추가 응답 fixture로 markup 모양의 아이디·잘못된 날짜 fallback·빈 이메일을 확인. 이것은 이름의 실제 DB 왕복 시험과 구분한다.
- MANAGER의 타인 상세/목록 API 및 관리 화면 **403**, 본인 수정의 CSRF 누락 **403**.
- 브라우저 page error 0. 외부 origin 요청은 차단했다.

신규 convention 3개 및 관련 기존 controller/service/security 테스트도 전체 스위트에서 통과했다. convention만으로 PASS 처리하지 않았다.

### Finding Closure

**C-01 CLOSED.** 실제 저장→다른 역할의 상세 브라우저 경로에서 HTML 해석이 사라졌다.

### Regression Check

정상 문자열, 이메일 버튼/복사, 위임 이벤트, 프로필·모달, 역할·CSRF 계약 유지.

### Scope Check

작은 템플릿 변경과 관련 테스트/문서만 존재. sanitizer·validation 강화·의존성·migration 없음.

### Documentation Check

[브라우저 검증 절차](C:/Users/user/IdeaProjects/CMS/docs/verification/admin-detail-rendering.md)와 구현이 일치한다. 문서의 9/24 결과는 과거 기록이며 위 결과는 이번 재실행이다.

### Remaining Risk

향후 새 평문 항목을 `html`로 잘못 넣는 회귀 가능성은 유지해야 할 테스트 대상이다. 현재의 다른 기능까지 전면 XSS 안전하다고 인증한 것은 아니다. rollback은 해당 렌더링 결함을 다시 연다.

### Result

**PASS**

## PR 2 — H-01 bootstrap과 로그인 상태 분리

### Planned Change

ADMIN + ACTIVE/LOCKED/PASSWORD_EXPIRED allowlist를 기동 skip 및 충돌 후 reconciliation에 동일하게 적용한다. 계정 자동 복구·기존 필드 변경 없이 신규 provisioning만 유지한다.

### Actual Implementation

[AdminBootstrapLoader:75](C:/Users/user/IdeaProjects/CMS/src/main/java/com/cms/admin/member/AdminBootstrapLoader.java:75)의 단일 allowlist를 `run()`과 [createOrReconcile():133](C:/Users/user/IdeaProjects/CMS/src/main/java/com/cms/admin/member/AdminBootstrapLoader.java:133)이 공유한다. [repository:39](C:/Users/user/IdeaProjects/CMS/src/main/java/com/cms/admin/member/repository/MemberRepository.java:39)는 역할과 상태 집합을 함께 검사한다. 충돌은 별도 transaction에서 같은 ID·ADMIN·allowlist를 재확인할 때만 흡수한다. 기존 계정에 setter/update를 호출하지 않는다.

### Plan Match

**Exact — 제품 정책 기준.** 영구 `AdminBootstrapStartupIntegrationTest`는 dev context에서 loader를 직접 호출하는 DB 시험이라는 제한이 있다. 실제 prod startup 증거는 따로 필요하며 이번에 보완했다.

### Evidence

Unit Test + MariaDB Integration + **Deployment Simulation / 실제 prod JAR startup**.

검증용 컨테이너 내부에서 상태별 독립 schema를 구성했다. bootstrap 변수를 비운 상태로 `CmsApplication.main()`이 실행되는 현재 JAR를 기동했다. prod 단독 활성화, 프로세스 생존과 반복 health를 확인하고, login/reset 전에 기존 `member` 전체 행을 전후 비교했다.

| 독립 DB 상태 | bootstrap 값 없는 기동 | 기존 전체 필드 | 새 ID 자격증명 추가 시험 |
|---|---|---|---|
| empty | bootstrap 실패 | 빈 상태 유지 | 성공, 새 ACTIVE ADMIN 1명 |
| MANAGER only | bootstrap 실패 | 불변 | 성공, 기존 MANAGER 불변 |
| ACTIVE ADMIN | 성공 | 불변 | 불필요 |
| auto LOCKED, 5분 | 성공 | 불변 | 불필요 |
| auto LOCKED, 40분 경과 | 성공 | **LOCKED/lockedAt 포함 불변** | 불필요 |
| manual LOCKED, lockedAt=null | 성공 | 불변 | 불필요 |
| PASSWORD_EXPIRED | 성공 | 불변 | 불필요 |
| DISABLED only | bootstrap 실패 | 불변 | 성공, 기존 DISABLED 불변 |
| DELETED only | bootstrap 실패 | 불변 | 성공, 기존 DELETED 불변 |
| ACTIVE + LOCKED | 성공 | 두 계정 불변 | 불필요 |
| LOCKED + PASSWORD_EXPIRED | 성공 | 두 계정 불변 | 불필요 |

비교에는 상태·이메일·password hash·lockedAt·passwordChangedAt·reset token·만료 시각을 포함했다. 실패 4건은 다른 설정 문제가 아니라 bootstrap 거절임을 로그로 확인했다. 신규 ID 시험 4건에서도 기존 행 전체가 동일했다. dev 기본 ADMIN은 만들지 않았다.

별도 합성 fixture로 실제 HTTP recovery도 수행했다.

| 상태 | 로그인 | 유효한 형식/해시의 token으로 reset | 최종 상태 |
|---|---|---|---|
| manual LOCKED | 거절 | 400 | LOCKED |
| auto LOCKED 미만료 | 거절 | 400 | LOCKED |
| auto LOCKED 만료 | 성공 | 이 시험에서 불필요 | 로그인 요청의 lazy unlock으로 ACTIVE |
| PASSWORD_EXPIRED | 거절 | 204 | reset 후 ACTIVE |
| DISABLED | 거절 | 400 | DISABLED |
| DELETED | 거절 | 400 | DELETED |

기존 unit 15개, bootstrap DB 15개, collision 1개 및 로그인·만료·reset·세션 관련 기존 테스트도 통과했다. 충돌의 LOCKED 허용/DISABLED 거절은 실제 DB 테스트가 있고, 나머지 역할·상태 분기는 unit과 코드로 확인했다. 모든 조합의 동시 충돌을 이번에 각각 prod JAR로 재현했다고 주장하지 않는다.

### Finding Closure

**H-01 CLOSED.** 기동 허용과 인증 허용의 분리가 실제 동작한다.

### Regression Check

미구성 fail-fast, 신규 ID 생성, 기존 폐기 계정 비부활, 잠금·만료 인증 제한 유지. 기존 살아있는 세션의 비밀번호 변경 복귀 경로는 기존 테스트로 확인하며, “reset만 전역적으로 유일한 복귀 수단”이라는 새 정책을 만들지 않았다.

### Scope Check

member count 우회, marker/table, provisioning subsystem, 인증 서비스 재작성, migration 없음.

### Documentation Check

deployment/compose/env/member 규칙의 allowlist는 일치한다. 다만 recovery drill 문서가 `AdminBootstrapStartupIntegrationTest`를 실제 prod 8상태 기동 증거처럼 읽히게 인용하는 것은 정정할 필요가 있다. 그 클래스 자체의 Javadoc은 제한을 올바르게 밝힌다.

### Remaining Risk

서로 다른 bootstrap ID를 사용하는 다중 인스턴스는 별도 ADMIN을 만들 수 있다는 기존 운영 계약은 그대로다. 이전 바이너리로 rollback하면 ACTIVE ADMIN 부재 시 다시 기동 실패할 수 있다. rollback 편의를 위한 계정 상태 변경은 권하지 않는다.

### Result

**PASS**

## PR 3 — M-03 오류 상태와 안전한 진단

### Planned Change

누락된 framework 예외만 좁게 매핑하고 기존 오류 형식·Security/HTML 경계를 유지한다. 예상 밖 500은 제한된 안전한 정보를 한 번 기록한다.

### Actual Implementation

[GlobalApiExceptionHandler:203](C:/Users/user/IdeaProjects/CMS/src/main/java/com/cms/common/api/GlobalApiExceptionHandler.java:203)에 400/405/415 및 승인된 406 handler가 있다. 405는 예외의 지원 메서드로 Allow를 구성한다. 기존 handlers는 `jsonError`로 JSON Content-Type을 명시한다. [catch-all:380](C:/Users/user/IdeaProjects/CMS/src/main/java/com/cms/common/api/GlobalApiExceptionHandler.java:380)은 method, route pattern/unmatched, exception class, 최대 10개 stack frame만 기록한다. Throwable/message/cause/raw request를 넘기지 않는다.

### Plan Match

**Exact.** 406·binding 실패 메시지 제한·JSON 응답 보장은 최신 승인 범위이지 unrelated change가 아니다.

### Evidence

Unit + **실제 SecurityConfig 포함 MVC** + 현재 prod JAR HTTP.

| 사례 | 이번 결과 | 검증 경로 |
|---|---|---|
| malformed JSON | 400 JSON_PARSE_ERROR | prod HTTP |
| validation | 400 VALIDATION_ERROR | prod HTTP |
| invalid numeric path | 400 INVALID_REQUEST | prod HTTP |
| unauthenticated | 401 UNAUTHORIZED | prod HTTP |
| MANAGER 인가 / CSRF | 403 ACCESS_DENIED | browser/prod HTTP |
| resource missing | 404 RESOURCE_NOT_FOUND | prod HTTP |
| unsupported method | 405 METHOD_NOT_ALLOWED | prod HTTP, Allow=`GET,DELETE,PATCH` |
| duplicate | 409 DUPLICATE_RESOURCE | prod HTTP |
| unsupported media | 415 UNSUPPORTED_MEDIA_TYPE | prod HTTP |
| rate limit | 429 RATE_LIMITED | prod HTTP, limiter 활성 |
| unexpected failure | 500 INTERNAL_ERROR | 실제 Security 필터를 포함한 MVC stub controller |
| unacceptable response | 406 NOT_ACCEPTABLE | Security+MVC |

prod `/does-not-exist-verification`은 HTML 404였다. 500을 만들기 위해 제품 코드를 바꾸거나 운영 DB 장애를 일으키지 않았다. 기존 `ApiErrorContractIntegrationTest` 7개와 handler 11개가 이번 실행에서 통과했다. 로그 단위 시험은 ERROR 정확히 1회, exception/cause의 비밀번호·token 표식 비노출을 검증한다. raw body/header/cookie/query를 읽지 않는 것은 코드 흐름도 확인했다.

### Finding Closure

**M-03 CLOSED.** 확인된 세 client error 오분류와 catch-all 진단 공백이 해소됐다.

### Regression Check

Security 401/403 선행 처리를 advice가 덮지 않는다. PublicWebExceptionAdvice의 높은 우선순위/패키지 제한은 변경하지 않았다. 기존 HTML, JSON parse, validation, conflict, limiter 테스트도 통과했다.

### Scope Check

예외 architecture 전면 재작성·새 logging framework 없음.

### Documentation Check

API conventions와 troubleshooting에 실제 매핑·안전 로그 계약이 반영됐다.

### Remaining Risk

로그 검증은 이 catch-all의 계약이다. Hibernate, Security, 별도 public advice 등 모든 logger가 항상 하나만 출력한다고 확장하지 않는다. 최종 Gate E에서는 실제 배포 로그 수집 경로까지 확인한다.

### Result

**PASS**

## PR 4 — M-04 메뉴 행 잠금

### Planned Change

일반 수정도 최초 대상 조회부터 기존 pessimistic row lock을 사용한다. 잠금 후 최신 값으로 null 필드를 보존한다.

### Actual Implementation

[@Transactional updateMenu():76](C:/Users/user/IdeaProjects/CMS/src/main/java/com/cms/admin/menu/service/MenuService.java:76)의 첫 repository 조회가 `findByIdForUpdate`다. 이후 null 보존 값을 계산한다. `deactivateMenu()`도 같은 row lock을 쓴다. 현재 controller→service 경로에 대상 entity의 선행 일반 조회는 없다. 새 부모 이동/전체 tree locking은 추가하지 않았다.

### Plan Match

**Minor deviation — 제품은 일치, 영구 테스트의 순서 보증은 축소됨.**

### Evidence

Static Code Trace + Unit/MVC + **Actual MariaDB Concurrency / 실제 API**.

기존 `MenuConcurrencyIntegrationTest` 3개는 모두 통과했다. 추가로 현재 prod JAR의 실제 메뉴 API에서 두 순서를 고정했다. 격리 DB의 시험용 trigger만으로 첫 transaction의 UPDATE 직전 대기를 만들었으며 제품 코드는 바꾸지 않았다. 별도 connection이 소유한 named lock을 gate로 사용했다. 두 번째 요청은 실제 `INNODB_LOCK_WAITS`와 transaction/connection ID로 확인한 후 gate를 해제했다. Future 미완료나 고정 sleep만으로 잠금을 추정하지 않았다.

| 순서 | 첫 connection / 대기 connection | 실제 대기 query | 응답 | 최종 값 |
|---|---|---|---|---|
| A update 선행 → B deactivate | 4 / 3 | SELECT … FOR UPDATE | A 200 → B 204 | 이름 `Updated A-first`, useYn=false |
| B deactivate 선행 → A update | 3 / 4 | SELECT … FOR UPDATE | B 204 → A 200 | 이름 `Updated B-first`, useYn=false |

첫 transaction이 row lock을 보유한 상태와 두 번째 transaction의 row wait를 관측했다. 그 lock이 해제돼야 다음 쓰기가 진행하므로 직렬화 순서도 확인된다. 양쪽 모두 성공했고 deadlock/timeout을 정상 성공으로 흡수하지 않았다. fixture trigger와 메뉴 행은 finally에서 제거했으며 종료 시 trigger 수 0을 재확인했다.

### Finding Closure

**M-04 CLOSED — 승인된 useYn 생략/null 요청 범위.**

### Regression Check

기존 부모 비활성화/자식 재활성화 DB 시험, 메뉴 null 필드/role/ord 보존 unit, controller·sidebar·member concurrency 회귀가 통과했다. 부모 비활성화 vs 자식 생성, 같은 행의 서로 다른 일반 필드 수정, timeout/409 후 전체 rollback을 각각 결정적으로 고정하는 추가 실기는 이번에 전부 수행하지 않았다. Gate D에서 구분해서 보완해야 한다.

### Scope Check

@Version, optimistic locking 전환, migration, read-only 경로 잠금, 전체 tree rewrite 없음. 기존 child→parent 재활성화와 parent만 잠그는 비활성화 구조를 새 전략으로 바꾸지 않았다.

### Documentation Check

stale-form의 명시적 `useYn=true` 재전송은 문서에 제외 범위로 정확히 기록돼 있다. 다만 barrier 시험의 양방향 보증을 과장한 설명은 PR4-T1에 따라 정정한다.

### Remaining Risk

같은 row의 쓰기 대기는 의도된 비용이다. 현재 경로의 lock을 future caller가 이미 로드한 stale persistence context에도 무조건 refresh한다고 일반화하면 안 된다. rollback은 기존 lost update를 다시 연다.

### Result

**PASS WITH MINOR FOLLOW-UP** — 제품 추가 변경이 아니라 영구 회귀 하니스/증거 설명 보강.

## PR 5 — M-02 SMTP timeout

### Planned Change

prod SMTP connection/read/write timeout만 추가하고 기존 token cleanup과 async 구조를 유지한다.

### Actual Implementation

[application-prod.yml:28](C:/Users/user/IdeaProjects/CMS/src/main/resources/application-prod.yml:28)의 세 속성은 각각 `mail.smtp.connectiontimeout`, `mail.smtp.timeout`, `mail.smtp.writetimeout`이다. 기본값은 10000/30000/30000ms다. [compose:44](C:/Users/user/IdeaProjects/CMS/docker-compose.prod.yml:44)는 세 환경변수를 명시적으로 전달하며 `:-` fallback을 쓴다. `.env.example`과 `_prod-env-guard.sh`도 세 키를 포함한다.

### Plan Match

**Exact.**

### Evidence

Configuration Inspection + 실제 Spring Bean + **SMTP Fault Test**.

- prod mail configuration 테스트 **17개**, local SMTP fault 테스트 **3개** 모두 이번에 통과.
- fault는 greeting 무응답, STARTTLS handshake 정지, negotiation 후 write backpressure다. 300ms 시험값을 쓰며, write 시험은 90초 read timeout/작은 송신 버퍼/큰 본문으로 read timeout에 가려지는 것을 제한한다.
- 실제 prod Spring 컨텍스트를 격리 DB로 기동해 `JavaMailSenderImpl.getJavaMailProperties()`에서 **10000/30000/30000** 확인.
- 같은 실제 mail Bean으로 loopback SMTP 정상 전송을 수행했고 fixture가 DATA 수신을 확인했다. 외부 이메일 발송 없음. 이 정상 시험은 실제 외부 STARTTLS 인증 검증은 아니다.
- 실제 Compose config 해석에서 빈 timeout 입력은 **10000/30000/30000**, 합성 override는 **5000/15000/20000**으로 전달됨을 확인했다. 실제 운영 `.env.prod`나 secret은 읽지 않았다.
- `.env.prod`→guard→compose→Spring placeholder→mail Bean의 코드 전달 경로를 대조했다. 최종 실제 운영 `.env.prod`와 컨테이너를 한 번에 관통하는 인증은 Gate F에 남긴다.

### Finding Closure

**M-02 CLOSED — 승인된 prod socket timeout 범위.**

### Regression Check

send 실패의 조건부 token cleanup, 최신 token 보호, reset 응답·만료 기존 테스트 통과. 별도 실제 reset recovery도 유지됨을 확인했다.

### Scope Check

dedicated executor, broker, retry queue, 새 async infrastructure 없음. 메일 서비스 제품 코드는 변경하지 않았다.

### Documentation Check

10/30/30초는 초기값으로 설명되며, 단위는 ms다. 잘못된 값은 Spring String map에서 자동 검증되지 않는다는 운영 책임도 기록돼 있다. 직접 JAR의 빈 변수는 Compose의 `:-`와 동일하다고 가정하지 않는다.

### Remaining Risk

실제 connect blackhole/delay는 이번에도 별도로 결정적 재현하지 않았다. 속성 반영을 connect fault 성공으로 표기하지 않는다. DNS/전체 작업 deadline/지속적인 작은 응답은 socket timeout의 보장 범위 밖이다. Gate F에는 최종 운영 override 검증, negotiation 후 read 정지, 실제 staging SMTP 정상 지연 확인이 남는다. rollback 시 timeout 제거보다 여유 있는 유한값 조정을 우선한다.

### Result

**PASS**

## PR 6 — M-01/M-05 운영 계약

### Planned Change

quiesced를 정규 recovery backup으로 채택하고 online/drill 경로를 분리한다. 실패·재개 책임과 복원 검증을 명확히 한다. ingress는 제품 중립 계약만 만들고 실제 경계 인증은 미완료로 남긴다.

### Actual Implementation

정규 quiesced/보조 online 구분, 일일 수동 실행, 세 BACKUP_DIR 분리, 같은-host 한계, RPO 목표와 실제 시점 구분, 복구 후 참조 파일 확인, ingress 10개 체크리스트가 문서화됐다. backup/restore scripts와 실제 IP 처리는 변경하지 않았다.

### Plan Match

**Minor deviation — 문서의 실행 가능한 실패 절차가 일부 누락됨.**

### Evidence

Static Code Trace + Configuration Inspection + **안전한 런북 실패 흐름 시뮬레이션**. 과거 drill 문서는 증거 검토만 했다.

- backup은 transaction DB dump 후 파일 tar다. 정지한 앱 및 다른 writer 부재가 일관성의 전제다.
- restore script는 파괴적 단계 이후 실패 시 앱을 정지시키고 안전 백업을 안내한다. 이 방어는 현재도 존재한다.
- [recovery-drill.md](C:/Users/user/IdeaProjects/CMS/docs/verification/recovery-drill.md)는 9/27의 synthetic 공개/비공개 공지·첨부·프로필 변경 후 복원, 전체 3파일 hash/UID:GID, 다운로드 인가, Flyway validate, checksum 형식 손상 사전 거절을 기록한다. health만 보고 성공한 기록은 아니다.
- 다만 해당 기록에는 복원 후 앱 사용자 **새 파일 쓰기·삭제**, 전체 RTO 실측값, 파괴적 단계 이후 강제 실패의 실제 실행 증거가 없다. 이전 문서의 완료 문구로 이 빈칸을 채우지 않는다.
- 그 drill의 로컬 disposable stack 사용은 최신 승인된 한시적 격리 정책과 일치한다. 실배포 후에는 별도 daemon/VM이 필요하다. 원격 Docker context만 바꾸는 검증은 권하지 않는다.
- [deployment-edge.md](C:/Users/user/IdeaProjects/CMS/docs/verification/deployment-edge.md)의 TLS 위치, backend 차단, proxy 신뢰·헤더 재작성, 두 실제 client IP, 감사 IP, scheme/cookie, HTTPS redirect, reset URL origin 체크는 모두 있고 **미검증**이라고 명시한다.

### Finding Closure

- **M-01: OPERATIONAL CONTRACT NOT READY.** 주된 방향은 맞지만 PR6-R1 보완 전 정규 백업 절차 완료 판정을 보류한다.
- **M-05: DEPLOYMENT CONTRACT READY / runtime verification pending.**

### Regression Check

script 동작의 변경은 없다. 정규·보조·drill 보존 경로 분리도 의도에 맞다. 다만 문서만 되돌려도 실제 restore 데이터가 원복되는 것은 아니다.

### Scope Check

새 snapshot infrastructure, storage migration, generic IP abstraction, 실제 ingress 제품 설정 없음. 승인된 관련 백업/ingress 로드맵 항목의 문서 통합은 unrelated 변경으로 보지 않는다.

### Documentation Check

PR6-R1 외 다음 기록은 정정/완료 범위 구분이 필요하다.

- plan 상단·일부 실행 기록의 PR 미병합/미완료 표현은 현재 #37~#42 이력과 다르다. 과거 실행 시점 설명은 보존하되 현재 상태와 구분한다.
- PR 4의 barrier 동등성 설명은 약화해야 한다.
- recovery 문서가 참조하는 bootstrap DB test는 prod JAR startup 자체를 검증하지 않는다.
- RTO를 drill의 실측값에 연결하지만 해당 drill에는 전체 stop→검증 완료 구간의 수치가 없다. 없는 값을 추정해 채우지 않는다.

### Remaining Risk

실제 restore/외부 ingress 인증은 다음 단계다. 현재 운영 데이터에 destructive drill을 실행하지 않았다. 런북 수정 없이 Gate G를 통과시켜서는 안 된다.

### Result

**PARTIAL** — M-05 계약은 완료, M-01 런북은 보완 필요.

## 전체 diff / Scope Check

`f890195..HEAD` 35개 파일을 변경 목적별로 분리했다.

| 구분 | 확인 결과 |
|---|---|
| Intended | 템플릿, bootstrap/repository, API advice, menu service, SMTP YAML/compose/guard, 백업·ingress 런북 |
| Supporting | 해당 테스트, fixture, verification 문서, 규칙 문서, 계획·감사 이력, backup 디렉터리 gitignore |
| Unrelated | 확인된 unrelated 제품 변경 없음. 기존 사용자 dirty roadmap/portfolio는 이번 PR 변경 판정에서 제외 |

build.gradle·기존 Flyway migration·Entity version·Clock/main·executor·backup/restore 제품 scripts에 승인되지 않은 변경이 없다. Redis/Kafka/Kubernetes/API Gateway/S3/frontend framework/sanitizer/new dependency도 없다. diff whitespace 검사 통과.

## Test Quality / 이번 실행 기록

### 실행한 명령 및 결과

`gradlew.bat clean compileJava compileTestJava test bootJar --console=plain`

- BUILD SUCCESSFUL, 7 tasks 실행.
- JUnit XML 77개 suite, tests 763 / failures 0 / errors 0 / skipped 1.
- skip: `LocalDiskFileStorageTest.load_symlinkEscape_rejected` — Windows의 심볼릭 링크 생성 제약. Linux 결과를 이번에 실행했다고 주장하지 않는다.
- Flyway 11개 migration 적용/validate 확인. 신규 migration 필요 없음.
- 신규 mail 설정 시험의 deprecated `PropertyPlaceholderHelper` 경고 및 bootstrap unit의 unchecked 경고가 있지만 compile 실패는 아니다. 이번 remediation 범위를 확대해 정리하지 않는다.

| 핵심 시험 | 이번 결과 | 증명하는 층 |
|---|---|---|
| AdminMemberTemplateConventionTest | 3 통과 | 보조 static convention |
| AdminBootstrapLoaderTest | 15 통과 | 역할·상태·충돌 unit |
| AdminBootstrapStartupIntegrationTest | 15 통과 | 실제 MariaDB, loader 직접 호출 |
| AdminBootstrapConcurrencyIntegrationTest | 1 통과 | 실제 unique collision |
| ApiErrorContractIntegrationTest | 7 통과 | Security 포함 MVC |
| GlobalApiExceptionHandlerTest | 11 통과 | 상태·안전 로그 unit |
| MenuConcurrencyIntegrationTest | 3 통과 | 실제 MariaDB, 한계는 PR4-T1 |
| ProdMailTimeoutConfigurationTest | 17 통과 | YAML/compose 정합성 및 mail auto-config |
| PasswordResetMailTimeoutIntegrationTest | 3 통과 | 실제 loopback socket fault, repository는 mock |
| 별도 browser | 통과 | 실제 prod JAR/DB/DOM/clipboard/인가/CSRF |
| 별도 startup | 11 상태 + 신규 provisioning 4건 통과 | 독립 schema + prod JAR |
| 별도 M-04 실기 | A-first/B-first 통과 | 실제 API + InnoDB lock-wait |
| 별도 mail probe | prod 속성/정상 수신 통과 | 실제 mail Bean + loopback SMTP |

관련 기존 member/menu/security/reset/controller/HTML/limiter 시험은 전체 스위트에 포함돼 실행됐다. “모두 실제 E2E”라고 표현하지 않는다.

### 검증 도구 오류와 제품 오류 구분

추가 recovery 하니스는 처음에 로그인 실패 URL을 잘못 예상했고, 로그인 페이지의 CSRF를 meta로 오인했으며, 짧은 fixture token을 넣어 DTO 검증에서 400을 받았다. 각각 현재 `/admin/login-error`, hidden `_csrf`, 64자 hex token 계약에 맞춰 **하니스만** 보정한 뒤 최종 6상태 요청 시험을 통과했다. 앞선 잘못된 token의 400은 계정 상태 거절 증거로 사용하지 않았다. 제품 코드 변경은 없다.

Docker가 시작돼 있지 않아 Docker Desktop을 기동한 뒤 Testcontainers를 실행했다. Testcontainers 자원은 종료 후 정리됐다. 추가 실기는 `cms-verify-20260928` 전용 컨테이너와 loopback 44018/44019 앱만 사용했다. 임시 trigger 0, recovery fixture 0을 확인한 후 앱 PID와 컨테이너 ID/label을 대조해 종료했다. **합성 데이터 전용 일회용 DB는 제거됐으며 복구 대상으로 보존하지 않는다.** 기존 `cms-app-dev` / `cms-db-dev`는 종료·데이터 수정하지 않았다.

생성된 build/test 결과와 브라우저 캡처는 git 제외 산출물이다. 기존 PR 1의 generated 하니스를 메모리에서 재사용했고 제품/영구 테스트 파일로 추가하지 않았다.

## Regression / Rollback

| 변경 | 현재 검증 | rollback 주의 |
|---|---|---|
| PR 1 | UI/인가/CSRF 보존 | 되돌리면 C-01 재개 |
| PR 2 | 기동/계정 불변/인증 제한 | 이전 버전은 ACTIVE ADMIN 없으면 기동 실패 가능 |
| PR 3 | 상태·JSON·HTML·안전 로그 | 되돌리면 client error 500 및 진단 공백 재개 |
| PR 4 | 양방향 DB 직렬화 | 되돌리면 lost update 재개; 테스트 보완 필요 |
| PR 5 | 유한값·실제 fault·정상 전송 | timeout 삭제보다 유한값 조정 우선 |
| PR 6 | 계약 대부분 일치, 런북 실패 누락 | 문서 revert와 이미 실행한 restore 복구는 별개 |

스키마/data transformation이 없으므로 PR 1~5는 바이너리/설정 rollback이 가능하다. 그렇다고 이전 결함까지 안전해지는 것은 아니다.

## 최종 결과 표

| PR | Finding | Plan Match | Finding Closed | Tests | Final Result |
|---|---|---|---|---|---|
| PR 1 | C-01 | Exact | Yes | 실제 browser 포함 PASS | PASS |
| PR 2 | H-01 | Exact, 시험 층 구분 | Yes | DB/unit + prod 11상태/신규 4건 + recovery PASS | PASS |
| PR 3 | M-03 | Exact | Yes | Security+MVC / HTTP / 안전 로그 PASS | PASS |
| PR 4 | M-04 | Minor deviation | Yes, 승인 범위 | 실제 양방향 PASS, 영구 하니스 보완 | PASS WITH MINOR FOLLOW-UP |
| PR 5 | M-02 | Exact | Yes, prod socket 범위 | properties / socket fault / 정상 SMTP PASS | PASS |
| PR 6 | M-01 / M-05 | Minor deviation | M-01 No / M-05 계약 Yes | 런북 실패 누락 재현, restore/ingress 재실행 아님 | PARTIAL |

## Finding 기준 최종 상태

| Finding | 최종 상태 |
|---|---|
| C-01 | CLOSED |
| H-01 | CLOSED |
| M-02 | CLOSED |
| M-03 | CLOSED |
| M-04 | CLOSED — useYn 생략/null 범위 |
| M-01 | OPERATIONAL CONTRACT NOT READY — PR6-R1 |
| M-05 | DEPLOYMENT CONTRACT READY — runtime pending |
| M-06 | REJECTED / NO ACTION — 제품 변경 없음 |

## 구현 후 남은 blocking issue

### Must Fix Before Gates

**PR 6 재개: PR6-R1 런북 실패·원래 상태·재개 책임을 보완하고 안전한 실패 시뮬레이션을 통과시킨다.** 정규 백업 성공과 앱 health 성공을 분리한다. 새로운 script 플랫폼이나 제품 리팩터링은 필요 없다.

### Minor Follow-up Before Gates

- PR4-T1: 동시성 시험의 예상 예외/cleanup을 좁게 보완하고, 두 순서의 결정적 증거를 영구 시험 또는 재현 가능한 검증 절차로 남긴다. barrier가 두 순서를 보장한다는 문구는 정정한다.
- current plan의 상태 요약, bootstrap 시험 층 인용, recovery의 미수행/미기록 항목을 현재 사실과 맞춘다. 과거 audit 전체를 최신 사실처럼 덮어쓰지 않는다.

### Gate-only Validation / 운영 준비 공백

이번 검증은 Gate A~H의 전체 인증이 아니다. 아래를 이번 통과 결과로 대체하지 않는다.

| Gate | 남은 검증 |
|---|---|
| A | Linux CI와 Windows symlink skip 보완, 최종 RC/복원본 validation |
| B | 최종 RC session/reset 전체 계약 및 browser 재확인 |
| C | 최종 이미지/env의 동일 상태 행렬, collision/복구 증거 연결 |
| D | PR4-T1 반영 및 부모/자식 생성·일반 수정끼리·timeout/409 rollback의 결정적 추가 시험 |
| E | 최종 수집 로그의 민감 표식/중복 확인, public HTML 500/429 등 전체 경계 |
| F | 실제 운영 env→컨테이너 mail Bean, 잘못된 override 차단 판단, negotiation 후 read stall, staging SMTP |
| G | 수정된 런북으로 격리 restore; 복원 후 앱 사용자 쓰기/삭제, 완전한 hash 증거·RTO, 파괴적 단계 이후 실패 시 정지 확인 |
| H | 실제 topology에서 두 외부 IP, spoofing, trusted proxy, TLS/scheme/cookie/reset origin. 현재 전부 runtime pending |

실제 ingress가 없는 것을 새 외부 취약점으로 과장하지 않지만, 이 상태를 외부 production-ready라고 선언하지 않는다. destructive restore는 명시적 승인과 격리 환경에서만 한다.

## Final Verdict

**FIX REQUIRED BEFORE GATES.**

재개 대상은 **PR 6의 운영 문서**다. PR 4에는 작은 시험·기록 보완이 필요하다. 확인된 제품 finding 5개를 다시 전면 구현할 이유는 없다.

deploy-check 보조 판정은 **needs-attention**이다. 새로운 Critical/High 제품 결함이 아니라 운영 계약 및 검증 증거의 좁은 공백이다.

지금은 최종 Gate 인증에 진입하거나 배포 가능을 선언하지 않는다. 위 보완을 확인한 뒤 다음 단계는 **Gate A~H Production Readiness Verification**이다.
