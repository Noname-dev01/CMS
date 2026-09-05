# PLAN — 자기 정보 수정의 행 잠금 누락 해소 (감사 H-02)

> 개정 이력
> v1 (2026-09-05): 최초 작성.
> v2 (2026-09-05): codex 적대적 리뷰 1라운드 반영. (1)Test B를 barrier 기반 순수 동시 실행에서 latch 기반 명시적 순서 제어로 재설계 — 기존 설계는 위험한 인터리빙을 강제하지 못해 `findById`로 회귀해도 통과할 수 있는 약한 테스트였음(수용). (2)(3) 재설계된 Test B는 `PasswordResetService.requestReset` 대신 `issueToken`과 동일한 DB 동작을 직접 재현해 비동기 메일 발송·락 실패 삼키기로 인한 거짓양성 경로를 원천 제거(수용). (4) 단위 테스트에 `findByIdForUpdate` 호출 검증(`verify`)을 추가해 호출 계약을 고정(수용). (5) 완료 기준 문구를 실제 검증 범위(이메일 변경↔재설정 토큰 발급 경합)로 좁힘(수용). (6) "새 데드락 클래스 없음" 표현을 "명시적 다중 행 잠금 순서 추가 없음"으로 완화(수용). (7) Playwright를 필수 완료 기준에서 권장 회귀 확인으로 격하(부분 수용 — 화면 미변경이라 CLAUDE.md 8번 규칙의 엄격 대상 아님). 기존 "테스트 A"(범용 락 충돌 증명)는 재설계된 Test B가 동일 메커니즘을 실제 서비스 경로에서 더 정확히 증명하므로 중복 제거.
> v3 (2026-09-05): codex 적대적 리뷰 2라운드 반영. **v2의 핵심 결함**: `Future.get(timeout)`이 감싸는 것은 `updateMyInfo()` 전체 호출인데, 수정 전 코드도 최종 UPDATE(flush) 단계에서 B의 행 잠금 때문에 대기한다 — 즉 "블로킹 여부"로는 수정 전/후를 구분할 수 없다(계획 본문이 스스로 이 사실을 적어놓고도 그것이 자신의 핵심 판별 논리와 모순됨을 놓친 논리 오류). (1)(2) 수용 — "블로킹=회귀 판별" 설계를 폐기. (3) 수용 — 테스트를 **락 실증(동시성 필요, 타임아웃 기법)**과 **최종 불변식(동시성 불필요, 순차 실행)** 둘로 분리. 락 실증은 PK 잠금(`findByIdForUpdate`)과 이메일 잠금(`findByEmailForUpdate`)이 서로 다른 인덱스로도 같은 행을 두고 충돌한다는 사실만 독립적으로 증명(기존 `ProfileImageMigrationRunnerIntegrationTest`의 id-vs-id 충돌 증명과 달리 PK-vs-email 교차 충돌은 이 코드베이스에서 처음 증명하는 사실이라 중복 아님). 최종 불변식은 B가 완전히 커밋한 뒤 A를 순차 호출해 "이전 이메일로 발급된 토큰이 이메일 변경 후 사라진다"만 확인 — 동시성 장치를 걷어내 flaky 위험을 원천 제거. (4) 수용 — 기존 `findByIdForUpdate_actuallyAcquiresRowLock`의 안전 패턴(예외 타입 명시 확인, `finally` 정리, 타임아웃 세션 변수 복원)을 그대로 재사용.
> v4 (2026-09-05): codex 적대적 리뷰 3라운드 반영("Future.get(timeout) 핵심 결함은 해소 확인됨"과 함께 잔여 3건 지적). (1) 수용 — **테스트 2("최종 불변식")가 사실은 수정 전 `findById` 코드에서도 통과한다**는 지적이 정확하다(B가 이미 커밋했으면 `findById`도 최신 토큰을 읽으므로). "경합을 증명한다"는 과장된 프레이밍을 걷어내고, 테스트 2는 "커밋된 기존 토큰이 이메일 변경 시 실제 DB 왕복(Mockito가 아닌 진짜 Hibernate dirty-check)으로 정확히 무효화되는가"라는 **순차 정합성 확인**으로 정직하게 재정의한다 — 회귀 판별은 이 테스트 단독이 아니라 (단위 테스트 호출 계약 + 테스트1 락 충돌 실증 + 테스트2 정합성)의 **조합**이 담당한다고 명시. (2) 수용 — 테스트 1의 정리(cleanup) 구조를 바깥쪽 `finally`에서 무조건 락 해제 + holder `Future`도 무조건 `get()`하도록 보강(기존 참고 테스트를 문자 그대로 복사하지 않고 이 부분만 강화). (3) 수용 — 테스트 1이 "두 잠금 쿼리의 물리적 충돌"만 증명하고, "서비스 트랜잭션이 끝까지 잠금을 유지한다"·"경합 후 최종 토큰이 사라진다"까지 보장하는 것은 Spring `@Transactional`·InnoDB 표준 동작(문서화된 사실)과의 조합 추론임을 명시.
> v4 후속 정정(4라운드 리뷰, ship 판정 + 비차단 지적 1건): 회귀 판별 근거를 "세 증거의 조합"이라 적어놓고 실제로는 ①~④ 네 가지를 나열한 숫자 불일치 발견 — "네 증거"로 정정(기술적 설계·완료 판단에는 영향 없는 표기 수정).

## Context

외부 기술 감사(`docs/CMS-technical-audit-2026-09-05.md` H-02)와 로드맵(`adversarial-review/project-direction-roadmap.md` "실행 로드맵 — Top 5 (2026-09-05 선정)" ②)에서 발굴된 항목. 코드 대조로 사실 확인 완료:

- `AdminMemberService`에서 대상 회원을 수정하는 모든 메서드 — `updateAdminMember`(181행), `updateMyProfileImage`(272행), `resetMyProfileImage`(294행), `changeMyPassword`(317행), `applyDefaultProfileImage`(340행) — 는 `memberRepository.findByIdForUpdate(id)`로 PESSIMISTIC_WRITE 행 잠금을 건다.
- **`updateMyInfo`(145행)만 유일하게** 잠금 없는 `memberRepository.findById(adminId)`를 쓴다.
- `PasswordResetService.issueToken`(142행)은 `memberRepository.findByEmailForUpdate(email)`로 이메일 기준 행 잠금을 건다.
- `Member.updateInfo`(102행)는 이메일이 실제로 바뀌면 `resetToken`/`resetTokenExpiryAt`을 `null`로 대입한다.
- `Member`에는 `@DynamicUpdate`가 붙어 있어(1~17행 주석) 변경된 컬럼만 UPDATE에 포함된다.

**경합 시나리오(수정 전)**: A(자기 정보 변경, 이메일 변경 포함)가 `findById`로 스냅샷을 읽는다(resetToken=null) → B(비밀번호 재설정 요청, 옛 이메일 대상)가 `findByEmailForUpdate`로 행 잠금을 잡고 토큰을 발급·커밋한다 → A가 이메일을 바꾸며 자기 스냅샷 기준으로 토큰에 다시 `null`을 대입한다(null→null) → `@DynamicUpdate`가 이 컬럼을 변경 없음으로 판단해 UPDATE에서 제외한다 → 커밋 후 DB에는 **새 이메일 + 옛 이메일로 발급된 유효한 재설정 토큰**이 함께 남는다. 옛 메일함 접근자가 이 경합 창에서 발급된 링크를 확보하면 이메일 변경 후에도 비밀번호를 재설정할 수 있다.

## 핵심 쟁점과 설계 결정

### 쟁점 1 — 수정 범위: `updateMyInfo`만 고칠 것인가, 더 넓게 볼 것인가

**선택지**
1. **`updateMyInfo`만 `findByIdForUpdate`로 전환한다.** (선택)
2. `updateMyInfo`뿐 아니라 `getMyInfo`(읽기 전용)도 잠금 하에 두거나, `Member.updateInfo` 자체를 재설계한다.

**결정: 1번.** 감사가 지목한 결함은 정확히 "쓰기 경로의 잠금 누락"이다. `getMyInfo`는 읽기 전용(`@Transactional(readOnly = true)`)이라 잠금이 필요 없고, 오히려 읽기 요청에 쓰기 잠금을 거는 것은 불필요한 직렬화로 가용성만 낮춘다. `Member.updateInfo`의 "이메일 바뀌면 토큰 null" 로직 자체는 정확하다 — 문제는 그 판단이 이루어지는 시점의 스냅샷이 오래됐다는 것뿐이므로, 호출부(서비스)의 조회 방식만 바꾸면 충분하다. 이는 이 클래스의 다른 5개 쓰기 메서드가 이미 쓰는 패턴과 동일해 **일관성 회복**이기도 하다.

### 쟁점 2 — 이 잠금 전환이 실제로 경합을 닫는가 (핵심 가정 검증 필요)

`findByIdForUpdate`는 PK(id) 인덱스로, `findByEmailForUpdate`는 UNIQUE 보조 인덱스(email)로 같은 행을 잠근다. InnoDB는 UNIQUE 보조 인덱스를 통한 `SELECT ... FOR UPDATE`가 매치되는 단일 행에 한해 **해당 클러스터드(PK) 레코드에도 락을 건다** — 즉 두 쿼리는 물리적으로 같은 락 리소스를 다툰다. 이 코드베이스는 이미 이 전제 위에 설계돼 있다(`findByIdForUpdate`/`findByEmailForUpdate`/`findActiveAdminIdsForUpdate` 전부 PESSIMISTIC_WRITE 사용, `ProfileImageMigrationRunnerIntegrationTest.findByIdForUpdate_actuallyAcquiresRowLock`가 동일 메커니즘을 이미 실증).

**선택지**
1. 위 전제를 신뢰하고 구현만 바꾼 뒤, 결과 상태(최종 불변식)만 통합 테스트로 검증한다.
2. **전제 자체(두 쿼리가 실제로 같은 행을 두고 상호 대기하는지)를 별도 통합 테스트로 직접 실증한다.** (선택)

**결정: 2번.** "감사 대응"이라는 성격상 고친 것이 실제로 문제를 닫는지 코드 리뷰만으로 끝내지 않고 실제 DB로 증명하는 편이 안전하다. (v3 확정: v2에서는 이 실증을 최종 불변식 테스트에 얹어 한 테스트로 합치려 했으나 2라운드 리뷰에서 "블로킹 위치를 구분 못 한다"는 결함이 드러났다 — **락 실증은 별도의 독립 테스트로 분리**한다. 상세는 쟁점 3 참조.)

### 쟁점 3 — 통합 테스트에서 경합을 어떻게 결정적으로(비-flaky) 재현할 것인가

**v1의 오류(1라운드 리뷰로 발견)**: `ExecutorService`로 두 서비스 메서드를 그냥 동시에 던지는 방식은 위험한 인터리빙을 강제하지 않아, `findById`로 회귀해도 운 좋게 통과할 수 있는 약한 테스트였다.

**v2의 오류(2라운드 리뷰로 발견)**: `Future.get(timeout)`으로 `updateMyInfo()` 전체 호출의 "블로킹 여부"를 회귀 판별 신호로 쓰려 했으나, **이 호출 전체가 블로킹되는 것은 수정 전/후 코드 모두에서 발생한다** — 수정 전(`findById`)에도 SELECT 자체는 즉시 끝나지만, 뒤이은 Hibernate flush/UPDATE 문이 B가 쥔 행 잠금 때문에 B의 커밋까지 대기한다. 즉 "A가 대기하는가"는 구현이 맞든 틀리든 항상 참이라 아무것도 구분하지 못하는 判별 신호였다(계획 본문이 이 사실을 스스로 서술해놓고도 그것이 자신의 핵심 판별 논리를 무너뜨린다는 걸 놓친 논리적 자기모순).

**v3 재설계 — 책임을 완전히 분리한 두 개의 독립 테스트**:

**테스트 1(락 상호 대기 실증, 동시성 필요)**: "PK 잠금(`findByIdForUpdate`)과 이메일 잠금(`findByEmailForUpdate`)이 같은 물리 행을 두고 실제로 충돌하는가"라는 쟁점 2의 전제만 좁게, 독립적으로 증명한다. `ProfileImageMigrationRunnerIntegrationTest.findByIdForUpdate_actuallyAcquiresRowLock`와 동일한 결정적 기법을 재사용한다 — 한 트랜잭션이 `findByEmailForUpdate(email)`로 락을 보유한 채 대기(`CountDownLatch`로 보유 시점 신호), 다른 트랜잭션이 `SET SESSION innodb_lock_wait_timeout = 1` 설정 후 `findByIdForUpdate(같은 id)`를 시도 → `PessimisticLockingFailureException`/MariaDB 에러코드 1205로 반드시 실패해야 한다(락 보유측은 `finally`에서 반드시 해제). 이 테스트는 서비스 계층을 거치지 않고 리포지토리 메서드 두 개만 다뤄 "SELECT 자체가 블로킹되는가"를 명확히 분리해 증명한다 — v2가 실패했던 "블로킹 위치 구분 불가" 문제를 근본적으로 피한다. (기존 테스트는 id-vs-id 충돌만 증명했고, PK 인덱스와 UNIQUE 보조 인덱스 간의 교차 충돌은 이 코드베이스에서 처음 증명하는 사실이라 중복이 아니다.)

**테스트 2(커밋된 토큰의 이메일 변경 시 무효화 — 순차 정합성 확인, "경합 증명" 아님)**:
1. 멤버 생성(email=old, resetToken=null).
2. `TransactionTemplate` 안에서 `memberRepository.findByEmailForUpdate(old)` + `member.issueResetToken(hash, expiry)`를 호출해 **완전히 커밋**한다(B의 동작 직접 재현 — 이유는 아래).
3. 실제 `adminMemberService.updateMyInfo(id, email=new 요청)`를 호출한다(수정된 실제 프로덕션 코드 경로).
4. **새 트랜잭션/새 영속성 컨텍스트**로 재조회해 `email == new && resetToken == null && resetTokenExpiryAt == null`을 확인한다.

**(v4 정정, 3라운드 리뷰 지적 1 수용)**: 이 테스트는 **경합을 증명하지 않는다** — B가 이미 완전히 커밋한 뒤에 A를 호출하므로, 수정 전 `findById` 코드도 이 시점엔 B가 커밋한 최신 토큰을 읽어 정상적으로 클리어한다(즉 이 테스트 단독으로는 `findByIdForUpdate`→`findById` 회귀를 잡지 못한다). 이 테스트의 진짜 가치는 "실제 DB 왕복에서 Hibernate `@DynamicUpdate`의 dirty-checking이 커밋된 실제 토큰 값을 정확히 null로 갱신하는가"를 **Mockito가 아닌 진짜 JPA/DB로** 검증하는 것 — 기존 `AdminMemberServiceTest.updateMyInfo_emailChange_clearsOutstandingResetToken`은 인메모리 목(mock) 엔티티로 같은 로직을 검증하지만 실제 Hibernate dirty-check·실제 UPDATE 문 생성까지는 증명하지 못한다. **회귀 판별(수정 전/후 구분)은 이 테스트 단독이 아니라 다음 네 증거의 조합이 담당한다**: ① 단위 테스트의 `verify(memberRepository).findByIdForUpdate(1L)` + `never().findById(1L)`(서비스가 실제로 어떤 메서드를 호출하는지 고정) ② 테스트 1(두 잠금 쿼리가 물리적으로 충돌한다는 사실 실증) ③ Spring `@Transactional`의 표준 동작(같은 트랜잭션 내 잠금 유지)과 InnoDB의 locking read(FOR UPDATE는 항상 최신 커밋값을 읽는다 — current read)라는 문서화된 사실 ④ 테스트 2(그렇게 읽은 최신값을 dirty-check가 정확히 처리한다는 정합성). 넷 중 하나라도 없으면 논거가 완결되지 않는다.

**선택지**
1. v2처럼 한 테스트에서 "락 실증"과 "최종 불변식"을 동시에 억지로 증명하려 한다. (v2, 기각)
2. **테스트 1(락 충돌 실증)과 테스트 2(정합성 확인)로 분리하고, 회귀 판별은 두 테스트 + 단위 테스트 + 문서화된 프레임워크 동작의 조합으로 설명한다.** (선택)

**결정: 2번.** 각 테스트가 정확히 하나의 좁은 사실만 책임져 결함이 있으면 정확히 어느 사실이 깨졌는지 알 수 있고, 어떤 테스트도 스스로 감당할 수 없는 "경합 재현" 같은 과장된 주장을 하지 않는다. 시간 기반 추정(짧은 wall-clock 타임아웃으로 "특정 지점 도달"을 추정하는 것) 없이 명시적 동기화(락 타임아웃 예외, 트랜잭션 완전 커밋)로만 결과가 결정된다는 점은 유지된다.

**B 역할 구현 방법(테스트 1·2 공통)**: `PasswordResetService.issueToken`은 `private`이라 직접 호출할 수 없고, `requestReset`을 쓰면 비동기 메일 발송·락 실패 삼키기(`PessimisticLockingFailureException` catch 후 조용히 반환)가 테스트 결정성을 해친다(1라운드 리뷰 지적 2·3, 유효). 대신 `TransactionTemplate` 안에서 `memberRepository.findByEmailForUpdate(oldEmail)` + `member.issueResetToken(hash, expiry)`를 **직접** 호출해 `issueToken`이 내부적으로 하는 것과 동일한 DB 동작(행 잠금 + 토큰 설정)만 재현한다 — `PasswordResetService`의 나머지 파이프라인(메일 발송 등)은 이 테스트의 관심사가 아니며 자체 테스트(`PasswordResetServiceTest`, `PasswordResetConcurrencyIntegrationTest`)가 이미 커버한다.

**반대 방향(A 선행)은 별도 테스트 불필요**: A가 먼저 커밋하면(email=new) B의 `findByEmailForUpdate(oldEmail)`는 매치되는 행이 없어(email UNIQUE 제약으로 이미 new로 바뀜) 자연히 no-op된다 — 위험한 조합 자체가 성립하지 않는 자명한 안전 경로라 별도 재현 테스트를 추가하지 않는다(과설계 방지).

**테스트 1의 안전성 체크리스트(2라운드 리뷰 지적 4, 3라운드 리뷰 지적 2 반영 — 기존 `findByIdForUpdate_actuallyAcquiresRowLock`을 문자 그대로 복사하지 않고 정리 구조를 보강)**:

**(v4 정정)** 3라운드 리뷰가 정확히 지적했듯, 참고 테스트의 `release.countDown()`은 contender 블록 내부 `finally`에만 있어 `lockHeld.await(...)` 자체의 단언(`assertTrue`)이 실패하면 그 지점에 도달하지 못해 락이 해제되지 않는다. 이를 막기 위해:
- **바깥쪽(메서드 최상위) `finally`에서 무조건 `release.countDown()`을 호출한다** — `lockHeld.await` 단언 실패, contender 쪽 단언 실패 등 테스트 흐름의 어느 지점에서 예외/실패가 나든 락 보유 스레드는 반드시 풀려난다.
- **holder(락 보유) 스레드의 `Future`도 테스트 흐름의 성공/실패와 무관하게 바깥쪽 `finally`에서 무조건 `get(타임아웃)`한다** — contender 쪽 단언이 먼저 실패해 중간에 예외가 던져지더라도, holder 스레드 내부에서 발생했을 수 있는 예외(예: `release.countDown()` 이후 트랜잭션 커밋 실패)가 조용히 유실되지 않고 반드시 드러난다.
- 타임아웃 예외가 `PessimisticLockingFailureException` 또는 메시지에 "1205"/"lock wait timeout"을 포함하는지 명시 확인해(`isLockTimeoutException` 헬퍼 재사용) 무관한 실패(세션 변수 오류 등)와 구분한다.
- `innodb_lock_wait_timeout` 세션 변수는 조회 전 원래 값을 캡처해 `finally`에서 복원한다.
- `lockHeld.await(...)`에도 제한 시간을 두고 성공 자체를 단언한다.
- `ExecutorService`는 바깥쪽 `finally`에서 `shutdownNow()` + `awaitTermination()`으로 종료를 확인한다.

**테스트 1이 증명하는 범위(3라운드 리뷰 지적 3 반영, 과장 방지)**: 테스트 1은 오직 "`findByEmailForUpdate`와 `findByIdForUpdate`가 같은 물리 행을 두고 실제로 락 충돌한다"는 좁은 사실 하나만 증명한다. "`updateMyInfo`의 서비스 트랜잭션이 메서드 종료까지 잠금을 유지하는가", "잠금 해제 후 A가 최신 토큰을 읽는가"는 이 테스트가 아니라 Spring `@Transactional`(트랜잭션 종료까지 잠금 유지)과 InnoDB locking read(FOR UPDATE는 항상 최신 커밋값을 읽는 current read)라는 **문서화된 표준 동작**에 근거한 추론이며, 그 정합성의 최종 확인은 테스트 2가 담당한다.

### 쟁점 4 — 기존 단위 테스트 수정 범위

`AdminMemberServiceTest`에서 `updateMyInfo`를 다루는 4개 테스트(`updateMyInfo_success`, `updateMyInfo_emailChange_clearsOutstandingResetToken`, `updateMyInfo_sameEmail_keepsResetToken`, `updateMyInfo_duplicateEmail`)가 모두 `given(memberRepository.findById(1L))...`로 스텁돼 있다. 구현을 바꾸면 이 스텁이 매치되지 않아 `NullPointerException`/`ResourceNotFoundException`으로 깨진다.

**결정**: 4곳 모두 `findById(1L)` → `findByIdForUpdate(1L)`로 스텁 대상만 변경한다(테스트 시나리오·검증 내용은 그대로 — 이 테스트들은 잠금 자체가 아니라 비즈니스 로직을 검증하는 것이므로). `getMyInfo_success`(285행)는 `findById` 그대로 유지(범위 밖). **(v2 추가, codex 지적 4 수용)**: `updateMyInfo_success`에 `verify(memberRepository).findByIdForUpdate(1L)`와 `verify(memberRepository, never()).findById(1L)`를 추가해, 이 단위 테스트 계층에서도 "잠금 조회를 쓴다"는 호출 계약 자체를 고정한다 — 통합 테스트 없이 단위 테스트만 도는 환경(예: IDE 개별 실행)에서도 `findById`로의 회귀를 즉시 잡기 위함이다.

## 구현해야 할 정확한 파일

- 수정: `src/main/java/com/cms/admin/member/service/AdminMemberService.java:145-158` (`updateMyInfo` — `findById` → `findByIdForUpdate`, 잠금 사유를 설명하는 Javadoc 추가)
- 수정: `src/test/java/com/cms/admin/member/service/AdminMemberServiceTest.java` (4개 테스트의 `findById(1L)` 스텁 → `findByIdForUpdate(1L)`, `updateMyInfo_success`에 호출 계약 `verify` 추가)
- 신규: `src/test/java/com/cms/admin/member/AdminMemberEmailResetTokenConcurrencyIntegrationTest.java` (`MariaDbContainerSupport` 상속, `@SpringBootTest`, 실제 `AdminMemberService`·`MemberRepository`·`TransactionTemplate` 빈을 `@Autowired`)
  - 테스트 1(락 상호 대기 실증): `findByEmailForUpdate` 보유 중 `findByIdForUpdate`(같은 id)가 `innodb_lock_wait_timeout` 단축 설정 하에 락 타임아웃으로 실패함을 확인
  - 테스트 2(최종 불변식, 순차 실행): B 역할(`findByEmailForUpdate`+`issueResetToken`)로 옛 이메일에 토큰 발급·완전 커밋 → 실제 `adminMemberService.updateMyInfo`로 이메일 변경 → 새 트랜잭션 재조회로 `email=new && resetToken=null && resetTokenExpiryAt=null` 확인

## 단계별 작업 순서

1. `AdminMemberService.updateMyInfo` 잠금 전환 + Javadoc
2. `./gradlew compileJava`
3. 기존 단위 테스트 4곳 스텁 수정 + 호출 계약 `verify` 추가
4. 신규 통합 테스트 2건 작성(테스트 1 — 락 상호 대기 실증, 테스트 2 — 최종 불변식)
5. `./gradlew test` 전체 통과 확인
6. (권장, 필수 아님) Playwright로 내 정보 수정 화면 골든 패스 회귀 확인 — 화면 자체는 변경하지 않으므로 CLAUDE.md 8번 규칙의 엄격 적용 대상은 아니지만, 서비스 동작이 바뀌므로 최소 스모크 확인은 안전망으로 수행

## 완료 기준

- [ ] 단위 테스트: `updateMyInfo`가 `findByIdForUpdate`를 호출하고 `findById`는 호출하지 않음(`verify` — 호출 계약 고정)
- [ ] 신규 통합 테스트 1: `findByEmailForUpdate` 보유 중 동시 `findByIdForUpdate`(같은 id)가 락 타임아웃 예외로 실패(PK 잠금과 이메일 잠금의 실제 상호 대기 실증) — 단언 실패 시에도 락 보유 스레드가 바깥쪽 `finally`로 반드시 해제됨
- [ ] 신규 통합 테스트 2: 옛 이메일로 토큰 발급·완전 커밋 후 `updateMyInfo`로 이메일 변경 시, 재조회한 최종 DB 상태가 email=new && resetToken=null && resetTokenExpiryAt=null (실제 DB 왕복에서의 정합성 확인 — 이 테스트 단독은 경합을 재현하지 않음, 위 네 항목과의 조합으로 H-02 수정 논거를 구성)
- [ ] 기존 `updateMyInfo` 단위 테스트 4건 통과(비즈니스 로직 회귀 없음)
- [ ] `./gradlew test` 전체 통과

## 리스크 / 범위 밖

- **성능 영향**: `updateMyInfo`는 이제 다른 쓰기 경로들과 동일하게 행 잠금을 잡는다 — 자기 정보 수정은 빈도가 낮은 관리 작업이라 처리량 영향은 무시 가능하다고 판단(다른 5개 메서드도 동일 패턴으로 이미 운영 중).
- **데드락 가능성**: `updateMyInfo`는 단일 잠금(자기 행)만 잡으므로 `updateAdminMember`(대상 행 + 활성 ADMIN 목록, 두 개의 서로 다른 잠금)와 달리 **명시적으로 다중 행 잠금 순서를 추가하지 않는다.** (v2 완화, codex 지적 6 수용 — `validateDuplicatedEmail`이 잠금 없는 일반 `findByEmail` 조회를 추가로 하므로 "데드락이 원천적으로 불가능"까지는 단정하지 않는다.)
- **스키마 변경 없음, 인가 정책 변경 없음** — 사용자 사전 협의 불필요(로드맵 착수 게이트와 일치).
- **범위 밖**: 관리자 수정(`updateAdminMember`)과 자기 수정(`updateMyInfo`) 간의 동일 대상 교차 경합(관리자가 남의 계정을 고치는 동시에 본인이 자기 정보를 고치는 경우, 대상이 다른 사람이면 애초에 별개 행이라 무관 — 대상이 "자기 자신"인 경우는 `updateAdminMember`가 "본인 계정은 내 정보 수정을 이용해주세요"로 거부하는 기존 정책상 발생 불가)는 새로 만드는 문제가 아니라 기존 정책으로 이미 닫혀 있어 별도 조치 불필요.
- **범위 밖(v1에서 이미 명시, 변동 없음)**: 이메일 변경이 A 먼저 커밋되는 방향은 UNIQUE 제약상 B가 자연히 no-op되는 자명한 안전 경로라 별도 테스트를 추가하지 않는다.

## 구현·검증 결과 (2026-09-05)

- **구현**: `AdminMemberService.updateMyInfo`(`src/main/java/com/cms/admin/member/service/AdminMemberService.java`) `findById` → `findByIdForUpdate` 전환 + 잠금 사유 Javadoc 추가. 계획대로 이 메서드 외 다른 코드 변경 없음.
- **단위 테스트**: `AdminMemberServiceTest`의 `updateMyInfo` 관련 4개 테스트(`updateMyInfo_success`·`updateMyInfo_emailChange_clearsOutstandingResetToken`·`updateMyInfo_sameEmail_keepsResetToken`·`updateMyInfo_duplicateEmail`) 스텁을 `findByIdForUpdate`로 전환. `updateMyInfo_success`에 `verify(memberRepository).findByIdForUpdate(1L)` + `verify(memberRepository, never()).findById(anyLong())` 추가(호출 계약 고정) — 통과 확인.
- **신규 통합 테스트**(`AdminMemberEmailResetTokenConcurrencyIntegrationTest`, Testcontainers MariaDB): 계획대로 2건 작성.
  - **테스트 1**(락 상호 대기 실증): `findByEmailForUpdate` 보유 중 `innodb_lock_wait_timeout=1` 설정 하 `findByIdForUpdate`가 실제로 **`Error: 1205-HY000: Lock wait timeout exceeded`**로 실패함을 실측 확인 — PK 잠금과 이메일 잠금이 물리적으로 같은 행을 두고 충돌한다는 쟁점 2의 핵심 전제가 실증됨.
  - **테스트 2**(정합성 확인): B 역할로 옛 이메일에 토큰 발급·커밋 후 실제 `updateMyInfo`로 이메일 변경 → 최종 `UPDATE` 문에 `reset_token`·`reset_token_expiry_at`이 실제로 포함되어 null로 갱신됨을 SQL 로그로 확인, 재조회 결과 `email=new && resetToken=null && resetTokenExpiryAt=null` 확인.
  - 둘 다 첫 실행에 통과(설계 단계에서 로직을 충분히 검증했기 때문으로 판단, 별도 수정 없음).
- **회귀 테스트**: `SPRING_PROFILES_ACTIVE=dev ./gradlew test` 전체 실행 — **658개 전체 통과, 실패/에러/스킵 0건**(직전 로드맵 기준 656개에서 신규 테스트 2건 순증, 기존 테스트 전부 무회귀).
- **실기 검증(Playwright)**: 계획상 "권장, 필수 아님"으로 격하했으나 수행함. dev DB(기존 실행 중이던 `cms-db-dev` 컨테이너, 3307)에 로컬 `bootRun`으로 연결해 확인(기존 컨테이너를 건드리지 않기 위해 `docker compose up` 대신 이 방식 선택 — dev-up.sh 시도 시 별도 compose 프로젝트("cms")로 이미 떠 있던 동명 컨테이너와 이름 충돌 발견, 제거하지 않고 우회).
  - admin/1234 로그인 → `/admin/member/info`에서 이름·이메일 변경 → 저장 성공("내 정보가 저장되었습니다.") → 원래 값으로 원복 → 재저장 성공 확인.
  - `/admin/member/manage`(회원 목록)·`/admin`(대시보드) 등 다른 관리 화면 회귀 없음 확인.
  - CSRF 문제 없음(Thymeleaf 폼 기반 제출, 자동 처리).
  - 무관한 발견(범위 밖, 추가 조사 안 함): 로그인 페이지 최초 로드 시 콘솔 에러 1건 — 로그인·기능 자체에는 영향 없음, 이번 변경과 무관.
- **이슈**: 없음(설계 단계에서 codex 리뷰 4라운드로 테스트 논리를 충분히 검증한 덕에 구현 단계에서 막힌 지점 없음).
- **후속**: 없음. 감사 H-02 항목 완료 — 로드맵/커밋PR 처리는 `/code-review-loop` → `/commitPR` 단계에서 진행.
