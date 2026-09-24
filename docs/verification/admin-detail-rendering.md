# 관리자 상세 평문 렌더링 회귀 검증 — C-01 / PR 1

## 범위와 전제

`admin-manage.html`의 상세 평문은 `value`, 내부에서 조립하는 이메일 버튼만 `html`로 구분한다. 평문은 `escapeHtml()`로 출력 시 한 번만 escape한다. DB/API에는 원문을 유지한다. sanitizer, 입력 금지 정책, DTO/서비스/인가 변경은 하지 않는다.

정적 convention/MVC 검사는 실제 브라우저 검증을 대체하지 않는다. 별도 프런트엔드 dependency를 설치하지 않고 사용 가능한 브라우저 자동화 도구로 아래 절차를 실행한다.

- 기존 dev/운영 DB와 분리된 일회용 MariaDB 10.11 및 임시 파일 저장소를 사용한다.
- 실제 JAR를 `prod`로 실행하고 별도 loopback 포트를 사용한다. Flyway 적용과 초기 ADMIN bootstrap 완료를 확인한다.
- 계정·설정은 검증용만 사용한다. SMTP는 미사용 로컬 목적지로 고정하고 브라우저의 외부 출처 요청을 차단한다.
- ADMIN이 API로 ACTIVE MANAGER fixture를 생성한다. 현재 비밀번호 정책을 만족하는 검증용 비밀번호를 사용한다.
- ADMIN/MANAGER는 서로 다른 브라우저 context에서 실제 로그인한다. MANAGER의 원래 이름·이메일·프로필 상태를 기록한다.

## 저장 → DB → ADMIN 화면

MANAGER는 `/admin/member/info`에서 세션의 CSRF 토큰을 얻어 `PATCH /admin/api/members/me`로 아래 이름을 각각 저장한다. 이메일은 기존 값을 유지한다.

| 입력 | 기대 표시 |
|---|---|
| `<span id="audit-name-marker">검증 이름</span>` | 태그 문법을 포함한 문자열 전체 |
| `홍길동 Alice` | 정상 한글·영문 이름 |
| `홍길동 & Alice ' " &lt;` | `&`, 따옴표, 문자 그대로의 `&lt;` 모두 보존 |

각 입력마다 다음을 확인한다.

1. MANAGER 수정 응답은 200이며 JSON `userName`은 입력 원문과 같다.
2. 독립 DB 조회로 `member.user_name` 원문을 확인한다. 터미널 인코딩 영향을 피하려면 `HEX(user_name)`과 입력 UTF-8 바이트를 비교한다.
3. ADMIN의 `GET /admin/api/members/{id}` 응답도 같은 원문이다.
4. ADMIN이 `/admin/member/manage`에서 `.js-detail[data-id="{id}"]`를 클릭한다.
5. `#detailContent`의 `이름` 항목 `.value`의 `textContent`가 원문과 정확히 같다. `#detailContent #audit-name-marker`의 개수는 **0**이다.
6. 해당 목록 행의 이름과 `#detailSummary .headline`도 원문과 같다. `innerHTML` 문자열 자체가 아니라 최종 DOM의 텍스트를 비교한다.
7. `.copy-email-btn`이 실제 버튼으로 존재하고 `data-email`은 원래 이메일이다. 내부 아이콘을 클릭해 위임 이벤트까지 확인하고, 클립보드 값과 `복사되었습니다` 표시를 확인한다.
8. 화면을 캡처하고 JavaScript page error가 없는지 확인한다.

수정 전 기준선에서는 첫 입력의 상세 표식 요소가 1개이고 이름 항목 텍스트는 `검증 이름`만 남는다. 수정 후에는 요소 0개와 원문 전체 표시가 동시에 성립해야 한다. 이벤트 핸들러·스크립트 실행이나 권한 변경 공격은 시험하지 않는다.

## 정상 UI / 역할 / CSRF

- MANAGER의 관리자 목록·상세 API 직접 요청은 403이다. ADMIN 전용 관리 페이지 직접 접근도 403인지 확인한다.
- MANAGER의 CSRF 없는 본인 PATCH는 403이며, 유효한 CSRF를 포함한 요청만 성공한다.
- ADMIN 상세 모달의 수정 진입 시 원문이 입력란에 나타나고, 정상 이름 수정·저장·취소·닫기가 동작한다.
- 프로필이 없을 때 fallback이 보인다. MANAGER가 기존 preset API로 `profile-1`을 선택한 뒤 ADMIN 상세 이미지가 로드되는지(`complete`, `naturalWidth > 0`) 확인한다.
- 원래 이름·이메일을 복구하고 추가한 preset도 원래 상태로 되돌린다. 검증 실패 시에도 정리한다.

### 평문 경계의 보조 브라우저 검증

DB의 날짜/enum 제약을 바꾸지 않고, 브라우저 자동화의 **상세 응답 fixture에만** 다음 값을 주입할 수 있다.

- `userId`: `<span id="audit-id-marker">아이디</span>`
- `createDate`: `<span id="audit-date-marker">날짜</span>` — `formatDate()`의 파싱 실패 fallback 검증.
- `email`: `null` — 기존 `-` 표시와 버튼 부재 검증.

아이디·생성일은 원문 텍스트이며 표식 요소는 0개여야 한다. 이는 실제 DB 저장 검증과 구분해 기록하고 route interception을 해제한다.

## 자동 테스트

Windows에서는 다음을 실행한다. Linux는 `./gradlew`를 사용한다.

```powershell
.\gradlew.bat compileJava compileTestJava bootJar --console=plain
.\gradlew.bat test --tests com.cms.admin.member.AdminMemberTemplateConventionTest --tests com.cms.admin.member.controller.AdminMemberControllerTest --tests com.cms.admin.member.service.AdminMemberServiceTest --tests com.cms.config.SecurityConfigTest --tests com.cms.config.ApiSecurityConfigTest --tests com.cms.admin.AdminSidebarAdviceTest --console=plain
.\gradlew.bat test --console=plain
```

신규 검증은 convention 3개, controller 8개, service 3개로 총 14개다. 서비스 단위 테스트의 엔티티 원문 검사는 실제 DB commit 증명이 아니다. 위 브라우저 검증에서 별도 DB 조회를 수행한다.

## 2026-09-24 실행 결과

- 기준 HEAD: `f89019556a3eff0fe500586255793aae0fff3a85` + PR 1 작업 트리. 브랜치: `feat/admin-detail-safe-text`.
- Java 17 / Spring Boot 3.5.16, 실제 prod JAR, 별도 loopback 앱과 일회용 MariaDB 10.11. 기존 dev/운영 DB는 사용하지 않았다.
- 수정 전: 새 convention 3개 중 2개 의도대로 실패. 실제 Chromium에서 무해한 이름 표식 DOM 1개 확인.
- 수정 후 첫 관련 실행: 168개 중 convention 1개 실패. 이메일 항목 중간 주석 때문에 정적 검사식이 매칭되지 않은 테스트/형식 문제였으며, 주석을 항목 설명 위치로 이동했다. 보안 조건을 약화하지 않았다.
- 재실행: 관련 **168개 모두 통과**, 실패·skip 0.
- 전체: **699개 중 698개 통과 / 실패·오류 0 / skip 1**. 기존 `LocalDiskFileStorageTest.load_symlinkEscape_rejected`가 Windows의 symlink 생성 미지원 조건으로 skip됐다. PR 1 신규 테스트에는 skip이 없다. Linux CI는 이번에 실행하지 않았다.
- 브라우저: 세 이름 모두 DB 원문·상세 JSON·목록·요약·상세 텍스트 일치, 새 DOM 0개, 이중 escape 없음. 이메일 복사·fallback/preset·모달 저장/취소/닫기·MANAGER 403·CSRF 403 모두 통과. page error 0.
- 보조 응답 fixture의 아이디·날짜 fallback·빈 이메일 검증도 통과했다. 정상 DB 날짜 검증과 혼동하지 않는다.
- MANAGER 이름·프로필 원복, 브라우저 종료, 검증 앱 종료, 일회용 DB 제거를 확인했다. 전체 테스트의 Testcontainers DB도 제거됐다. 기존 dev 컨테이너는 유지했다.

로컬 증거는 `build/verification/pr1/`의 `before-result.json`, `after-result.json`, `before-1.png`, `after-1.png`~`after-3.png`, `after-profile.png`와 앱 로그에 있다. [수정 후 캡처](../../build/verification/pr1/after-1.png). 이 경로는 git 제외 빌드 산출물이므로 clean 시 사라진다. PR 제출 시 필요한 캡처·테스트 보고서를 별도 첨부한다. 브라우저 자동화가 CI에 새로 설치된 것은 아니다.

## 남은 범위

이번 결과는 C-01/PR 1에 한정된다. 다른 remediation PR, Linux CI, 전체 release Gate A~H는 완료로 표시하지 않는다. 템플릿 revert는 C-01을 다시 열므로 배포 후 UI 문제가 생기면 좁은 fix-forward를 우선한다.
