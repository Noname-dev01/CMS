# 타 관리자 계정 수정/상태 변경 API — 설계 계획

> 작성일: 2026-07-10 · 기준 커밋: `76bba41` (Spring Boot 3.5.16 업그레이드 #8)
> 대상: `PATCH /admin/api/members/{id}` (+ 관리 화면 수정 UI + 대상자 세션 강제 만료)
> 상태: **구현 완료 (2026-07-10, 브랜치 `feat/admin-member-update`) — 하단 "구현·검증 결과" 참고**

## 개정 이력

- v11 변경(리뷰 10차 반영 — 유일 지적, 리뷰어가 "이것만 명시하면 구현 착수 가능" 판정): **락 예외 → 409 변환을 필수 구현 항목으로 승격.** 현재 `GlobalApiExceptionHandler`는 도메인 `ConflictException`만 409로 매핑하고 그 외는 500 — 설계 서술의 "409로 변환"이 구현 항목으로 못 박히지 않으면 정상적인 동시성 충돌이 500으로 노출된다 [high 수용]. `GlobalApiExceptionHandler`에 `@ExceptionHandler(PessimisticLockingFailureException.class)` → 409 `RESOURCE_CONFLICT` 추가(전역 핸들러인 이유: Spring 락 예외는 flush/커밋 시점 발생이라 서비스 try-catch를 우회 가능). 컨트롤러 슬라이스 테스트(락 예외 → 409)를 필수 테스트로 추가. 리뷰어가 대상 행 잠금·lost update 방향의 코드베이스 정합성을 확인함.
- v10 변경(리뷰 9차 반영 — 유일 지적, 실코드 확인 기반): **대상 회원 row 잠금 추가 — lost update 차단.** `Member`에 `@Version`이 없고 Hibernate는 기본으로 전체 컬럼을 UPDATE하므로, 같은 대상에 대한 동시 PATCH(이메일만 수정 vs 잠금/강등)에서 늦게 flush되는 쪽이 낡은 status/userType을 되써 권한 회수를 조용히 무효화할 수 있다(세션 만료 이벤트도 미발행) [high 수용]. 대상 조회를 `findById` → **`findByIdForUpdate`(PESSIMISTIC_WRITE)**로 변경해 같은 회원에 대한 PATCH를 직렬화 — menu `updateMenu()` 선례와 동일 패턴. 파생 효과 명시: 대상 행 잠금 + 가드 집합 잠금 공존으로 교차 강등 시 데드락이 가능하나 InnoDB가 감지·롤백하고 기존 예외 변환(→ 409 "다시 시도")으로 수렴, 불변식 유지. 교차 PATCH 동시성 테스트(email-only vs 잠금) 추가.
- v9 변경(리뷰 8차 반영 — 유일 지적): **최후 ADMIN 가드 쿼리를 네이티브 SQL로 확정.** v8의 "JPQL 스칼라 프로젝션 + `@Lock(PESSIMISTIC_WRITE)`"는 JPA 표준 보장 밖(비관적 락은 엔티티 결과 대상이 통상 계약)이라 프로바이더가 쿼리를 거부하거나 **조용히 `FOR UPDATE`를 생략**할 수 있고, 후자는 가드가 있다고 믿는데 실제로 없는 최악의 실패 모드다 [high 수용]. `nativeQuery = true`로 `SELECT id ... FOR UPDATE`를 SQL에 명시해 프로바이더 재량을 제거. **락 실증 리포지토리 통합 테스트** 추가 — 트랜잭션 1이 락 보유 중 트랜잭션 2가 짧은 `innodb_lock_wait_timeout`으로 같은 쿼리 실행 시 락 대기 타임아웃 발생을 단언(FOR UPDATE 미발행 시 실패하는 테스트).
- v8 변경(리뷰 7차 반영 — 유일 지적): **최후 ADMIN 가드 쿼리를 스칼라 프로젝션으로 확정.** 엔티티 반환 잠금 쿼리는 영속성 컨텍스트에 이미 로드된 인스턴스의 낡은 필드를 돌려줄 수 있어(JPA 1차 캐시), 구현자가 필드 기반으로 판정하면 상호 강등 레이스에서 불변식이 깨질 수 있다 [high 수용]. 가드는 `SELECT m.id`(활성 ADMIN id만) + `PESSIMISTIC_WRITE` 스칼라 잠금 쿼리로 명세 — 영속성 컨텍스트를 경유하지 않아 낡은 상태로는 구현이 불가능. 동시성 통합 테스트의 단언을 응답 코드가 아닌 **최종 DB 상태(활성 ADMIN ≥ 1)**로 변경. 그 외 계획 전반에 추가 지적 없음.
- v7 변경(리뷰 6차 반영 — 전부 구체화·정합성 지적, 설계 방향 무변경): (1) **최후 ADMIN 가드 락 구체화** — `findActiveAdminsForUpdate()`(활성 ADMIN 엔티티 행을 `ORDER BY id`로 `PESSIMISTIC_WRITE` 잠금)로 확정, count aggregate `@Lock` 금지 명문화, 단일 정렬 잠금 쿼리로 데드락 소지 제거, `PessimisticLockingFailureException` → 409 [high 수용]. (2) 실 DB 2스레드 상호 강등 **동시성 통합 테스트** 추가 [medium 수용]. (3) **best-effort 문구 전면 통일** — UI 안내에 경계 노출, Playwright 표현에서 "즉시" 제거 [medium 수용]. (4) 만료 실패 **ERROR 로그를 필수 계약으로 확정** + 리스너 실패 주입 테스트 추가 + 배선 실패는 e2e 통합 테스트가 필수 게이트 명시. 감사 로그 승격은 기각(액션 타입·라벨·동기화 테스트 파급 대비 이득 낮음) [medium 부분 수용].
- v6 변경(리뷰 5차 반영): (1) **계약 문구 최종 강화** — "이 기능은 즉시 접근 차단 수단이 아니며 극단적 경합 시 기존 세션이 세션 타임아웃까지 유효할 수 있다"를 Swagger·계획 계약에 타임아웃 경계와 함께 명시 [high 수용]. (2) **만료 트리거 규칙 개정** — "모든 상태 실변경(→ACTIVE 포함) 또는 역할 실변경 + 요청 status가 LOCKED/DISABLED면 값이 같아도 만료(멱등 재잠금)". →ACTIVE 만료 생략 가정(생존 세션과 모순) 폐기 [medium 3 수용], 멱등 재잠금이 "만료 실패 시 복구 경로 부재"를 해소 — 자동 재시도는 결정적 버그에 무의미하므로 기각 [medium 1 부분 수용]. (3) **최후 활성 ADMIN 가드 신설** — 자기 자신 금지 불변식은 직렬 요청에서만 성립, 동시 상호 강등 레이스는 비관적 락 카운트 가드(활성 ADMIN 1명 미만이 되는 변경 → 409)로 차단, menu의 `findByIdForUpdate` 선례 준용 [medium 2 수용].
- v5 변경(리뷰 4차 + 사용자 결정 반영): **세션 만료의 보증 수준을 "즉시 종료 보장"에서 "기존 세션 만료 처리(best-effort immediate)"로 정직하게 완화** [리뷰 high 1의 대안 권고 채택 — 사용자 결정]. UI·Swagger·계획 문구에서 "즉시 종료" 주장을 제거하고 잔여 한계(커밋 경합 시 극단적 예외)를 명시적 계약으로 문서화. "밀리초" 등 근거 없는 정량 주장 삭제 [리뷰 medium 수용]. **outbox/재시도 권고는 기각** — 세션 저장소 자체가 인메모리라 JVM 재시작 시 세션이 소멸하므로 인메모리 만료 연산의 영속 outbox는 보호 대상이 없는 패턴 오적용이며, 리뷰어가 나열한 배선(wiring) 버그 위험은 end-to-end 통합 테스트("로그인된 대상자 잠금 → 다음 요청 거부")가 전 체인을 관통해 커버 [리뷰 high 2 반박]. 인메모리 revocation 필터·영속 revocation 체크는 사용자 결정으로 후속 검토 항목에 유지.
- v4 변경(리뷰 3차 반영): **결정 6-1c 재재설계 — 트랜잭션 내 만료(v3안) 폐기, 커밋 후 만료(AFTER_COMMIT) 채택.** v3의 "트랜잭션 내 만료 = split-brain 계약상 불가능" 주장은 동시성 하에서 거짓 — 만료 후·커밋 전 창에서 대상자가 재로그인하면 미커밋(구 ACTIVE) 상태로 인증에 성공하고 그 세션은 만료 패스를 피해 살아남는다 [리뷰 high 수용]. 서비스가 실변경 감지 시 이벤트를 발행하고 `@TransactionalEventListener(phase = AFTER_COMMIT)` 리스너가 만료를 수행하는 방식으로 변경. 커밋 후에는 신규 로그인이 새 상태를 읽어 거부되므로 레이스가 닫힌다. 리뷰가 권고한 "영속 revocation 버전 체크"는 매 요청 DB 재검증이 필요한 과설계로 **기각** — 밀리초 미만 straddle 창은 잔여 리스크로 문서화. 테스트 계획에 만료-커밋 순서 검증과 "잠금 후 대상자 다음 요청 거부" 통합 테스트 추가 [리뷰 medium 부분 수용 — v3 레이스 시뮬레이션은 v4에서 레이스 소멸로 불필요].
- v3 변경(리뷰 2차 반영): **결정 6-1 재설계.** (1) 세션 만료 호출을 컨트롤러(커밋 후 + 예외 격리)에서 **서비스 트랜잭션 내부(상태 반영 후)**로 이동 — 만료 실패 시 전체 롤백(500)되어 "잠금 성공 응답 + 세션 생존" split-brain 원천 차단, 실패는 `@AdminActionLogged` FAIL 로그(REQUIRES_NEW)로 관측 가능 [리뷰 high 1 수용 — 단 "실패 보고" 권고 대신 더 강한 원자성 방식 채택]. (2) 만료 트리거를 요청 DTO 형태 판정에서 **엔티티 before/after 실변경 감지**로 변경 — 같은 값 no-op PATCH의 강제 로그아웃 악용 불가 [리뷰 medium 수용]. (3) 만료된 세션의 API 요청 응답 계약을 설계로 확정: 커스텀 `SessionInformationExpiredStrategy`가 `/admin/api/**`는 JSON 401, 페이지는 `/admin/login` 리다이렉트 — 전략 단위 테스트 포함 [리뷰 high 2 수용].
- v2 변경(리뷰 1차 + 사용자 결정 반영): **결정 6 — 세션 강제 만료를 이번 스코프에 포함(B안 채택)**. 리뷰 1라운드 high 지적("잠금·비활성·강등이 활성 세션에 적용되지 않으면 권한 회수 API가 즉시 차단 수단이 되지 못함 — 성공 응답과 실제 접근 제어 상태 불일치")을 수용하고, 사용자가 B안(SessionRegistry 기반 강제 만료 포함)을 선택. 쟁점 6-1(세션 만료 설계) 신설, 작업 단계·테스트·리스크·고지 갱신.
- v1: 최초 작성 (리뷰 전)

## Context

로드맵 1단계 "계정 라이프사이클 마감"의 첫 작업. 현재 관리자 CRUD는 생성(`POST /members`)·조회(`GET /members`, `/members/{id}`)·자기수정(`PATCH /members/me`)만 있고, **타 관리자 계정의 수정·상태 변경(LOCKED 해제 등)이 불가능**하다. 이 API가 없으면 잠긴 계정을 풀 방법이 DB 직접 수정뿐이다.

**스코프 제외** (별도 후속 작업):
- 비밀번호 초기화/재설정 (로드맵 1단계 3번 작업 — 메일 기반 재설정과 함께)
- 로그인 실패 N회 → LOCKED 자동 전이 (로드맵 1단계 2번 작업)
- 계정 삭제(DELETED 전이) 전용 API

## 정찰 요약

| 확인 대상 | 발견 |
|-----------|------|
| `AdminMemberService` | `validateAdminTarget()`(ROLE_USER 대상은 404), `normalizeUserName/Email`, `validateDuplicatedEmail(email, excludeId)` — 전부 재사용 가능 |
| `MenuUpdateRequest` + `MenuService.updateMenu()` | 이 프로젝트의 PATCH 컨벤션: **null 필드는 기존값 유지**, 변경 불가 필드는 DTO에서 제외(upMenuNo 방식) |
| `@AllowedRoles` / `AllowedRolesValidator` | enum 부분 허용 검증 패턴. null은 통과(@NotNull에 위임) — PATCH의 optional 필드와 궁합이 맞음 |
| `Member` 엔티티 | `updateInfo(userName, email)` 존재. 역할/상태 변경 도메인 메서드는 없음 → 신규 필요. `@Setter` 금지 컨벤션 |
| `AdminActionTypes` + 동기화 테스트 2개 | 새 actionType 추가 시 `ALL` 목록과 `admin/log/manage.html`의 `ACTION_TYPE_LABELS` 갱신이 테스트로 강제됨 |
| `AdminMemberControllerTest` | `@WebMvcTest` + MockConfig(서비스·시큐리티 목) + `MethodSecurityTestConfig` + csrf() 패턴. `AdminSidebarAdvice` 때문에 `MenuService` 목 빈 필요(이미 있음) |
| `CustomUserDetailsService` | 상태 검사는 **로그인 시점에만** 수행 → 상태 변경이 활성 세션에 즉시 반영되지 않음 (쟁점 6) |
| `admin-manage.html` | 상세 모달은 조회 전용. 상태 변경 fetch 호출이 아직 없어 CSRF 헤더 패턴을 이 화면에 새로 넣어야 함 (head fragment의 `<meta name="_csrf">`는 이미 렌더링됨) |
| `GlobalApiExceptionHandler` | `InvalidRequestException`→400, `DuplicateResourceException`→409, `ConflictException`→409, `ResourceNotFoundException`→404 이미 매핑 완료 |
| `AdminMemberController.refreshAuthentication` 패턴 | "서비스 트랜잭션 커밋 후 컨트롤러에서 후처리 + 내부 예외 격리" 선례 — 세션 만료 호출 위치의 기준 |

## 설계 쟁점과 결정

### 쟁점 1 — 수정 가능 필드 범위

- A안: `userName`, `email`, `userType`, `status` (인적 정보 + 권한 + 상태)
- B안: `status`만 (상태 변경 전용 API)
- C안: A안 + 비밀번호 초기화

**결정: A안.**
- 왜: 로드맵 항목이 "수정/상태 변경"을 명시하고, B안이면 이름·이메일 오타 수정에 여전히 DB 접근이 필요해 CRUD가 완성되지 않는다. C안의 비밀번호 초기화는 "임시 비밀번호를 어떻게 전달하는가"라는 별도 설계(메일 발송)가 필요해 3번 작업과 묶는 것이 옳다.
- `userId`는 로그인 식별자이므로 변경 불가 — DTO에서 제외한다 (menu의 `upMenuNo` 제외 방식과 동일).

### 쟁점 2 — 부분 수정 시맨틱

- A안: null 필드는 기존값 유지 (menu PATCH와 동일)
- B안: 전체 필드 필수 (사실상 PUT)

**결정: A안.**
- 왜: 이미 `MenuUpdateRequest`로 확립된 프로젝트 컨벤션이고, "잠금 해제"처럼 status 하나만 바꾸는 유스케이스가 주력이다. `userType`은 `@AllowedRoles(ROLE_ADMIN, ROLE_MANAGER)` 재사용(validator가 null 통과), `userName`은 menu 방식대로 `@Pattern(".*\\S.*")`으로 "보내면 공백 불가", `email`은 `@Email @Size(max=100)`.

### 쟁점 3 — status 허용값

- A안: 5개 상태 전부 지정 가능
- B안: `ACTIVE`, `LOCKED`, `DISABLED`만 허용
- C안: 상태 전이 매트릭스(from→to 화이트리스트) 도입

**결정: B안.**
- 왜: `DELETED`는 소프트 삭제라 "수정"이 아니라 별도 `DELETE /members/{id}` 엔드포인트의 책임이다(REST 시맨틱, 후속 작업). `PASSWORD_EXPIRED`는 시스템(비밀번호 만료 정책)이 전이시킬 상태지 관리자가 수동 지정할 값이 아니다. C안은 현재 상태가 5개뿐이고 운영 시나리오(잠금↔해제↔비활성)가 단순해 과설계 — 전이 제약이 실제로 필요해지면 그때 도입한다.
- 구현: `@AllowedStatuses` 커스텀 validator 신설 (`AllowedRolesValidator`와 동일 구조, CLAUDE.md의 "열거형 일부 허용은 커스텀 ConstraintValidator" 컨벤션).
- **DELETED 상태인 계정은 수정 자체를 거부**한다 → `ConflictException`(409). 조회는 되는 리소스이므로 404보다 "현재 상태와 충돌"인 409가 정직하다.

### 쟁점 4 — 자기 자신 대상 요청

- A안: 전면 금지 (400)
- B안: 허용하되 자기 강등·자기 잠금만 금지
- C안: 제한 없음 + 마지막 활성 ADMIN 카운트 검증

**결정: A안 (자기 자신은 `/members/me` 사용 유도) + 최후 활성 ADMIN 가드 (v6 추가).**
- 왜: 이 API의 호출 주체는 항상 "활성 상태로 로그인한 ADMIN"이다. 자기 자신을 대상에서 제외하면 **직렬 요청에서는** 활성 ADMIN 수가 0이 될 수 없다(호출자 본인이 남는다). B안은 "자기 이름 수정은 되고 강등은 안 되는" 분기 규칙이 늘어 기각.
- **가드가 추가로 필요한 이유 (v6, 리뷰 5차 medium 수용)**: 자기 자신 금지만으로는 **동시 요청**을 못 막는다 — 두 활성 ADMIN이 동시에 서로를 강등/잠금하면 둘 다 자기 검사를 통과해 활성 ADMIN 0명이 될 수 있다. 대상이 `ROLE_ADMIN`이고 변경이 활성 ADMIN 자격을 제거하는 경우(강등 또는 status가 ACTIVE 이탈), 활성 ADMIN 행들을 잠근 뒤 카운트해 변경 후 활성 ADMIN이 1명 미만이면 `ConflictException`(409, "최소 1명의 활성 관리자가 유지되어야 합니다.")을 던진다.
- **락 구체화 (v7 도입, v9 확정)**: `MemberRepository.findActiveAdminIdsForUpdate()` — **네이티브 쿼리**: `@Query(value = "SELECT id FROM member WHERE user_type = 'ROLE_ADMIN' AND status = 'ACTIVE' ORDER BY id FOR UPDATE", nativeQuery = true)`. **스칼라(id) 반환인 이유 (v8)**: 엔티티 반환 잠금 쿼리는 행 집합은 SQL 수준에서 최신이어도, 영속성 컨텍스트에 이미 로드된 `Member` 인스턴스가 있으면 낡은 필드를 가진 기존 인스턴스를 돌려준다(JPA 1차 캐시) — 필드 기반 판정 구현을 원천 봉쇄하기 위해 id만 반환한다. **네이티브 SQL인 이유 (v9)**: JPQL 스칼라 프로젝션에 `@Lock`을 붙이는 것은 JPA 표준 보장 밖이라 프로바이더가 거부하거나 조용히 `FOR UPDATE`를 생략할 수 있다 — SQL에 `FOR UPDATE`를 명시해 재량을 제거하고, 락 발행 여부는 별도 통합 테스트로 실증한다. 가드 판정: 잠금 조회가 반환한 id 집합에서, 이번 변경으로 활성 ADMIN 자격을 잃는 대상 id를 제외한 수가 1 미만이면 409. **count aggregate에 `@Lock`을 붙이지 않는다** — 집계 쿼리는 행 잠금을 보장하지 않는다. 두 상호 강등 트랜잭션이 같은 정렬 순서(`ORDER BY id`)로 같은 행 집합을 잠그므로 가드끼리는 첫 행에서 직렬화된다. `PessimisticLockingFailureException`(락 타임아웃·데드락 감지)의 409 변환은 **`GlobalApiExceptionHandler`에 전역 핸들러로 추가한다 (v11, 필수 구현 항목)** — 현재 핸들러에는 이 매핑이 없어 기본 500으로 떨어지며, 락 예외는 flush/커밋 시점에 발생할 수 있어 서비스 계층 try-catch로는 안정적으로 잡을 수 없다. `@ExceptionHandler(PessimisticLockingFailureException.class)` → 409(`RESOURCE_CONFLICT`, "동시 변경과 충돌했습니다. 다시 시도해주세요."), 컨트롤러 테스트로 증명.
- **대상 행 잠금 (v10, 리뷰 9차 high 수용 — lost update 차단)**: 대상 조회를 `MemberRepository.findByIdForUpdate(id)`(PESSIMISTIC_WRITE, menu `updateMenu()` 선례)로 수행해 **같은 회원에 대한 PATCH를 직렬화**한다. 이유: `Member`에 `@Version`이 없고 Hibernate는 `@DynamicUpdate` 없이 전체 컬럼을 UPDATE하므로, 이메일만 수정하는 트랜잭션이 동시 진행된 잠금/강등의 결과를 낡은 값으로 되쓸 수 있다(권한 회수의 조용한 무효화 + 세션 만료 이벤트 미발행). 행 잠금으로 두 번째 PATCH가 첫 번째 커밋 이후의 최신 상태를 읽게 된다.
- **데드락 처리 방침 (v10, v11 앵커링)**: 대상 행 잠금(1차) → 가드 집합 잠금(2차) 순서에서, 서로 다른 대상을 향한 두 회수 PATCH가 교차하면(예: Tx1이 B를, Tx2가 A를 잠근 뒤 둘 다 가드 집합 {A,B} 요청) 데드락이 가능하다. InnoDB가 즉시 감지해 한쪽을 롤백하며, **위의 전역 핸들러(v11 필수 항목)**에 따라 409("다시 시도")로 응답된다 — 불변식(활성 ADMIN ≥ 1)은 어느 쪽이 이기든 유지되고, 재시도로 해소되는 운영상 무해한 충돌이다.
- 구현: `AdminSecurityService.getCurrentAdminId()`와 대상 id 비교 → 일치 시 `InvalidRequestException`(400, "본인 계정은 내 정보 수정을 이용해주세요.").

### 쟁점 5 — 대상 유효성

- 기존 `validateAdminTarget()` 재사용: `ROLE_USER` 계정은 이 API의 관리 대상이 아님 → 404 (상세 조회와 동일 정책).

### 쟁점 6 — 대상자 활성 세션과의 정합성

타 계정을 `LOCKED`/`DISABLED`로 바꾸거나 강등해도, 상태·권한 검사는 로그인 시점(`CustomUserDetailsService`)에만 수행되고 principal이 세션에 캐시되므로 **아무 조치가 없으면 대상자의 기존 세션이 유지**된다.

- A안: 현 단계 수용 — "재로그인부터 적용"을 명시적 제약으로 문서화
- B안: `SessionRegistry` + `HttpSessionEventPublisher` 도입해 대상자 세션 강제 만료

**결정: B안 (v2에서 변경 — 리뷰 1라운드 high 지적 수용 + 사용자 결정).**
- 왜: 이 API의 주력 유스케이스가 잠금·비활성·강등인데, 성공 응답을 반환하고도 대상자가 계속 관리자 기능을 쓸 수 있으면 **API의 의미(권한 회수)와 실제 효과가 불일치**한다. v1의 "상호 신뢰 환경" 가정은 문서화된 근거가 없는 임의 가정이었다. 세션 인프라 추가 비용을 치르더라도 권한 경계를 즉시 반영하는 것이 옳다.

### 쟁점 6-1 — 세션 강제 만료 설계 (v2 신설)

**(a) SessionRegistry 채우기**
- Spring Security 표준 방식: `SecurityConfig`에 `sessionManagement(s -> s.maximumSessions(-1).sessionRegistry(...))` + `HttpSessionEventPublisher` 빈 등록.
- `maximumSessions(-1)`은 동시 세션 수를 제한하지 않으면서(기존 로그인 정책 무변경) 세션 등록·만료 추적만 활성화한다. **URL 인가 규칙과 로그인 정책(동시 로그인 허용 수 포함)은 바꾸지 않는다.**
- 만료된 세션의 다음 요청은 `ConcurrentSessionFilter`가 가로챈다. **응답 계약을 설계로 확정한다 (v3, 리뷰 high 2 수용)**: 커스텀 `SessionInformationExpiredStrategy`를 구현해 요청 URI가 `/admin/api/**`이면 기존 `ApiAuthenticationEntryPoint`와 동일한 JSON 401(`UNAUTHORIZED`) 포맷으로 응답하고, 그 외(페이지 요청)는 `/admin/login`으로 리다이렉트한다. API 클라이언트가 HTML 리다이렉트를 받는 계약 위반을 차단하며, 이 분기는 전략 클래스 단위 테스트로 증명한다.

**(b) 만료 트리거 (v6 개정, 리뷰 3·5차 반영)**
- 만료 조건: **① `status` 실변경(→ACTIVE 복귀 포함 모든 전이), ② `userType` 실변경(승격·강등 모두), ③ 요청 `status`가 `LOCKED`/`DISABLED`인 경우 — 값이 기존과 같아도(멱등 재잠금)**.
- ①이 →ACTIVE를 포함하는 이유 (v6): "잠긴 계정은 활성 세션이 없다"는 v4 가정은 best-effort 계약이 인정한 생존 세션 경합과 모순된다 — 생존 세션이 있었다면 잠금 해제 시점에 낡은 principal째로 살아나므로, 상태가 실제로 바뀌면 항상 청소한다.
- ③의 이유 (v6): "잠금 성공 + 만료 실패" 상황에서 재잠금이 no-op이면 만료를 재시도할 방법이 없다. 멱등 재잠금을 만료 트리거로 인정하면 **운영자 복구 경로**(ERROR 로그 확인 → 같은 값으로 재저장)가 생긴다. 이미 LOCKED/DISABLED인 계정의 세션 만료는 남용 우려가 없다 — 2라운드에서 차단한 남용은 "실변경 없이 **활성** 관리자를 강제 로그아웃"이었고, 그 경로(status=ACTIVE 동일값·userType 동일값)는 여전히 만료를 트리거하지 않는다.
- 승격도 만료하는 이유: principal 캐시 일관성 — 재로그인 즉시 새 권한이 적용되고, "강등만 만료" 분기보다 규칙이 단순하다.
- 이름·이메일만 바뀐 경우는 만료하지 않는다 (topbar 표기는 대상자 재로그인/다음 세션에서 갱신 — 저위험 스테일 수용).

**(c) 호출 위치와 계층 — 커밋 후 만료, 이벤트 기반 (v4, 리뷰 3차 high 수용)**
- 신규 컴포넌트 `AdminSessionService`(`com.cms.config.auth`)에 `expireSessionsFor(Long memberId)` 구현: `SessionRegistry.getAllPrincipals()`를 순회해 `CustomUserDetails`의 member id가 일치하는 principal의 모든 세션을 만료. (principal equals/hashCode에 의존하지 않는 id 비교 — `CustomUserDetails`가 equals를 구현하지 않았기 때문)
- 흐름: 서비스가 실변경(6-1b)을 감지하면 트랜잭션 내에서 `AdminSessionRevokeEvent(targetMemberId)`를 발행(`ApplicationEventPublisher`)하고, 리스너(`AdminSessionRevokeListener`, `com.cms.config.auth`)가 `@TransactionalEventListener(phase = AFTER_COMMIT)`으로 **커밋 성공 후에만** `expireSessionsFor()`를 호출한다. 롤백되면 이벤트는 소비되지 않는다(만료 없음 — 상태 변경도 없으므로 정합).
- **v3안(트랜잭션 내 만료)을 폐기한 이유**: `expireNow()`는 DB 트랜잭션과 결합되지 않는 인메모리 연산이라, "만료 후·커밋 전" 창에서 대상자가 재로그인하면 미커밋(구 ACTIVE) 상태를 읽고 인증에 성공하며, 그 신규 세션은 이미 지나간 만료 패스를 피해 **커밋 후에도 살아남는다**. v3의 원자성 주장은 동시성 하에서 성립하지 않았다. AFTER_COMMIT에서는 만료 패스가 커밋 뒤에 돌므로 커밋 전 생성된 세션까지 쓸어내고, 커밋 후 신규 로그인은 새 상태(LOCKED 등)를 읽어 로그인 자체가 거부된다.
- **만료 실패 관측성 — 필수 계약 (v7 승격)**: 커밋 후 실패는 응답을 뒤집을 수 없으므로(이미 200 반환 경로), 리스너의 **ERROR 로그 기록은 선택이 아닌 필수 계약**이며 실패 주입 단위 테스트(리스너 내부 예외 → ERROR 로그 + 예외 미전파)로 검증한다. 재잠금으로 복구 가능한 실패(일시적 상태)와 복구 불가능한 **배선 실패**(리스너 미등록, SessionRegistry 미적재, principal 매칭 버그)를 구분한다 — 후자는 로그조차 남지 않을 수 있으므로 **e2e 통합 테스트("로그인된 대상자 잠금 → 다음 요청 거부")를 배선 검증의 필수 게이트**로 삼는다. 감사 로그(AdminActionLog) 승격은 기각: 새 actionType·화면 라벨·동기화 테스트 파급 대비, 단일 노드 백오피스에서 ERROR 로그 + 필수 통합 테스트로 충분하다.
- **보증 수준 — best-effort immediate (v5 사용자 결정, v6 문구 확정)**: 이 기능의 계약은 "잠금·비활성·권한 변경 시 대상자의 **기존 세션을 만료 처리한다**"이며, **즉시 접근 차단 수단이 아니다**. 로그인 인증의 DB 읽기가 커밋 직전에 일어나고 세션 등록이 커밋 후 만료 패스 이후에 완료되는 경합 창에서는 신규 세션이 생존할 수 있고, 이 경우 대상자의 접근은 **세션 타임아웃(현재 설정 없음 = 서블릿 기본 30분) 또는 멱등 재잠금(6-1b③) 시점까지** 유지될 수 있다. 이 경계는 Swagger 설명에 그대로 명시한다. 완전 차단(매 요청 상태 재검증·revocation 필터)은 프로젝트 규모(관리자 수 명, 단일 노드 백오피스) 대비 과해 **계약을 정직하게 완화하는 쪽을 선택**했다(리뷰 4차 high 1의 대안 권고, 사용자 결정).
- **outbox/재시도 기각 근거 (리뷰 4차 high 2 반박, 5차 medium 1 부분 수용으로 보완)**: 세션 저장소가 인메모리(서블릿 컨테이너 세션 + `SessionRegistryImpl`)이므로 JVM 재시작 시 세션 자체가 소멸한다 — 재시작 후 재실행할 영속 outbox는 보호할 대상이 없다. 같은 JVM 내 실패도 `expireNow()`는 인메모리 연산이라 일시 장애가 아닌 **결정적 버그**이며, 자동 재시도는 같은 결과를 반복할 뿐이다 — 배선 결함은 end-to-end 통합 테스트로 잡는다. 대신 **운영 복구 경로를 설계에 내장**한다: 만료 실패는 ERROR 로그로 기록되고, 운영자가 같은 값으로 재저장(멱등 재잠금, 6-1b③)하면 만료가 재시도된다.

### 쟁점 7 — 감사 로깅

- `AdminActionTypes.ADMIN_UPDATE` 신설, `@AdminActionLogged(actionType = ADMIN_UPDATE, targetType = "MEMBER", targetIdExpression = "id")`.
- 파급(테스트로 강제됨): `AdminActionTypes.ALL` 목록 + `admin/log/manage.html`의 `ACTION_TYPE_LABELS`에 라벨 추가.

### 쟁점 8 — 관리 화면 수정 UI

- A안: API만 구현 (화면은 후속)
- B안: `admin-manage.html` 상세 모달에 수정 폼(이름·이메일·권한·상태) + 저장 버튼 추가

**결정: B안.**
- 왜: "관리자 CRUD 완성"이 목표인데 호출할 화면이 없으면 미완이다. 실기 검증(Playwright)도 UI가 있어야 골든 패스를 확인할 수 있다. 규모는 기존 모달에 보기/수정 모드 전환 + PATCH fetch 하나로 제한한다(새 페이지 없음).
- 제약: 이 화면에는 상태 변경 fetch가 처음이므로 **CSRF 헤더(`X-CSRF-TOKEN`) 패턴을 추가**해야 한다(head fragment의 meta에서 읽기 — 기존 컨벤션).
- 본인 계정 행은 수정 진입을 막는 처리(쟁점 4와 일관): 모달에서 내 계정이면 수정 버튼 대신 "내 정보" 링크 안내. 판별용으로 현재 관리자 id가 필요 — `GET /members/me` 응답의 id를 화면 로드 시 1회 조회해 사용 (새 모델 속성 추가 없이 기존 API 재사용).
- 잠금·비활성·권한 변경 저장 시 "대상자의 기존 로그인 세션은 만료 처리됩니다. (드물게 세션 타임아웃까지 지연될 수 있음)" 안내 문구를 UI에 노출한다 (v7: 쟁점 6-1c의 best-effort 계약을 UI에도 경계까지 동일하게 노출 — "즉시 종료" 같은 과장 금지).

### 쟁점 9 — HTTP 명세 요약

| 항목 | 값 |
|------|-----|
| 엔드포인트 | `PATCH /admin/api/members/{id}` |
| 인가 | `@PreAuthorize("hasRole('ADMIN')")` (MANAGER 불가 — 생성·목록과 동일) |
| 요청 | `AdminMemberUpdateRequest` { userName?, email?, userType?, status? } — 전부 optional, 단 **모두 null이면 400** (무의미 요청 거부) |
| 응답 | 200 OK + `AdminMemberResponse` |
| 오류 | 400 검증 실패·자기 자신 대상·전부 null / 401 미인증 / 403 MANAGER / 404 없는 id·ROLE_USER 대상 / 409 이메일 중복·DELETED 계정 수정 |
| 부수 효과 | 상태·권한 변경 시 대상자의 기존 세션 만료 처리 — **즉시 접근 차단 수단 아님**: 커밋 경합 시 기존 접근이 세션 타임아웃(기본 30분) 또는 재잠금까지 유지될 수 있음 (Swagger 설명에 경계까지 명시) |

- `refreshAuthentication()` 불필요: 타인 수정이므로 내 principal은 변하지 않는다 (자기 자신은 쟁점 4로 차단).

## 스키마 / 인가 정책 영향 (사전 고지)

- **스키마 변경: 없음.** 기존 컬럼만 사용, Flyway 마이그레이션 불필요.
- **URL 인가 규칙 변경: 없음.** 신규 엔드포인트에 기존 패턴(`@PreAuthorize("hasRole('ADMIN')")`) 적용만.
- **SecurityConfig 변경: 있음 (v2, 사용자 승인 2026-07-10).** 세션 관리 설정 추가 — `sessionManagement` + `SessionRegistry` + `HttpSessionEventPublisher` + expired 전략. 동시 로그인 제한은 두지 않으며(`maximumSessions(-1)`) 로그인 정책·URL 권한은 그대로다.

## 작업 단계 (의존 방향 안쪽부터)

1. **도메인**: `Member`에 도메인 메서드 추가 — `changeRole(Role)`, `changeStatus(MemberStatus)` (updateDate 갱신 포함). 기존 `updateInfo()` 재사용.
2. **DTO·검증**: `AdminMemberUpdateRequest` + `@AllowedStatuses`/`AllowedStatusesValidator` 신설. → `./gradlew compileJava`
3. **세션 인프라**: `SecurityConfig` sessionManagement 설정 + `HttpSessionEventPublisher` 빈 + 커스텀 `SessionInformationExpiredStrategy`(API는 JSON 401 / 페이지는 로그인 리다이렉트) + `AdminSessionService.expireSessionsFor()` + `AdminSessionRevokeEvent`/`AdminSessionRevokeListener`(AFTER_COMMIT) 구현. → `./gradlew compileJava`
4. **Service**: `AdminMemberService.updateAdminMember(currentAdminId, targetId, request)` — 자기 자신 차단(id 비교, 조회 전) → **대상 잠금 조회 `findByIdForUpdate`**(v10)·검증(404/ROLE_USER/DELETED) → **최후 활성 ADMIN 가드**(대상이 ADMIN이고 활성 자격 제거 시 네이티브 FOR UPDATE id 조회, 위반 409) → null 아닌 필드만 반영(이메일 중복 검증 포함) → **6-1b 트리거 충족 시 `AdminSessionRevokeEvent` 발행(만료는 커밋 후 리스너가 수행)** → 응답 변환. `AdminActionTypes.ADMIN_UPDATE` 추가, `MemberRepository`에 잠금 조회 2종 추가, **`GlobalApiExceptionHandler`에 `PessimisticLockingFailureException` → 409 핸들러 추가 (v11 필수)**.
5. **Controller**: `PATCH members/{id}` 매핑 + Swagger 어노테이션 (세션 만료는 서비스 책임 — 컨트롤러 후처리 없음). → `./gradlew compileJava`
6. **테스트**: 서비스 단위 + 컨트롤러 슬라이스 + `AdminSessionService` 단위(실제 `SessionRegistryImpl`로 등록→만료 왕복) + 동기화 테스트. → `./gradlew test`
7. **화면**: `admin-manage.html` 모달 수정 모드 + CSRF fetch + 본인 계정 분기 + 세션 종료 안내 문구. `log/manage.html` 라벨 추가.
8. **실기 검증(Playwright)**: ADMIN 로그인 → 타 계정 이름/상태 변경 왕복(저장→재조회→원복) → 본인 계정 수정 차단 확인 → **두 브라우저 컨텍스트로 잠금 후 대상자의 다음 요청이 차단됨(만료 처리)을 확인** (v7: "즉시성"은 계약이 아니므로 보장 문구로 쓰지 않는다) → MANAGER 로그인 시 API 403 확인 → 기존 로그인/화면 회귀 (세션 설정 추가가 기존 로그인 흐름을 깨지 않는지 포함).

## 테스트 계획 상세

- **기본값/부분 수정**: status만 보낸 요청이 userName·email·userType을 유지하는가 (menu 패턴의 null 유지 왕복)
- **경계**: 전부 null 400, userName 공백 400, email 형식 오류 400, userType=ROLE_USER 400(@AllowedRoles), status=DELETED/PASSWORD_EXPIRED 400(@AllowedStatuses)
- **보호 규칙**: 자기 자신 400, DELETED 계정 수정 409, ROLE_USER 대상 404, 타인 이메일과 중복 409, **최후 활성 ADMIN 제거 시도 409**(대상이 유일한 활성 ADMIN일 때 강등/잠금/비활성 거부 — 참고: 자기 자신 금지로 호출자가 항상 별도 활성 ADMIN이므로 이 409는 주로 동시성·데이터 이상 방어)
- **세션 만료**: (서비스 단위) 상태 실변경(→ACTIVE 포함)·userType 실변경·**멱등 재잠금(LOCKED/DISABLED 동일값)** 시 `AdminSessionRevokeEvent` 발행됨 / **status=ACTIVE 동일값·userType 동일값·이름·이메일만 변경 시 발행 안 됨**(활성 관리자 강제 로그아웃 남용 차단 유지). (순서 검증) 리스너가 AFTER_COMMIT에 바인딩되어 **커밋 전에는 만료가 실행되지 않고, 롤백 시 실행되지 않음**. (통합 — **배선 검증 필수 게이트**) 로그인된 대상자를 잠근 뒤 대상자의 다음 요청이 거부되는 end-to-end 왕복. (실패 주입, v7 추가) 리스너 내부에서 만료가 예외를 던져도 ERROR 로그가 남고 예외가 전파되지 않음. (인프라 단위) `SessionRegistryImpl` 왕복(등록→expireNow→isExpired), `SessionInformationExpiredStrategy` 분기(API 요청 → JSON 401 포맷, 페이지 요청 → `/admin/login` 리다이렉트). *커밋 경합 창의 세션 생존은 v5에서 계약상 허용된 한계(best-effort)이므로 보장 대상 테스트가 아니다 — 계약이 보장하는 것("기존 세션 만료 처리")만 테스트한다.*
- **최후 ADMIN 가드**: (단위) 유일한 활성 ADMIN을 강등/잠금하는 요청 409, 다른 활성 ADMIN이 존재하면 통과 — 가드 로직·경계값. (통합, v7 추가·v8 강화) 실 DB에서 2스레드가 서로를 동시 강등/잠금하는 시나리오 — **단언 대상은 최종 DB 상태**: 커밋 완료 후 DB에서 조회한 활성 ADMIN 수가 반드시 ≥ 1 (응답 코드 200/409 조합은 보조 확인). (락 실증, v9 추가) 트랜잭션 1이 `findActiveAdminIdsForUpdate()` 락 보유 중일 때, 트랜잭션 2가 짧은 `innodb_lock_wait_timeout`으로 같은 쿼리 실행 → 락 대기 타임아웃 발생 단언 — 쿼리가 실제로 `FOR UPDATE` 행 잠금을 획득하지 않으면 실패하는 테스트. (lost update 방지, v10 추가) 같은 대상에 email-only PATCH와 잠금/강등 PATCH를 동시 실행 — 최종 DB 상태에서 회수 변경(LOCKED/강등)이 보존되고(되살아나지 않음) 세션 만료 이벤트가 유실되지 않음을 단언
- **락 실패 계약 (v11 필수)**: 서비스가 `PessimisticLockingFailureException`(및 하위 `CannotAcquireLockException` 등)을 던질 때 컨트롤러 응답이 500이 아닌 409 `RESOURCE_CONFLICT`임을 컨트롤러 슬라이스 테스트로 증명
- **인가**: MANAGER 403, 미인증(csrf 없는 요청 포함) 401/403
- **동기화**: `AdminActionTypeSyncTest`·`AdminActionTypeLabelSyncTest` 통과 (ADMIN_UPDATE 등록)

## 리스크

1. **세션 설정이 전역 필터 체인에 추가됨**: `ConcurrentSessionFilter`·세션 등록이 기존 로그인/로그아웃 흐름에 영향을 줄 수 있다 — 실기 검증 8단계에서 기존 로그인 회귀를 명시적으로 확인한다. (v1의 "세션 스테일" 리스크는 B안 채택으로 해소)
2. **admin-manage.html은 공유 화면**: 모달 구조 변경이 기존 상세 조회 흐름을 깨면 안 된다 — 회귀 검증 필수.
3. **감사 로그 동기화 테스트**: actionType 추가 누락 시 테스트가 잡아주지만, 라벨(한국어) 추가는 화면 파일 수정이라 놓치기 쉬움 — 작업 단계 7에 명시.
4. ~~**no-op PATCH의 과잉 만료**~~ → v3에서 해소: 실변경 감지 기반 트리거로 변경 (쟁점 6-1b).
5. ~~**만료 성공 후 커밋 실패 창**~~ → v4에서 소멸: AFTER_COMMIT 방식은 롤백 시 만료 자체가 실행되지 않는다.
6. **커밋 경합 창의 세션 생존 (계약상 허용된 한계)**: v5에서 "즉시 종료 보장"이 아닌 "기존 세션 만료 처리(best-effort)"로 계약을 완화해 리스크가 아니라 **문서화된 계약 한계**가 됨 — Swagger·UI 문구에 반영, 완전 차단(revocation 체크)은 후속 검토 항목 (쟁점 6-1c).
7. **커밋 후 만료 실패는 응답에 반영 불가**: ERROR 로그로 관측 + 멱등 재잠금(6-1b③)이 운영 복구 경로 — `expireNow()`가 인메모리 연산이라 현실적 확률은 낮음 (쟁점 6-1c).
8. **최후 ADMIN 가드의 락 범위**: 활성 ADMIN 행들에 PESSIMISTIC_WRITE — 관리자 수가 적어 락 경합 비용은 무시 가능, 대신 데드락 가능성을 낮추기 위해 가드는 대상 행 잠금과 동일 트랜잭션에서 일관된 순서로 수행 (쟁점 4).

## 후속 (이 작업에서 하지 않음)

- `DELETE /admin/api/members/{id}` (DELETED 소프트 삭제 전용)
- 로그인 실패 잠금, 비밀번호 재설정 메일 (로드맵 1단계 잔여)
- 세션 만료 보증 강화 — 인메모리 revocation 필터 또는 영속 revocation 체크 (v5에서 계약 완화로 대체, 멀티 노드·외부 세션 저장소 도입 시 재평가)

---

## 구현·검증 결과 (2026-07-10)

### Context

v11 계획을 사용자 승인 후 그대로 구현. 브랜치 `feat/admin-member-update`. 계획 대비 이탈 없음 — 작업 단계 1~8을 순서대로 수행했고 설계 결정(쟁점 1~9)을 전부 계획대로 반영했다.

### 핵심 확정 사항

- **API**: `PATCH /admin/api/members/{id}` — 부분 수정(null 유지), `userId` 제외, 전부 null 400(`@AssertTrue`), status는 `@AllowedStatuses`로 ACTIVE/LOCKED/DISABLED만, 본인 대상 400, DELETED 409, ROLE_USER 404
- **동시성**: 대상 행 `findByIdForUpdate`(PESSIMISTIC_WRITE) + 최후 활성 ADMIN 가드 `findActiveAdminIdsForUpdate`(네이티브 `SELECT id ... FOR UPDATE`, 스칼라 반환) + `PessimisticLockingFailureException` → 409 전역 핸들러(v11 필수 항목)
- **세션 만료**: `SessionRegistry`(`maximumSessions(-1)`) + `AdminSessionRevokeEvent` → `AdminSessionRevokeListener`(AFTER_COMMIT, 실패 시 ERROR 로그·예외 미전파) + `AdminSessionExpiredStrategy`(API JSON 401 / 페이지 리다이렉트). 트리거: status 실변경(→ACTIVE 포함)·userType 실변경·멱등 재잠금(LOCKED/DISABLED 동일값)
- **감사**: `AdminActionTypes.ADMIN_UPDATE` + 로그 화면 라벨 "관리자 수정"
- **화면**: admin-manage.html 상세 모달 보기/수정 모드 전환, 변경분만 전송(diff), CSRF `X-CSRF-TOKEN` 헤더, 본인 계정 "내 정보" 안내, DELETED 수정 불가 안내, best-effort 세션 만료 문구

### 구현 파일

- 도메인/DTO: `Member`(changeRole/changeStatus), `AdminMemberUpdateRequest`, `AllowedStatuses`/`AllowedStatusesValidator`
- 세션 인프라: `SecurityConfig`(sessionManagement + SessionRegistry/HttpSessionEventPublisher 빈), `AdminSessionExpiredStrategy`, `AdminSessionService`, `AdminSessionRevokeEvent`, `AdminSessionRevokeListener`
- 서비스/리포지토리: `AdminMemberService.updateAdminMember()`, `MemberRepository`(잠금 조회 2종), `AdminActionTypes`, `GlobalApiExceptionHandler`(락 예외 409)
- 웹/화면: `AdminMemberController`(PATCH + Swagger best-effort 경계 명시), `admin/member/admin-manage.html`(수정 모드), `admin/log/manage.html`(라벨)
- 테스트: `AdminMemberServiceTest`(+13), `AdminMemberControllerTest`(+12, 락 예외 409 계약 포함), `AdminSessionServiceTest`, `AdminSessionExpiredStrategyTest`, `AdminSessionRevokeListenerTest`(AFTER_COMMIT 바인딩 + 실패 주입 ERROR 로그), `AdminMemberUpdateConcurrencyIntegrationTest`(상호 잠금 불변식·**FOR UPDATE 락 실증**·**lost update 방지**·커밋 후 이벤트 소비), `AdminSessionRevocationIntegrationTest`(**e2e 배선 게이트**: 실로그인→잠금→다음 요청 거부 API 401/페이지 리다이렉트)

### 검증 결과

- `./gradlew test` 전체 통과 (28개 클래스, 실 MariaDB 통합 테스트 포함)
- Playwright 실기 검증 (스크린샷: `.playwright-mcp/01~05-*.png`):
  - 골든 패스: 모달 수정 모드 → 이름+상태(LOCKED) 저장 → DB 반영 확인 → UI로 원복 확인
  - 본인 계정: 수정 버튼 미노출 + "본인 계정은 내 정보에서 수정해주세요." 안내
  - **세션 만료 실기**: 브라우저(대상자 로그인) + curl(admin 세션 잠금) 2세션 — 잠금 직후 대상자 다음 페이지 요청이 `/admin/login` 리다이렉트, LOCKED 재로그인은 `/admin/login-error` 거부
  - MANAGER: `/admin/member/manage` 페이지 403, PATCH API JSON 403(ACCESS_DENIED), self API 200(회귀 정상)
  - 감사 로그: `ADMIN_UPDATE SUCCESS MEMBER #114` 기록 + 활동 로그 화면 "관리자 수정" 라벨 표시
  - 회귀: 기존 로그인/로그아웃 흐름(세션 설정 추가 후), 메뉴 관리·동적 사이드바 정상

### 이슈

- 없음. 콘솔의 favicon.ico 500은 기존 이슈(본 작업과 무관, memory에 기록됨).
- Mockito `@InjectMocks` 대상에 `ApplicationEventPublisher` 생성자 파라미터가 추가되어 기존 서비스 테스트에 `@Mock` 추가 필요했음(반영 완료).

### 구현 후 Codex 리뷰 (2026-07-10, 트리아지 완료)

- **[P2] 빈 이메일 저장 가능 — 수용·수정 완료**: `AdminMemberUpdateRequest.email`에 `@Email`만 있어 `""`·공백 문자열이 검증을 통과해 빈 이메일이 저장될 수 있었다(생성은 `@NotBlank` 필수라 PATCH로만 뚫리는 구멍). `userName`과 동일한 `@Pattern(".*\\S.*")`을 추가해 "필드가 존재하면 공백 불가, null은 기존값 유지"로 정렬. 컨트롤러 슬라이스 테스트(`""`·`"   "` → 400 VALIDATION_ERROR) 추가, `./gradlew test` 전체 재통과.
- 그 외 지적 없음.

### 후속

- 상단 "후속 (이 작업에서 하지 않음)" 목록과 동일 (DELETE 엔드포인트, 로그인 실패 잠금, 비밀번호 재설정 메일, revocation 강화)
