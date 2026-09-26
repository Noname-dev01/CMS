# REMEDIATION PLAN

작성일: 2026-09-23

기준 HEAD: `f89019556a3eff0fe500586255793aae0fff3a85`

최종 판단 근거: [INDEPENDENT VERIFICATION REVIEW](deploy-check-2026-09-23.md). [PROJECT TECHNICAL AUDIT](deploy-check-2026-09-22.md)은 배경 및 과거 판정 이력으로만 사용한다.

상태: **PR 1·PR 2 구현·검증·커밋·PR·머지 완료(PR 1 2026-09-24 `9294af5` #37, PR 2 2026-09-24 `eaaccb6` #38) / PR 3 구현·검증 완료(2026-09-26, `fix/api-error-contract` 브랜치 · 커밋·PR·머지 전) / PR 4~6 구현 미착수**. H-01 A안 정책은 2026-09-23 승인대로 유지한다. 사용자가 PR 1·PR 2·PR 3 구현을 각각 승인해 순서대로 완료했다. 전체 remediation 구현·배포 승인을 의미하지는 않는다.

개정 이력:

- v10 변경(2026-09-25): PR 3(M-03) 계획 리뷰 2라운드(codex CLI, v9 수정 검증) — **ship 판정.** 406 handler 추가가 기존 handler 우선순위와 충돌하지 않음(`Exception` catch-all보다 구체적 handler 우선), `FieldError.isBindingFailure()`로 타입 변환 실패를 문구·언어에 의존하지 않고 신뢰성 있게 판별 가능(`MethodArgumentNotValidException` 경로도 같은 `buildValidationMessage()`를 공유해 함께 보호됨), 오류 `ResponseEntity`에 JSON Content-Type을 직접 지정하는 접근이 유효함(단 `@ExceptionHandler(produces=...)`로 handler 선택 자체를 제한하는 방식은 `Accept: text/html`에서 handler 미선택 문제를 만들 수 있어 대체 수단으로 사용 금지 — Implementation Steps에 반영) 확인. 비차단 지적 1건(수용) — 6절 오류 행렬·Gate E·Final Execution Checklist가 아직 406·확장 범위(JSON Content-Type 전체 보장·BindException 비노출)를 반영하지 않아 PR 3 본문과 동기화했다. 이로써 PR 3 계획 리뷰 루프 종료 — 구현 착수는 별도 사용자 확인 필요.
- v9 변경(2026-09-25): PR 3(M-03) 구현 착수 전 계획 리뷰 1라운드(codex CLI, 실제 코드 대조) — needs-attention, 지적 3건 전부 수용. (1) `HttpMediaTypeNotAcceptableException`(406, Accept 협상 실패)이 계획의 세 handler에 빠져 catch-all(500)로 새는 구멍 발견 → 4번째 handler로 추가. (2) "기존 JSON Content-Type 유지"가 실제 코드(기존 handler들이 Content-Type 미지정)와 맞지 않음 → API 분기 전체의 JSON Content-Type 보장을 범위에 포함, 회귀 테스트를 "HTML 아님" 대신 "상태+Content-Type+4필드 본문" 동시 검증으로 강화. (3) 기존 `BindException` 처리(`buildValidationMessage()`)가 타입 변환 실패 메시지를 그대로 노출해(예: enum 필드에 임의 문자열 입력 시 그 값이 메시지에 반영) 이번 PR의 "안전한 진단·민감정보 미노출" 완료 기준과 충돌 → 사용자 확인 후 PR 3 범위에 포함, 타입 변환 실패 필드 오류만 고정 문구로 대체(일반 Bean Validation 문구는 유지). Target Behavior·Implementation Steps·Tests to Add·Completion Criteria 갱신.
- v8 변경(2026-09-25): `/updateRoadmap` 사실확인 후 PR 1·PR 2의 커밋·PR·머지 완료를 문서에 반영. PR 2는 커밋 `eaaccb6` "보안: ADMIN bootstrap 기동 조건을 상태 allowlist로 확장 (감사 H-01) (#38)"로 머지됨(`gh pr view 38` state=MERGED·mergedAt=2026-09-24T11:52:37Z, `gh pr checks 38` test pass 2m0s). PR 1은 `9294af5` #37(기존에 이미 반영됨). Final Execution Checklist의 PR 2 두 항목을 `[x]`로 갱신.
- v7 변경(2026-09-24): `/suggestRoadmap` → PR 2 선택 → 계획 리뷰 ship(v5·v6) → 사용자 구현 승인 순으로 PR 2(H-01) 구현. `MemberRepository.existsByUserTypeAndStatusIn` 신규(기존 `existsByUserTypeAndStatus` 대체·삭제), `AdminBootstrapLoader`의 존재 질의·재조회 흡수 조건을 `ELIGIBLE_STATUSES={ACTIVE,LOCKED,PASSWORD_EXPIRED}`로 통일. 신규 테스트 30개(단위 7·Testcontainers 통합 15, 기존 클래스 확장 포함) 전부 통과, `./gradlew test` 전체 720개 통과. 실제 prod JAR + 별도 일회성 MariaDB로 "LOCKED 관리자만 있어도 부트스트랩 변수 없이 정상 기동"을 실기 확인(사용자 dev 스택은 건드리지 않음). `docs/deployment.md`·`.env.example`·`docker-compose.prod.yml`·`com.cms.admin.member/CLAUDE.md` 문서 동기화. 상세는 PR 2 섹션의 "PR 2 실행 기록" 참조.
- v6 변경(2026-09-24): PR 2(H-01) 계획 리뷰 2라운드(codex CLI, v5 수정 검증) — **ship 판정.** 1라운드 두 지적(동시성 보장 범위, EXPIRED 복귀 경로 문구)이 실제 코드와 대조해 해소됐음을 재확인. 비차단 지적 1건(수용) — "재조회 전 상태 변경" 테스트 문구가 넓어 구현 시 기대 결과가 불명확할 수 있다는 지적에 따라, Tests to Add의 해당 항목을 3가지 구체 사례(exists=true 직후 DISABLED 전환→skip 유지/exists=false 이후 같은 ID 생성→재조회 기준 흡수/충돌 후 재조회 전 DISABLED 전환→실패, LOCKED·EXPIRED는 allowlist에 따라 흡수)로 세분화했다. 이로써 PR 2 계획 리뷰 루프 종료 — 구현 착수는 별도 사용자 확인 필요.
- v5 변경(2026-09-24): PR 2(H-01) 구현 착수 전 계획 리뷰 1라운드(자체 적대적 리뷰, codex CLI로 실제 코드·기존 테스트 대조) 수용 2건 반영 — (1) bootstrap 존재 질의(`existsByUserTypeAndStatusIn` 등)와 INSERT가 원자적 한 동작이 아니므로, "전역적으로 신규 ADMIN 한 명만 생성된다"고 서술하지 않고 "존재 질의 실행 시점의 판정"이며 "동일 설치의 모든 인스턴스가 같은 bootstrap 자격증명(같은 ID)을 쓴다"는 운영 전제 위에서 안전함을 명시. 기존 `AdminBootstrapConcurrencyIntegrationTest`가 `createOrReconcile()`을 순차 2회만 호출해 병렬 존재확인·재조회 전 상태 변경을 검증하지 못하는 공백을 Tests to Add에 명시. (2) "EXPIRED는 적격 reset 성공 후에만 ACTIVE 복귀"라는 문구가 실제 코드(`Member.changePassword()`가 일반 비밀번호 변경으로도 EXPIRED→ACTIVE를 수행, `PasswordExpiryIntegrationTest`가 이미 검증)와 모순되어 "bootstrap은 만료 상태를 변경하지 않는다. reset 시나리오에서는 적격 reset 성공 후 ACTIVE로 복귀하며, 기존 내 비밀번호 변경 경로의 만료 해소도 유지한다"로 한정. 부가로 상태표에 `DISABLED ADMIN + ACTIVE MANAGER/USER`(적격 ADMIN 없음→bootstrap 변수 필요) 사례, "same ID DISABLED/DELETED 충돌 실패"에 "다른 적격 ADMIN이 없을 때"라는 전제, 자동 잠금 만료+비밀번호 만료 동시 발생(`CustomUserDetailsService`가 잠금 해제 다음에 만료 판정) 테스트를 Tests to Add에 추가. D-01 정책(대안 A) 자체는 변경 없음 — 정책 서술의 정확도·테스트 공백만 보완.
- v4 변경(2026-09-24): PR 1만 사용자 승인 후 구현. 상세 평문/이메일 markup 분리, 신규 회귀 14개, 관련 168개 통과, 전체 698개 통과·기존 symlink 1개 skip, 실제 prod JAR 브라우저 회귀 통과. 다른 PR 정책·범위는 변경하지 않음. PR 1 실행 기록 및 검증 문서 추가.
- v1: 현재 HEAD 재탐색, 6개 PR 경계, 최소 변경안, 회귀 테스트 및 배포 Gate 작성. 제품 코드·설정·기존 테스트·과거 보고서는 수정하지 않음.
- v2 변경: 자체 적대적 리뷰 R-01/R-02 수용 — 복원 스크립트의 localhost health와 격리 VM 실행 위치를 일치시키고, 메뉴 잠금 회귀에 실제 DB 대기 관측을 명시. D-01의 수동 LOCKED 기동 정책은 사용자 결정 필요로 유지. 최종 구현 승인은 아직 없음.
- v3 변경: 사용자 승인에 따라 D-01의 H-01 A안 확정. `ROLE_ADMIN` + `ACTIVE / LOCKED / PASSWORD_EXPIRED` 존재 시 기동 허용, 수동 LOCKED 포함 기존 계정 필드 불변, DISABLED/DELETED-only 또는 ADMIN 부재 시 신규 bootstrap 요구를 확정했다. 정책 승인 체크만 완료하고 구현·테스트·운영 실행은 미완료로 유지한다.

## 1. Executive Summary

이번 목적은 확정된 C-01/H-01/M-03/M-04를 작은 코드 변경으로, M-02를 SMTP timeout 설정으로 해결하는 것이다. M-01/M-05는 운영 계약으로 다루고, REJECTED인 M-06은 제품 수정에서 제외한다.

**계획 작성 및 PR 1 착수 직전에는 2차 보고서와 대상 구현의 drift가 없었다.** 기준 HEAD와 대상 소스·설정·테스트를 재확인했다. 아래 표와 각 PR의 Current Behavior는 구현 전 기준선이며, 2026-09-24 PR 1 결과는 해당 PR 아래 실행 기록을 따른다. 기존 작업 트리의 로드맵 변경, 미추적 감사 문서·portfolio는 그대로 보존했다.

| 항목 | 이번에 다시 확인한 현재 코드 | 계획 결론 |
|---|---|---|
| C-01 | `admin-manage.html`의 목록·상단 요약은 escape, 상세 `detailItems`의 아이디·이름은 raw 값이며 `innerHTML`에 연결 | 현재 문자열 렌더링을 유지하고 평문/의도된 마크업 경계를 좁게 수정 |
| H-01 | `AdminBootstrapLoader.run()`이 ADMIN+ACTIVE만 검사. 로그인·reset의 lazy unlock은 별도 요청 경로 | DB의 역할·상태 allowlist로 기동 조건을 분리하는 A안 정책 확정. 구현은 미착수 |
| M-03 | catch-all이 미분류 예외를 500으로 반환하고 진단 로그 없음. public advice·Security는 별도 경계 | 누락된 MVC 예외 4종(v9: 406 추가) 및 안전한 500 진단, API 전체 JSON Content-Type 보장, BindException 타입 변환 메시지 비노출까지 추가 |
| M-04 | 이름 수정은 `findById`, 비활성화는 `findByIdForUpdate`. 부모 변경 API는 없음 | 일반 수정도 최초 조회부터 같은 대상 행 잠금. 부모 경합을 실제 DB로 재검증 |
| M-02 | prod SMTP auth/STARTTLS만 설정. timeout 없음. 발송 실패 시 조건부 token 정리 있음 | 3개 timeout과 설정 전달·검증만 변경 |
| M-01 | DB dump 후 tar. 스크립트의 prod 컨테이너·volume 이름 고정. 정지 백업 절차 존재 | 정규 recovery backup으로 quiesced 모드 추천. 전용 격리 Docker daemon에서 복원 훈련 |
| M-05 | 앱 host 게시 `127.0.0.1:8080`, 실제 ingress 없음. limiter는 remoteAddr, 감사는 전달 헤더도 사용 | 제품별 proxy 설정을 미리 만들지 않고 배포 체크리스트 정의 |
| M-06 | 실제 main의 KST 고정 유지 | 변경 없음 |

Java 17 / Spring Boot 3.5.16 / MariaDB 10.11 / 기존 Flyway와 Testcontainers를 유지한다. **추천안 전체에 신규 DB migration·제품 dependency·인프라가 필요 없다.**

최초 계획 수립 단계에서는 정적 재확인만 수행했고 새 구현·테스트는 하지 않았다. 이후 PR 1의 실제 구현·검증 결과는 별도로 기록했다. 이전 독립 검증의 51개 테스트 통과를 PR 1의 새 실행 결과로 재사용하지 않는다.

## 2. Scope

### Fix

- C-01: 관리자 상세의 평문 안전 렌더링.
- H-01: 계정의 로그인 상태와 초기 ADMIN 구성 필요 여부 분리.
- M-03: 400/405/415/406 매핑 및 catch-all 500의 안전한 진단, API 전체 JSON Content-Type 보장, BindException 타입 변환 메시지 비노출.
- M-04: 같은 메뉴에 대한 일반 수정/비활성화의 동시성 규칙 일관화.
- M-02: prod SMTP connection/read/write timeout 설정 및 적용 확인.

### Operational / Documentation

- M-01: online과 quiesced recovery backup의 용도·실패 처리·격리 복구 훈련.
- M-05: 실제 ingress 결정 이후의 proxy/TLS/client IP 검증 계약.
- 변경된 bootstrap/API/SMTP 계약에 직접 관련된 문서만 현행화.

### Explicitly Excluded

M-06 수정, 전체 Clock 전환, 시간대 환경변수 의무화, 데이터 시각 보정, Redis/Kafka/Kubernetes/microservices/API Gateway, S3 전환, frontend framework 교체, sanitizer library, 전체 optimistic locking, mail 전용 bounded executor/broker/retry queue, distributed snapshot, 패키지 재배치, 무관한 cleanup.

현행 인가 matcher·CSRF·비밀번호/잠금/만료 정책 자체는 바꾸지 않는다. H-01은 **기동 정책 변경**이며 로그인 자격 완화가 아니다.

## 3. Dependency Map

| 작업 | 코드상 선행 의존 | 통합 검증 의존 / 공유 파일 |
|---|---|---|
| PR 1 C-01 | 없음 | Gate B의 실제 ADMIN/MANAGER 브라우저 세션 |
| PR 2 H-01 | H-01 A안 정책 승인 완료; 구현 착수는 별도 | reset 복구 검증은 PR 5 적용 상태로 최종 재실행. 배포 문서를 PR 5/6과 공유 |
| PR 3 M-03 | 없음 | PR 4의 409 계약을 유지. 기존 Security/HTML 분기 회귀 확인 |
| PR 4 M-04 | 없음 — 기존 409 handler 이미 존재 | PR 3 후 최종 409 HTTP 검증을 함께 수행하면 원인 분리가 쉬움 |
| PR 5 M-02 | 없음 | 환경변수 표·compose 주석이 PR 2/6과 일부 겹침 |
| PR 6 M-01/M-05 | 문서 작성은 독립 가능 | 최종 복원·재기동은 PR 2/5 포함 release candidate로 검증 |

제품 코드 변경 4건은 목적·실패 모드·rollback 단위가 다르므로 각각 별도 PR로 둔다. SMTP 설정 역시 UI나 bootstrap PR에 끼워 넣지 않는다. 백업과 ingress는 **동일 배포 runbook의 운영 계약**이므로 PR 6으로 묶되, 커밋은 두 관심사로 나눌 수 있다.

공통 테스트 프레임워크·새 예외 기반 클래스·공통 IP 계층을 선행 PR로 만들지 않는다. 공유 문서 충돌은 해당 계약을 소유한 PR에서 작게 해결한다.

## 4. Recommended Implementation Order

1. **H-01 A안 정책 확정 — 완료.** 아래 확정 계약을 구현의 기준으로 사용한다. 실제 제품 변경은 별도 구현 착수 승인 후 수행한다.
2. **PR 1 — C-01.** High 위험이면서 수정 범위·rollback 단위가 가장 작다. 우선 브라우저 회귀로 경계를 닫는다.
3. **PR 2 — H-01.** High이며 배포·복원 이후 재기동에 직접 영향을 준다. 확정된 A안의 기동 허용과 인증 제한을 분리해 구현한다.
4. **PR 3 — M-03.** 작은 범위로 오류 계약·관측 가능성을 먼저 고정해 이후 동시성 검증의 실패 분류를 명확히 한다.
5. **PR 4 — M-04.** 실제 DB 경합을 고친다. 기존 테스트가 사용하는 잠금 및 부모 불변식을 함께 확인한다.
6. **PR 5 — M-02.** timeout 및 운영 전달 경로를 검증한다. 코드상 독립이므로 **구현 착수 승인 이후 필요하면 앞당길 수 있다.** PR 번호는 순서 강제가 아니라 변경 단위 식별자다.
7. **PR 6 — 운영 runbook / restore drill / ingress 계약.** 정규 backup 정책을 확인하고 최종 release candidate로 격리 복원을 수행한다.
8. **Gate A~H 통합 검증.** 개별 PR 통과만으로 production-ready를 선언하지 않는다.

H-01 정책 승인 대기는 해소됐다. PR 수를 줄이려고 정책 변경·UI·DB 잠금을 한 PR에 합치지 않는다.

## 5. PR Plan

### PR 1 — 관리자 상세 평문 안전 렌더링

#### Goal

저장된 이름·아이디 등 평문이 ADMIN 상세 화면에서 DOM markup이 되지 않도록 한다.

#### Finding

C-01 · High · Security Issue / Bug.

#### Current Behavior

[admin-manage.html](../src/main/resources/templates/admin/member/admin-manage.html)의 `renderTable()`은 평문을 `escapeHtml()` 처리한다. `renderDetail()`의 상단 이름도 escape하지만, `detailItems`의 아이디·이름은 raw이고 `detailContent.innerHTML`에 삽입된다. 이메일은 버튼 markup을 만들되 이메일 값 자체는 escape한다. 상세 생성일의 `formatDate()`는 파싱 실패 시 원문을 반환하므로 같은 평문 경계에 포함한다.

이메일 복사는 `detailContent`의 위임 이벤트와 `.copy-email-btn`, `data-email`에 의존한다. 전체 컨테이너를 단순 `textContent`로 바꾸면 이 기능이 깨진다.

#### Target Behavior

목록·요약·상세에 평문은 원래 문자로 보인다. 입력 문자열이 DOM 구조를 만들지 않는다. 이메일 버튼·복사·프로필·모달 수정 UI는 기존대로 동작한다. DB에는 원래 문자열을 유지한다.

| 대안 | 변경·안전성 | 회귀 위험 | 판단 |
|---|---|---|---|
| A: 상세 row를 DOM 생성 + `textContent`로 구성 | 평문/markup 구분이 명확하지만 현재 row 조립을 더 많이 교체 | class, 버튼 구조, 이벤트 위임 회귀 범위 증가 | 유효한 대안. 이번에는 필수 아님 |
| B: 기존 문자열 조립 + `escapeHtml()` | 이미 쓰는 함수 재사용, 수정 작음 | escape 누락·이중 escape에 대한 회귀 방어 필요 | **추천** |

B안에서도 “모든 `item.value`를 escape”하지 않는다. **상세 목록 안에서 평문 항목과 코드가 만드는 이메일 markup을 명시적으로 구분**하고, 평문은 렌더링 경계에서 정확히 한 번 escape한다. 이메일 markup만 내부에서 만드는 별도 값/분기로 유지한다. 범용 renderer나 외부 HTML을 받아들이는 API는 만들지 않는다.

#### Files Likely Affected

- 기존 변경: `src/main/resources/templates/admin/member/admin-manage.html`.
- 테스트 보강: `src/test/java/com/cms/admin/member/controller/AdminMemberControllerTest.java`, 필요 시 `.../service/AdminMemberServiceTest.java`의 원문 왕복 확인.
- 신규 후보: `src/test/java/com/cms/admin/member/AdminMemberTemplateConventionTest.java` — 저장소의 기존 template convention test 방식 재사용.
- 신규 검증 절차: `docs/verification/admin-detail-rendering.md` — 실제 브라우저 회귀 입력·역할·selector·예상값 기록.
- DTO/Entity/SecurityConfig/build.gradle/migration 변경 없음.

#### Implementation Steps

1. 현재 무해한 표식의 저장→상세 DOM 생성 실패를 격리 fixture에서 확인한다.
2. 상세 텍스트 항목과 의도된 이메일 markup을 분리한다. 아이디·이름·생성일·역할·상태는 평문 경계에서 escape한다.
3. 기존 `escapeHtml()`을 재사용한다. 이미 escape한 값을 다시 escape하지 않도록 상세 목록의 데이터 형태를 맞춘다.
4. 목록·상단 요약은 안전한 기존 구현을 유지한다. 프로필 URL 정책이나 입력 validation으로 확대하지 않는다.
5. CI용 좁은 convention/MVC 테스트와 실제 브라우저 절차를 함께 남긴다. 정적 문자열 검사만으로 브라우저 안전성을 통과 처리하지 않는다.

#### Tests to Add / Update

- MANAGER가 `<span id="audit-name-marker">검증 이름</span>`을 자기 이름으로 저장 → ADMIN이 상세 조회 → 이름 영역의 `textContent`가 원문과 같고, 상세 영역의 `#audit-name-marker` 요소 수는 0.
- 정상 한글·영문 이름, `&`, 작은/큰따옴표, 문자 그대로의 `&lt;`를 포함한 이름의 표시 보존 및 이중 escape 없음.
- 목록·요약·상세 일치, 이메일 버튼 존재·표시·복사 값 보존, 프로필 표시와 모달 편집/닫기 재확인.
- MANAGER의 타 관리자 API 접근 403, 본인 수정 성공, CSRF 누락 거절 유지.
- convention test는 raw 텍스트 삽입 재도입을 빠르게 탐지하는 보조 장치다. 브라우저 test는 사용 가능한 자동화 도구로 수행하고 결과를 기록한다. 새 npm/frontend framework는 도입하지 않는다. 현재 CI가 브라우저까지 자동 실행한다고 표기하지 않는다.

#### Existing Tests to Re-run

`AdminMemberControllerTest`, `AdminMemberServiceTest`, `SecurityConfigTest`, `ApiSecurityConfigTest`, `AdminSidebarAdviceTest`.

#### Regression Risks

이메일 버튼이 문자로 표시됨, 한글/특수문자 이중 escape, 위임 이벤트 selector 파손. 서버 입력 금지로 우회하면 기존 정상 이름 호환성도 깨질 수 있으므로 입력 정책은 바꾸지 않는다.

#### Rollback

템플릿·테스트만 되돌릴 수 있고 DB 역변환은 없다. 다만 revert는 보안 결함을 다시 연다. 외부 운영 중 문제가 생기면 좁은 UI 보정 또는 해당 화면 접근 제한 하의 fix-forward를 우선하며, 이전 화면으로 무조건 복귀해 안전하다고 선언하지 않는다.

#### Dependencies

다른 PR 선행 불필요. 실제 브라우저 검증에 격리 ADMIN/MANAGER fixture만 필요하다.

#### Completion Criteria

무해한 표식이 문자 그대로 표시되고 새로운 DOM 요소가 생기지 않음. 정상 UI·권한·CSRF 회귀 통과. 신규 dependency·migration 없음.

#### PR 1 실행 기록 — 2026-09-24

- Context: 사용자가 PR 1만 구현 승인. 착수 직전 기준 HEAD 및 대상 코드가 계획과 일치함을 확인하고 `feat/admin-detail-safe-text` 브랜치에서 작업했다.
- 확정 사항: Option B 그대로 적용. 상세 `value`는 raw 평문, 코드가 조립하는 이메일만 `html`. 출력 경계에서 `escapeHtml(item.value)`를 한 번 적용한다. 목록·요약·저장/API 원문·인가/CSRF·DB schema는 변경하지 않았다.
- 구현 파일: `admin-manage.html`, 신규 `AdminMemberTemplateConventionTest`, 기존 `AdminMemberControllerTest`·`AdminMemberServiceTest`. 기록은 member `CLAUDE.md`, 본 계획 및 [실제 브라우저 회귀 절차/결과](../docs/verification/admin-detail-rendering.md)에 한정했다.
- 신규 테스트: convention 3개 + MVC 8개 + service 3개 = 14개. MVC/service 검사가 실제 DB/DOM 검증을 대신한다고 주장하지 않는다.
- 검증 결과: compileJava/compileTestJava/bootJar 성공. 지정 기존 5종+신규 convention의 168개 모두 통과. 전체 test는 699개 중 698개 통과, 실패·오류 0, 기존 Windows symlink 시험 1개 skip. Linux CI 미실행.
- 실기: 격리 MariaDB + 실제 prod JAR + Chromium. MANAGER 저장→DB 원문 조회→ADMIN 상세에서 수정 전 표식 DOM 1개, 수정 후 0개 및 원문 표시 확인. 정상 이름/특수문자·목록/요약·이메일 복사·프로필 fallback/preset·편집/취소/닫기·MANAGER API/페이지 403·CSRF 403 통과. 응답 fixture로 아이디·잘못된 날짜 fallback·빈 이메일도 검증했다.
- 발견 이슈: 수정 전 convention 2개 실패는 예상한 red 결과. 수정 후 convention 1개가 주석 위치 때문에 실패해 주석을 옮긴 뒤 통과했다. 범위 밖 제품 수정은 없었다.
- 정리/후속: 검증 계정 표시값 원복, 브라우저/검증 앱 종료, 일회용 DB와 Testcontainers DB 제거 확인. 기존 dev 스택·사용자 작업은 유지했다. PR 2~6 및 최종 RC Gate는 미완료이며, Linux CI와 커밋/PR 생성은 별도다. 캡처와 진단 하니스는 git 제외 `build/verification/pr1/` 산출물이다.

### PR 2 — ADMIN bootstrap과 로그인 상태 분리

#### Goal

기존 ADMIN의 잠금·비밀번호 만료가 공개 서비스 전체의 재기동 불능으로 이어지는 결합을 제거한다. 관리자 미구성 검증과 기존 계정 접근 제한은 유지한다.

#### Finding

H-01 · High · Operational Risk / Architecture Issue.

#### Current Behavior

[AdminBootstrapLoader](../src/main/java/com/cms/admin/member/AdminBootstrapLoader.java)와 [MemberRepository](../src/main/java/com/cms/admin/member/repository/MemberRepository.java)는 ADMIN+ACTIVE 존재 여부만 확인한다. 없으면 초기 자격증명으로 신규 계정을 생성한다. 기존 계정과 같은 ID가 충돌하면 `createOrReconcile()`도 ADMIN+ACTIVE인 경우만 흡수한다.

[LoginFailureService](../src/main/java/com/cms/config/auth/LoginFailureService.java), [PasswordExpiryService](../src/main/java/com/cms/config/auth/PasswordExpiryService.java), [PasswordResetService](../src/main/java/com/cms/admin/member/service/PasswordResetService.java)의 상태 복구는 요청 시점 동작이다. 수동 LOCKED는 `lockedAt=null`이며 lazy unlock 대상이 아니다.

#### Target Behavior

**확정 정책 D-01: 대안 A — 2026-09-23 사용자 승인.** DB에서 `ROLE_ADMIN` 역할과 `ACTIVE / LOCKED / PASSWORD_EXPIRED` allowlist를 만족하는 계정이 하나라도 있으면 기존 ADMIN이 구성된 설치로 보고 신규 bootstrap을 건너뛰며 애플리케이션 기동을 허용한다. 이는 현재 DB를 기준으로 한 운영상 판정이지, 과거 구성 이력을 별도 marker로 영구 증명한다는 뜻은 아니다.

확정된 불변 조건:

- 자동·수동 LOCKED 모두 앱 기동을 허용하지만 잠금 상태를 유지한다. bootstrap에서 로그인 허용이나 자동 잠금 해제를 수행하지 않는다.
- PASSWORD_EXPIRED도 기동만 허용하며 만료 상태를 정상화하지 않는다.
- DISABLED/DELETED ADMIN만 존재하거나 ADMIN이 없으면 기존처럼 유효한 신규 ADMIN bootstrap 자격증명을 요구한다. 빈 DB와 MANAGER-only도 이 분기에 포함한다.
- 신규 bootstrap은 별도 ADMIN 생성이며 기존 DISABLED/DELETED 계정을 부활시키거나 동일 ID의 기존 계정을 덮어쓰지 않는다. 기존 unique 제약도 유지한다.
- **어느 bootstrap 분기에서도 기존 계정의 상태, 비밀번호 해시, `lockedAt`, `passwordChangedAt`, reset token·만료 시각 등 기존 필드를 변경하지 않는다.** 신규 계정 생성에 필요한 최초 필드 설정과 기존 계정 변경을 구분한다.
- 기동 이후 로그인/reset 요청에서 동작하는 기존의 만료된 자동 잠금 해제·비밀번호 재설정 정책은 그대로다. 수동 LOCKED의 자동 해제를 새로 허용하지 않는다. **정정(v5):** bootstrap 자체는 만료 상태를 변경하지 않는다. EXPIRED의 ACTIVE 복귀는 reset 시나리오에서는 적격 reset 성공 후에, 그리고 기존에 이미 존재하는 살아있는 세션의 `changeMyPassword()` 경로(`Member.changePassword()`)로도 동일하게 일어난다 — bootstrap이 이 기존 경로를 막거나 "reset만 유일한 복귀 수단"으로 서술하지 않는다.
- **동시성 보장 범위 명시(v5 추가):** 존재 질의(`existsByUserTypeAndStatusIn` 등)와 신규 ADMIN INSERT는 원자적인 한 동작이 아니다 — 이 정책이 보장하는 것은 "존재 질의를 실행한 시점에 적격 ADMIN이 있었는가"이지, 기동 완료 시점까지 전역적으로 신규 ADMIN이 정확히 한 명만 생기는 것을 보장하지 않는다. 서로 다른 bootstrap 자격증명(다른 ID)을 가진 두 인스턴스가 동시에 존재 확인을 통과하면 서로 다른 ADMIN이 각각 생성될 수 있다 — **동일 설치의 모든 인스턴스가 같은 bootstrap 자격증명(같은 ID)을 사용한다**는 운영 전제 위에서만 "신규 ADMIN 한 명"이 보장된다. 이 전제는 `docs/deployment.md`의 bootstrap 설명에도 명시한다.

검토한 세 대안의 의미(아래 B/C는 비교 이력이며 채택하지 않음):

- **A — 현재 DB의 역할·상태 allowlist:** 새 marker 없음. DISABLED/DELETED만 있으면 기존처럼 명시적 신규 관리자 자격증명을 요구한다.
- **B — 작은 provisioning marker/config:** 운영자가 완료 marker를 유지한다. 비교를 위해 `완료 marker + ADMIN 행 존재`를 정상 기동 조건으로 정의한다. ADMIN 행 존재 검사는 빈 DB나 MANAGER-only에서 오래된 marker로 검증을 우회하지 않기 위한 필수 방어다. 상태와 완전히 분리하면 DISABLED/DELETED-only도 서비스는 기동하며 관리자는 로그인 불가하다. marker가 누락되면 기존 DB라도 초기 구성 절차가 필요해진다.
- **C — 명시적 bootstrap 실행:** 자격증명 입력·계정 생성을 별도 일회성 실행으로 분리한다. 일반 앱은 ADMIN 행 존재를 검사하고 어떠한 상태도 변경하지 않는다. 비교안에서는 DISABLED/DELETED-only도 일반 앱 기동을 허용하므로, 운영 정책상 수용 여부를 따로 결정해야 한다. 새 ADMIN 구성은 별도 명령으로 한다.

아래 표의 `신규 구성`은 **유효한 자격증명으로 별도 ACTIVE ADMIN을 새로 생성**한다는 뜻이다. 기존 행의 복구·부활을 뜻하지 않는다.

| DB 상태 | A: 확정안 | B: marker/config | C: 명시적 실행 |
|---|---|---|---|
| 빈 DB | 자격증명 없으면 실패, 유효하면 신규 구성 후 기동 | marker=true만으로 통과 금지. 자격증명과 초기 구성 필요 | 일반 기동 실패. 유효 자격증명으로 bootstrap 실행 후 기동 |
| MANAGER만 존재 | 빈 DB와 동일 | marker만으로 통과 금지. ADMIN 구성 필요 | 일반 기동 실패. 명시적 ADMIN 구성 필요 |
| ACTIVE ADMIN | 변수 없이 기동, 상태 유지 | 완료 marker 있으면 기동; 없으면 설정/초기 구성 필요 | 일반 기동, 상태 유지 |
| LOCKED ADMIN | 변수 없이 기동, 자동·수동 잠금 모두 유지 | 완료 marker 있으면 기동, 잠금 유지 | 일반 기동, 잠금 유지 |
| PASSWORD_EXPIRED ADMIN | 변수 없이 기동, 만료 유지 | 완료 marker 있으면 기동, 만료 유지 | 일반 기동, 만료 유지 |
| DISABLED ADMIN만 | 변수 없으면 실패, 유효한 새 ID로 신규 구성 가능 | 완료 marker 있으면 기동, DISABLED 유지 | 일반 기동, DISABLED 유지; 관리자 복구는 별도 명령 |
| DELETED ADMIN만 | 변수 없으면 실패, 유효한 새 ID로 신규 구성 가능 | 완료 marker 있으면 기동, DELETED 유지 | 일반 기동, DELETED 유지; 관리자 복구는 별도 명령 |
| ACTIVE + 다른 비활성 ADMIN | 변수 없이 기동, 전체 상태 유지 | 완료 marker 있으면 기동, 전체 상태 유지 | 일반 기동, 전체 상태 유지 |

| 비교 | A | B | C |
|---|---|---|---|
| 변경 범위 | repository 존재 질의와 runner 분기·테스트 | marker 전달·누락·복구 일관성까지 추가 | 실행 lifecycle·배포 순서·복구 절차 추가 |
| migration | 불필요 | 환경/파일 marker면 불필요지만 복원 동기화 부담. DB marker/table은 이번 범위에서 제외 | 불필요 |
| 주요 이득 | 기존 구조 유지, 일시 상태의 기동 차단 제거 | 현재 계정 상태와 더 강한 분리 | 계정 생성의 운영 실행을 가장 명시적으로 통제 |
| 비용/위험 | 폐기 상태만 남으면 계속 수동 재구성 필요. 역사적 완료 이력은 없음 | stale marker·설정 누락·DB 복원 불일치. 잘못된 flag가 무관리 상태를 숨길 수 있음 | 최초 설치/복구마다 실행 절차 증가, 관리 명령 보안·종료 코드 검증 필요 |
| 현재 판단 | **사용자 승인으로 확정** | 현재 요구 대비 비용 큼, 미채택 | 별도 provisioning 요구가 생긴 뒤 고려, 미채택 |

A에서 수동 LOCKED의 기동도 허용하는 이유는 **관리자 로그인 차단과 공개 앱 가용성을 분리**하기 때문이다. 수동 잠금을 일시 상태나 자동 복구 가능 상태로 재분류하지 않는다. 모든 ADMIN이 수동 LOCKED이면 별도 운영자 복구는 여전히 필요하다.

**상태표 보강(v5 추가):**

- 위 상태표에 없던 `DISABLED ADMIN + ACTIVE MANAGER/USER` 조합도 명시한다 — 적격 ADMIN이 없으므로 "빈 DB"·"MANAGER만 존재"와 동일하게 변수 없으면 실패, 유효하면 신규 구성이다(ADMIN 역할이 아닌 계정의 상태는 D-01 allowlist 판정과 무관).
- "same ID DISABLED/DELETED는 부활하지 않고 충돌 실패"(Tests to Add 참조)에는 **다른 적격 ADMIN이 없을 때**라는 전제를 명시한다 — 예: `ACTIVE ADMIN A` + `DISABLED ADMIN B`가 이미 있는 DB에 bootstrap 자격증명 ID가 B와 같아도, A가 존재 질의를 이미 충족시키므로 전역 skip이 먼저 적용되고 변수 검증·충돌 실패 경로로 가지 않는다.

#### Files Likely Affected

- `src/main/java/com/cms/admin/member/AdminBootstrapLoader.java`.
- `src/main/java/com/cms/admin/member/repository/MemberRepository.java`.
- `src/test/java/com/cms/admin/member/AdminBootstrapLoaderTest.java`.
- `src/test/java/com/cms/admin/member/AdminBootstrapConcurrencyIntegrationTest.java`.
- 신규 후보: `src/test/java/com/cms/admin/member/AdminBootstrapStartupIntegrationTest.java`.
- `src/main/java/com/cms/admin/member/CLAUDE.md`, `docs/deployment.md`, `.env.example`, `docker-compose.prod.yml`의 bootstrap 설명. compose 변수 계약 자체는 A안에서 그대로다.
- 로그인·비밀번호 서비스와 Role/Status enum, Member entity, 인증 matcher는 **읽기·회귀 검증 대상이며 변경 예정 아님**.

#### Implementation Steps

1. 승인된 D-01 계약과 위 상태표를 회귀 테스트의 기준으로 사용한다. 현재 동작을 설명하는 운영 문서는 실제 구현이 반영되는 PR에서 갱신하며, 정책 승인만으로 이미 수정 완료된 것처럼 바꾸지 않는다.
2. ADMIN 역할 + 위 allowlist를 정확히 검사하는 repository 존재 질의를 추가/대체한다. 예: `existsByUserTypeAndStatusIn`. `member count > 0`이나 역할만 보는 무조건 통과는 사용하지 않는다.
3. runner의 skip 조건을 이 질의로 바꾸고 기동 실패 메시지를 “ACTIVE 관리자 부재”가 아닌 승인된 provisioning 조건에 맞춘다.
4. 신규 INSERT/BCrypt/이메일 정규화/TransactionTemplate은 재사용한다. **동시 생성 중 상태가 LOCKED/EXPIRED로 바뀐 같은 ID의 reconciliation도 같은 역할·상태 기준으로 정렬**해 skip 조건과 충돌 흡수 조건이 서로 어긋나지 않게 한다. 다른 역할, DISABLED/DELETED, 다른 ID의 충돌은 성공으로 흡수하지 않는다. 비밀번호·기존 이메일은 덮어쓰지 않는다.
5. 새 상태 행렬을 unit + 실제 prod 기동에 적용하고 기존 recovery 요청 동작이 그대로인지 확인한다.
6. bootstrap 변수 제거 권고, 만료/수동 잠금의 운영 복구 설명을 현재 정책과 맞춘다. 추가 marker·환경변수·migration은 만들지 않는다.

#### Tests to Add / Update

- 표의 8개 상태, LOCKED의 자동 만료 전/후 및 수동 잠금, LOCKED+EXPIRED만 있는 혼합 상태.
- ACTIVE/LOCKED/EXPIRED가 있으면 초기 변수 누락·잘못된 값에도 bootstrap 변수 검사·INSERT·인코딩이 없음.
- 빈 DB/MANAGER-only/DISABLED-only/DELETED-only는 변수 없으면 실패. 유효한 **새 ID**의 변수로 신규 ADMIN 1명 생성, 기존 계정 상태 불변.
- same ID LOCKED/EXPIRED는 runner skip, same ID DISABLED/DELETED는 부활하지 않고 충돌 실패. 다른 역할의 동일 ID·중복 이메일도 무조건 흡수하지 않음.
- `createOrReconcile()`의 실제 DB unique 충돌/새 트랜잭션 재조회 및 허용/거부 역할·상태 조합.
- prod 기동 직후, login/reset 요청 **전에** 상태·lockedAt·passwordChangedAt·비밀번호 해시·token이 바뀌지 않았는지 확인.
- 그 후 자동 잠금 만료는 기존 로그인/reset 요청에서만 해제, 미만료·수동 LOCKED는 거절, DISABLED/DELETED는 reset 거절 유지. **(v5 정정)** EXPIRED의 ACTIVE 복귀는 적격 reset 성공 후 **뿐 아니라** 기존에 이미 존재하는 살아있는 세션의 `changeMyPassword()` 경로로도 일어난다 — bootstrap 계획이 "reset 성공 후에만"으로 전역 불변식을 새로 만들지 않는다. 두 경로 모두 회귀 확인 대상이다.
- 부트스트랩 없는 상태에도 공개 공지·reset 화면이 기동하고 prod에 dev 기본 계정이 생기지 않음.
- **(v5 추가) 병렬 존재확인 경합**: 서로 다른 bootstrap ID(따라서 다른 자격증명)를 가진 두 실행이 모두 `exists=false`를 확인한 뒤 각자 INSERT를 시도하면 서로 다른 ADMIN 두 명이 생성될 수 있음을 실제 DB로 확인 — 이는 결함이 아니라 "동일 설치는 동일 bootstrap ID를 쓴다"는 운영 전제의 경계임을 테스트 설명에 명시한다. 기존 `AdminBootstrapConcurrencyIntegrationTest`(`createOrReconcile()` 순차 2회 호출)와 구분되는 **병렬** 시나리오로 별도 추가.
- **(v5 추가, round2 명확화)** **재조회 전 상태 변경**: 최초 존재 질의는 skip 여부를, 충돌 후 재조회는 reconciliation 여부를 결정한다는 원칙 아래 다음 3가지를 각각 고정 검증한다 — (1) `exists=true` 직후 대상이 DISABLED로 변경되면 runner는 그대로 skip한다(이 경로에는 재조회가 없다). (2) `exists=false` 이후 같은 ID가 생성되면 INSERT 충돌 후 재조회에서 읽은 역할·상태로 흡수 여부를 결정한다. (3) 충돌 후 재조회 전에 같은 ID가 DISABLED로 변경·커밋되면 흡수하지 않고 실패하며, LOCKED/PASSWORD_EXPIRED라면 allowlist에 따라 흡수한다.
- **(v5 추가) 상태 혼합**: `DISABLED ADMIN + ACTIVE MANAGER/USER`에서 변수 없으면 실패·유효하면 신규 구성 확인. `ACTIVE ADMIN A + DISABLED/LOCKED/EXPIRED ADMIN B`에서 bootstrap 자격증명 ID가 B와 같아도 전역 skip이 우선되어 변수 검증·충돌 실패 없이 기동함을 확인(B는 그대로 방치).
- **(v5 추가) 자동 잠금 만료 + 비밀번호 만료 동시 발생**: `lockedAt`이 30분을 넘긴 LOCKED이면서 `passwordChangedAt`도 90일을 넘긴 계정은 bootstrap 직후에는 LOCKED 상태를 그대로 유지(bootstrap은 자동 해제하지 않음)하되, 이후 로그인 시도 시 `CustomUserDetailsService`가 자동 잠금 해제 다음에 비밀번호 만료를 판정해 PASSWORD_EXPIRED로 로그인이 거절됨을 확인 — "자동 잠금 해제 → 로그인 성공"만 검증하는 기존 케이스와 구분되는 별도 시나리오.

실제 기동 테스트는 공유 dev seed가 있는 DB를 재사용해 행렬을 오염시키지 않는다. Testcontainers의 **독립 DB/schema와 명시적 prod 설정**을 사용하고 실제 `CmsApplication`으로 기동한다. Gradle의 `SPRING_PROFILES_ACTIVE=dev`를 높은 우선순위 설정으로 명시적으로 대체해 dev+prod가 동시에 켜지지 않게 한다. 최종 실행 JAR 검증은 runner 완료·프로세스 생존·안정 health까지 확인한다. 순간적인 최초 health 200만으로 성공 판정하지 않는다.

#### Existing Tests to Re-run

`AdminBootstrapLoaderTest`, `AdminBootstrapConcurrencyIntegrationTest`, `PasswordFieldBoundaryTest`, `EmailNormalizerTest`, `ProfileGuardEnvironmentPostProcessorTest`, `LoginFailureLockoutIntegrationTest`, `PasswordExpiryIntegrationTest`, `PasswordResetServiceTest`, `PasswordResetConcurrencyIntegrationTest`, `AdminSessionRevocationIntegrationTest`, `CmsApplicationTests`, `ActuatorExposureTest`.

현재 bootstrap unit test의 ACTIVE 전용 mock·이름·설명을 새 조건에 맞춰 갱신한다. `CmsApplicationTests`는 dev seed 검증이므로 prod 행렬을 대체하지 못한다.

#### Regression Risks

MANAGER-only 오통과, 명시적으로 폐기한 계정 부활, 상태가 바뀐 기존 계정 암호 덮어쓰기, 동시 생성 충돌의 과도한 흡수, dev/prod 프로파일 혼합. 가장 중요한 것은 **기동 허용을 로그인 허용으로 오해하지 않는 것**이다.

#### Rollback

스키마 변경이 없어 이전 바이너리로 되돌릴 수 있다. 그러나 기존 버전은 ACTIVE ADMIN이 없으면 다시 기동 실패한다. rollback 전 이전 버전의 기동 조건을 확인하고, 충족되지 않으면 patched image 유지/fix-forward를 선택한다. rollback 편의를 이유로 잠금·폐기 상태를 자동 변경하지 않는다.

#### Dependencies

D-01 정책 승인 완료. PR 5 없이 코드 테스트는 가능하지만 실제 메일 기반 복구의 최종 완료 판정은 PR 5와 함께 한다. 정책 확정과 구현 착수 승인은 구분한다.

#### Completion Criteria

상태 행렬·prod 실행 JAR·복구 요청 회귀 통과, 기존 계정 무변경, 초기 관리자 미구성 fail-fast 유지, 문서 정합성 확보. migration 없음.

#### PR 2 실행 기록 — 2026-09-24

- Context: 사용자가 `/suggestRoadmap` → PR 2 선택 → 계획 리뷰(codex CLI 2라운드, v5·v6, ship) → 구현 착수 순으로 승인. `feat/admin-detail-safe-text`(PR 1)가 이미 머지된 기준 HEAD 위에서 `security/admin-bootstrap-eligible-status` 브랜치로 작업했다.
- 구현: `MemberRepository`에 `existsByUserTypeAndStatusIn(Role, Collection<MemberStatus>)` 신규 파생 쿼리 추가, 기존 `existsByUserTypeAndStatus(Role, MemberStatus)`는 유일한 호출부(`AdminBootstrapLoader`)와 함께 완전히 대체해 삭제(사용처 없는 코드 남기지 않음). `AdminBootstrapLoader`에 `ELIGIBLE_STATUSES = {ACTIVE, LOCKED, PASSWORD_EXPIRED}` 상수를 두고 `run()`의 skip 판정과 `createOrReconcile()`의 재조회 흡수 조건 양쪽에 동일하게 적용(계획 Implementation Step 4 요구사항). 기동 실패 메시지를 "ACTIVE 상태의..."에서 "적격(ACTIVE/LOCKED/PASSWORD_EXPIRED) 상태의..."로 갱신. 클래스 Javadoc에 D-01 정책·동시성 보장 범위·운영 전제를 명시(v5·v6 리뷰 반영 내용과 동일 문구). `docs/deployment.md`·`.env.example`·`docker-compose.prod.yml`·`com.cms.admin.member/CLAUDE.md`의 부트스트랩 설명을 새 allowlist·동시성 전제에 맞춰 갱신. `AdminMemberService`·`Member`·`LoginFailureService`·`PasswordExpiryService`·`CustomUserDetailsService`·인증 matcher는 계획대로 무변경(읽기·회귀 검증 대상으로만 사용).
- 신규 테스트: `AdminBootstrapLoaderTest`(Mockito)에 7건 순증(적격 상태 allowlist 존재질의 검증 2건, LOCKED/PASSWORD_EXPIRED 재조회 흡수 2건, DISABLED/DELETED/다른 역할 재조회 거부 3건) — 총 15개. 신규 `AdminBootstrapStartupIntegrationTest`(Testcontainers MariaDB) 15건: 상태 행렬(ACTIVE/LOCKED/PASSWORD_EXPIRED 존재 시 변수 없이 skip 3건, 빈 DB/MANAGER-only/DISABLED-only/DELETED-only 변수 필요 4건, DISABLED-only에 신규 ID로 생성 가능 1건), v5 상태 혼합 2건(DISABLED+ACTIVE MANAGER는 부적격 취급, ACTIVE A+DISABLED B는 B 방치하며 skip), v5 재조회 전 상태 변경 실 DB unique 충돌 경로 2건(LOCKED 흡수·DISABLED 거부), v5 병렬 존재확인 경합 1건(barrier로 두 인스턴스 exists=false 동시 통과 재현 → 서로 다른 ADMIN 2명 생성 확인), v5 자동 잠금 만료+비밀번호 만료 동시 발생 1건(bootstrap 직후 LOCKED 유지 → 로그인 시도 시뮬레이션에서 PASSWORD_EXPIRED로 전이해 거절). 이 클래스는 `@Profile("prod")` 컨텍스트 전환 대신 기존 `AdminBootstrapConcurrencyIntegrationTest` 관례(POJO 직접 생성)를 따라 dev 프로파일 Testcontainers로 실 DB 제약·트랜잭션을 검증한다 — 프로파일 전환 자체는 별도 실기 검증(아래)으로 확인.
- 회귀 이슈: Spring TestContext가 동일 설정(`@SpringBootTest(classes=CmsTestApplication)`)의 컨텍스트·Testcontainers 컨테이너를 여러 테스트 클래스에 걸쳐 재사용해, `TestMemberLoader`(dev seed)가 컨텍스트 최초 기동 시 만든 `admin` 계정이 이 클래스 실행 시점에 이미 존재함을 발견 — "적격 ADMIN 없음" 상태 행렬 케이스를 전부 오염시켰다. `@BeforeEach`에서 `admin` 행을 백업 후 제거하고 `@AfterEach`에서 동일 값으로 복원하는 방식으로 격리(다른 테스트 클래스의 `CmsApplicationTests.testMemberLoader_seedsAdminAccount`처럼 이 seed의 영속 존재를 전제하는 테스트에 영향 없음, 실제로 조합 재실행해 회귀 없음 확인). `LocalDateTime` 초기 assert가 MariaDB 컬럼의 마이크로초 절단으로 실패해 `isEqualToIgnoringNanos`로 교정. 테스트 비밀번호 일부가 `@MinCodePoints(15)` 경계에 미달해 검증 실패했던 것도 교정.
- 검증 결과: `./gradlew compileJava compileTestJava` 성공. `AdminBootstrapLoaderTest` 15개·`AdminBootstrapStartupIntegrationTest` 15개·`AdminBootstrapConcurrencyIntegrationTest` 1개(무회귀)·`CmsApplicationTests` 2개(무회귀) 각각 통과 확인 후, `SPRING_PROFILES_ACTIVE=dev ./gradlew test` 전체 실행 — **720개 전체 통과, 실패·오류 0**(신규 30개 순증: 단위 7·통합 15 + 기존 재확인 무변경, PR 1 이후 기준 698개에서 신규 클래스·케이스 순증).
- 실기: 로컬 Docker(사용자 dev 스택 `cms-app-dev`/`cms-db-dev`는 건드리지 않음)에 별도 일회성 MariaDB(`cms-verify-db`, 포트 3309)를 띄우고 `./gradlew bootJar`로 갱신한 실제 JAR을 `SPRING_PROFILES_ACTIVE=prod`로 직접 실행(`docker-compose.prod.yml`은 사용자 dev 스택과 호스트 포트 8080이 충돌해 이번엔 우회): (1) 빈 DB + 유효한 `ADMIN_BOOTSTRAP_*` 3변수 → 정상 기동, `member` 테이블에 `ROLE_ADMIN`/`ACTIVE` 1행 생성 확인(DB 직접 조회). (2) 그 계정을 `LOCKED`로 직접 전이한 뒤 **부트스트랩 변수 전부 제거**하고 재기동 → **이번 PR의 핵심 변경대로 정상 기동**(`/actuator/health` 200, 예외 없음), DB 재조회로 `status=LOCKED`·`locked_at` 원값 그대로 무변경 확인. 검증용 컨테이너·`.env.prod`·임시 스토리지 디렉터리는 종료 후 전부 제거, 사용자 dev 스택 무변경 확인(`docker ps`).
- 이슈: 처음 직접 JAR 실행 시 PATH에 `java`가 없어 `nohup: failed to run command 'java'` 실패 — JDK 홈(`C:\Users\user\.jdks\corretto-17.0.18`) 절대경로로 우회. 첫 실기 시도는 `docker-compose.prod.yml` 그대로 사용해 사용자 dev 스택과 호스트 포트 8080이 충돌 — 즉시 `docker compose down`으로 정리하고 dev 스택 무변경 확인 후, compose 대신 직접 JAR 실행 + 별도 포트(8099)/별도 DB 컨테이너(3309)로 재시도해 충돌 없이 완료. 두 번째 재기동 검증에서 로컬 `build/libs/*.jar`이 코드 변경 전 stale 빌드였음을 발견(구 오류 메시지 문구로 확인)해 `./gradlew bootJar` 재실행 후 재검증.
- `/code-review-loop` 2라운드: 1라운드에서 [P2] 지적 1건 수용 — `AdminBootstrapStartupIntegrationTest`의 병렬 존재확인 경합 테스트가 테스트 코드의 별도 `existsByUserTypeAndStatusIn` 호출만 `CountDownLatch`로 동기화하고 실제로 실행되는 `run()` 내부 재조회는 동기화하지 못해 스레드 스케줄링에 따라 간헐적으로 실패할 수 있었다(운영 코드 결함 아님, 테스트 설계 결함). `run()` 대신 존재 재확인 없이 곧바로 INSERT하는 `createOrReconcile()`을 `CyclicBarrier`로 실행 지점 직전에 동기화하도록 재설계해 결정적으로 만들었다(반복 3회 재실행 통과 확인). 2라운드는 지적 0건(ship). 전체 720개 재확인 통과.
- 후속: 없음. 감사 H-01(D-01 대안 A) 완료. 커밋 `eaaccb6` "보안: ADMIN bootstrap 기동 조건을 상태 allowlist로 확장 (감사 H-01) (#38)"로 반영, PR #38 머지 확인(`gh pr view 38` state=MERGED, mergedAt=2026-09-24T11:52:37Z) + CI(`gh pr checks 38`) `test` pass(2m0s) — 커밋·PR·머지까지 완료.

### PR 3 — API 오류 계약과 안전한 500 진단

#### Goal

일반 클라이언트 오류를 500으로 오분류하지 않고, 예상 밖 장애의 진단 위치를 남긴다.

#### Finding

M-03 · Medium · Bug / Operational Risk.

#### Current Behavior

[GlobalApiExceptionHandler](../src/main/java/com/cms/common/api/GlobalApiExceptionHandler.java)의 기존 validation/JSON/권한/404/409 handlers는 유지할 대상이다. 잘못된 path variable, 지원하지 않는 method/media type은 catch-all 500에 도달한다. [ApiErrorResponse](../src/main/java/com/cms/common/api/ApiErrorResponse.java)는 `timestamp/path/code/message` 구조다.

[SecurityConfig](../src/main/java/com/cms/config/SecurityConfig.java)의 API 401/403은 필터에서 처리한다. 인증되지 않은 CSRF 실패는 현재 401이다. [PublicWebExceptionAdvice](../src/main/java/com/cms/publicweb/support/PublicWebExceptionAdvice.java)는 publicweb에만 우선 적용되고 이미 로그/HTML 500을 처리한다. [CustomErrorController](../src/main/java/com/cms/error/CustomErrorController.java)는 주로 HTML 404/429를 담당한다.

#### Target Behavior

| 예외/상황 | 상태 / code 제안 | 보존 조건 |
|---|---|---|
| `MethodArgumentTypeMismatchException` | 400 / 기존 `INVALID_REQUEST` | 잘못된 값 원문을 메시지에 재출력하지 않음 |
| `HttpRequestMethodNotSupportedException` | 405 / 신규 `METHOD_NOT_ALLOWED` | 서버가 제공하는 지원 method로 `Allow` header 구성 |
| `HttpMediaTypeNotSupportedException` | 415 / 신규 `UNSUPPORTED_MEDIA_TYPE` | 고정된 안전한 메시지, 기존 JSON 오류 포맷 |
| `HttpMediaTypeNotAcceptableException` | 406 / 신규 `NOT_ACCEPTABLE` | 고정된 안전한 메시지, JSON 오류 포맷 유지 |
| 예상 밖 나머지 Exception | 500 / 기존 `INTERNAL_ERROR` | client 응답은 일반 메시지 유지, 안전한 ERROR 기록 |

**(v9 계획 리뷰 수용 — 406 추가)**: `HttpMediaTypeNotSupportedException`(요청 `Content-Type` 문제)과 `HttpMediaTypeNotAcceptableException`(응답 형식 협상 실패)은 서로 다른 예외다. 인증된 정상 요청에 `Accept: text/html`만 붙여도 후자가 발생해 원래 계획의 catch-all(500)로 샐 수 있었다 — 이번 PR의 목표("일반 클라이언트 오류를 500으로 오분류하지 않는다")와 정면으로 충돌하는 구멍이라 4번째 좁은 handler로 추가한다.

포괄적 `TypeMismatchException`/`ConversionNotSupportedException` 전체를 무조건 400으로 바꾸지 않는다. 서버 conversion 설정 결함까지 클라이언트 탓으로 바뀌지 않도록 실제 MVC 입력 변환 예외에 한정한다.

**(v9 계획 리뷰 수용 — BindException 메시지 비노출)**: 기존 `BindException` 처리(`buildValidationMessage()`)는 `FieldError.getDefaultMessage()`를 그대로 응답에 담는다. 이 기본 메시지는 Bean Validation 문구뿐 아니라 **Spring 타입 변환 실패 메시지**로도 만들어질 수 있어, 예를 들어 `GET /admin/api/members?userType=FAKE_TOKEN_MARKER`처럼 열거형 필드에 잘못된 값을 보내면 변환 실패 메시지에 입력 표식이 그대로 반영되는 실제 경로가 있다(400으로 분류된다는 사실이 응답 메시지 안전을 보장하지 않음). 기존 결함이라 이번 PR의 "500 오분류" 범위보다는 넓지만, 같은 파일(`GlobalApiExceptionHandler`)이고 "안전한 진단 로그·민감정보 미포함"이라는 이번 PR의 완료 기준과 직결돼 함께 처리하기로 한다 — `buildValidationMessage()`가 **타입 변환 실패로 생성된 필드 오류**를 감지하면 고정된 안전한 문구로 대체하고, 일반 Bean Validation 문구(`@NotNull`, `@Size` 등)는 기존대로 유지한다.

500 로그에는 method, **라우트 pattern**(가능하면 `HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE`, 없으면 고정 `unmatched`), exception class, 제한된 stack frame 위치를 기록한다. raw URI/query, body, header, cookie, 비밀번호, reset token, 예외 message/`toString()`/cause message는 기본 로그에 넣지 않는다. raw Throwable 전체를 그대로 로깅하면 메일 본문·토큰을 가진 예외의 프로젝트 계약과 충돌할 수 있다.

**(v9 계획 리뷰 수용 — JSON Content-Type 보장)**: 기존 validation handler(`GlobalApiExceptionHandler.java:49`)·JSON 파싱·비즈니스 오류 handler 대부분과 catch-all(`:366`)은 응답 `Content-Type`을 명시하지 않는다. `@RestControllerAdvice`만으로는 `Accept: text/html` 요청에도 JSON 응답이 보장되지 않으며, "HTML이 아니면 통과"라는 회귀 기준은 빈 응답도 통과시키는 약한 검증이다. API 분기(`/admin/api/**`·공개 API 경로)의 모든 오류 응답이 `application/json` Content-Type을 명시적으로 갖도록 보장하는 것을 이번 PR 범위에 포함한다 — 신규 handler 4종뿐 아니라 기존 handler·catch-all에도 동일하게 적용.

진단은 같은 handler 안의 작은 처리로 한정한다. 예를 들어 예외 class와 상위 frame 10개 수준의 제한된 위치 정보로 시작하며, 별도 범용 redaction/logging 프레임워크를 만들지 않는다. 새 error 응답 필드나 추적 ID 체계도 이번 필수 범위가 아니다.

#### Files Likely Affected

- `src/main/java/com/cms/common/api/GlobalApiExceptionHandler.java`.
- 신규 후보: `src/test/java/com/cms/common/api/GlobalApiExceptionHandlerTest.java` — 로그/세부 매핑.
- 신규 후보: `src/test/java/com/cms/common/api/ApiErrorContractIntegrationTest.java` — 실제 Security 포함 요청 행렬.
- 필요 시 기존 `AdminMemberControllerTest`, `MenuControllerTest`의 새 입력 사례.
- `docs/troubleshooting.md`, `.claude/skills/api-conventions/SKILL.md`의 현행 상태 코드 표, 해당 API의 문서 애너테이션은 관련 endpoint에 한정해 확인.
- `ApiErrorResponse`, Security handlers, public advice, CustomErrorController는 기본 변경 대상이 아니라 회귀 검증 대상.

#### Implementation Steps

1. Security를 통과한 실제 API 경로에서 현재 500 재현 테스트를 추가한다(406 포함). method/media type/Accept 검증에 인증·CSRF를 누락해 401/403을 잘못 시험하지 않는다.
2. 기존 advice에 위 네 개의 좁은 handler(400/405/415/406)를 추가하고, 신규 handler뿐 아니라 **API 분기의 모든 오류 응답이 JSON `Content-Type`을 명시적으로 갖도록 보장**한다. 각 handler가 오류 `ResponseEntity`에 JSON Content-Type을 직접 지정하는 방식으로 구현한다 — `@ExceptionHandler(produces = "application/json")`로 handler 선택 자체를 제한하는 방식은 `Accept: text/html` 요청에서 handler가 선택되지 않는 별도 문제를 만들 수 있어 **대체 수단으로 쓰지 않는다**(v10 계획 리뷰 확인). `Accept: text/html` 요청도 API 오류를 HTML로 바꾸지 않는지 확인한다(정상 처리 가능한 조회 + 지원하지 않는 Accept 케이스 포함).
3. catch-all에 안전한 서버 진단을 추가한다. 예외 원문은 client와 로그 모두에 무조건 노출하지 않는다.
4. `buildValidationMessage()`가 타입 변환 실패로 생성된 필드 오류를 감지하면 고정된 안전한 문구로 대체한다. 일반 Bean Validation 문구는 그대로 유지한다.
5. 일반 4xx에는 ERROR stack 기록을 추가하지 않는다. public advice·Security·controller·service에 동일 로그를 중복 삽입하지 않는다.
6. 관련 문서의 상태 코드 계약만 갱신한다. advice 상속 구조·우선순위·public 오류 페이지를 전면 변경하지 않는다.

#### Tests to Add / Update

- 400/401/403/404/405/406/409/415/429/500 행렬은 6절과 Gate E 참조.
- 405 `Allow`, API JSON type·4필드, malformed JSON/validation의 기존 code 유지.
- **406**: 정상 처리 가능한 조회 + 지원하지 않는 `Accept` 조합에서 고정 메시지·JSON 응답·ERROR 이벤트 0개 확인. 기존 오류 요청에 `Accept: text/html`을 붙이는 회귀와는 별도 케이스로 둔다.
- **JSON Content-Type 보장**: 신규 handler뿐 아니라 기존 validation/JSON 파싱/비즈니스 오류/catch-all 전부에서 기대 상태 + `application/json` Content-Type + 실제 4필드 본문을 함께 검증한다("HTML이 아님"만으로 판정하지 않음).
- **BindException 메시지 비노출**: 열거형 등 타입 변환 실패로 생긴 필드 오류에 입력 표식(가짜 토큰 등)을 넣어 응답에 그대로 노출되지 않는지 확인. 일반 Bean Validation 문구(`@NotNull` 등)는 기존 문구가 그대로 유지되는지 함께 확인.
- 예외 메시지·cause·body·query에 가짜 비밀번호/token 표식을 넣고 로그와 client response에 노출되지 않는지 확인. 개인정보가 담길 수 있는 raw 경로 대신 route pattern 사용 검증.
- 별도 로그 없는 테스트 예외에 대해 이 handler의 ERROR 이벤트가 정확히 1개이며 class/진단 위치가 있음. 예상 4xx는 해당 ERROR 이벤트 0개.
- 전체 시스템 로그가 무조건 1줄이라고 단정하지 않는다. DB 자체 로그나 감사 저장 실패는 다른 사건이다. 같은 예외를 catch-and-rethrow하면서 새 ERROR를 추가하는 변경은 금지한다.
- public HTML 500, 관리자/공개 HTML 404, 공개 429 페이지 회귀. HTML 405/415/406 신규 화면 제작은 범위 밖이다.

#### Existing Tests to Re-run

`AdminMemberControllerTest`, `MenuControllerTest`, `NoticeControllerTest`, `NoticeAttachmentControllerTest`, `PasswordResetControllerTest`, `ApiSecurityConfigTest`, `SecurityConfigTest`, `NoHandlerFoundDispatchTest`, `CustomErrorControllerTest`, `PublicNoticeControllerTest`, `RateLimitResponseTest`, `RateLimitFilterTest`.

현재 일부 controller slice는 실제 SecurityConfig 대신 테스트 보안을 쓴다. 이것만으로 전체 체인을 검증했다고 보지 않고 실제 설정을 사용하는 통합 테스트를 별도로 둔다.

#### Regression Risks

예외 우선순위 변경, 401/403을 400/405로 덮기, API에 HTML 응답, token이 포함된 throwable 로깅, 404/429 HTML 분기 파손. `ApiErrorResponse`의 시간 생성 방식은 M-06 제외 정책에 따라 변경하지 않는다.

#### Rollback

스키마 변화 없이 handler·테스트·문서만 revert 가능. 기존 잘못된 500과 진단 공백이 돌아옴을 명시한다. 일반 4xx 계약을 소비하는 새 client가 있다면 rollback 시 함께 호환성을 확인한다.

#### Dependencies

다른 PR 선행 불필요. 기존 pessimistic lock 409 handler를 보존하므로 PR 4는 이 PR 없이도 구현 가능하다.

#### Completion Criteria

전체 오류 행렬(406 포함), Security precedence, API 분기 전체 JSON Content-Type 보장, BindException 타입 변환 메시지 비노출, HTML 회귀, 안전한 진단 로그 검증 통과. 새 예외 architecture·dependency·migration 없음.

#### PR 3 실행 기록 — 2026-09-26

- Context: 사용자가 `/suggestRoadmap` → PR 3 선택 → 계획 리뷰(codex CLI 2라운드, v9·v10, ship) → 구현 착수 순으로 승인. `fix/api-error-contract` 브랜치에서 작업했다.
- 구현: `GlobalApiExceptionHandler`에 `@ExceptionHandler` 4종(`MethodArgumentTypeMismatchException`→400, `HttpRequestMethodNotSupportedException`→405+`Allow`헤더, `HttpMediaTypeNotSupportedException`→415, `HttpMediaTypeNotAcceptableException`→406) 신규 추가. 모든 handler(기존 12개+신규 4개)가 `jsonError(status, path, code, message)` 공통 조립 메서드를 거치도록 리팩터링해 응답 `Content-Type: application/json`을 중앙에서 보장(개별 handler의 `.contentType(...)` 누락 여지 제거). `buildValidationMessage()`에 `FieldError.isBindingFailure()` 분기를 추가해 타입 변환 실패 필드 오류는 고정 문구("입력값 형식이 올바르지 않습니다.")로 대체하고 일반 Bean Validation 문구는 그대로 유지. catch-all(`handleException`)에 안전한 진단 로그(`method`·`HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE` 라우트 패턴(없으면 `"unmatched"`)·`e.getClass().getName()`·`StackTraceElement[]` 상위 10개 프레임) 추가 — Throwable 인스턴스를 로거에 직접 넘기지 않아 message/cause 체인 자동 노출을 피함. `handleMethodNotSupported`는 `Set<HttpMethod>`를 `allow(HttpMethod...)`에 넘기려다 컴파일 오류(가변 인자 불일치)가 나 `toArray(new HttpMethod[0])`로 교정.
- 신규 테스트: `GlobalApiExceptionHandlerTest`(순수 단위 13개 — ERROR 이벤트 정확히 1개·라우트 패턴 fallback·민감정보(예외 message/cause/raw URI) 미노출·400/405(Allow 유/무)/415/406 매핑·BindException 타입 변환 대체/일반 검증 문구 유지), `ApiErrorContractIntegrationTest`(실제 `SecurityConfig` 포함 슬라이스 7개 — 400/405+Allow/415/406/500/`Accept: text/html` 회귀/미인증 401). 기존 `MenuControllerTest`에 경로 변수 타입 불일치(`GET /admin/api/menus/abc`) 400 회귀 1건, `AdminMemberControllerTest`에 검색 열거형 필드 입력 표식 비노출(`?userType=FAKE_TOKEN_MARKER`) 1건 추가 — 둘 다 실제 프로덕션 엔드포인트 대상.
- 이슈: 1라운드 계획 리뷰에서 `HttpMediaTypeNotAcceptableException`(406) 자체가 원 계획에서 완전히 빠져 있던 것을 발견해 구현 범위에 반영(계획 v9). 초기 `ApiErrorContractIntegrationTest`의 406 테스트가 `{id}` 경로에 `produces=APPLICATION_JSON_VALUE`를 얹은 상태로 `Accept: text/html` 타입 불일치 회귀 테스트와 같은 엔드포인트를 공유해, Accept 협상이 핸들러 매핑 단계에서 먼저 실패(406)해버려 기대한 400에 도달하지 못하는 테스트 설계 결함을 발견 — 406 전용 엔드포인트(`/admin/api/error-contract-test-strict`, produces 제약 있음)와 타입 불일치용 엔드포인트(produces 제약 없음)를 분리해 해결.
- 검증 결과: `./gradlew compileJava compileTestJava` 성공. 신규·수정 테스트 클래스 개별 실행 통과 확인 후, `SPRING_PROFILES_ACTIVE=dev ./gradlew test` 전체 실행(Docker Desktop 재기동 후 Testcontainers 포함) — **740개 전체 통과, 실패·오류 0**(신규 21개 순증: 단위 13·통합 7·기존 파일 확장 2, PR 2 이후 기준 720개에서 순증). `docs/troubleshooting.md`(애플리케이션/런타임 카테고리)·`.claude/skills/api-conventions/SKILL.md`(상태 코드 표에 405/406/415, JSON Content-Type 보장, BindException 비노출 반영) 문서 동기화 완료.
- 후속: 없음. 감사 M-03 완료. `/code-review-loop` → `/commitPR` 단계 예정.

### PR 4 — 메뉴 같은 행 쓰기의 비관적 잠금 일관화

#### Goal

메뉴 이름 변경이 다른 트랜잭션이 완료한 비활성화를 되돌리지 않게 한다.

#### Finding

M-04 · Medium · 재현된 Bug / Data Integrity Risk.

#### Current Behavior

[MenuService](../src/main/java/com/cms/admin/menu/service/MenuService.java)의 일반 update만 일반 조회를 사용한다. [MenuRepository](../src/main/java/com/cms/admin/menu/MenuRepository.java)의 `findByIdForUpdate()`는 기존 `PESSIMISTIC_WRITE` JPQL이다. [Menu](../src/main/java/com/cms/admin/menu/Menu.java)는 전체 컬럼 update 대상이며, `upMenuNo`는 수정 DTO/도메인 update에서 변경하지 않는다.

기존 실제 경합은 A 일반 조회 → B 비활성화 커밋 → A 이름 수정 커밋 → `useYn=true` 복귀였다.

#### Target Behavior

일반 update도 **최초 대상 엔티티 조회부터 `findByIdForUpdate()`**를 사용한다. 별도 일반 조회로 엔티티를 먼저 로드한 후 잠금만 추가하지 않는다. null 필드는 **잠금 획득 후 읽은 최신 값**을 유지한다. 조회 API와 사이드바까지 잠그지 않는다.

#### Files Likely Affected

- `src/main/java/com/cms/admin/menu/service/MenuService.java`.
- `src/main/java/com/cms/admin/menu/MenuRepository.java`는 기존 query 재사용, 설명 갱신 정도.
- `src/test/java/com/cms/admin/menu/service/MenuServiceTest.java`.
- `src/test/java/com/cms/admin/menu/service/MenuConcurrencyIntegrationTest.java`.
- 필요 시 `src/test/java/com/cms/admin/menu/controller/MenuControllerTest.java`의 409 regression.
- `src/main/java/com/cms/admin/menu/CLAUDE.md`, `docs/troubleshooting.md`의 해당 동시성 계약.
- Menu entity/DTO/schema/전체 tree 구조는 변경하지 않는다.

#### Implementation Steps

1. 원래 실패 조건을 실제 서비스 호출로 재현하는 same-row 테스트를 먼저 작성해 현재 코드에서 실패를 확인한다. mock 저장소 동작으로 DB 결과를 대신하지 않는다.
2. `updateMenu()`의 `deactivationRequested`에 따른 조회 분기를 제거하고 모든 update에서 기존 잠금 질의를 사용한다.
3. null 필드 보존·활성 자식 검사·부모 재활성화 검증은 유지한다. 쿼리 호출 순서와 실제 transaction 경계를 검사한다.
4. 아래 잠금 그래프를 기준으로 양방향 경합을 검증한다. 회귀가 확인되면 그 좁은 원인을 해결하고, 단순히 “409도 가능”이라고 기존 불변식 테스트를 약화하지 않는다.
5. 업데이트 테스트의 일반 `findById` stub을 잠금 stub으로 변경한다. 조회 전용 테스트는 그대로 둔다.

현재 경로 및 제안 후 잠금 순서:

| 경로 | 제안 후 잠금/조회 순서 | 주의점 |
|---|---|---|
| 같은 메뉴 이름·일반 필드 수정 | 대상 행 X lock → 수정 → commit | 추가 부모 잠금 없음 |
| 해당 메뉴 비활성화 | 대상 행 X lock → 활성 자식 존재 조회 → 수정 | 존재 조회는 현재 별도 `FOR UPDATE`가 아님 |
| 비활성 자식 재활성화 | 자식 X lock → 부모 X lock → 부모 상태 검증 → 수정 | 새로 자식 잠금이 선행됨 |
| 부모 비활성화 | 부모 X lock → 활성 자식 존재 조회 | 기존 자식의 X lock을 별도로 획득하지 않음 |
| 하위 메뉴 생성 | 부모 X lock → 형제 ord 조회 → 새 자식 INSERT | 기존 자식 행을 잠그는 parent→existing-child 경로와 다름 |

이 코드만으로 **“항상 부모→자식 순서”라고 주장할 수 없다.** 추천안은 기존 구조의 작은 target-lock 확대다. 부모 비활성화는 자식 잠금을 취하지 않으므로 정상 경로에서 단순한 child→parent / parent→same-child 교착 쌍은 확인되지 않는다. 부모는 API로 재배치되지 않고 메뉴 FK도 현재 없다. 그렇더라도 DB 격리 수준, SQL의 실제 잠금, 여러 단계 트리 경합은 테스트해야 한다. DB 직접 조작으로 만든 순환 구조까지 지원하기 위해 새로운 전체 tree lock 전략을 도입하지 않는다.

서비스는 단일 `@Transactional` 안에서 읽기·검증·변경·commit을 끝낸다. 네트워크/UI 대기나 SMTP를 추가하지 않는다. 운영 코드에 테스트용 latch를 넣지 않는다. 테스트 계측에서만 after-query/before-query 시점을 제어하고 종료·원복을 보장한다.

#### Tests to Add / Update

**원래 A-read → B-commit → A-commit 순서는 수정 후 같은 행 잠금과 양립하지 않는다.** B 커밋을 기다리며 A의 잠금을 계속 잡으면 테스트가 스스로 교착된다. 다음과 같이 실패 재현과 회귀 계약을 나눈다.

1. **같은 사건의 A 선행 회귀:** A의 실제 서비스 조회 후 대기 → B 비활성화 시작. 구버전에서는 B가 먼저 완료해 최종 true가 되는 것을 확인한다. 수정 후에는 B가 같은 행 잠금을 기다리는 것을 관찰하고 A를 해제한다 → A 이름 commit → B 비활성화 commit → 최종 이름은 A 값, `useYn=false`. coordinator는 B commit만을 기다리지 않고 A 해제를 보장한다. query 진입 latch와 bounded future 대기를 사용해 무한 대기를 막는다. **Future 미완료만을 잠금 증거로 삼지 않는다.** 실제 서비스/저장소 호출 진입, SQL과 connection 식별 또는 검증용 DB lock-wait 관측, 해제 뒤 양쪽 commit 및 최종 값을 함께 확인한다. 관측에 필요한 권한은 일회용 테스트 DB에만 사용하며 운영 계정 권한을 넓히지 않는다.
2. **B 선행 회귀:** B가 비활성화 잠금을 잡은 상태에서 A update 진입 → B commit → A가 최신 false를 읽고 이름 수정 → 최종 false와 새 이름 유지.
3. 화면에서 A가 먼저 GET한 뒤 B가 비활성화하고 A가 이름만 PATCH하는 실제 HTTP 순서도 확인한다. GET과 PATCH는 서로 다른 transaction이므로 원래 서비스 내부 stale entity 문제와 구분한다.
4. 기존 부모 비활성화 vs 자식 재활성화 테스트 유지. 양방향 latch 순서에서도 비활성 부모 아래 활성 자식이 남지 않아야 한다.
5. 부모 비활성화 vs 활성 자식 생성, 같은 행 일반 수정끼리의 서로 다른 필드 보존, lock timeout의 409 및 전체 transaction rollback 확인.
6. 실제 생성 SQL에 잠금이 적용되는지 확인한다. 전체 컬럼 UPDATE 자체를 없애는 것이 완료 기준은 아니다. 최신 상태를 잠금 안에서 유지하는 것이 기준이다.

테스트는 기존 `MariaDbContainerSupport`와 독립 transaction을 사용한다. 클래스 전체 `@Transactional`로 여러 thread의 commit을 가리지 않는다. 대기 제한·executor 종료·fixture 삭제를 `finally`/정리 단계에서 보장한다. 단순 sleep 후 결과 하나만 확인하거나, 예상하지 못한 모든 예외를 성공으로 흡수하지 않는다.

#### Existing Tests to Re-run

`MenuServiceTest`의 null-useYn/ord/accessRole 보존, 재활성화, 비활성화 및 잠금 호출 테스트는 stub 변경 영향이 있다. `MenuConcurrencyIntegrationTest`, `MenuControllerTest`, `AdminSidebarAdviceTest`, `SecurityConfigTest`를 재실행한다.

최종 Gate D에서는 기존 member 경합 테스트도 수행하되 이 PR에서 해당 로직을 수정하지 않는다.

#### Regression Risks

잠금 대기 증가, child→parent 잠금 추가로 인한 기존 parent 경합 변화, 테스트 하니스 교착, 선행 일반 조회/1차 캐시로 인한 stale 값, 잘못된 null 처리. 기존 엔티티를 다른 경로에서 미리 로드하는 호출이 추가된다면 잠금 조회만으로 항상 refresh된다고 가정하지 않는다.

#### Rollback

migration 없이 서비스 변경 revert 가능. 다만 lost update가 재발하므로 문제 발생 시 해당 메뉴의 동시 편집을 제한하고 수정 버전 유지/fix-forward를 우선 검토한다. rollback에 데이터 일괄 변경은 필요 없다.

#### Dependencies

기존 409 handler 재사용. PR 3는 편리한 통합 검증 순서이지 잠금 구현의 필수 의존이 아니다.

#### Completion Criteria

same-row 양방향 실제 MariaDB 회귀에서 비활성화·이름 수정 모두 보존. 기존 부모/자식 불변식 유지. 예상 밖 deadlock/timeout을 숨기지 않음. 신규 version column/migration 없음.

### PR 5 — SMTP timeout 설정과 운영 전달

#### Goal

SMTP 연결·읽기·쓰기 지연을 유한한 timeout으로 제한한다. executor나 메일 workflow는 바꾸지 않는다.

#### Finding

M-02 · Medium · Operational Risk.

#### Current Behavior

`application-prod.yml`은 SMTP auth/STARTTLS만 설정한다. `PasswordResetService`는 token commit 후 비동기 전송, send/dispatch 예외 처리, token 해시 일치 시 조건부 정리를 이미 수행한다.

현재 설정 스타일은 `MAIL_USER` 등 명시적 환경변수 → compose environment → Spring placeholder이다. `.env.prod`에 키만 추가하면 컨테이너에 자동 전달되지 않는다. `scripts/_prod-env-guard.sh`는 compose보다 우선하는 host shell 변수를 unset하여 `.env.prod` 단일 입력 정책을 지킨다.

#### Target Behavior

보수적인 초기 후보는 **connection 10초 / read 30초 / write 30초**다. 작은 reset 메일이지만 네트워크·SMTP 응답 변동을 감안해 수백 ms 수준의 공격적인 운영값은 피한다. 이 값은 관측 기반 최종 최적값이 아니라 시작점이며 staging SMTP 검증 후 승인한다.

| SMTP property | prod 기본 후보(ms) | 선택 환경변수 |
|---|---:|---|
| `mail.smtp.connectiontimeout` | 10000 | `MAIL_SMTP_CONNECTION_TIMEOUT_MS` |
| `mail.smtp.timeout` | 30000 | `MAIL_SMTP_READ_TIMEOUT_MS` |
| `mail.smtp.writetimeout` | 30000 | `MAIL_SMTP_WRITE_TIMEOUT_MS` |

JavaMail 속성은 `spring.mail.properties` 아래의 실제 위 key로 전달한다. 환경변수 이름의 자동 relaxed binding을 추측하지 않고 명시 placeholder를 사용한다. 적용된 `JavaMailSenderImpl` properties를 확인한다.

세 설정은 socket 단계 timeout이며 **작업 전체 70초 deadline이나 DNS·queue 대기 시간 제한을 보장하지 않는다.** 일부 기본 timeout이 무한인 점과 속성은 [Spring Boot 3.5 메일 설정](https://docs.spring.io/spring-boot/3.5/reference/io/email.html), ms 단위와 socket별 의미는 [Angus SMTP 문서](https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/package-summary.html)를 참고했다. 10/30/30초는 이 프로젝트를 위한 제안값이지 공식 권장 숫자를 인용한 것이 아니다.

#### Files Likely Affected

- `src/main/resources/application-prod.yml`.
- `docker-compose.prod.yml`의 세 선택 environment mapping.
- `.env.example`의 선택 키·ms·기본값 설명. 실제 `.env.prod`는 운영자가 적용하며 커밋하지 않음.
- `scripts/_prod-env-guard.sh`의 변수 목록에 세 키 추가 — SMTP config 전달 일관성만 보완.
- `docs/deployment.md`의 메일 설정·검증·변경 반영 방식.
- 신규 후보: `src/test/java/com/cms/config/ProdMailTimeoutConfigurationTest.java`.
- 신규 후보: `src/test/java/com/cms/admin/member/service/PasswordResetMailTimeoutIntegrationTest.java`.
- 기존 `PasswordResetServiceTest` 필요 시 보강. executor/PasswordResetService 제품 로직 변경은 예정하지 않음.

#### Implementation Steps

1. 실제 prod YAML을 로드한 설정 테스트로 현재 미설정을 확인한다. 테스트 코드에만 정답 property를 주입해 운영 YAML 검증을 생략하지 않는다.
2. YAML placeholder와 compose fallback을 각각 명시한다. 예: 누락 시 10000/30000/30000. compose에서는 빈 값도 fallback되도록 `:-` 형태를 사용한다.
3. 선택 키를 `.env.example`, guard 목록, 배포 문서에 함께 연결한다. host shell 잔존 값이 `.env.prod`를 덮지 않는지 확인한다.
4. 실제 Bean의 세 속성과 로컬 SMTP read 지연 종료를 확인한다. token cleanup은 기존 구현을 유지한다.
5. 운영값 변경은 컨테이너 **재생성/재기동으로 환경을 다시 읽어야 함**을 문서화한다. 파일만 편집하거나 기존 컨테이너 `restart`만 하는 것으로 새 환경이 적용된다고 가정하지 않는다.

#### Tests to Add / Update

- 기본값, 유효한 override, 각 ms key가 실제 JavaMail properties에 들어가는지 검사.
- compose 누락/빈 값 fallback과 `.env.prod`/host 우선순위 검증. 검증에는 dummy secrets만 사용하고 `compose config` 전체 출력에 실제 secret을 노출하지 않는다.
- **잘못된 값:** `0`, 음수, 단위 문자열(`30s`), 비정수, 과대 정수는 지원하지 않는다. 현재 mail properties는 Map이라 Spring 기동이 반드시 실패한다고 보장할 수 없다. invalid override를 조용히 “안전한 기본값으로 복구된다”고 문서화하지 않는다. Gate F에서 최종 resolved 값이 양의 정수 ms인지 검증하고, 부적합하면 배포 중단·값 제거/수정 후 재검증한다. direct JAR의 빈 문자열 override 역시 compose의 `:-` 보장 밖이다.
- 별도 제품 validator를 새로 만들지 않는다. 설정 검증 fixture로 위 잘못된 값을 식별하는 절차를 고정하고, 배포자가 최종 값 확인 책임을 가진다.
- 테스트용 read timeout은 짧게 override해 로컬 서버가 greeting/응답을 보내지 않는 경우 유한 시간에 반환하는지 확인. 연결 거부는 즉시 실패 테스트이지 connection timeout 재현이 아님을 구분.
- connection/write hang은 별도 통제된 네트워크 fault/socket 시험으로 검증한다. 작은 reset 메일 하나로 write block이 반드시 발생한다고 가정하지 않는다. 테스트 fixture에서만 충분한 write/읽기 정지로 backpressure를 만들고 전체 관찰 deadline을 둔다. 외부 SMTP로 폭주시키지 않는다.
- 전송 지연 후 예외에서도 HTTP 응답 균일성, 발급 token 조건부 정리, 새 token 보호, 정리 실패 시 TTL 방어 유지. 외부 수신자에게 테스트 메일을 보내지 않는다.

#### Existing Tests to Re-run

`PasswordResetServiceTest`, `PasswordResetConcurrencyIntegrationTest`, `PasswordResetControllerTest`, `PasswordExpiryIntegrationTest`, `LoginFailureLockoutIntegrationTest`, `ProfileGuardEnvironmentPostProcessorTest`, `ActuatorExposureTest`.

#### Regression Risks

값을 지나치게 짧게 설정해 정상 메일까지 실패, ms/초 혼동, .env만 추가하고 compose 전달 누락, 잘못된 값의 provider fallback, 실제 SMTP 전송은 완료됐으나 응답이 늦어 token이 정리되는 기존의 실패 모호성. 자동 retry는 이번 해결책에 넣지 않는다.

#### Rollback

설정값을 검증된 더 여유 있는 **유한 값**으로 되돌리고 컨테이너를 재생성한다. timeout 삭제로 무한 대기를 다시 여는 rollback은 피한다. DB migration·token 일괄 삭제는 불필요하다.

#### Dependencies

코드상 독립. 구현 착수 승인 이후 필요하면 순서를 앞당길 수 있다. 최종 reset 복구 검증은 PR 2와 함께 수행한다.

#### Completion Criteria

prod 기본/override 적용 확인, 세 timeout의 유효한 값 검증, 로컬 지연 종료·token 정리 회귀 통과. executor/queue/dependency 변경 없음.

### PR 6 — Backup/Restore 및 ingress 배포 계약

#### Goal

기존 도구를 안전하게 운영할 기준과, 외부 공개 전 확인할 네트워크 신뢰 경계를 문서화한다.

#### Finding

M-01 · Medium Operational Risk, M-05 · Low Deployment Requirement.

#### Current Behavior

[prod-backup.sh](../scripts/prod-backup.sh)는 앱을 정지하지 않으며 DB dump 뒤 파일 tar를 수행한다. [prod-restore.sh](../scripts/prod-restore.sh)는 검사·대화형 승인·정지·안전 백업·DB/파일 교체·재기동을 수행한다. 파괴적 복구 시작 후 실패하면 앱을 정지 상태로 유지한다. 컨테이너/volume/health endpoint 이름은 고정이다.

`docs/deployment.md`는 online 불일치와 quiesced 절차를 이미 설명하지만 정기 cron 예시는 online이다. 정규 recovery 기준을 명확히 선택할 필요가 있다. 앱은 loopback에 게시되고 실제 ingress 제품은 정해져 있지 않다.

#### Target Behavior

현재 규모에서는 **quiesced를 정규 recovery backup으로 추천**한다. 중단 시간·주기·담당자는 운영자가 확정한다. online은 서비스 연속성을 우선하는 보조 백업으로 남길 수 있으나 동일 시점 복구 보장으로 표시하지 않는다.

실제 ingress가 결정되기 전에는 특정 proxy 설정이나 새 IP abstraction을 구현하지 않는다. 7절의 checklist를 실제 경로에서 통과해야 인터넷 공개할 수 있다.

#### Files Likely Affected

- `docs/deployment.md`.
- 신규 후보: `docs/verification/recovery-drill.md`, `docs/verification/deployment-edge.md`.
- `docs/troubleshooting.md`에 새로 검증한 운영 실패 처리만 필요 시 기록.
- backup/restore scripts·Makefile·Dockerfile·SecurityConfig·limiter·IP resolver 제품 코드 변경 없음.

#### Implementation Steps

1. online/quiesced 모드의 보장과 정규 복구 기준을 분리한다. 기존 online cron 예시는 online이라고 표시하고 정규 recovery backup과 혼동하지 않게 한다.
2. 정지 확인·DB 유지·backup 성공/실패·앱 재개·health 확인·운영자 통보 책임을 runbook에 적는다.
3. **격리 Docker daemon/폐기 가능한 VM**을 준비한다. 고정 container/volume 때문에 같은 daemon의 `compose -p`만 바꾸는 것은 격리가 아니다. 이름 override를 지원하도록 script를 바꾸지 않는다. restore의 health probe는 Docker context가 아니라 실행 셸의 `127.0.0.1:8080`을 보므로, **스크립트 실행 셸과 검증용 앱의 localhost가 같은 격리 VM 안에 있도록 한다.** 호스트에서 원격 Docker context만 바꾼 실행은 충분하지 않다.
4. dummy 데이터로 quiesced backup→변경→restore drill을 실행하고 참조 파일·공지·프로필·권한·재기동을 확인한다. 손상 checksum은 별도 복사본으로 시험한다.
5. M-05 체크리스트를 제품 중립적으로 추가한다. 기존 nginx 예시는 최종 제품 선택이나 설정 검증 완료로 읽히지 않게 정리한다.
6. 동작이 바뀐 bootstrap/SMTP 문서를 다시 통합 검토하되 각 PR의 코드 변경을 재혼합하지 않는다.

#### Tests to Add / Update

영구 운영 시험 절차와 결과 양식을 남긴다. DB/파일 내용을 실제 바꿔 복원이 일어났음을 확인하고, 단순히 health가 200인 것만으로 성공 처리하지 않는다. 상세 drill은 7절과 Gate G에 따른다. ingress가 없으면 Gate H는 **외부 공개 미검증**으로 남긴다.

#### Existing Tests to Re-run

제품 코드 변경이 없으므로 이 PR에 새 단위 테스트 의무는 없다. 통합 RC의 Gate A 결과를 연결하고, shell syntax/문서 명령은 격리 환경에서 확인한다. 실제 restore는 별도 명시적 승인 아래 검증 환경에서만 실행한다.

#### Regression Risks

고정 이름의 운영 컨테이너를 테스트 대상으로 오인, backup 실패 후 앱 방치, restore 실패 후 부분 복원 앱을 잘못 재기동, 권한/UID 손실, 기존 online cron을 정합 백업으로 오표기, 실제 client IP 대신 proxy IP로 quota 공유.

#### Rollback

문서 변경 revert 가능. **실행한 restore는 문서 revert로 되돌아가지 않는다.** drill은 폐기 가능한 환경에서만 수행하며 운영 복구 시에는 기존 안전 백업과 수동 확인 절차를 따른다. 도구의 성공 시 재기동/실패 시 정지 정책을 변경하지 않는다.

#### Dependencies

drill의 최종 대상 이미지는 PR 2/5를 포함해야 한다. ingress 검증은 실제 topology 결정에 의존하며, 문서 PR 완료와 Gate H 완료는 별개다.

#### Completion Criteria

정규 backup 모드·중단 허용·담당자 확정, 격리 복원 증거 확보, 실패 처리 확인, ingress checklist와 미검증 범위 명시. 새 backup platform·proxy abstraction 없음.

## 6. Test Plan

| 검증 층 | 추가/보강할 증거 | 환경 및 주의 |
|---|---|---|
| Unit | H-01 역할·상태 분기/충돌 흡수, M-03 매핑·안전 로그, M-04 null 필드/잠금 호출 | Mockito가 실제 transaction 직렬화를 증명한다고 간주하지 않음 |
| MVC + Security | C-01 본인 수정/ADMIN 403, M-03 행렬, 기존 reset/CSRF | 실제 SecurityConfig·method security 포함 경로 필요 |
| MariaDB | H-01 기동 fixture/unique 경합, M-04 양방향 same-row 및 parent/child | 기존 Testcontainers 재사용, 기동 행렬은 별도 schema로 dev seed 격리 |
| Browser | C-01 저장→다른 역할 상세→문자 표시·DOM 0·이메일 버튼 | 실제 app, dummy 계정, 외부 요청 차단/제한, 결과·스크린샷 기록 |
| Startup | H-01 8상태+자동/수동 LOCKED, prod JAR runner 완료 | dev 환경변수 혼입 금지, 안정 health·프로세스 확인 |
| Configuration / SMTP | prod YAML→compose→실제 mail properties, 지연 종료·token 정리 | 외부 SMTP 의존 없는 local fixture; operation deadline과 socket timeout 구분 |
| Deployment / Recovery | 고정 이름 도구의 격리 daemon 복원, 실제 ingress 2-IP | 실제 운영 DB/volume에서는 재현 금지 |

오류 계약의 최종 행렬:

| 사례 | HTTP / code | 시험 전제 |
|---|---|---|
| malformed JSON | 400 / `JSON_PARSE_ERROR` | 유효한 인증/CSRF |
| validation error | 400 / `VALIDATION_ERROR` | 유효 JSON |
| invalid path parameter | 400 / `INVALID_REQUEST` | 실제 숫자 ID mapping, 인증 통과 |
| unauthenticated API | 401 / `UNAUTHORIZED` | 기존 필터 동작 유지 |
| 역할 부족 / 인증 사용자의 CSRF 실패 | 403 / `ACCESS_DENIED` | 미인증 CSRF 실패는 기존 401이므로 구분 |
| resource/handler missing | 404 / `RESOURCE_NOT_FOUND` | API JSON; 페이지는 기존 HTML |
| unsupported method | 405 / `METHOD_NOT_ALLOWED` | Security가 허용하는 API 경로 + CSRF + `Allow` |
| duplicate | 409 / `DUPLICATE_RESOURCE` | 기존 unique 처리 유지 |
| conflict / pessimistic lock failure | 409 / `RESOURCE_CONFLICT` | reset의 별도 400/200 은닉 계약 제외 |
| unsupported media type | 415 / `UNSUPPORTED_MEDIA_TYPE` | 인증/CSRF, JSON 소비 API에 text/plain |
| rate limit | 429 / `RATE_LIMITED` | limiter 명시 활성화, `Retry-After` 확인 |
| unexpected server failure | 500 / `INTERNAL_ERROR` | 서버 진단 기록, client 내부 정보 미노출 |

Gradle test 태스크는 기본적으로 `SPRING_PROFILES_ACTIVE=dev`, `CMS_RATE_LIMIT_ENABLED=false`를 주입한다. startup/prod와 429 테스트는 이를 명시적으로 덮고 실제 활성값을 확인해야 한다. 기존 Windows symlink 시험은 환경에 따라 skip될 수 있으므로 skip 개수만 보지 말고 이유와 Linux 결과를 확인한다.

## 7. Operational Changes

### M-01 — 두 backup 모드

| 구분 | Online Backup | Quiesced Recovery Backup — 추천 정규 모드 |
|---|---|---|
| 앱 쓰기 | 계속 허용 | 모든 application writer 정지·진행 중 쓰기 종료 확인 |
| DB | 실행 중, transaction dump | 실행 중, 정지된 앱 상태를 dump |
| 파일 | 뒤 시점 tar | 앱 쓰기가 없는 상태에서 tar |
| 보장 | DB/file 동일 시점 아님, missing/orphan 가능 | 외부 writer도 없다는 전제에서 DB/file 경합 제거 |
| 사용 목적 | 보조·낮은 중단 비용 | 실제 복구 기준 |
| 실패 후 책임 | 실패 기록·기존 유효 backup 유지 | 시작 전 상태 기록 후 담당자가 앱 재개·health 확인 |

정규 백업 runbook은 다음을 명시한다.

1. daemon/컨테이너/volume/출력 경로를 확인하고 다른 backup/restore·외부 writer가 없는지 확인한다.
2. 앱이 원래 실행 중이었는지 기록한다. 앱만 정지하고 실제 정지 확인 후 backup한다. DB까지 내리는 `prod-down`을 사용하지 않는다.
3. DB dump→파일 tar→checksum 성공 여부를 기록한다. 기존 script는 실패 시 앱을 자동 재개하지 않으므로 담당자가 종료 코드와 무관하게 재개 책임을 가진다.
4. **backup 실패만으로 DB/file이 복원 중인 것은 아니다.** 원래 실행 중이던 앱은 쓰기 작업 종료/다른 복구 부재를 확인해 다시 기동하고 health를 확인한다. 원래 정지돼 있던 앱은 승인 없이 임의 기동하지 않는다.
5. **restore의 파괴적 단계 이후 실패는 다르다.** 이때는 기존 trap처럼 앱을 정지 상태로 유지하고 안전 백업으로 별도 복구 판단한다. backup 실패의 재개 규칙을 restore에 적용하지 않는다.
6. 백업 성공 여부·모드·중단 시간·image/commit·산출물 경로를 운영 기록에 남긴다. manifest 형식을 새로 바꾸지 않아도 된다. 기존 retention 14일과 같은-host 한계는 그대로 명시한다.

정규 주기·허용 중단 시간·보관 위치는 실제 운영자가 승인한다. 무중단이 필수라면 online 한계를 수용할지 별도 판단하며 이 계획이 자동으로 새 snapshot 시스템 구축으로 전환되지는 않는다.

### 격리 restore drill

1. **전용 Docker daemon/VM**을 식별하고 운영과 연결되지 않음을 확인한다. 현재 스크립트의 고정 `cms-*-prod`, volume 이름, `127.0.0.1:8080`을 그 환경 안에서만 사용한다. 단순 compose project-name 변경으로 격리를 주장하지 않는다. 스크립트도 격리 VM 안에서 실행해 Docker 대상과 host-side curl의 localhost 대상이 일치하게 한다. 원격 context만 바꾸고 로컬 운영 앱의 health를 잘못 확인해서는 안 된다.
2. synthetic ADMIN/MANAGER, 공개/비공개 공지, 공지 첨부, 업로드 프로필, preset/legacy 예외 fixture를 준비한다. 진짜 사용자 데이터·secret은 사용하지 않는다.
3. backup 직전 DB 참조 목록과 대표 파일 hash를 기록하고 quiesced backup을 생성한다.
4. fixture를 변경한 뒤 **신뢰한 백업**으로 restore해 변경 전 데이터가 돌아오는지 확인한다. 필요하면 동일한 이름의 빈 스택을 전용 환경에서 구성하여 새 volume 복구도 검증한다.
5. 공지 attachment의 모든 key와 `UPLOADED` 프로필의 `profile/` namespace 파일 존재를 확인한다. PRESET/NONE/LEGACY_INLINE을 업로드 파일로 잘못 판정하지 않는다. 공개 첨부는 공개 조건을 만족하는 fixture에서 실제 다운로드한다.
6. 대표 첨부·프로필 바이트 hash, content type, 공지 공개/비공개, 관리자 프로필 표시, 파일 UID/GID `10001:10001`, 앱 사용자의 읽기·새 업로드·삭제 가능 여부를 확인한다.
7. Flyway validate, 최종 runner 완료, health와 RestartCount 안정성을 확인한다. H-01의 LOCKED/EXPIRED가 있는 복원본도 상태를 바꾸지 않고 기동해야 한다.
8. 백업 복사본의 checksum을 고의로 불일치시켜 복구가 **파괴적 변경 전** 거절되고 기존 fixture가 유지되는지 확인한다. 원본 유효 백업은 보존한다.
9. 파괴적 단계 이후의 통제된 실패는 이 격리 환경에서만 시험하고, 앱 정지·안전 백업 안내·수동 재복구를 확인한다. 테스트 종료 후 환경을 폐기하되 검증 증거는 남긴다.

### M-02 — SMTP 운영 설정

세 선택 timeout 키의 단위는 ms다. `.env.prod`→compose→실제 mail Bean을 대조하고, 누락/빈 값 fallback과 override 출처를 확인한다. 세 값은 positive integer여야 하며 잘못된 값은 배포 승인 불가다. 설정 변경 후 컨테이너 재생성, 실제 test-only SMTP 정상 전송 및 fault probe를 수행한다. 전용 executor·재시도 정책은 추가하지 않는다.

### M-05 — 제품 중립 Deployment Contract

- [ ] 외부 client → ingress → backend의 실제 hop과 TLS 종료 위치를 기록했다.
- [ ] host ingress라면 loopback backend 접근을 확인했다. ingress가 container라면 그 container의 `127.0.0.1`은 앱 host가 아니라는 점을 반영해 실제 private 경로를 확인했다.
- [ ] backend host port는 인터넷에 직접 노출되지 않으며 별도 private/container 경로의 우회도 제한했다.
- [ ] client가 보낸 X-Forwarded-For / X-Real-IP / Forwarded 및 scheme 관련 헤더를 ingress가 제거·재작성한다. 여러 proxy가 있으면 승인된 hop 정책을 명시했다.
- [ ] 앱이 신뢰할 proxy 범위를 명시했다. `forward-headers-strategy` 한 줄만으로 검증 완료라고 보지 않는다.
- [ ] 서로 다른 두 외부 client가 서로 다른 remote address/rate-limit key로 관찰된다. 같은 NAT의 두 브라우저를 “두 IP”로 오인하지 않는다.
- [ ] 임의 forwarded 헤더를 바꿔 quota를 회피하거나 감사 IP를 위조할 수 없는지 시험했다. 감사 IP와 limiter가 각기 다른 해석을 하는 현재 코드도 실제 경로에서 대조했다.
- [ ] HTTPS 외부 요청을 앱이 올바른 scheme으로 인식하고 `Secure`/`HttpOnly` cookie, HTTP→HTTPS redirect, 혼합 콘텐츠, redirect loop를 확인했다.
- [ ] `APP_BASE_URL`의 reset 링크가 최종 HTTPS origin과 일치한다.

proxy 제품·trusted range·인증서·호스트가 미정이면 이 항목은 미완료다. 이번 PR에서 임의 제품 설정을 정하지 않는다. 체크 실패가 실제로 발견되면 해당 ingress 통합 범위의 최소 수정안을 별도 승인받는다.

## 8. Documentation Updates

| 문서 | 변경 소유 PR | 필요한 정합성 |
|---|---|---|
| member `CLAUDE.md` | PR 2 | ACTIVE-only bootstrap 설명을 승인된 allowlist로 변경. 로그인 ACTIVE-only와 구분 |
| `.env.example`, compose 주석 | PR 2/5 | bootstrap 적용 상태, timeout 선택값·단위·default. 실제 secret 추가 금지 |
| `docs/deployment.md` | PR 2/5/6 | bootstrap 행렬·복구·SMTP 전달·정규 backup 모드·ingress 조건 |
| API conventions 문서 | PR 3 | 405/415/406의 추가 계약, 기존 400/401/403/404/409/429 유지 |
| menu `CLAUDE.md` | PR 4 | 같은 대상 update 잠금, 부모 경로의 실제 순서 |
| `docs/troubleshooting.md` | 관련 PR | 원인·수정·회귀 증거만 기록; 해결하지 않은 것을 완료로 표시하지 않음 |
| 신규 `docs/verification/*` | PR 1/6 | 브라우저 입력·예상 DOM, 격리 restore/ingress 실행 절차와 증거 |
| 본 계획 | 각 PR 완료 시 | 실제 파일·테스트 결과·미검증 항목을 구분해 기록 |

직접 관련된 기존 문서 drift도 같은 문단에서 정정한다. 예를 들어 deployment 문서의 bootstrap 비밀번호 “4자 미만” 설명은 현재 credentials의 15 코드포인트/72 UTF-8 byte 정책과 맞지 않는다. Swagger 404를 여전히 500이라고 적은 알려진 제약도 현재 handler/tests와 대조해 갱신한다. 새 보안 정책을 만드는 작업이 아니다.

9/17·9/22·9/23 감사 문서는 **과거 이력으로 보존**한다. 9/22의 M-06을 현재 미완료 결함으로 복사하지 않는다. 기존 로드맵 완료 체크나 커밋/PR 생성은 이번 계획 작성의 범위가 아니다.

## 9. Regression Risk Matrix

| 변경 | 주요 회귀 위험 | 방어 테스트 |
|---|---|---|
| C-01 평문 escape | 이메일 버튼 파손, 이중 escape | 실제 DOM 0, 원문 text 비교, 복사 버튼/프로필/편집 UI |
| H-01 skip 조건 | MANAGER-only 통과, 폐기/수동 잠금 부활 | 8상태+수동/자동 LOCKED, DB 필드 불변, 로그인/reset 거절 |
| H-01 reconciliation | 중복 충돌 과도 흡수, credential 덮어쓰기 | 같은 ID 역할/상태별 unique 충돌 DB 테스트 |
| M-03 새 handler(400/405/415/406) | Security/HTML 오류 precedence 회귀, 406 handler-선택 제한 부작용 | 전체 보안 포함 행렬, API JSON Content-Type 전체 보장, 기존 public/admin HTML |
| M-03 진단 로그 | body/token/예외 메시지 유출, 중복 ERROR | 민감 표식 부재, 안전한 위치 정보, handler 이벤트 수 |
| M-03 BindException 비노출 | 타입 변환 실패 판별 오류(`isBindingFailure()`)로 일반 검증 문구까지 대체 | 일반 Bean Validation 문구 유지 확인, 타입 변환 실패 케이스만 고정 문구 확인 |
| M-04 target lock | 대기·deadlock, stale 1차 캐시, 하위 불변식 위반 | MariaDB 양방향·부모/자식·생성 경합, 409/rollback |
| M-02 timeout | 단위/키/전달 오류, 무한 fallback, 정상 메일 실패 | resolved Bean properties, invalid 설정 거절 Gate, local fault 및 정상 SMTP |
| M-01 운영 절차 | 잘못된 daemon 파괴, 정지 앱 방치, 부분 복원 기동 | daemon 식별, checksum 선검사, 실패 단계별 정지/재개, file hash/UID |
| M-05 ingress | proxy IP로 quota 공유, forwarded spoof, scheme/cookie 오류 | 실제 2-IP·위조 헤더·직접 접근·HTTPS/cookie 시험 |

## 10. Production Readiness Gates

### Gate A — Build / Full Tests

- [ ] 최종 RC HEAD·제품 diff·dependency·migration 목록을 기록했다. 예상 밖 변경은 별도 승인 없이 포함하지 않았다.
- [ ] Docker가 있는 환경에서 `gradlew.bat clean compileJava compileTestJava test bootJar --console=plain` 성공. Linux CI의 전체 테스트도 통과했다.
- [ ] 기존 Flyway V1부터 현재 끝까지 새 DB에 적용되고 `ddl-auto=validate`가 성공했다. 기존 migration checksum을 바꾸지 않았다.
- [ ] 기존 DB 형태의 격리 복원본에서도 validation·기동을 확인했다. 이번 PR에 새 migration이 없어야 한다.
- [ ] skipped/aborted test를 사유별 검토했다. Windows symlink 환경 skip을 Linux 증거로 보완하고 새 핵심 경합/기동 테스트가 Docker 부재 때문에 skip되지 않았다.

### Gate B — Security / Functional Regression

- [ ] C-01 MANAGER 저장→ADMIN 상세의 실제 브라우저 회귀 통과: 원문 문자 표시, 새 표식 DOM 요소 0.
- [ ] 정상 이름·이메일 복사·프로필·목록/요약/편집 UI 통과.
- [ ] ADMIN/MANAGER 인가, 직접 URL/API 403, CSRF, session 만료 계약 유지.
- [ ] password reset의 균일 응답·일회용 token·만료·계정 상태 거절·새 token 보호 통과.

### Gate C — Startup / Recovery

- [x] H-01 D-01 A안 정책이 명시 승인됐다(2026-09-23). 아래 구현·검증 항목은 아직 미완료다.
- [ ] empty / ACTIVE / LOCKED / PASSWORD_EXPIRED / DISABLED / DELETED / MANAGER-only / mixed ADMIN의 prod 기동 행렬 통과.
- [ ] 자동 잠금 만료 전·후와 수동 잠금을 구분했고 기동 자체는 계정 필드를 변경하지 않았다.
- [ ] 빈 DB의 자격증명 누락 실패·유효 신규 ADMIN 생성, 중복 ID/이메일 거절, 기존 allowlist 계정의 변수 없는 기동을 확인했다.
- [ ] prod JAR의 runner 완료와 안정 상태를 확인했다. dev 기본 계정은 생성되지 않았다.
- [ ] 기동 후 요청 경로의 lazy unlock/reset만 원래 정책대로 동작했다.

### Gate D — Concurrency

- [ ] M-04 A 선행/B 선행의 최종 false+새 이름 유지와 실제 대기/커밋 순서를 확인했다.
- [ ] 기존 메뉴 부모 비활성화/자식 재활성화 및 자식 생성 경합 통과.
- [ ] `AdminMemberUpdateConcurrencyIntegrationTest`, `AdminMemberEmailResetTokenConcurrencyIntegrationTest`, `PasswordResetConcurrencyIntegrationTest`, `LoginFailureConcurrencyIntegrationTest`, `AdminBootstrapConcurrencyIntegrationTest` 통과.
- [ ] 무한 latch 대기·fixture 잔존·예상 밖 예외 무시가 없고 lock timeout/409에서 부분 변경이 남지 않았다.

### Gate E — Error Contract

- [ ] 6절의 400/401/403/404/405/409/415/429/500 행렬을 실제 Security 포함 경로에서 통과했다.
- [ ] 405 Allow, 429 Retry-After, API JSON shape·Content-Type을 확인했다.
- [ ] 미인증 CSRF 실패 401 등 기존 우선순위를 유지했다. limiter 시험은 명시적으로 활성화했다.
- [ ] 예상 밖 500 진단에 원인 class/위치가 있고 body/password/token/query/exception message가 없다. 불필요한 중복 ERROR를 추가하지 않았다.
- [ ] public HTML 500 및 admin/public HTML 404/공개 429 회귀 통과.

### Gate F — SMTP

- [ ] 최종 실행 환경의 connection/read/write 속성이 모두 유한한 양의 정수 ms이며 승인된 값과 일치한다.
- [ ] 기본값·override·누락·compose 빈 값·host 우선순위를 확인했고 잘못된 override로 배포하지 않았다.
- [ ] local read 지연, 즉시 연결 실패, 통제된 connection/write 지연 결과를 구분해 기록했다. 세 속성 binding만으로 모든 fault 재현 완료라고 쓰지 않았다.
- [ ] token 조건부 정리·최신 token 보호·TTL·정상 reset flow 통과. 승인된 staging SMTP 목적지에서 정상 발송 시간도 확인했다.
- [ ] executor/queue/broker/retry 구조가 변경되지 않았다.

### Gate G — Backup / Restore

- [ ] quiesced를 정규 recovery backup으로 쓸지 운영자가 승인하고 담당자·주기·허용 중단을 기록했다.
- [ ] 전용 daemon/VM의 synthetic 데이터로 실제 script backup→restore를 수행했다.
- [ ] DB key 참조 파일·공지·프로필·대표 hash·UID/GID·앱 쓰기 권한을 확인했다.
- [ ] Flyway validate·runner 완료·안정 health를 확인했다.
- [ ] checksum 실패는 파괴 전 차단, 파괴 후 실패는 앱 정지 유지, 일반 backup 실패는 담당자의 재개 확인으로 구분했다.
- [ ] 같은-host 백업의 한계와 미수행 검증을 숨기지 않았다.

### Gate H — Deployment Edge

- [ ] 실제 ingress가 있으면 7절 proxy/TLS checklist를 모두 통과하고 topology·설정·시험 결과를 기록했다.
- [ ] ingress가 없으면 “로컬 prod 검증만 완료 / 외부 공개 미승인”으로 명시했다. 미구축 상태를 가정된 취약점으로 부풀리지는 않지만 인터넷 production-ready로도 선언하지 않는다.

**Release 판단:** PR merge·Gate A 통과만으로 완료가 아니다. 적용 대상 Gate B~G 및 실제 인터넷 공개 시 H의 증거를 모아 RC를 재검토한다. 미완료 Gate는 사유·영향·승인 주체를 기록하고, 확인되지 않은 항목을 통과로 처리하지 않는다.

## 11. Deferred Work

| 이번에 하지 않는 일 | 다시 검토할 조건 |
|---|---|
| M-06 제품 수정 / 전체 Clock 전환 / DB 시각 보정 | 별도 실행 진입점이나 외부 writer에서 새로운 실제 오류가 확인될 때만 별건 검토 |
| mail 전용 bounded executor·queue·retry/broker | timeout 적용 후에도 실제 적체·다른 비동기 작업 간섭이 관측될 때 |
| provisioning marker / 별도 bootstrap command | 현재 DB 기반 정책으로 표현할 수 없는 lifecycle·운영 자동화 요구가 생길 때 |
| sanitizer / frontend rewrite | 실제로 사용자 rich HTML을 허용해야 하는 별도 요구가 승인될 때 |
| 전체 optimistic locking / menu version column | stale 화면 저장에 대한 명시적 충돌 UX 등 새 요구가 생길 때 |
| 무중단 backup snapshot / object storage 전환 | 정지 시간 허용 불가·데이터 규모·복구 목표가 실제로 기존 절차를 넘을 때 |
| 공통 IP 추상화 / distributed limiter | 실제 ingress·다중 instance 구성과 측정 결과가 필요성을 보여줄 때 |
| Redis/Kafka/Kubernetes/microservices/API Gateway | 이번 remediation과 무관. 별도 사용자 가치·운영 근거 없이는 추가하지 않음 |

이 표는 후속 구현 승인이 아니다. 관측 조건이 충족되어도 별도 범위·대안 검토를 거친다.

## 12. Final Execution Checklist

- [x] **H-01 정책 승인:** D-01의 A안 확정(2026-09-23). 수동 LOCKED 포함 기동 허용, 인증 제한 및 기존 계정 필드 불변을 유지한다.
- [x] **PR 1 구현 착수 승인:** 2026-09-24 PR 1만 별도로 승인받았다. 다른 PR과 정규 backup 운영 실행은 승인 범위에 포함하지 않는다.
- [x] **PR 1 시작 전 기준선:** HEAD·작업 트리·기존 사용자 변경을 확인하고 `feat/admin-detail-safe-text` 브랜치에서 작업했다. 무관한 로드맵·portfolio는 변경하거나 커밋하지 않았다.
- [x] **PR 1:** 상세의 텍스트/이메일 markup 분리와 escape만 구현했다.
- [x] **PR 1 테스트:** 정적 보조/MVC와 실제 브라우저에서 새 DOM 0·원문 표시·이메일 UI·MANAGER 403을 확인했다. 상세 수치·skip은 PR 1 실행 기록 참조.
- [x] **PR 2:** 승인된 bootstrap allowlist와 같은 ID reconciliation 조건을 일치시키고 계정 상태를 자동 변경하지 않았다. 커밋 `eaaccb6` #38 머지 완료.
- [x] **PR 2 테스트:** 8상태·자동/수동 LOCKED·충돌·실제 prod JAR/복구 경로를 검증했다. `./gradlew test` 720개 전체 통과, CI(`gh pr checks 38`) `test` pass 확인.
- [x] **PR 3:** 좁은 400/405/415/406 handler와 안전한 500 진단, API 전체 JSON Content-Type 보장, BindException 타입 변환 메시지 비노출만 추가했다. 커밋·PR·머지는 아직(`fix/api-error-contract` 브랜치).
- [x] **PR 3 테스트:** Security 포함 전체 오류 행렬(406 포함)·민감 표식 미노출·기존 HTML 회귀 통과. `./gradlew test` 740개 전체 통과(신규 21개 순증).
- [ ] **PR 4:** 일반 메뉴 update도 최초 대상 조회부터 기존 행 잠금을 사용한다.
- [ ] **PR 4 테스트:** A/B 양방향 same-row 및 부모/자식 MariaDB 검증 통과. 잠금 후 불가능한 B 선커밋을 강요하는 하니스가 없다.
- [ ] **PR 5:** 세 timeout 기본값·선택 override·compose/guard/.env.example 전달을 연결했다. 구현 착수 승인 후 필요하면 선행 가능하다.
- [ ] **PR 5 테스트:** 실제 resolved properties·invalid override 배포 거절·로컬 fault·token 정리·정상 reset flow 검증 통과.
- [ ] **PR 6:** online/quiesced 계약, backup/restore 실패 시 서로 다른 재개 정책, 제품 중립 ingress checklist를 기록했다.
- [ ] **PR 6 검증:** 전용 daemon에서 실제 quiesced backup→restore, 참조 파일/hash/UID/재기동/checksum 실패를 확인했다.
- [ ] **통합 RC:** Gate A~G를 다시 수행했다. 실제 외부 공개는 Gate H까지 확인했다.
- [ ] **인계:** 최종 HEAD·PR·테스트·스크린샷·drill 증거·rollback 조건·미검증 항목을 묶어 배포 승인을 요청했다. 제품 변경 전/후를 혼동하지 않았다.

### 마지막 범위 자체 검증

- [ ] M-06 수정, Clock 전환, 데이터 9시간 보정이 없다.
- [ ] M-02는 timeout 및 전달·검증이며 executor/broker 계획이 없다.
- [ ] M-01은 기존 도구의 운영 계약·격리 drill이며 새 backup platform이 없다.
- [ ] M-05는 ingress 결정 전 제품 설정/IP abstraction을 선행하지 않는다.
- [ ] H-01은 회원 수가 아니라 ADMIN 역할과 승인된 상태 조건을 사용한다.
- [ ] LOCKED/EXPIRED/DISABLED/DELETED를 기동 중 자동 정상화하지 않는다.
- [ ] M-04는 기존 비관적 잠금을 사용하고 version/migration을 추가하지 않는다.
- [ ] C-01은 기존 escaping을 재사용하며 sanitizer/framework를 추가하지 않는다.
- [ ] 모든 Fix에 재현 가능한 회귀 검증과 완료 조건이 있다.
- [ ] 계획은 현재 HEAD에 근거하며 과거 감사·테스트 성공을 새 구현 완료로 오인하지 않는다.

체크박스 중 **H-01 정책 승인과 PR 1 착수·구현·범위 내 검증만 완료**했다. 다른 PR 및 통합 RC Gate 체크는 향후 실행 확인용이며 완료 표시가 아니다. 기존 감사 문서와 다른 PR의 정책 결정은 변경하지 않았다.

### 계획 리뷰 기록 — 자체 적대적 리뷰, 1라운드

출처: 주 에이전트의 자체 검토. 별도 독립 에이전트를 호출하지 않았다. `feature`가 참조하는 `plan-review-loop` 절차에 따라 지적 전체를 먼저 제시한 후 아래와 같이 분류했다.

| ID | 지적 원문 | 트리아지 / 반영 |
|---|---|---|
| R-01 | Docker context만 원격으로 바꾸면 스크립트의 `127.0.0.1:8080` health 검사는 원격 앱이 아닌 현재 호스트를 볼 수 있다. 스크립트도 격리 VM 안에서 실행하도록 명시해야 한다. | **수용.** PR 6 및 restore drill의 실행 셸/daemon/health 대상 일치 조건 보강 |
| R-02 | Future가 잠시 완료되지 않았다는 사실만으로 DB 잠금 대기를 증명할 수 없다. 서비스 호출 진입, SQL 또는 DB lock-wait 관측, 최종 커밋 결과를 함께 확인해야 한다. | **수용.** PR 4의 실제 대기 증거와 테스트 DB 권한 범위 보강 |
| D-01 | 수동 LOCKED도 계정 상태를 유지한 채 앱 기동을 허용할지는 승인 대상이다. 추천안으로 남기되 확정으로 표시하면 안 된다. | **당시 결정 필요 → 2026-09-23 사용자 승인으로 해소.** A안 확정, 수동 잠금·기존 필드 유지. 제품 구현은 별도 착수 승인 전까지 하지 않음 |

**1라운드 종료 당시에는 정책 승인 대기였으며, D-01은 이후 사용자 승인으로 해소됐다.** 이번 변경은 그 결정을 기록한 것이며 새 리뷰 라운드나 production `ship` 판정이 아니다. `feature`의 정책/구현 승인 경계를 유지하여 제품 구현으로 넘어가지 않는다. 정규 quiesced backup의 중단 허용·담당자 확정도 실제 운영 실행 전에 별도로 필요하다.
