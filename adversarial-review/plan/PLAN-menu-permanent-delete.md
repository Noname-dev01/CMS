# PLAN — 메뉴 영구삭제(하드 삭제) + 비활성화 경로 분리

> 상태: ✅ v5 ship (2026-10-01, 구현 전 — 적대적 리뷰 5라운드 ship, 구현은 사용자 승인 대기)
>
> **개정 이력**
> - 5라운드: **ship, 신규 지적 0건**. 서비스 잠금·검사 순서, 감사 확장 적용 지점·직접 호출처, 화면 상태 관리, 기존 테스트 교체 범위가 코드와 부합함을 확인(정적 대조 기준 — 빌드·테스트·playwright 미실행)
> - v5 변경(4라운드 no-ship, 신규 P2 1건 수용): 메뉴명에 내부 CR/LF가 있는 기존·신규 메뉴는 단일행 입력창이 개행을 제거해 이름 확인을 통과할 수 없음 → 확인 모달이 이름 원문 대신 **가역 이스케이프한 확인 문자열**을 표시·비교(서버 로그 이스케이프와 같은 규칙), 화면 검증에 제어문자 이름 추가(설계 §6). 서버 계약·D1~D10 변경 없음. 4라운드는 그 밖에 구현 착수를 막는 누락·코드 모순 없음으로 판정
> - v4 변경(3라운드 no-ship, P2 1건·P3 1건 수용): ① 로그 이스케이프 범위가 `\p{Cntrl}`(Java 17에서 ASCII 제어문자만)로는 `U+0085`·`U+2028`·`U+2029`를 놓침 → `[\p{Cc}\p{Zl}\p{Zp}]` + 역슬래시 이스케이프로 확장, 테스트 입력에 세 문자 추가(설계 §5). ② 문서 정정 위치 누락 → `notice/CLAUDE.md:10-11`, `MenuRepository.java:41-43` Javadoc 추가(설계 §7). 운영 호출처·기존 테스트 교체 범위·감사 조회/응답 매핑·V12 검증 계획은 3라운드에서 추가 누락 없음 확인
> - v3 변경(2라운드 no-ship, 신규 P2 1건 수용): 서버 로그에 삭제된 이름·URL을 쓰는 완화책이 **로그 주입 경로**를 새로 만든다(메뉴 URL·이름은 길이만 검증, 개행 저장 가능) → 서버 로그용 라벨의 제어문자 이스케이프 + 감사 실패 주입 테스트 추가(설계 §5). D10(최선 노력)은 변경 없음. 2라운드는 §3-2·§5 직접 호출처·§6 스냅샷이 코드와 부합함을 확인
> - v2 변경(1라운드 no-ship 5건 + 보강 2건 전부 수용):
>   - P1-1 수용: 삭제 뒤 낡은 구조 초안이 `applyStructure`의 `items.size() > all.size()` 검사(`MenuService.java:197`)에 먼저 걸려 **400**을 받고, 화면은 400에서 초안을 유지한다(409에서만 폐기) → 집합 불일치를 먼저 판정해 409로 통일(사용자 결정 D9). "설계 §3-2" 신설
>   - P1-2 수용: 상세 조회 응답이 늦게 도착하면 모달이 열린 사이 `selectedMenuNo`가 바뀌어 **다른 메뉴가 삭제**될 수 있음 → 모달 개방 시 `{menuNo, menuName, useYn}` 스냅샷 고정(설계 §6)
>   - P2-3 수용: `AdminActionLogService.log(...)` 직접 호출처 `AdminAccountAutoLockListener` 누락 → PR A 범위·테스트 명시(설계 §5)
>   - P2-4 수용: 감사 저장은 업무 커밋 뒤 최선 노력이라 삭제 성공 + 감사 유실 가능 → 최선 노력으로 수용(사용자 결정 D10), 위험·완료 기준에 명시
>   - P3-5 수용: 감사 대상 메서드는 `deactivateMenu`→`deleteMenu` 교체이므로 **12개 유지**("13개" 철회). V9는 "비었을 때만"이 아니라 공지 URL이 없을 때 삽입하는 시드이며, 이미 적용된 마이그레이션이 재실행되지 않는다는 결론은 동일
>   - 보강 수용: V11→V12 업그레이드 경로 테스트 구체화, 하위 생성 경합 테스트는 **비활성 자식 생성**으로 구성
> - v1: 초안
> 출처: 사용자 요청 "메뉴관리의 비활성화 말고 영구삭제는 없는지 검토" → 검토 결과 영구삭제 경로 없음(`DELETE /admin/api/menus/{id}`도 `useYn=false` 처리)
> 유형: feat · **스키마 변경 있음**(감사 로그 컬럼 1개 추가) · 인가 정책 변경 없음 · 신규 의존성 없음

## Context

지금 메뉴는 DB에서 지울 방법이 없다. `DELETE /admin/api/menus/{id}`는 이름과 달리 `useYn=false`만 하고(`MenuService.deactivateMenu`), 화면 [삭제] 버튼도 "비활성화하시겠습니까?"를 띄운다. 그래서 잘못 만든 메뉴·오타 메뉴가 영원히 남고, 비활성 메뉴는 "비활성 포함" 트리(구조 편집의 기본 모드이자 `PUT /structure`의 요청 집합)에 계속 쌓인다.

이 계획은 **비활성 상태이고 자식이 없는 메뉴만** 영구삭제할 수 있게 하고, 그 과정에서 `DELETE`의 의미를 영구삭제로 바꾸며 비활성화는 기존 `PATCH /{id}`(`useYn=false`)로 일원화한다.

## 사용자 결정 (2026-10-01 확정)

| # | 항목 | 결정 |
|---|---|---|
| D1 | 허용 조건 | **비활성 + 자식 없음**만 영구삭제. 활성이거나 자식이 하나라도(활성·비활성 무관) 있으면 거부 |
| D2 | 시드 메뉴(V3·V9) | **제한 없음** — 일반 메뉴와 동일하게 삭제 가능. 시드는 테이블이 완전히 빌 때만 도므로 전부 지우면 복구는 수동 SQL(수용) |
| D3 | API | `DELETE /admin/api/menus/{id}` = **영구삭제**로 변경. 비활성화는 `PATCH /{id}` `{useYn:false}`만 사용 |
| D4 | 화면 | 기존 [삭제]를 **[비활성화]**로 이름 변경. **비활성 메뉴 선택 시에만** 빨간 [영구삭제] 노출 |
| D5 | 감사 기록 | **`MENU_DELETE` 신설 + 삭제된 메뉴명·URL을 감사 로그에 남기도록 확장**(컬럼 추가) |
| D6 | 비활성화 감사 | `PATCH`는 기존대로 **`MENU_UPDATE`**로 기록. `MENU_DEACTIVATE`는 더는 발생하지 않으며 상수·라벨은 과거 로그 호환으로 유지 |
| D7 | 확인 절차 | **메뉴명을 직접 입력해야** [영구삭제] 확정 버튼 활성화 |
| D8 | 거부 응답 | 활성 메뉴 삭제·자식 존재 **둘 다 409** (`ConflictException`) |
| D9 | 낡은 구조 초안 | `PUT /structure`에서 **요청 메뉴 집합이 현재 트리 도달 집합과 다르면 항상 409**. 기존 "요청 수 > 전체 행 수 → 400"은 집합 비교 뒤로 밀려 사실상 사라짐 |
| D10 | 감사 보존 수준 | **최선 노력으로 수용** — 감사 저장 실패 시 삭제는 성공하고 이름·URL 기록이 유실될 수 있음(기존 감사 격리 정책과 일치). 원자적 저장은 하지 않음 |

## 정찰에서 확정한 사실

| 사실 | 근거 |
|---|---|
| `DELETE /{id}` → `deactivateMenu` → `target.deactivate()`(`useYn=false`). 하드 삭제 코드 없음 | `MenuController.java:93-102`, `MenuService.java:156-169`, `Menu.java:109` |
| `deactivateMenu`·`DELETE` 호출처는 `manage.html:976-1013`의 [삭제] 핸들러 하나뿐(화면 → fetch). 다른 JS·외부 호출자 없음 | `manage.html`, Grep 결과 |
| **`PATCH /{id}`(`updateMenu`)가 이미 비활성화를 처리**하고 `deactivateMenu`와 같은 불변식을 갖는다: 대상 `findByIdForUpdate` 잠금 + 활성 자식 있으면 409 | `MenuService.java:104-154`(145-148행 주석이 "deactivateMenu()와 동일한 불변식") |
| `menu` 테이블은 PK 외 인덱스·FK 없음(`up_menu_no` FK 없음) → 삭제로 인한 참조 무결성 오류 없음. 메뉴를 가리키는 다른 테이블 없음(감사 로그 `target_id`는 FK 없는 숫자) | `V1__init_schema.sql:37-50` |
| 메뉴 PK는 `AUTO_INCREMENT`. MariaDB 10.2.4+는 InnoDB 자동증가값을 영속하므로 삭제된 `menu_no`가 재사용되지 않는다(과거 감사 로그 `targetId`가 다른 메뉴를 가리킬 위험 없음). CI·운영 이미지는 `mariadb:10.11` | `ci.yml`, `docs/deployment.md` |
| 사이드바·구조 반영은 DB의 현재 행만 읽는다 → 행이 사라져도 후속 오류 경로 없음. 구조 반영은 낡은 초안이면 409(요청 집합 ≠ 트리 도달 집합) | `MenuService.applyStructure` |
| 비활성화·재활성화 = 대상 행 단일 잠금, 생성·구조 반영·`accessRole` 수정 = 전체 행 `menuNo` 오름차순 잠금 | `menu/CLAUDE.md`, `MenuService.java:63`·`:112` |
| 감사: `@AdminActionLogged`의 `targetIdExpression`으로 **결과 객체 getter 하나**를 뽑고, 실패 로그는 `targetId`가 항상 null. 반환이 `void`면 targetId가 null이 되므로 서비스는 응답 객체를 반환하고 컨트롤러가 버려 204를 낸다(공지 삭제와 같은 패턴) | `AdminActionLogAspect.java:122-146`, `admin/notice/CLAUDE.md` |
| **감사 로그에는 메뉴명 같은 필드가 없다**(`target_type`·`target_id`·`error_message`만). 하드 삭제하면 사후에 무엇을 지웠는지 알 수 없다 → D5 | `AdminActionLog.java`, `V1__init_schema.sql:52-69` |
| 신규 감사 액션은 `AdminActionTypes` 상수 + `ALL` + 활동 로그 화면 `ACTION_TYPE_LABELS`(`log/manage.html:167-`)에 함께 등록해야 하며 동기화 테스트 2종이 누락을 잡는다 | `AdminActionTypes.java`, `AdminActionTypeSyncTest`·`AdminActionTypeLabelSyncTest` |
| 최신 마이그레이션은 `V11`. 다음은 `V12`. `ddl-auto: validate`라 엔티티 컬럼 추가는 마이그레이션이 필수 | `db/migration/`, 루트 `CLAUDE.md` |
| 구조 편집 중(초안 있음)에는 저장·삭제·추가·토글이 이미 잠긴다 | `manage.html:562` |
| 보안: 메뉴 API는 컨트롤러 `@PreAuthorize("hasRole('ADMIN')")` + `SecurityConfig` `/admin/**` ADMIN 이중 커버 → 같은 경로·같은 메서드의 의미만 바뀌므로 **인가 정책 변경 없음** | `MenuController.java`, `com.cms.config` `CLAUDE.md` |

## 설계

### 1. 서비스 `MenuService.deleteMenu(Long menuNo)` (기존 `deactivateMenu` 대체)

```
@Transactional
@AdminActionLogged(actionType = MENU_DELETE, targetType = "MENU",
                   targetIdExpression = "menuNo", targetLabelExpression = "auditLabel")
MenuDeleteResult deleteMenu(Long menuNo)
```

1. `findByIdForUpdate(menuNo)` — 없으면 404(`ResourceNotFoundException`). 이 호출이 이 트랜잭션의 **첫 애플리케이션 테이블 조회**(잠금 읽기 — 스냅샷이 일찍 고정되지 않게)
2. `useYn == true` → **409** `"활성 메뉴는 영구삭제할 수 없습니다. 먼저 비활성화해주세요."`
3. `existsByUpMenuNo(menuNo)`(신규, 활성·비활성 무관) → **409** `"하위 메뉴가 있어 영구삭제할 수 없습니다. 하위 메뉴를 먼저 삭제하거나 이동해주세요."`
4. 결과 스냅샷(`menuNo`·`menuName`·`menuUrl`)을 **삭제 전에** 만든 뒤 `menuRepository.delete(target)`
5. 스냅샷 반환(컨트롤러는 버리고 204)

검사 순서를 "활성 → 자식"으로 둔 이유: 활성 메뉴는 어차피 자식이 있어도 "먼저 비활성화"가 첫 안내가 돼야 한다(비활성화 자체가 활성 자식이 있으면 막힘).

**응답 객체**: 컨트롤러 응답에 노출하지 않는 서비스 내부 결과 `MenuDeleteResult`(`menuNo`, `menuName`, `menuUrl`, `@JsonIgnore getAuditLabel()`)를 둔다. `MenuResponse`에 감사 전용 getter를 섞지 않는다.

### 2. 잠금·동시성 (결정: 대상 행 단일 잠금)

전체 행 잠금(`findAllForUpdate`)은 쓰지 않는다. 대상 행 단일 잠금으로 충분하다 — 근거:

| 경합 | 결과 |
|---|---|
| 삭제 ↔ **하위 생성**(`createMenu`는 전체 행 잠금, 대상 포함) | 삭제가 먼저면 생성은 대기 → 대상이 사라져 "부모 메뉴를 찾을 수 없습니다" 404. 생성이 먼저면 삭제는 대기 후 `existsByUpMenuNo`가 새 자식을 보고 409 |
| 삭제 ↔ **구조 반영**(`applyStructure`, 전체 행 잠금) | 삭제가 먼저면 반영은 대기 후 최신 행 집합 ≠ 요청 집합 → **409(§3-2에서 400 선행 분기를 고쳐야 성립)**. 반영이 먼저면 삭제는 대기 후 자식 유무를 새로 읽음 |
| 삭제 ↔ **재활성화/수정**(`updateMenu`, 대상 행 잠금) | 같은 행에서 직렬화. 수정이 먼저면 삭제가 활성 여부를 새로 읽어 409/통과, 삭제가 먼저면 수정은 404 |
| 삭제 ↔ 삭제 | 같은 행 직렬화, 뒤 요청은 404 |
| 삭제 ↔ 자식의 **재활성화**(자식→부모 개별 잠금) | 자식이 존재하는 동안 부모는 삭제 거부(자식 존재). 자식이 먼저 삭제된 뒤의 재활성화는 404 |

교착은 기존 규약대로 InnoDB 탐지 → `GlobalApiExceptionHandler`가 `PessimisticLockingFailureException`을 409로 변환(가용성 문제로 허용). `existsByUpMenuNo`는 비잠금 읽기이므로 **잠금 읽기 뒤에 호출**해야 REPEATABLE READ 스냅샷이 잠금 이후에 고정된다(기존 비활성화와 같은 순서 요건).

### 3. 비활성화 경로 정리

- `MenuService.deactivateMenu`·`Menu.deactivate()`·`MenuController.deactivateMenu` 삭제. 비활성화는 `updateMenu`(이미 동일 불변식·잠금)가 담당한다
- 화면 [비활성화]는 `PATCH /{id}`에 **`{useYn:false}`만** 보낸다(기존 저장은 `buildPayload()`가 폼 전체를 보내 stale-form lost-update 위험이 있었다 — 이 경로는 필드를 하나만 보내 그 위험을 키우지 않는다)
- 감사는 `MENU_UPDATE`(D6). `MENU_DEACTIVATE` 상수·`ALL`·라벨은 과거 로그 호환으로 유지하고 주석으로 "더는 발생하지 않음"을 남긴다

### 3-2. `applyStructure` 검사 순서 수정 (D9, 리뷰 P1-1)

현재 순서: 중복 `menuNo` 400 → 전체 잠금 읽기 → **`items.size() > all.size()` 400**(`MenuService.java:197`) → 도달 집합 비교 409. 고아 행 없는 트리에서 다른 관리자가 메뉴 하나를 삭제하면 삭제 전 초안은 항상 이 400에 걸리고, 화면은 400에서 초안을 유지하므로(`manage.html:763`) 삭제된 메뉴가 담긴 초안으로 반영을 계속 시도하게 된다.

- 개수 검사를 **제거**하고 도달 집합 비교(409)를 먼저 둔다. 요청 집합이 도달 집합과 같다면 `size ≤ all.size`는 도달 집합 ⊆ 전체 행에서 저절로 성립하므로 개수 검사는 중복이다
- 영향: 기존 테스트 `MenuServiceStructureTest.moreThanAll_rejected`(`:268`)는 400을 고정하므로 **409 기대로 수정**. `menu/CLAUDE.md`의 "요청 수 > 전체 행 수만 400"과 Swagger `applyStructure` 설명(`MenuController.java:85-86`)도 정정
- 화면은 변경 없음 — 409면 서버 메시지 + 초안 폐기 + 트리 재조회(`menu/CLAUDE.md` 기술된 기존 흐름)
- 검증: 삭제 전 초안 → 다른 관리자의 삭제 → 반영 409 → 화면 재조회까지 통합 테스트·playwright로 확인

### 4. 컨트롤러

```
@Operation(summary = "메뉴 영구삭제", description = "하드 삭제. 비활성 상태이고 하위 메뉴가 없는 메뉴만 가능...")
@ApiResponse 204 / 404 / 409(활성 메뉴 또는 하위 메뉴 존재)
@DeleteMapping("/{id}")  @PreAuthorize("hasRole('ADMIN')")
```

경로·메서드·인가는 그대로, 의미만 변경. URI는 기존 `/{id}`를 유지한다(`api-conventions`: 자원은 명사, 동사 없음).

### 5. 감사 로그 확장 (D5)

- **스키마 `V12__add_admin_action_log_target_label.sql`**: `ALTER TABLE admin_action_log ADD COLUMN target_label VARCHAR(500) NULL;` — 추가·nullable이라 기존 행·롤링 호환 안전
- `AdminActionLog.targetLabel`(`@Column(length = 500)`) 추가, `AdminActionLogService.log(...)`에 인자 추가, `AdminActionLogResponse.targetLabel` 추가(Jackson `non_null`이라 null이면 키 생략), 조회 매핑(`AdminActionLogQueryService`) 반영
- `@AdminActionLogged.targetLabelExpression()`(기본 `""`) 추가. `AdminActionLogAspect`는 `extractTargetId`와 같은 getter 방식으로 문자열을 뽑아 **성공 로그에만** 기록(실패 로그는 대상이 확정되지 않아 `targetId`와 같이 null). 길이 500 초과 시 자름
- 라벨 형식: `"{menuName} ({menuUrl})"`, URL이 없으면 `menuName`만
- 활동 로그 화면(`log/manage.html`): 상세 모달에 "대상 이름" 행 추가, 목록 "대상" 열은 `MENU #12 · 이름`처럼 표시. **모든 값은 기존 `escapeHtml`로 이스케이프**(메뉴명은 관리자 입력이라 XSS 경로)
- 다른 액션은 `targetLabelExpression`을 지정하지 않으므로 동작 불변
- **`AdminActionLogService.log(...)` 직접 호출처**(리뷰 P2-3): `AdminAccountAutoLockListener`(`:36`)가 Aspect 밖에서 이 메서드를 직접 호출한다. 시그니처에 `targetLabel`을 추가하므로 **PR A 범위에 이 리스너를 포함**하고 `targetLabel=null`을 전달한다. 연속된 `String` 인자(IP·URI·메서드·라벨·오류 메시지)가 많아 위치가 뒤바뀌기 쉬우므로 각 인자의 DB 컬럼 매핑을 테스트로 고정한다
- 영향 테스트: `AdminAccountAutoLockListenerTest`(인자 수·필드 검증·실패 주입, `:80`), `AdminActionLogServiceTest`(리플렉션으로 기존 시그니처 조회, `:36`), `AdminActionLogAspectTest`
- **보존 수준(D10, 리뷰 P2-4)**: 감사는 업무 커밋 뒤 `REQUIRES_NEW`로 최선 노력 저장되고 실패는 격리된다. 따라서 `메뉴 삭제 커밋 → 감사 INSERT 실패 → DELETE 204`가 가능하며 이때 삭제된 이름·URL은 DB에 남지 않는다. 완화로 `AdminActionLogAspect.logSuccess`의 실패 `log.error`에 `actionType`·`targetId`와 함께 **라벨(이름·URL)을 포함**해 서버 로그에서 복구할 수 있게 한다(감사 저장 실패는 이미 error 로그로 남는 경로)
- **로그 주입 방어(리뷰 2라운드 P2)**: 메뉴명·URL은 길이만 검증되고 개행 등 제어문자가 그대로 저장된다(`MenuCreateRequest.java:26`, `MenuService.java:87`). 이 값을 서버 로그에 그대로 쓰면 `/x\r\nFAKE AUDIT` 같은 값이 가짜 로그 줄을 만들 수 있고 화면의 `escapeHtml`은 서버 로그를 보호하지 않는다. 따라서 **서버 로그에 쓰는 라벨은 `[\p{Cc}\p{Zl}\p{Zp}]`(CR·LF·탭 등 제어문자 + 유니코드 줄·문단 구분자 `U+2028`·`U+2029`, `U+0085` 포함 — Java 17 `\p{Cntrl}`은 ASCII 제어문자만 잡아 이 셋을 놓친다)를 복원 가능한 형태(`\r`·`\n`·`\uXXXX` 이스케이프)로 치환**해서 기록하고, 복원 가능성을 위해 **원래의 역슬래시(`\`)도 `\\`로 이스케이프**한다. DB에 저장하는 `target_label`은 원문을 유지한다(화면에서 이스케이프, 감사 정확성)
- 테스트: 감사 저장 실패를 주입한 상태에서 `AdminActionLogAspectTest`가 (a) 예외 전파 안 함(기존)과 함께 (b) 로그 출력에 `actionType`·`targetId`·라벨이 포함되고 (c) 제어문자가 이스케이프돼 **개행이 새 줄로 나오지 않음**을 검증한다(로그 어펜더 캡처). 입력은 `\r\n`뿐 아니라 **`U+0085`·`U+2028`·`U+2029`**와 역슬래시 포함 값을 모두 포함한다

### 6. 화면 `templates/admin/menu/manage.html`

- `btnDelete`(id 유지 가능) 텍스트 "삭제" → **"비활성화"**, 핸들러는 `PATCH /admin/api/menus/{id}` + `{useYn:false}` + `Content-Type: application/json` + CSRF 헤더. 오류 문구는 서버 메시지 사용
- 신규 `btnPermanentDelete`(btn-danger): **선택된 메뉴가 `edit` 모드이고 `useYn=false`일 때만 표시**, 활성 메뉴에서는 [비활성화]만 표시(비활성 메뉴에서 [비활성화]는 숨김)
- **삭제 대상 스냅샷 고정(리뷰 P1-2)**: 현재 상세 조회는 시작 시 `mode`·`selectedMenuNo`를 비우지 않고, 응답 도착 시 `selectSeq`만 확인하고 선택을 바꾼다(`manage.html:669`). `busy` 검사는 조회 시작에만 있어 이미 진행 중인 응답은 못 막는다. 기존 삭제 핸들러도 대상을 전역 `selectedMenuNo`에서 읽는다(`:995`). 이 패턴을 재사용하면 *A 선택 → B 상세 조회 시작 → A의 모달 열기 → B 응답 도착 → A 이름 입력 후 변경된 `selectedMenuNo`(B)가 삭제*가 가능하다. 따라서:
  - 상세 조회가 **서버 응답으로 확정된 시점**의 `{menuNo, menuName, useYn}`을 선택 스냅샷으로 보관한다
  - 모달을 **열 때** 그 스냅샷을 모달 전용 변수로 **복사해 고정**하고, 이름 비교·`DELETE` URL·결과 메시지 모두 이 복사본만 쓴다(전역 `selectedMenuNo`·폼 값은 읽지 않는다)
  - [영구삭제] 버튼 노출은 폼의 **저장하지 않은 "사용 여부" 체크박스가 아니라** 스냅샷의 서버 `useYn`으로 판정한다
  - 모달이 열려 있는 동안 선택 변경·트리 재조회는 모달을 닫도록 하거나 모달을 modal-static으로 열어 배경 조작을 막는다. 확정 클릭은 `busy`로 중복 전송을 막는다
- 클릭 → **확인 모달**: 메뉴명 입력창 + 안내("영구 삭제되며 복구할 수 없습니다"). 입력값이 고정된 스냅샷의 **확인 문자열**과 **정확히 일치할 때만** 확정 버튼 활성화(양끝 공백 trim 비교). 서버는 이 입력을 검증하지 않는다(UX 안전장치 — 서버 계약은 D1·D8)
- **제어문자가 든 메뉴명의 확인 규칙(리뷰 4라운드 P2)**: 메뉴명은 `@NotBlank`+길이만 검증되고 서비스는 양끝 공백만 제거하므로 내부 CR/LF가 보존된 이름을 만들 수 있다(`MenuCreateRequest.java:21`, `MenuService.java:488`). 단일행 입력창은 CR/LF를 제거해 이런 메뉴는 이름을 그대로 입력할 수 없고, 신규 입력만 막아서는 이미 저장된 행이 해결되지 않는다. 따라서 모달은 원문 대신 **가역 이스케이프한 확인 문자열**을 표시·비교한다: 역슬래시 → `\\`, CR → `\r`, LF → `\n`, 그 밖의 `[\p{Cc}\p{Zl}\p{Zp}]` → `\uXXXX`(서버 로그용 이스케이프와 같은 규칙, §5). 제어문자가 없는 평범한 이름은 확인 문자열 = 원문이라 동작이 달라지지 않는다. 사용자는 모달에 표시된 확인 문자열을 그대로 입력(복사 가능)한다. 가역이므로 문자 그대로의 `\n`(역슬래시+n)과 실제 개행이 서로 다른 확인 문자열(`\\n` vs `\n`)을 갖는다
- 화면 검증 추가: 메뉴명이 CR·LF·CRLF·문자 그대로의 `\n`·`U+2028`·역슬래시를 포함하는 비활성 잎 메뉴를 만들어(API 직접 생성) 영구삭제 확인이 표시된 확인 문자열 입력으로만 활성화되는지 확인한다
- 확정 → `DELETE /admin/api/menus/{스냅샷 menuNo}`. 성공: 선택 초기화 + 트리 재조회 + 성공 메시지, 404면 "이미 삭제되었습니다" 후 트리 재조회, 409면 서버 메시지 표시(트리는 유지)
- 초안(드래그 구조 변경)이 있는 동안 두 버튼은 기존 잠금 규칙(`busy`·`updateDraftLock`)을 그대로 따른다
- 구현 후 **playwright로 실제 화면 확인**(루트 CLAUDE.md 작업 방식 8)

### 7. 문서

- `menu/CLAUDE.md`: 영구삭제 계약(조건·409·잠금·감사)과 "비활성화는 PATCH만" 추가, 모순되는 기존 서술 정정. **구조 반영 서술의 "요청 수 > 전체 행 수만 400"을 "집합 불일치는 409"로 정정**(D9)
- `log/CLAUDE.md`: `target_label` 계약(성공 로그만·500자 절단·이스케이프 의무·**최선 노력 보존, 감사 저장 실패 시 라벨 유실 가능** — D10), 감사 대상 메서드명 `deactivateMenu`→`deleteMenu`(개수 12 유지)
- **정정이 필요한 기존 서술 위치(리뷰 3라운드 P3)**: `admin/notice/CLAUDE.md:10`("`useYn`과 `deleted`는 별도 — 메뉴처럼 하나로 겸치지 않는다": 메뉴는 이제 `useYn`(비활성)과 하드 삭제를 모두 가지므로 비교 문구를 사실에 맞게 수정)·`:11`(`MenuService.deactivateMenu()` 참조 → `deleteMenu()`/공지 DELETE와 같은 이유로 응답 객체 반환), `MenuRepository.java:41-43` Javadoc("비활성화(삭제 및 PATCH useYn=false)" → 삭제와 비활성화를 구분해 서술)
- Swagger 설명 갱신, `adversarial-review/menu-management-plan.md:7`의 "삭제는 하드 삭제가 아니라 비활성화" 결정에 후속 변경 각주
- 비자명한 이슈가 나오면 `docs/troubleshooting.md` 기록

## 영향 범위

| 영역 | 영향 |
|---|---|
| 호출 화면/JS | `manage.html`만. `DELETE` 호출 의미 변경 + `PATCH` 비활성화 호출 신규 |
| 외부 API 호출자 | 없음(관리자 화면 전용). **배포 전 단계**라 구버전 화면 캐시 영향은 낮으나, 구 화면이 이미 비활성인 메뉴에서 [삭제]를 누르면 안내 문구("비활성화")와 달리 영구삭제된다 — 활성 메뉴는 409로 안전하게 막힘 |
| DB | `admin_action_log.target_label` 컬럼 1개 추가(V12). `menu` 스키마 변경 없음 |
| 엔티티 | `AdminActionLog` 필드 추가, `Menu.deactivate()` 삭제 |
| 기존 API 계약 변경 | `PUT /admin/api/menus/structure`: 요청 집합 불일치(개수 초과 포함)가 **400 → 409**로 변경(D9). 호출처는 `manage.html`뿐이며 이미 409를 "낡은 초안"으로 처리 |
| 보안 | 인가 정책 변경 없음. 파괴적 동작이 늘었지만 ADMIN 전용·CSRF 필수·감사 기록(이름 포함) |
| 시드 | 시드 메뉴 삭제 허용(D2). 전부 지워지면 Flyway 시드는 재실행되지 않음 — 수동 SQL 복구 |

## 테스트 계획

**서비스 단위(`MenuServiceTest`)** — `deactivateMenu_*` 3건을 교체
- 비활성 + 자식 없음 → `delete` 호출·스냅샷 반환
- 활성 메뉴 → 409, `delete` 미호출
- 비활성 자식(활성 자식 아님)만 있어도 → 409 (`existsByUpMenuNo`)
- 대상 없음 → 404
- `findByIdForUpdate`로 잠금(`deleteMenu_locksTargetRow`)

**컨트롤러 슬라이스(`MenuControllerTest`)**: `DELETE` 204 / 404 / 409(2종 메시지) / 미인증 / USER 권한 403, CSRF 누락 거부

**동시성 통합(`MenuConcurrencyIntegrationTest`, 실 MariaDB)**
- 기존 `menuService.deactivateMenu(...)` 3곳(`:151`·`:224`·`:335`)을 `updateMenu(useYn=false)`로 교체해 기존 의도 유지
- 신규: 삭제 ↔ 하위 생성(양방향 순서) — **하위 생성은 비활성 자식으로 구성**해야 삭제의 `existsByUpMenuNo`(활성·비활성 무관)를 실제로 검증한다(활성 자식만 쓰면 `...AndUseYnTrue`와 구분되지 않음), 삭제 ↔ 구조 반영(**삭제 전 초안 → 삭제 커밋 → 반영 409**, §3-2), 삭제 ↔ 재활성화, 삭제 ↔ 삭제 — 실제 락 대기 관측 방식은 기존 테스트와 동일
- 감사 커밋 순서: `deleteMenu`가 최상위 트랜잭션 진입점임을 확인하고 정상 삭제 시 `MENU_DELETE` SUCCESS 1건 + `targetId`·`targetLabel` 기록 검증. 정상 경로 SUCCESS만으로는 커밋 순서를 증명할 수 없으므로 **기존 `applyStructure_commitTimeFailure_recordsFailNotSuccess` 등 커밋 실패 테스트는 그대로 유지**한다. `log/CLAUDE.md`의 "감사 대상 12개 메서드"는 `deactivateMenu`→`deleteMenu` 교체이므로 **개수 변경 없음**(문구에서 메서드명만 정정)
- **구조 반영 수정 테스트(§3-2)**: `MenuServiceStructureTest.moreThanAll_rejected`(`:268`)를 409 기대로 수정하고, 요청 집합 불일치가 개수와 무관하게 409임을 추가 검증

**감사 로그**: `AdminActionLogAspectTest`(라벨 추출·500자 절단·실패 시 null), `AdminActionLogServiceTest`, 조회 매핑·응답 직렬화(null이면 키 생략), `AdminActionTypeSyncTest`·`AdminActionTypeLabelSyncTest`(MENU_DELETE 등록)

**마이그레이션(업그레이드 경로)**: 공용 Testcontainers(`MariaDbContainerSupport`)는 새 DB를 띄우고 `prod-smoke`도 기존 볼륨을 거부하므로 기존 검증만으로 V11→V12 업그레이드를 증명할 수 없다. 별도 시험으로 **V11까지만 마이그레이션 → 기존 형식의 `admin_action_log` 행 삽입 → V12 적용 → 기존 행 보존(`target_label` NULL) 확인 → 신규 라벨 행 왕복 → Hibernate `validate` 통과**를 확인한다(Flyway `target("11")`로 단계 적용). 빈 DB 전체 적용 경로는 기존 테스트가 담당

**화면(playwright)**: 활성 메뉴 → [비활성화]만 보임·PATCH 발생 / 비활성 메뉴 → [영구삭제]만 보임·메뉴명 불일치 시 확정 비활성·일치 시 DELETE 발생·트리에서 사라짐 / 자식 있는 비활성 부모 → 409 메시지 / 활동 로그 화면에 `메뉴 영구삭제` + 메뉴명 표시 / **지연 상세 응답 시나리오**(A 선택 → B 상세 조회를 지연 → A 모달 열기 → B 응답 도착 → A 이름 확정 시 `DELETE`가 A의 번호로만 나감, 모달 재개방·확정 중복 클릭 방어) / **낡은 초안**(드래그 초안 → 다른 세션이 메뉴 삭제 → [반영] 409 → "최신 구조로 새로고침" 후 초안 폐기)

## 단계 (PR 분할 제안)

1. **PR A — 감사 로그 `target_label` 확장**: V12·엔티티·서비스(`log` 시그니처)·Aspect·`AdminAccountAutoLockListener`·응답·활동 로그 화면·테스트·V11→V12 업그레이드 시험. 단독으로 무해(아무도 라벨을 지정하지 않음)
2. **PR B — 영구삭제 + 비활성화 분리 + 낡은 초안 409**: `deleteMenu`·컨트롤러·`MENU_DELETE`·`applyStructure` 검사 순서 수정(§3-2, 기존 테스트·Swagger·문서 정정)·화면 [비활성화]/[영구삭제] 모달(스냅샷 고정)·테스트·문서

(분할이 과하다고 판단하면 한 PR로 합칠 수 있다 — 승인 단계에서 확정)

## 위험과 한계

- **복구 불가**: 영구삭제는 되돌릴 수 없다. 완화: 비활성+자식 없음 조건, 메뉴명 입력 확인, 감사 로그에 이름·URL 보존(D5). 백업 복구 외 수단 없음
- **시드 전체 삭제**: D2로 수용. 시드 재생성 기능 없음. V3는 테이블이 비었을 때만, V9는 공지 URL 메뉴가 없을 때만 삽입하는 시드이며 **이미 적용된 마이그레이션은 Flyway가 재실행하지 않으므로** 삭제한 시드 메뉴는 수동 SQL 없이 돌아오지 않는다
- **감사 보존은 최선 노력(D10)**: 메뉴 삭제 커밋 후 감사 INSERT가 실패하면 삭제는 성공(204)하고 이름·URL 기록은 DB에 남지 않는다. 서버 `log.error`에 라벨을 함께 남겨 복구 단서를 확보한다. 원자적 보존은 범위 밖
- **낡은 선택 상태**: 다른 관리자가 같은 메뉴를 먼저 삭제/재활성화하면 404·409로 응답 — 화면은 메시지 표시 후 트리 재조회
- **`ord` 빈 자리**: 삭제로 형제 `ord`에 빈 자리가 생기나 표시 순서(`ord asc, menuNo asc`)는 유지되고 다음 구조 반영이 정리한다(무해, 부모 이동 때와 같음)
- **서버는 메뉴명 입력을 검증하지 않음**: API를 직접 호출하면 확인 절차 없이 삭제 가능(ADMIN 전용·CSRF·감사 기록으로 수용)
- **기존 `menu_no` 감사 이력**: 삭제된 메뉴를 가리키는 과거 `target_id`는 그대로 남는다(FK 없음, 번호 재사용 없음). 과거 로그에는 `target_label`이 없다

## 완료 기준

- 비활성+자식 없는 메뉴만 `DELETE`로 행이 사라지고, 활성·자식 있음·없음은 각각 409·409·404
- 비활성화는 `PATCH {useYn:false}`로 동작하고 화면 [비활성화]가 이를 사용
- 정상 처리 시 감사 로그에 `MENU_DELETE`·`targetId`·`targetLabel`(메뉴명·URL)이 남고 활동 로그 화면에 이스케이프되어 표시(감사 저장 실패 시 라벨 유실 가능 — D10 수용)
- 삭제 뒤 낡은 구조 초안은 `PUT /structure`에서 409를 받고 화면이 초안을 폐기·재조회(D9)
- 지연 상세 응답이 있어도 확인 모달이 연 시점의 메뉴만 삭제(스냅샷 고정)
- `AdminAccountAutoLockListener` 등 기존 감사 직접 호출이 시그니처 변경 뒤에도 동작하고 인자 매핑 테스트 통과
- V11→V12 업그레이드 경로 시험 통과
- `./gradlew test` 통과(동시성 통합 포함), CI `prod-smoke`의 Flyway V12 적용 통과, playwright 실기 확인
