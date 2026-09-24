# INDEPENDENT VERIFICATION REVIEW

검토일: 2026-09-23 · 대상: [2026-09-22 PROJECT TECHNICAL AUDIT](deploy-check-2026-09-22.md)의 C-01, H-01, M-01~M-06

기준 HEAD: `f89019556a3eff0fe500586255793aae0fff3a85`. 현재 작업 트리의 실제 코드·설정을 기준으로 검증했다. 기존 보고서와 사용자 변경은 보존했다. 제품 코드, 설정, migration, 기존 테스트를 수정하지 않았으며, 이번 검토의 저장소 산출물은 이 보고서뿐이다. 구현 계획은 포함하지 않는다.

## Executive Summary

기존 감사의 모든 결론을 유지하지 않는다. **C-01은 Critical에서 High로 하향하고, M-06은 철회한다.** M-04는 기존 정적 추론을 실제 MariaDB 서비스 경합으로 재현해 Bug 판정을 확정했다. H-01 역시 prod 프로파일 기동으로 핵심 주장을 확인했으나, 재시작 조건과 계정 상태별 차이를 명확히 한정한다.

- C-01: MANAGER가 저장한 무해한 마크업이 ADMIN 상세 화면에서 실제 DOM 요소가 되었다. 다른 사용자에게 전달되는 저장형 HTML 해석 결함이며 권한 경계에 영향을 줄 수 있다. 스크립트 실행·계정 승격은 시험하지 않았다.
- H-01: 초기 관리자 변수가 없을 때 ACTIVE ADMIN이 없는 상태에서 기동이 실패한다. 이미 만료된 자동 잠금도 기동 중에는 풀리지 않았다. 잠금 자체가 실행 중인 앱을 종료시키는 문제는 아니다.
- M-01: 이미 문서화된 온라인 백업의 일관성 trade-off다. 기존 정지 상태 백업 절차로 대응할 수 있으며 새 백업 아키텍처가 필요한 것은 아니다.
- M-02: timeout 부재와 기본 executor 특성은 확인했다. 우선 필요한 것은 SMTP timeout 설정이며, 전용 bounded executor를 당장 필수로 만들 근거는 부족하다.
- M-03: 400/405/415 성격의 일부 요청이 실제 인증된 HTTP 경로에서도 500이 된다. 별도 로깅이 없는 예상 밖 API 예외도 기본 로그 수준에서 진단 기록이 남지 않았다.
- M-04: 메뉴 이름 수정이 앞서 커밋된 비활성화를 되돌리는 것을 재현했다. 영향은 메뉴 데이터의 lost update이며 인증 우회로 확대하지 않는다.
- M-05: 현재 loopback 배포의 즉시 외부 취약점으로 보지 않는다. 감사 IP 신뢰성의 제한과 향후 proxy 배포 요구사항으로 범위를 좁힌다.
- M-06: 실제 `main()`이 JVM 기본 시간대를 KST로 설정한다. 이전 감사가 진입점을 누락했다. UTC로 실행한 JAR에서도 KST 기동을 확인했다.

**Final Verdict: no-ship — 외부 production 배포 기준.** 확인된 Critical은 없지만 C-01과 H-01의 High 위험은 남는다. 이는 나머지 모든 항목에 코드 수정을 요구하거나, 전체 저장소를 다시 감사해 통과시켰다는 뜻이 아니다.

## 검증 범위와 방법

이번 검토는 위 8개 지적의 재검증으로 제한했다. `deploy-check` 스킬의 읽기 전용 검토·보고서 분리·판정 기준을 적용하되, 사용자 요청에 따라 전면 재감사나 수정계획으로 확장하지 않았다.

### 실행한 검증

- Java 17로 `gradlew.bat compileJava compileTestJava bootJar --console=plain`: 성공.
- 관련 기존 테스트 5개 클래스, **51개 통과 / 실패 0 / 오류 0 / skip 0**.
  - `AdminBootstrapLoaderTest`: 9개.
  - `PasswordResetServiceTest`: 32개.
  - `MenuConcurrencyIntegrationTest`: 1개.
  - `LoginFailureLockoutIntegrationTest`: 4개.
  - `PasswordExpiryIntegrationTest`: 5개.
- 별도 Testcontainers MariaDB 10.11에 Flyway를 적용하고 실제 prod 프로파일 앱을 기동했다. DB·저장소·포트·메일 목적지는 검증 전용으로 격리했다.
- 실제 Spring Security + MVC + JPA 앱에 Chromium으로 로그인해 무해한 DOM 렌더링 및 API 상태 코드를 확인했다. 브라우저의 외부 목적지 요청은 차단했다.
- 인메모리 진단 하니스에서 실제 서비스 프록시를 호출해 메뉴 경합을 순서 고정으로 재현했다. 제품 소스나 테스트 파일을 바꾸지 않았다.
- 실제 애플리케이션 컨텍스트에 연결한 MockMvc로 예상 밖 예외 및 기본 로그 수준을 검증했다.
- 로컬 SMTP 모의 소켓으로 greeting/read 지연을 확인했다. 외부 메일은 보내지 않았다.
- `-Duser.timezone=UTC`를 준 실행 JAR로 실제 `main()` 진입을 검증했다.

기존 1차 감사의 전체 테스트 수를 이번 실행 결과로 재사용하지 않는다. 이번에는 위 **51개 관련 테스트만** 재실행했다. 진단 하니스는 영구 회귀 테스트로 추가하지 않았다.

검증용 앱·하위 실행 JAR·MariaDB 컨테이너를 종료했고, 해당 컨테이너 제거를 확인했다. 운영 DB·운영 volume·실제 사용자 데이터에는 접근하지 않았다. Docker Desktop 자체는 종료하지 않았다. 일반 빌드 산출물과 임시 저장소 디렉터리는 제품 변경으로 취급하지 않는다.

### 검증하지 않은 범위

- C-01의 실제 스크립트 실행, 권한 변경, 계정 탈취: 의도적으로 실행하지 않았다.
- 실제 운영 reverse proxy 및 TLS ingress: 저장소 구성만 검토했으며 배포된 외부 환경은 확인하지 않았다.
- 실제 운영 백업·restore 및 복구 데이터 전수 검증: 실행하지 않았다.
- SMTP 연결·쓰기 지연과 대규모 queue 포화: 직접 재현하지 않았다.
- 전체 서비스 부하·전체 테스트 스위트: 이번 재검증 범위 밖이다.

## No-Ship Findings

## [C-01] 관리자 상세 화면의 저장된 입력값 렌더링

### Previous Claim

MANAGER의 이름이 ADMIN 상세 모달의 HTML sink에 삽입되며, 저장형 XSS가 ADMIN 권한 경계를 넘으므로 Critical이라는 주장.

### Verification

**검증 수준: Static Code Trace · Integration Test · Browser/UI Regression Test · Configuration Inspection.**

현재 연결 경로는 다음과 같다.

`MANAGER → PATCH /admin/api/members/me → updateMyInfo() → DB → ADMIN GET /admin/api/members/{id} → renderDetail() → innerHTML`

- [AdminMemberController](../src/main/java/com/cms/admin/member/controller/AdminMemberController.java) 116행의 본인 수정은 ADMIN/MANAGER에 허용되고, 수정 대상은 현재 principal의 ID다.
- [AdminMyInfoUpdateRequest](../src/main/java/com/cms/admin/member/dto/request/AdminMyInfoUpdateRequest.java)의 이름 검증은 필수·길이 제한이다. [AdminMemberService](../src/main/java/com/cms/admin/member/service/AdminMemberService.java) 162행 `updateMyInfo()`는 trim 후 저장하며, 427행 `toResponse()`는 이름을 그대로 반환한다.
- [admin-manage.html](../src/main/resources/templates/admin/member/admin-manage.html) 636행의 `data.userName`이 651행 `detailContent.innerHTML`로 전달된다. 목록·상단 요약의 escaping은 이 상세 영역을 보호하지 않는다.
- [SecurityConfig](../src/main/java/com/cms/config/SecurityConfig.java)와 실제 응답에 이 경로를 막는 CSP가 없었다. CSP meta도 없었다. 실제 세션 쿠키는 HttpOnly였다.

검증 전용 MANAGER가 이름을 `<span id="audit-name-marker">audit-safe-name</span>`으로 수정했다. 이벤트 핸들러나 스크립트를 포함하지 않은 표식이다.

| 관찰 | 실제 결과 |
|---|---|
| MANAGER의 관리자 목록 API 직접 접근 | 403 |
| MANAGER 본인 이름 수정 | 200, 응답에 원문 유지 |
| 독립 DB 조회 | 원문 저장 확인 |
| ADMIN의 해당 회원 상세 API | 200, 동일 원문 |
| ADMIN 상세 모달의 표식 DOM 요소 수 | 1 |
| 표식 DOM 요소의 텍스트 | `audit-safe-name` |
| 응답 CSP / 페이지 CSP meta | 없음 / 0개 |

따라서 단순 문자열 표시나 self-XSS만의 문제는 아니다. 다른 권한 주체의 화면에서 저장값을 HTML로 해석하는 사실까지 실제 브라우저로 확인했다.

CSRF는 공격자가 자신의 허용된 수정 요청을 하는 것을 차단하지 않는다. 만약 이 sink에서 스크립트가 실행되면 동일 출처 페이지의 CSRF 값을 이용할 수 있고, HttpOnly도 브라우저의 인증 요청 자체를 막지 않는다. 관리자 API의 역할 검증은 정상이나 피해 ADMIN의 세션으로 수행되는 요청과 공격자의 직접 요청은 구분해야 한다. **스크립트 실행 이후의 권한 API 악용은 정적 분석에 따른 위험 판단이며, 승격 E2E를 성공시켰다는 뜻은 아니다.**

### Counterarguments

- 외부 무인증 입력만으로 성립하지 않는다. 이름 수정 가능한 계정과 ADMIN의 상세 조회가 필요하다.
- ADMIN/MANAGER의 직접 API 권한 구분, CSRF, 다른 영역의 escaping은 실제로 동작한다.
- 안전한 DOM 검증만으로 특정 공격 payload나 계정 승격까지 실증했다고 주장할 수 없다.
- 모든 운영자가 같은 신뢰 수준인 단일인 설치에서는 악용 가능성이 더 낮다. 다만 제품은 두 역할을 실제로 구분한다.

### Result

**CONFIRMED — SEVERITY ADJUSTED.** 저장형 HTML 해석 결함은 확인. Critical이라는 우선순위는 하향한다.

### Final Severity

**High · Security Issue / Bug.**

### Actual Conditions

악성 또는 탈취된 MANAGER 계정이 자기 이름을 저장하고, ADMIN이 그 회원의 상세 화면을 연다. 이 값이 안전한 텍스트로 처리되지 않는 현재 UI 경로가 필요하다.

### Actual Impact

다른 관리자의 UI에 임의 마크업이 삽입된다. 현재 sink와 CSP 부재는 ADMIN 브라우저 내 스크립트 실행 및 인증된 행위 악용 위험을 만든다. 단순 표시 깨짐으로 축소할 수 없지만, 무조건적인 원격 ADMIN 탈취로 표현해서도 안 된다.

### Smallest Safe Change

평문 필드의 출력 escaping 또는 안전한 텍스트 렌더링으로 해당 경계를 닫는다. 기존 의도된 버튼 마크업과 사용자 텍스트를 구분하면 충분하다. sanitizer 라이브러리, 이름 저장 정책 전면 변경, DB migration은 필요하지 않다. CSP는 추가 방어이지 대체 수정이 아니다.

### Required Tests

MANAGER 저장 → ADMIN 상세 조회의 동일 흐름에서 표식이 새 DOM 요소가 아니라 문자 그대로 표시되는지 확인한다. 목록·요약·상세, 정상 이름, 이메일 버튼, MANAGER의 ADMIN API 403도 유지되어야 한다. 공격 코드나 실제 권한 상승을 테스트할 필요는 없다.

### Recommendation

**Fix before production.** 운영 절차로 ADMIN의 상세 조회를 영구 금지하는 것보다 좁은 렌더링 수정이 작고 안전하다.

## [H-01] ADMIN 상태와 애플리케이션 기동 조건의 결합

### Previous Claim

일시적인 LOCKED/PASSWORD_EXPIRED 상태와 최초 관리자 미구성을 동일하게 취급하여 앱 전체 기동을 실패시킨다는 High 지적.

### Verification

**검증 수준: Static Code Trace · Unit Test · Integration Test · Deployment Simulation.**

[AdminBootstrapLoader](../src/main/java/com/cms/admin/member/AdminBootstrapLoader.java) 59행은 `existsByUserTypeAndStatus(ROLE_ADMIN, ACTIVE)`만 확인한다. 존재하면 환경변수 검사 없이 반환한다. 없다면 63행 이후 초기 관리자 3변수를 요구하고 신규 생성으로 진행한다. 기존 잠금 계정의 상태 복구는 수행하지 않는다.

각각 분리한 MariaDB 스키마에 실제 migration과 fixture를 적용하고 prod 프로파일의 전체 Spring 앱을 기동했다. 다음은 **초기 관리자 3변수가 없는 조건**이다.

| DB 상태 | prod runner / 생성 분기 | 초기 변수 필요 | 최종 기동 | 기존 상태 자동 변경 | 기동 후 reset 화면 |
|---|---|---|---|---|---|
| 완전히 빈 DB | 실행 / 진입 | 필요 | 실패 | 대상 없음 | 연결 불가 |
| ACTIVE ADMIN 존재 | 실행 / 즉시 반환 | 불필요 | 성공 | 없음 | HTTP 200 |
| LOCKED ADMIN만 존재 | 실행 / 진입 | 필요 | 실패 | 없음 | 연결 불가 |
| PASSWORD_EXPIRED ADMIN만 존재 | 실행 / 진입 | 필요 | 실패 | 없음 | 연결 불가 |
| DISABLED ADMIN만 존재 | 실행 / 진입 | 필요 | 실패 | 없음 | 연결 불가 |
| DELETED ADMIN만 존재 | 실행 / 진입 | 필요 | 실패 | 없음 | 연결 불가 |
| ADMIN 없이 ACTIVE MANAGER만 존재 | 실행 / 진입 | 필요 | 실패 | 없음 | 연결 불가 |
| LOCKED와 ACTIVE ADMIN 공존 | 실행 / 즉시 반환 | 불필요 | 성공 | 없음 | HTTP 200 |

LOCKED fixture는 자동 잠금 시각을 **31분 전**으로 설정했다. 기동 실패 후에도 LOCKED가 유지됐다. 즉, 이미 자동 해제 가능 시간이 지나도 bootstrap 경로에서 lazy unlock은 실행되지 않는다.

추가로 같은 LOCKED 계정 ID를 bootstrap 변수로 제공하면 신규 INSERT의 unique 충돌 이후 ACTIVE 여부 확인을 통과하지 못해 실패했다. **다른 유효한 신규 ID**를 제공하면 새 ACTIVE ADMIN을 생성하고 기동했다. 원래 LOCKED 계정은 그대로였다. 빈 DB에 유효한 초기 변수를 제공하는 정상 생성도 검증용 최초 기동에서 확인했다.

[PasswordResetService](../src/main/java/com/cms/admin/member/service/PasswordResetService.java) 155행은 실행 중인 앱의 요청 경로에서 만료된 자동 잠금을 해제한다. ACTIVE/PASSWORD_EXPIRED의 ADMIN/MANAGER가 재설정 대상이다. 아직 만료되지 않은 자동 잠금, 수동 잠금, DISABLED/DELETED는 동일하게 복구되는 상태가 아니다. 관련 기존 잠금·만료·reset 테스트도 통과했다.

### Counterarguments

- fail-fast는 우연한 실수가 아니라 주석과 설계에 기록된 의도된 정책이다. 관리자 없는 앱을 정상 운영으로 오인하지 않게 하는 장점이 있다.
- 잠금 발생만으로 실행 중인 앱이 즉시 종료되지 않는다. **ACTIVE ADMIN 부재와 재시작이 함께** 필요하다.
- ACTIVE ADMIN이 하나라도 남으면 기동한다. 비밀번호가 오래되었어도 DB 상태가 아직 ACTIVE이면 이 검사 자체는 통과한다.
- LOCKED를 모두 일시 상태라고 부르면 안 된다. `lockedAt=null`인 수동 잠금은 자동 해제 대상이 아니다.
- Spring 웹서버는 runner 완료 전에 잠시 시작될 수 있다. 검증 결과는 최종 기동 실패·컨텍스트 종료 후 복구 endpoint를 사용할 수 없다는 뜻이지, 초기화 도중 어떤 소켓도 순간적으로 열리지 않는다는 뜻이 아니다.
- 운영자의 신규 bootstrap 계정 생성 등 수동 복구 수단은 존재한다. 복구 불가능한 장애는 아니다.

### Result

**CONFIRMED — SCOPE ADJUSTED.** 핵심 주장은 실증됐다. 발생 조건을 재시작 및 저장된 상태로 한정하고 자동 잠금·수동 잠금·명시적 폐기를 구분한다.

### Final Severity

**High · Operational Risk / Architecture Issue.** 소수 ADMIN 설치에서 공개 기능까지 재시작 불능이 되는 영향은 의미가 있다. Critical은 아니다.

### Actual Conditions

모든 ADMIN이 DB상 ACTIVE가 아닌 상태에서 재시작하고, 유효한 신규 관리자 생성 조건이 없는 경우. 초기 bootstrap 변수를 제거하는 정상 운영 절차와 함께 발생할 수 있다.

### Actual Impact

관리자 로그인뿐 아니라 같은 앱의 공개 화면과 자체 비밀번호 복구 경로도 사용할 수 없다. 외부 운영 개입이 필요하다. 기존 계정을 자동 복구하거나 암호를 덮어쓰는 문제는 확인되지 않았다.

### Smallest Safe Change

최초 ADMIN 프로비저닝 필요 여부와 기존 ADMIN의 현재 로그인 가능 여부를 분리하는 좁은 정책 조정이 가장 균형 잡혔다. 잠금 자체와 인증 제한은 유지한다. `member count > 0`만으로 통과시키면 MANAGER만 있는 설치까지 정상으로 오인하므로 안전하지 않다.

현재 상태 질의만으로 “과거에 한 번 정상 프로비저닝되었음”이라는 이력을 완전히 증명할 수는 없다. 그렇다고 새 이력 테이블이나 migration이 당장 필요하다고 결론내리지 않는다. DISABLED/DELETED만 남은 상태는 명시적 재프로비저닝 정책으로 다뤄야 하며, 수동 잠금도 자동으로 해제하면 안 된다.

현 정책을 유지하는 대안은 별도 비상 ADMIN과 검증된 복구 절차다. 빈도는 줄이지만 상태와 기동의 결합은 남으므로, 코드 조정을 대체하려면 운영자가 그 가용성 trade-off를 명시적으로 수용해야 한다.

### Required Tests

위 8개 기동 행렬, 자동 잠금 만료 전후, 수동 잠금, 기존 ID bootstrap 충돌, 신규 ID 복구, 실제 reset 적격성을 함께 고정한다. 앱 기동 허용이 인증 허용이나 DISABLED/DELETED 부활로 이어지지 않아야 한다.

### Recommendation

**Fix before production.** 부트스트랩 전체를 별도 서비스로 분리하거나 새 운영 프레임워크를 만드는 것은 우선 요구사항이 아니다.

## Needs-Attention Findings

## [M-01] DB와 파일 백업 시점 차이

### Previous Claim

온라인 DB dump와 파일 tar의 시점이 달라 정상 완료된 백업에서도 파일 누락·orphan이 발생할 수 있다는 Medium 지적.

### Verification

**검증 수준: Static Code Trace · Configuration Inspection.** 실제 복원 재현은 실행하지 않았다.

[prod-backup.sh](../scripts/prod-backup.sh) 101행의 `mariadb-dump --single-transaction` 이후 114행에서 파일 volume을 tar한다. 앱 쓰기를 멈추는 절차가 script 안에 없다. DB의 트랜잭션 snapshot은 외부 파일시스템 snapshot이 아니다.

[NoticeAttachmentService](../src/main/java/com/cms/admin/notice/service/NoticeAttachmentService.java)의 첨부 삭제와 [AdminMemberService](../src/main/java/com/cms/admin/member/service/AdminMemberService.java)의 프로필 교체는 커밋 후 이전 파일을 삭제한다. 따라서 DB snapshot 이후 삭제가 커밋되고 tar가 그 파일에 도달하기 전 물리 삭제가 끝나면, 복원 DB의 옛 key에 해당하는 파일이 없다. 반대로 snapshot 이후 새 파일이 생겨 tar에 포함되면 DB에서 참조하지 않는 파일이 남을 수 있다.

### Counterarguments

[deployment.md](../docs/deployment.md) 103행은 양방향 불일치를 이미 설명하고, 106행부터 앱만 정지하는 quiesced backup 절차를 제공한다. 이를 새로 발견한 무방비 백업 버그라고 부를 수 없다. 관리자 쓰기가 드문 작은 서비스에서는 온라인 백업을 의식적으로 선택할 수 있다.

### Result

**CONFIRMED — SCOPE ADJUSTED.** 문서화된 온라인 모드의 한계이며, 모든 백업 방식의 결함은 아니다.

### Final Severity

**Medium · Operational Risk.** 온라인 쓰기와 겹치는 경우의 잔여 위험 기준이다. 기존 quiesced 절차를 정상 실행하면 이 경합 조건은 제거된다.

### Actual Conditions

온라인 백업 중 DB snapshot과 해당 파일 수집 사이에 업로드·삭제·교체가 발생한다. 백업을 조용한 시간에 실행하는 것만으로 쓰기 부재가 보장되지는 않는다.

### Actual Impact

복원 후 일부 다운로드 실패 또는 미참조 파일 잔존. checksum 통과는 archive의 무결성을 증명할 뿐 DB 참조와 파일의 동일 시점을 증명하지 않는다.

### Smallest Safe Change

새 코드 없이 기존 quiesced 절차를 정규 복구 기준 백업으로 선택하면 된다. 앱과 다른 쓰기 주체를 중지하고 DB는 dump 가능 상태로 유지해야 한다. 온라인 모드를 쓸 경우 이 일관성 수준을 명시적으로 수용한다. dump/tar 순서 반전만으로 해결되지 않는다.

### Required Tests

격리 환경에서 정지 상태 백업을 복원하고 DB의 파일 key 존재 여부와 대표 파일 내용을 확인한다. 백업 실패 시 앱 재개 책임도 확인한다. 실제 운영 데이터를 대상으로 파괴적 restore를 시험할 필요는 없다.

### Recommendation

**Document / accept risk.** 배포 전에 백업 모드와 복구 허용 수준은 선택해야 하지만 script 재작성·무중단 snapshot 도입은 필수가 아니다.

## [M-02] SMTP timeout과 비동기 실행

### Previous Claim

SMTP timeout 부재와 큰 기본 executor queue 때문에 메일 장애 시 작업이 누적될 수 있다는 Medium 지적.

### Verification

**검증 수준: Static Code Trace · Configuration Inspection · Unit Test · Integration Test.**

[application-prod.yml](../src/main/resources/application-prod.yml) 13행 이후 SMTP 설정과 실제 prod Bean을 확인했다.

| 항목 | 실제 값/동작 |
|---|---|
| `mail.smtp.connectiontimeout` / `timeout` / `writetimeout` | 세 속성 모두 미설정 |
| executor | `ThreadPoolTaskExecutor` |
| core / max pool size | 8 / 2147483647 |
| queue capacity | 2147483647 |
| rejection policy | `ThreadPoolExecutor.AbortPolicy` |
| dispatch 실패 | catch 후 기존 토큰 해시와 일치할 때 조건부 정리 |
| send 실패 | catch, 제한된 로그, 조건부 토큰 정리 |
| token 유효기간 / 계정별 재발급 간격 | 30분 / 60초 |

[PasswordResetService](../src/main/java/com/cms/admin/member/service/PasswordResetService.java) 107행 이후는 토큰 DB 커밋과 발송을 분리한다. SMTP 지연 중 DB 트랜잭션을 계속 붙잡는 구현이 아니다. dispatch 및 send 예외 처리와 최신 토큰을 지우지 않는 조건부 정리도 이미 있다.

로컬 SMTP 소켓이 연결은 수락하되 greeting을 보내지 않도록 시험했다. timeout 미설정은 **약 1,070ms 관찰 후에도 대기**했고, read timeout 300ms를 설정한 동일 probe는 **약 306ms에 MailSendException**으로 반환했다. 미설정 probe는 관찰 후 소켓을 닫아 종료했다. 무한 시간을 실제로 기다려 검증했다는 뜻은 아니다.

일부 SMTP timeout 기본값이 무한이라는 해석은 공식 설명과도 일치한다. [Spring Boot 3.5 메일 설정](https://docs.spring.io/spring-boot/3.5/reference/io/email.html).

### Counterarguments

- 즉시 연결 실패·send 예외·executor 제출 실패는 이미 처리된다. “비동기 예외 처리가 없다”는 해석은 틀리다.
- max가 매우 커도 즉시 대량 스레드를 생성하는 것은 아니다. 현재 큰 queue에서는 core 이후 작업이 주로 queue에 쌓인다.
- 공개 요청 rate limit과 계정별 cooldown이 있다. 작은 관리자 수에서 곧바로 heap 고갈이 난다는 근거는 없다.
- 네트워크 연결은 OS 수준에서 실패할 수도 있다. 애플리케이션 timeout 미설정을 모든 연결이 영원히 멈춘다는 의미로 쓰면 안 된다.
- read 지연과 달리 실제 쓰기 hang·queue 포화는 이번에 재현하지 않았다.

### Result

**CONFIRMED — SCOPE ADJUSTED.** 우선 문제는 SMTP 응답 지연을 유한하게 제한하지 않은 설정이다. bounded executor를 당장 필수로 보는 주장은 지지하지 않는다.

### Final Severity

**Medium · Operational Risk.**

### Actual Conditions

SMTP가 연결·응답·쓰기 단계에서 장시간 정체하고, 후속 재설정 작업이 계속 들어온다. 즉시 예외가 발생하는 SMTP 장애와 구분해야 한다.

### Actual Impact

비밀번호 복구 메일의 지연·미수신, 작업 점유, 늦게 발송된 토큰의 만료. 예외가 반환되지 않는 동안 실패 후 토큰 정리도 실행되지 않는다. 전체 서비스 OOM은 입증하지 않았다.

### Smallest Safe Change

운영 SMTP 연결·읽기·쓰기 timeout을 유한하게 설정한다. 환경설정만으로 가능하며 새 dependency나 migration이 필요 없다. 현재 규모에서는 이것을 우선하고, 실제 queue 적체 또는 다른 비동기 작업과의 간섭이 확인될 때 전용 bounded executor를 판단한다.

### Required Tests

실제 적용 설정 확인, 연결·읽기·쓰기 지연의 유한 종료, 즉시 실패와 최신 토큰 보호를 확인한다. executor를 바꾸기로 할 때만 포화·거절·종료 경로를 추가 검증한다. 기존 32개 reset 테스트는 통과했다.

### Recommendation

**Fix before production — SMTP timeout 설정에 한정.** 전용 executor·재시도 queue·브로커 도입은 현재 미룬다.

## [M-03] 일반 요청 오류 분류와 예외 로그

### Previous Claim

일부 일반 요청 오류가 catch-all에 잡혀 500이 되고, 예상 밖 API 예외의 진단 로그가 남지 않는다는 Medium 지적.

### Verification

**검증 수준: Static Code Trace · Integration Test · Browser/UI Regression Test.**

[GlobalApiExceptionHandler](../src/main/java/com/cms/common/api/GlobalApiExceptionHandler.java) 365행의 `Exception` 처리보다 더 구체적인 handler가 없는 MVC 예외가 500으로 변환된다. [PublicWebExceptionAdvice](../src/main/java/com/cms/publicweb/support/PublicWebExceptionAdvice.java)의 높은 우선순위는 `publicweb` 패키지에 한정되어 관리자 API의 이 결과를 바꾸지 않는다.

실제 prod 유사 앱에 ADMIN으로 로그인하고, 변경 요청에는 유효한 CSRF를 포함해 HTTP 요청을 보냈다.

| 요청 | 실제 HTTP / 응답 code |
|---|---|
| `GET /admin/api/members/abc` | 500 / `INTERNAL_ERROR` |
| 지원하지 않는 `PUT /admin/api/members/1` | 500 / `INTERNAL_ERROR` |
| 회원 생성에 `Content-Type: text/plain` | 500 / `INTERNAL_ERROR` |
| 회원 생성에 malformed JSON | 400 / `JSON_PARSE_ERROR` |
| 회원 생성에 validation 실패 JSON | 400 / `VALIDATION_ERROR` |
| 존재하지 않는 회원 ID 조회 | 404 / `RESOURCE_NOT_FOUND` |

추가로 실제 컨텍스트와 Security를 연결한 MockMvc에서 `getMyInfo()` 서비스 호출에만 인메모리 예외를 주입했다. 500 JSON 응답은 반환됐지만 기본 INFO 수준의 root Logback 수집기에 해당 요청의 INFO/WARN/ERROR 기록은 **0개**였다. 주입은 검증 직후 제거했다.

동일 컨텍스트에서 미인증 잘못된 ID 요청은 401, 인증됐어도 CSRF 없는 변경 요청은 403이었다. Security가 먼저 거절하는 요청까지 모두 500이라는 주장은 성립하지 않는다.

### Counterarguments

- validation, JSON 파싱, 404, 403 및 명시적 도메인 예외 처리는 이미 있다. 오류 처리 전체가 잘못된 것은 아니다.
- Spring은 처리된 예외를 DEBUG 수준에서 기록할 수 있다. DB·서비스·public advice가 자체 기록하는 경우도 있으므로 “모든 500은 무로그”가 아니다.
- 실제 HTTP 검증과 예외 주입 검증을 구분해야 한다. 예상 밖 RuntimeException은 전체 컨텍스트 MockMvc에서 확인했으며 자연 발생한 운영 장애를 관찰한 것은 아니다.
- 클라이언트에 내부 stack trace를 숨기는 현재 응답 정책은 유지할 가치가 있다.

### Result

**CONFIRMED.** 기존 격리 MockMvc 결과를 전체 인증·MVC 경로에서 확인했다. 로그 누락은 별도 로깅이 없는 catch-all 경로로 한정한다.

### Final Severity

**Medium · Bug / Operational Risk.**

### Actual Conditions

Security를 통과한 요청에서 미처리 프레임워크 예외가 발생하거나, 별도 로그가 없는 예상 밖 예외가 글로벌 API catch-all까지 전파된다.

### Actual Impact

클라이언트 입력 오류의 잘못된 장애 분류 및 실제 서버 장애의 원인 추적 공백. 정보 노출이나 인증 우회는 확인되지 않았다.

### Smallest Safe Change

해당 400/405/415 의미를 보존하고, 예상 밖 오류의 안전한 진단 기록 책임을 한 곳에 둔다. 요청 본문·비밀번호·토큰을 무조건 기록하거나 모든 계층에 중복 로그를 추가하지 않는다. advice 계층 전면 재설계는 필요 없다.

### Required Tests

위 상태 코드 행렬을 Security 포함 환경에서 고정하고, 예기치 않은 오류의 서버 진단 기록·민감정보 미노출을 확인한다. HTML 오류 경로와 이미 자체 로그를 남기는 예외의 중복도 확인한다.

### Recommendation

**Fix before production.** High급 차단 문제는 아니지만 작은 변경으로 API 계약과 장애 추적성을 개선할 가치가 있다.

## [M-04] 메뉴 일반 수정과 비활성화의 lost update

### Previous Claim

일반 수정과 비활성화가 다른 잠금 규칙을 사용하므로 이름 수정이 다른 트랜잭션의 비활성화를 되돌릴 수 있다는 Medium 지적. 이전 감사에서는 DB 재현이 없었다.

### Verification

**검증 수준: Static Code Trace · MariaDB Concurrency Test · Integration Test.**

[MenuService](../src/main/java/com/cms/admin/menu/service/MenuService.java) 76행 `updateMenu()`는 `useYn=false` 요청에만 대상 행 잠금을 사용하고, 이름만 수정할 때는 일반 조회한다. 124행 `deactivateMenu()`는 행 잠금을 사용한다. [Menu](../src/main/java/com/cms/admin/menu/Menu.java)에는 `@Version`과 `@DynamicUpdate`가 없다.

실제 Spring 서비스 프록시와 MariaDB 10.11을 사용했다. 조회 결과를 돌려주기 직전에 인메모리 latch로 A만 대기시켰다. 다른 로직을 재구현한 모의 서비스가 아니다.

1. A가 실제 `updateMenu()`로 활성 메뉴를 조회하고 대기.
2. B가 실제 `deactivateMenu()`를 호출하고 커밋.
3. 별도 조회에서 `useYn=false` 확인.
4. A를 재개해 이름만 바꾸는 요청을 flush/commit.
5. 별도 조회에서 이름 변경과 **`useYn=true` 복귀** 확인.

관찰값:

```text
A_READ=true
B_COMMITTED_USE=false
AFTER_A_USE=true
AFTER_A_NAME=AUDIT_AFTER_A
```

Hibernate가 실제 출력한 SQL은 다음처럼 `use_yn`을 포함했다.

```sql
update menu set access_role=?,create_date=?,menu_desc=?,menu_icon=?,
menu_name=?,menu_url=?,ord=?,up_menu_no=?,update_date=?,use_yn=?
where menu_no=?
```

### Counterarguments

- 단일 운영자가 같은 메뉴를 동시에 수정하지 않는 설치에서는 발생 가능성이 낮다.
- 기존 부모 비활성화/자식 재활성화 경합 테스트는 통과했다. 다만 이번 동일 메뉴의 일반 수정 경합과 다른 계약을 검증한다.
- 메뉴 표시 상태는 서버의 인증·메서드 권한 검사를 대신하지 않는다. 메뉴 복귀를 곧바로 권한 상승이라고 부르면 안 된다.
- `@DynamicUpdate`는 변경하지 않은 `useYn`을 SQL에서 제외해 이번 순서를 막을 수 있다. 그러나 일반적인 동시 필드 변경·명시적 상태 변경·부모 불변식까지 모두 해결한다는 보장은 아니다. 이번에 그 대안을 적용해 재시험하지는 않았다.

### Result

**CONFIRMED.** 기존 Bug 판정을 실제 DB 결과로 뒷받침한다.

### Final Severity

**Medium · Bug / Data Integrity Risk.**

### Actual Conditions

동일 메뉴에 대한 일반 수정 트랜잭션이 먼저 읽고, 다른 트랜잭션이 비활성화를 커밋한 후 앞선 수정이 커밋한다.

### Actual Impact

이미 완료된 비활성화가 의도치 않게 취소된다. 이번 재현으로 확인한 범위는 `useYn` lost update이며, 다른 모든 필드 경합까지 실증했다고 확대하지 않는다.

### Smallest Safe Change

같은 대상의 쓰기 경로에 일관된 동시성 규칙을 적용하는 좁은 수정이 적절하다. 기존 행 잠금 방식의 일관화는 migration 없이 가능하나 부모/자식 잠금 순서를 함께 검토해야 한다. `@Version` 도입과 전체 수정 UI의 충돌 처리 체계까지 확대할 필요는 아직 없다.

### Required Tests

위 A-read → B-commit → A-commit 순서의 MariaDB 회귀 테스트를 영구화하고 기존 부모/자식 경합 테스트를 유지한다. 잠금 방식 변경 시 역순 경합과 timeout/충돌 응답도 확인한다.

### Recommendation

**Fix before production.** High는 아니지만 실제로 확인된 조용한 상태 되돌림이며 좁은 수정 가치가 있다. 단일 운영자 조건에서는 일정 기간 위험 수용이 가능하다.

## [M-05] Proxy와 client IP 해석 기준

### Previous Claim

reverse proxy 뒤에서 모든 사용자가 한 rate-limit bucket을 공유하고, 전달 헤더를 신뢰하는 감사 IP가 위조될 수 있다는 Medium 지적.

### Verification

**검증 수준: Static Code Trace · Configuration Inspection.** 실제 proxy 환경 재현은 하지 않았다.

- [docker-compose.prod.yml](../docker-compose.prod.yml) 55행은 앱 host port를 `127.0.0.1:8080:8080`에만 게시한다. DB host port는 없다.
- 저장소에 운영 reverse proxy 서비스나 forwarded-header 처리 설정이 없다. [deployment.md](../docs/deployment.md)는 인터넷 공개 전 proxy/TLS 구성을 별도 요구한다.
- [RateLimitFilter](../src/main/java/com/cms/config/ratelimit/RateLimitFilter.java) 58행은 `getRemoteAddr()`를 사용한다.
- [AdminActionLogAspect](../src/main/java/com/cms/admin/log/aspect/AdminActionLogAspect.java) 101행 이후는 신뢰 출처 확인 없이 X-Forwarded-For 마지막 값, 다음 X-Real-IP, 마지막 remote address를 선택한다.

### Counterarguments

- 저장소의 현재 loopback 게시 구성만으로 외부 공격자가 직접 앱에 접근 가능하다고 볼 수 없다. 실제 호스트의 별도 전달 구성 유무는 미확인이다.
- limiter가 임의 X-Forwarded-For를 직접 믿지 않는 것은 현재 모델에서 보호다.
- 감사 IP는 현재 인가 판단의 근거가 아니다. 감사 헤더 조작과 rate-limit 우회를 동일시하면 안 된다.
- proxy가 실제로 배치되었을 때의 주소 복원 정책은 현재 존재하는 코드 결함과 분리해야 한다. 이전 감사도 조건부임을 적었으나 Medium 운영 결함처럼 묶는 우선순위는 낮출 수 있다.

### Result

**CONFIRMED — SCOPE ADJUSTED.** 감사 IP 신뢰성 제한은 남지만, quota 공유의 주된 위험은 실제 proxy 배포 시 검증할 요구사항이다. 현재 심각도도 하향한다.

### Final Severity

**Low · Operational Risk / 감사 로그 신뢰성 제한.** proxy 미설정으로 다중 사용자의 복구 기능이 제한되는 상황이 실제 구성에서 확인되면 재평가한다.

### Actual Conditions

앱에 도달 가능한 요청이 감사용 전달 헤더를 제공하거나, 향후 proxy 배포에서 신뢰할 실제 IP 복원을 구성하지 않는 경우.

### Actual Impact

감사 IP의 신뢰도 저하. proxy 도입 조건에서는 여러 사용자가 하나의 quota를 공유할 수 있다. 현재 외부 인증 우회나 임의 IP 헤더에 의한 limiter 우회를 확인한 것은 아니다.

### Smallest Safe Change

현재 감사 IP를 검증된 보안 식별자로 취급하지 않는다. 인터넷 ingress 도입 시 전달 헤더 제거·재작성, 신뢰 proxy 한정, backend 우회 차단을 배포 계약으로 정하면 된다. 선택한 ingress로 해결되는 범위를 먼저 확인하고 필요할 때만 앱의 IP 소비 정책을 맞춘다. Redis나 distributed limiter는 해결책이 아니다.

### Required Tests

실제 예정 배포 경로에서 서로 다른 두 client IP, 위조 전달 헤더, proxy 우회, 감사 IP와 quota key를 확인한다. 저장소 정적 구성만으로 이 검증을 통과 처리하지 않는다.

### Recommendation

**Document / accept risk — 현재 구성 기준.** proxy를 통한 외부 공개 시에는 반드시 통합 검증한다. 지금 proxy 추상화 계층을 새로 만드는 것은 권장하지 않는다.

## [M-06] KST Clock과 JVM 기본 시각 혼용

### Previous Claim

Clock은 KST지만 Docker/JVM 기본 시간대가 UTC이면 `LocalDateTime.now()` 경로가 다른 기준으로 저장된다는 Medium 지적.

### Verification

**검증 수준: Static Code Trace · Configuration Inspection · Deployment Simulation.**

이전 보고서가 빠뜨린 방어가 있다. [CmsApplication](../src/main/java/com/cms/CmsApplication.java) 16행에서 `SpringApplication.run()` **전에** 다음을 실행한다.

```java
TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
```

[AppConfig](../src/main/java/com/cms/config/AppConfig.java) 18행의 Clock도 `Asia/Seoul`이다. Dockerfile의 `java -jar`는 이 실제 main을 실행한다. 이번에 `-Duser.timezone=UTC -jar ...`로 prod 프로파일 실행 JAR을 기동했고, 시작 로그의 offset은 `+09:00`이었다. 즉, 운영체제/JVM 초기 기본값이 UTC여도 애플리케이션 진입점에서 변경된다.

시간 경로도 역할별로 다시 확인했다.

| 용도 | 현재 시간 기준 | 판단 |
|---|---|---|
| 잠금·만료·reset token 및 주요 보안 판단 | 주입 Clock, KST | 기존 핵심 방어 유지 |
| 일부 회원 변경·메뉴·공지·행위 감사 시각 | JVM 기본 `LocalDateTime.now()` | 정상 main 실행에서는 KST |
| 생성·수정 시각 | Clock 또는 JVM 기본 시각 | 정상 실행에서 둘 다 KST |
| 대시보드 등 주요 통계 경계 | KST Clock | 일반 UTC 컨테이너만으로 혼용되지 않음 |
| 일부 검색 기본 날짜·파일 날짜 경로 | JVM 기본 날짜 | 정상 실행에서는 KST |
| DB 일반 시각 저장 | DATETIME 계열의 zone 없는 값 | DB session UTC라는 사실만으로 Java 값이 자동 9시간 이동한다고 볼 수 없음 |
| 화면의 zone 없는 날짜 해석 | 기존 문자열/브라우저 표시 방식 | 이번 주장에 따른 잘못된 결과는 재현하지 않음 |

검증용 MariaDB의 system timezone은 UTC였다. 별도 주의점은 SQL `NOW()` 기반 seed/backfill이다. 특히 [V6 migration](../src/main/resources/db/migration/V6__backfill_member_password_changed_at.sql)은 DB 세션이 UTC이면 기존 계정의 90일 유예 기준이 최대 9시간 이를 수 있음을 **명시적으로 수용**한다. 이는 기존 데이터 backfill의 제한된 trade-off이지, 정상 Java 실행 전체의 시간대 혼용을 입증하는 근거가 아니다.

### Counterarguments

- main을 거치지 않는 테스트 컨텍스트나 별도 실행 진입점에서는 JVM 기본 시간대가 다를 수 있다. 그 환경은 실제 배포 JAR과 구분해야 한다.
- Clock을 더 일관되게 주입하면 테스트 편의성은 좋아질 수 있지만, 그 사실만으로 현재 운영 Bug가 되지는 않는다.
- 모든 DB 세션과 외부 writer의 시간 정책을 확인한 것은 아니다. 이번 철회는 확인된 애플리케이션 실행 경로에 한정한다.

### Result

**REJECTED.** 이전 감사가 실제 진입점의 시간대 설정을 누락해 잘못된 운영 발생 조건을 제시했다.

### Final Severity

**None.** 테스트 편의성 또는 SQL backfill의 문서화된 잔여 차이는 별개의 Observation으로 이해할 수 있으나, 이번 M-06을 Medium으로 유지할 근거는 아니다.

### Actual Conditions

기존에 제시한 “UTC 컨테이너에서 정상 JAR 기동” 조건으로는 문제가 발생하지 않는다. main 우회, 기동 후 기본 timezone 변경, 별도 외부 writer는 다른 조건이며 현재 운영 결함으로 입증하지 않았다.

### Actual Impact

기존 주장에 따른 정상 배포의 생성·수정·감사 기록 혼용은 확인되지 않았다. V6의 제한된 유예 차이는 기존에 기록된 trade-off다.

### Smallest Safe Change

제품 코드를 고치지 않고 감사 판정을 정정한다. 추가 JVM timezone 환경변수나 전체 Clock 리팩터링을 필수 조치로 제시하지 않는다. 데이터 일괄 9시간 보정이나 migration은 하지 않아야 한다.

### Required Tests

이번 실제 JAR/UTC 시작 검증으로 기존 주장 반박에는 충분하다. 향후 다른 launcher나 외부 writer를 도입할 때 그 시간 계약을 검증하면 된다.

### Recommendation

**No action — 제품 변경 기준.** 이번 보고서로 기존 M-06 판정을 철회한다. 과거 보고서는 이력으로 보존한다.

## 운영 준비 공백 / 미확인 항목과 확인 방법

새 결함 목록이 아니라 기존 지적의 검증 경계다.

| 아직 남은 확인 | 필요한 확인 방법 | 현재 결론에 미치는 영향 |
|---|---|---|
| 실제 외부 proxy 구성 | 최종 ingress에서 2개 client IP·위조 헤더·backend 직접 접근 시험 | M-05를 현재 외부 취약점으로 확정하지 않음 |
| 백업 복구의 실제 파일 완전성 | 격리 복원 후 DB key/파일 존재·내용 대조 | 백업 script가 존재한다는 이유로 복구 성공을 보증하지 않음 |
| SMTP 연결·쓰기 지연 및 장기 적체 | 로컬 fault server와 적용 timeout 확인 | timeout 권고 유지, bounded executor 필수화는 보류 |
| 변경 후 회귀 | 각 항목 Required Tests | 이번 검증은 수정 완료나 배포 승인 검증이 아님 |

기존 테스트 재실행 명령은 다음과 같다.

```powershell
.\gradlew.bat compileJava compileTestJava bootJar --console=plain
.\gradlew.bat test --tests '*AdminBootstrapLoaderTest' --tests '*PasswordResetServiceTest' --tests '*MenuConcurrencyIntegrationTest' --tests '*PasswordExpiryIntegrationTest' --tests '*LoginFailureLockoutIntegrationTest' --console=plain
```

추가 기동·브라우저·경합 검증은 위 Verification에 기록한 fixture와 순서로 실행한 일회성 진단이다. 위 Gradle 명령만으로 새 진단까지 자동 재실행되지는 않는다. 영구 회귀 테스트 추가는 별도 승인 단계의 대상이다.

## 최종 자체 검증

- C-01은 직접 인증 우회와 혼동하지 않았고, 브라우저로 확인한 HTML 해석과 추론한 스크립트 영향 범위를 분리했다. 상호작용 조건을 반영해 High로 낮췄다.
- H-01은 의도된 fail-fast의 장점을 인정했다. 수동 복구 가능성과 재시작 조건을 반영해 Critical로 올리지 않았으며, 단순 회원 수 검사·자동 계정 부활을 대안에서 제외했다.
- M-01은 이미 있는 운영 절차를 우선했다. 문서화된 trade-off를 신규 코드 버그로 바꾸지 않았다.
- M-02는 현재 예외·토큰 정리 방어를 인정했고, 대규모 자원 고갈과 bounded executor의 필요성을 과장하지 않았다.
- M-03은 실제 인증 체인으로 재확인했고, 모든 오류·모든 로그 경로로 일반화하지 않았다.
- M-04는 올바른 실제 서비스 경로와 커밋 순서를 사용했다. 기존 부모/자식 테스트 통과를 반증으로 잘못 사용하지 않았다.
- M-05는 아직 없는 외부 구성을 가정해 현재 취약점이라고 판정하지 않았다.
- M-06은 production 진입점을 직접 실행했다. 테스트에서 main을 우회해 발생할 수 있는 차이를 production 문제로 바꾸지 않았다.

새 `NEW-*` 항목은 추가하지 않는다. 기존 결론과 최소 개선 범위를 확정하는 데 검토를 제한했다.

## 최종 요약

| ID | 기존 판정 | 2차 판정 | 최종 Severity | 조치 |
|---|---|---|---|---|
| C-01 | Critical · Security Issue/Bug | CONFIRMED — SEVERITY ADJUSTED | High | Fix before production |
| H-01 | High · Operational Risk | CONFIRMED — SCOPE ADJUSTED | High | Fix before production |
| M-01 | Medium · Operational Risk | CONFIRMED — SCOPE ADJUSTED | Medium, 온라인 쓰기 조건 | Document / accept risk |
| M-02 | Medium · Operational Risk | CONFIRMED — SCOPE ADJUSTED | Medium | Fix before production: timeout만 우선 |
| M-03 | Medium · Bug/Operational Risk | CONFIRMED | Medium | Fix before production |
| M-04 | Medium · Bug, 정적 추론 | CONFIRMED | Medium | Fix before production |
| M-05 | Medium · 조건부 운영/로그 위험 | CONFIRMED — SCOPE ADJUSTED | Low | Document / accept risk; proxy 도입 시 검증 |
| M-06 | Medium · Technical Debt/Operational Risk | REJECTED | None | No action |

### A. 반드시 수정할 항목

- **C-01:** 평문 이름이 ADMIN 화면에서 HTML이 되는 경계. production 전 차단 가치가 충분하다.
- **H-01:** 일시적 계정 상태가 전체 앱 재기동 불능으로 이어지는 정책 결합. 인증 제한은 유지하면서 재검토한다.
- **M-03 / M-04:** 실제 재현된 오류 계약·진단 공백과 메뉴 lost update. High급 긴급 결함은 아니지만 좁은 수정의 가치가 확인됐다. C-01/H-01과 같은 심각도로 취급하지 않는다.

### B. 테스트 또는 재현 후 결정할 항목

- 8개 항목 중 **전체 판정을 실증 대기 상태로 남긴 항목은 없다**. 다만 하위 운영 조건까지 모두 확인한 것은 아니다.
- M-05의 실제 proxy 구성이 정해졌을 때 필요한 앱 변경 범위.
- M-02의 전용 bounded executor 필요성: timeout 적용 후 실제 적체·간섭을 근거로 판단.
- M-01의 실제 복구 완전성: 격리 restore 훈련 전까지 복구 성공을 보증하지 않는다.

### C. 운영 설정 또는 절차로 해결할 항목

- **M-01:** 기존 quiesced backup 절차 채택 또는 온라인 일관성 한계 명시적 수용.
- **M-02:** SMTP 연결·읽기·쓰기 timeout 설정. 제품 코드나 dependency 추가가 우선은 아니다.
- **M-05:** ingress의 전달 헤더 신뢰 계약과 backend 접근 제한. 실제 배포 경로에서 검증한다.

### D. 철회하거나 미룰 항목

- **M-06은 철회.** 정상 JAR 기동의 JVM 시간대 미고정 주장은 틀렸다.
- C-01의 Critical 등급은 철회하되 실제 렌더링 문제 수정은 미루지 않는다.
- M-02의 전용 executor·queue·브로커 필수화는 미룬다.
- 백업용 분산 snapshot 체계, IP 해석 추상화, 전체 Clock 전환, 메뉴 버전 관리 체계의 선제 도입은 이번 증거만으로 요구하지 않는다.

## Final Verdict

**no-ship — C-01과 H-01의 High 위험이 남아 있기 때문.** Critical이 없다는 사실과 배포 가능 여부는 별개다. M-01/M-05는 운영 계약으로, M-02는 설정으로 우선 대응할 수 있다. M-06을 이유로 제품 코드를 바꿀 필요는 없다.

이 보고서는 “무엇이 실제로 수정할 가치가 있는가”에 대한 독립 검증 결과다. 수정 파일 목록·구체적 구현 순서·신규 기능 로드맵은 작성하지 않았다. 승인 전 코드 변경도 수행하지 않았다.
