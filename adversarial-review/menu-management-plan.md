# 메뉴 관리 기능 개발 계획

## Summary

- `feat/menu-management` 브랜치에서 미완성 상태인 메뉴 도메인(엔티티 + `manage.html`만 존재)을 완성한다. 정리 커밋(`README.md`, `.editorconfig`)은 이미 브랜치에 반영되어 있으므로 이번 작업은 **메뉴 기능 커밋**만 추가한다.
- 구현 순서는 백엔드 계약부터 고정한다: 공통 예외 추가 → `Menu` 도메인 정리 → Repository → DTO → Service → Controller(+Swagger) → 테스트 → `manage.html` 연동 → 사이드바 노출.
- 삭제는 하드 삭제가 아니라 **비활성화(useYn=false)** 로 처리하고, 트리 조회는 `useYn` 필터를 지원한다. 1차 버전의 순서 변경은 드래그가 아닌 **숫자 입력**으로 처리한다.

### 코드베이스 검증으로 확인한 사실

- **시각 처리**: `Member`가 JPA Auditing 없이 빌더+도메인 메서드(`updateInfo`/`changePassword`)로 `createDate`/`updateDate`를 수동 갱신 → Menu도 동일 방식.
- **409 확장 여지**: `GlobalApiExceptionHandler`(`com.cms.common.api`)는 현재 `DuplicateResourceException`만 409 처리 → `ConflictException` 추가 핸들러 필요 확인.
- **감사 로그**: `AdminActionLogAspect.extractTargetId()`가 반환 객체의 getter에서 targetId 추출 → 비활성화 메서드도 `MenuResponse` 반환 필수.
- **동기화 테스트**: `AdminActionTypeSyncTest`가 클래스패스 스캔으로 모든 `@AdminActionLogged.actionType`이 `AdminActionTypes.ALL`에 있는지 검증 → 상수 3종 등록 필수.
- **사이드바**: `admin/fragments/sidebar.html` 29~36줄에 주석 처리된 "메뉴 관리" 링크 존재, 인접 시스템 섹션이 `sec:authorize="hasRole('ROLE_ADMIN')"` 사용.
- **테스트 유형**: `MenuServiceTest`=순수 Mockito(DB 불필요), `MenuControllerTest`=`@WebMvcTest`(서비스 mock, DB 불필요). 리포지토리 슬라이스 테스트가 필요하면 `@DataJpaTest`+`@AutoConfigureTestDatabase(replace=NONE)`+`@ActiveProfiles("dev")`로 실제 MariaDB 사용(H2·testcontainers 없음).

### 확정된 결정 사항 (Codex 적대적 리뷰 3회 + 코드베이스 검토 반영)

- **필드 정합성**: 엔티티의 `menuCategory` 제거, 화면 폼과 일치하도록 `menuIcon`(아이콘 클래스명) 추가.
- **계층 필드 제거**: `menuLevel`·`topMenuNo` 필드를 엔티티에서 제거하고, 계층/최상위는 `upMenuNo`만으로 트리 조립 시 계산한다.
- **urlTarget 완전 제거**: 엔티티·DTO·화면 모두에서 삭제. 항상 null인 죽은 컬럼을 남기지 않는다. 향후 필요 시 재추가.
- **타입 명확화**: `useYn`→`Boolean`, `ord`→`Integer`, `upMenuNo`→`Long`.
- **부모 변경 불가**: 수정 요청(`MenuUpdateRequest`)에서 `upMenuNo`를 제외한다. 부모는 생성 시에만 결정되고 이동은 삭제 후 재생성으로 처리한다. → 다단계 순환 참조(A→B→C→A)가 **구조적으로 불가능**해진다.
- **비활성화 정책**: 대상 메뉴에 **활성(useYn=true) 하위 메뉴가 있으면 `409 RESOURCE_CONFLICT`로 거부**한다.
- **활성 부모 규칙**: **활성(useYn=true) 메뉴는 활성 부모 아래에만 생성·재활성화**할 수 있다. 부모가 비활성이면 거부한다. (생성뿐 아니라 PATCH로 useYn을 false→true로 되돌리는 경로에도 동일하게 적용)
- **동시성 방어(비관적 락) 1차 도입**: 생성·재활성화·비활성화 시 부모 row를 `PESSIMISTIC_WRITE`로 잠근 뒤 검증·반영해 경쟁 조건을 차단한다. (최상위 메뉴는 부모 없어 잠금 대상 아님)
- **동시성 방어 테스트 = Mockito 호출 검증 + 실제 DB 통합 테스트 1건**: `MenuServiceTest`(순수 Mockito)에서 생성/재활성화/비활성화 경로가 `findByIdForUpdate`를 호출하는지 `verify`로 확인한다. 다만 이것만으로는 `@Lock`이 실제 트랜잭션에서 의도한 row를 잠그고 경합을 직렬화하는지 증명하지 못한다(Codex 적대적 리뷰 지적). 이를 보완하기 위해 **`@SpringBootTest`+MariaDB 기반 실제 2스레드 경합 통합 테스트 1건을 1차에 포함**한다. 플래키 위험은 인터리빙과 무관한 **불변식 단정**(예: "비활성 부모 아래 활성 자식은 존재하지 않는다")으로 완화한다. 상세는 Test Plan의 "동시성 통합 테스트" 절 참고.
- **useYn null 처리 정책 확정**: 생성 시 `useYn` 누락은 `true`로 기본화하고, PATCH 시 `useYn`이 누락/null이면 기존값을 유지한다(부분 수정 시맨틱). 활성 부모 검증·재활성화 판단은 최종적으로 확정된 `useYn=true`에만 적용된다.
- **트리 손상 데이터 방어 = 전체 구현**: 순환·고아·미방문 노드 감지 → 트리 제외 + WARN 로그(menuNo 체인) + 전용 테스트까지 구현.
- **부모 이동 미지원 한계 수용**: 전용 이동 API는 후속 과제로 두고, 재생성 방식의 한계를 "알려진 한계"에 명시한다.
- **스키마 재생성은 dev 로컬 한정**: 공유/스테이징/운영 유사 DB가 없음을 확인함(dev 로컬 MariaDB만 사용). 재생성은 dev 로컬 초기화 절차로만 수행한다.

### 공통 컴포넌트 변경 (예외·액션 타입)

- **공통 예외 추가**: 현재 409를 반환하는 경로는 `DuplicateResourceException`(`DUPLICATE_RESOURCE`)뿐이라 "상태 충돌" 의미가 없다. 신규 추가:
  - `ConflictException`(`RuntimeException` 상속, `com.cms.common.exception`) — 기존 예외 클래스들과 동일 형태.
  - `GlobalApiExceptionHandler`에 `@ExceptionHandler(ConflictException.class)` 추가 → `RESOURCE_CONFLICT` 코드, `HttpStatus.CONFLICT`(409) 응답.
  - 공통 컴포넌트 변경이므로 기존 핸들러 동작(중복/무결성 409)은 건드리지 않고 항목만 추가한다.
- **액션 타입 상수 추가**: `AdminActionTypes`(`com.cms.admin.log.constant`)에 `MENU_CREATE`·`MENU_UPDATE`·`MENU_DEACTIVATE` 3개 상수를 추가하고 `ALL` 목록에도 등록한다.

## Key Changes

### 메뉴 도메인 (`com.cms.admin.menu.Menu`)

- `Menu` 엔티티를 관리자 메뉴 리소스로 정리한다.
- 필드 구성:
  - 유지(String): `menuName`, `menuUrl`, `menuDesc`
  - 추가: `menuIcon`(String)
  - 제거: `menuCategory`, `menuLevel`, `topMenuNo`, `urlTarget`
  - 타입 변경: `useYn`(Boolean), `ord`(Integer), `upMenuNo`(Long)
  - 시각: `createDate`, `updateDate`
- `@Setter`/`@Data` 사용 금지 컨벤션 준수. 생성/변경/비활성화용 도메인 메서드를 엔티티에 추가하고, 상태 변경은 서비스에서만 수행한다.
  - `update(...)` — 수정 가능 필드 일괄 반영 (`upMenuNo`는 변경 대상 아님) + `updateDate = LocalDateTime.now()` 갱신
  - `deactivate()` — `useYn=false` 처리 + `updateDate` 갱신
- **시각 처리(기존 `Member` 패턴 준수)**: JPA Auditing을 쓰지 않고, 생성 시 서비스에서 빌더로 `createDate = LocalDateTime.now()`를 설정하고, 수정/비활성화 도메인 메서드에서 `updateDate`를 수동 갱신한다. (Member의 `updateInfo()`·`changePassword()`와 동일 방식)
- **컬럼 정의(기존 `Member` 패턴 준수)**: 각 String 필드에 `@Column(length=N)`을 명시한다. `menuName`은 `nullable=false, length=100`, `useYn`은 `nullable=false`. 나머지 String 필드는 적절한 길이로 명시한다.
- 코드 변경 후 `.\gradlew.bat compileJava`로 QMenu 재생성.

**스키마 처리 (dev 로컬 한정)**: dev 로컬 MariaDB만 사용하며 공유/스테이징/운영 유사 환경이 없음을 확인함. `application-dev.yml`의 `ddl-auto: update`는 기존 `VARCHAR`→숫자/불리언 타입 변경을 자동 반영하지 않으므로, **dev 로컬에서만** menu 테이블을 drop 후 재생성하는 것을 기본 방침으로 한다. 다만 dev 로컬 `menu` 테이블에 기존 데이터가 있는지 여부가 착수 시점에 불확실하므로(Codex 적대적 리뷰 지적: "기존 데이터 없음" 단언은 검증 없이는 데이터 손실 위험), 재생성 착수 전 반드시 아래 확인 절차를 거친다.

**착수 전 확인 절차**:
1. 대상 dev 로컬 DB(포트 3307, `.env.dev` 접속정보)에서 `SELECT COUNT(*) FROM menu;`로 row 수를 먼저 확인한다.
2. row가 0이면 drop 후 재생성을 그대로 진행한다.
3. **row가 1건 이상 존재하면 drop을 진행하지 않고**, 데이터 보존이 필요한지 먼저 사용자에게 재확인한다. 필요 시 `mysqldump`로 백업하거나, drop 대신 수동 `ALTER TABLE`(컬럼 타입 변경·삭제)로 전환한다.
4. 재생성 스크립트/명령은 실행 전 접속 대상이 dev 로컬(포트 3307)인지 재확인하고, dev 외 접속정보에서는 실행하지 않는다.
5. 확인 결과(row 수, 진행한 절차)와 재생성 필요성을 착수 시 `docs/troubleshooting.md`에 기록한다.

### Repository (`MenuRepository`)

- Spring Data JPA 인터페이스. 트리 조회용 전체/활성 목록, `upMenuNo` 기준 하위 조회(비활성화 시 활성 자식 존재 확인), 정렬 `ord ASC, menuNo ASC`.
- **형제 max(ord) 조회**: 생성 시 `ord` 누락 자동 배치를 위해 같은 부모(`upMenuNo`)의 `max(ord)`를 구하는 조회 메서드를 추가한다(최상위는 `upMenuNo IS NULL` 기준). 없으면 0에서 시작.
- **비관적 락 메서드**: 부모 row를 잠그기 위한 `@Lock(LockModeType.PESSIMISTIC_WRITE)` + `@Query` 조회 메서드(예: `findByIdForUpdate(Long menuNo)`)를 추가한다. 생성/재활성화 시 부모, 비활성화 시 대상 메뉴를 이 메서드로 조회한 뒤 검증한다.

### API

- `GET /admin/api/menus/tree?useYn=true|all`: 트리 조회. 기본값은 `true`(활성만). `all`은 비활성 포함 전체 트리. (`false` 단독 필터는 지원하지 않음 — 허용 외 값은 400)
- `GET /admin/api/menus/{id}`: 단건 조회.
- `POST /admin/api/menus`: 메뉴 생성, 성공 시 `201 Created`와 `Location` 반환.
- `PATCH /admin/api/menus/{id}`: 메뉴 수정 (부모 변경 제외).
- `DELETE /admin/api/menus/{id}`: 메뉴 비활성화, 성공 시 `204 No Content`. (서비스는 `MenuResponse`를 반환하고 컨트롤러가 204로 변환 — 감사 로그 targetId 확보를 위한 패턴)
- 모든 메뉴 API는 `@PreAuthorize("hasRole('ADMIN')")`를 적용한다.
- **SecurityConfig 변경 불필요**: `SecurityConfig`의 `/admin/**` 규칙은 명시적 예외(`/admin`, `/admin/member/info`, `/admin/api/members/me/**`)를 제외하고 이미 `hasRole("ADMIN")`이다. 따라서 신규 `/admin/menu/manage` 페이지와 `/admin/api/menus/**`는 필터 체인 레벨에서 **자동으로 ADMIN 전용 보호**되며, 인가 정책(`SecurityConfig`)을 별도로 수정할 필요가 없다(사전 협의 없이 인가 정책 변경 금지 원칙 준수). 컨트롤러의 `@PreAuthorize`는 방어적 이중화 목적이다.
- **Swagger 문서화**: 기존 컨트롤러 관례(`AdminMemberController` 등)에 따라 `@Tag`, 각 메서드에 `@Operation`(+ 주요 `@ApiResponse`) 부착. SpringDoc OpenAPI 단일화 정책 준수.
- 페이지 컨트롤러: `/admin/menu/manage` 자바 매핑이 없으므로 `MenuPageController`를 추가한다(기존 `AdminMemberPageController`/`AdminActionLogPageController` 패턴).

### 서비스 규칙

- `menuName`은 필수(`@NotBlank`), 최대 **100자**(`@Size(max=100)`). DB 컬럼은 `VARCHAR(100)`로 정의한다.
- **중복 허용**: `menuName`·`menuUrl`에 유니크 제약(DB `uk_*`)이나 서비스 중복 검증을 두지 않는다. 형제·전체에서 동일한 이름/URL을 허용한다(메뉴 트리 특성상 일반적이며 1차 범위 최소화).
- **PATCH 검증(부분 수정 + 도메인 불변식 동시 적용)**: `MenuUpdateRequest`의 각 필드는 `null`이면 기존값을 유지하지만, **non-null 값이 들어오면 생성 시와 동일한 Bean Validation 제약이 적용**된다. 특히 `menuName`은 non-null이면 `@Size(max=100)` + `@Pattern(regexp=".*\\S.*")`로 빈 문자열·공백만 입력·초과 길이를 모두 DTO 레벨에서 400 VALIDATION_ERROR로 거부한다(`@Size(min=1)`만으로는 공백 문자 하나짜리 입력을 걸러내지 못해 `@Pattern`으로 보강). 서비스에서도 `trim()` 후 blank 방어를 유지해 방어를 이중화한다. `menuUrl`·`menuIcon`·`menuDesc`도 non-null이면 각 컬럼 길이에 맞춘 `@Size(max=N)`을 적용한다.
- `ord`는 값이 있으면 0 이상 숫자로 검증한다(`@Min(0)`). **생성 시 `ord`가 누락(null)이면 같은 부모(`upMenuNo`) 형제 중 `max(ord)+1`로 자동 배치**해 맨 뒤에 둔다(최상위는 `upMenuNo=null` 형제 기준). PATCH 시 `ord`가 누락/null이면 기존값을 유지한다.
- **ord 중복 허용**: 동시 생성 시 `max(ord)+1`이 같은 값을 읽어 `ord`가 중복될 수 있다. 1차에서는 **중복을 허용**하고 정렬을 항상 `ord ASC, menuNo ASC`로 안정화해 표시 순서는 결정적으로 유지한다. 순서 재조정은 **후속 reorder API**로 처리한다.
- **useYn null 처리**: 생성 시 `useYn`이 누락(null)이면 `true`로 기본화한다. PATCH 시 `useYn`이 누락/null이면 기존값을 유지한다(부분 수정). 이 정규화를 부모 검증·재활성화 판단보다 먼저 수행해, 이후 로직은 항상 확정된 boolean만 다룬다.
- 생성 시 부모 메뉴(`upMenuNo`)가 지정되면 존재 여부를 검증한다. 없으면 `404 RESOURCE_NOT_FOUND`.
- **동시성 방어(비관적 락)**: 부모가 지정된 생성·재활성화, 그리고 비활성화 시 관련 부모 row를 `PESSIMISTIC_WRITE`로 조회(`findByIdForUpdate` 등)한 뒤 활성 부모 규칙/활성 하위 검사를 수행한다. 잠금 획득 → 검증 → 상태 반영이 한 트랜잭션에서 직렬화된다. (최상위 메뉴는 부모가 없어 잠금 대상 아님)
- **활성 부모 규칙**: 활성(`useYn=true`) 메뉴를 **생성하거나** PATCH로 `useYn`을 **false→true로 재활성화**할 때, 부모가 지정되어 있으면 **부모도 활성이어야 한다.** 부모가 비활성이면 `400 INVALID_REQUEST`로 거부한다. (최상위 메뉴, 즉 `upMenuNo=null`인 경우는 검증 생략)
- **부모 변경 불가**: 수정은 `upMenuNo`를 받지 않으므로 자기참조/순환이 발생하지 않는다. (자기참조·순환 검증 로직 자체가 불필요 — 구조적으로 차단됨)
- **비활성화**: `deactivate()`로 `useYn=false` 처리. 단, 대상에 **활성(useYn=true) 하위 메뉴가 있으면 `ConflictException`을 던져 `409 RESOURCE_CONFLICT`로 거부**한다. 하위가 없거나 모두 비활성일 때만 진행한다. 비활성화 서비스 메서드는 **`MenuResponse`를 반환**하고, 컨트롤러가 `204 No Content`로 변환한다. 이를 통해 `@AdminActionLogged`의 `targetIdExpression`이 반환값에서 `menuNo`를 추출할 수 있다.
- 트리 응답은 `ord ASC`, `menuNo ASC` 기준으로 안정 정렬한다.
- 트리 조립 시 계층(menuLevel)·최상위(topMenuNo)는 `upMenuNo` 관계를 따라 계산해 응답 `data`에 채운다.
- **순환 데이터 방어**: 부모 변경 불가 정책상 정상 경로에서는 순환이 생기지 않지만, DB 직접 조작 등 외부 원인으로 순환(A→B→C→A)이 존재할 수 있다. 트리 조립 시 방문 집합(visited set)으로 가드하되, 무한 재귀 방지에 그치지 않고 **순환 감지 시 해당 가지를 트리에서 제외**하고 **서버 로그(WARN)에 순환 경로의 `menuNo` 체인을 남긴다.** 나머지 정상 노드는 그대로 응답한다.
- **미방문 노드 검사(루트 없는 순환·고아 노드)**: 트리 조립 후 전체 조회 결과와 방문 집합을 대조해 미방문 노드를 식별하고, 해당 노드를 **트리에서 제외 + WARN 로그(menuNo 목록·사유)로 기록**한다(순환과 동일 정책).
- `@Transactional` 경계는 Service에 둔다. 조회 전용은 `@Transactional(readOnly = true)`.
- **감사 로깅**: 생성/수정/비활성화 메서드에 `@AdminActionLogged`를 부착한다(기존 member 도메인 패턴 참고).
  - `actionType`: 각각 `MENU_CREATE`·`MENU_UPDATE`·`MENU_DEACTIVATE` (위 공통 상수 등록 필수).
  - `targetType`: `"MENU"`. `targetIdExpression`: `"menuNo"` — 반환 객체(`MenuResponse`)에서 `getMenuNo()`로 추출. **비활성화 포함 모든 서비스 메서드가 `MenuResponse`를 반환해야** targetId가 null이 되지 않는다.

### 화면 (`admin/menu/manage.html`)

- 하드코딩된 jstree 더미 데이터를 제거하고 `GET /admin/api/menus/tree` 응답으로 렌더링한다.
- **폼 필드**: `menuName`, `menuUrl`, `menuOrder`(→`ord`), `menuIcon`, `menuUseYn`, `menuDesc`(textarea 신규 추가). (부모 선택 select 없음. `urlTarget`은 완전 제거됨)
- **jstree `dnd` 플러그인 제거**: 드래그 앤 드롭 순서 변경은 이번 범위에서 제외하고 후속 작업으로 둔다.
- 트리 위에 "비활성 포함" 체크박스(토글)를 추가한다. 체크 시 `useYn=all`로 트리를 다시 로드하고, 해제 시 `useYn=true`로 복원한다. 비활성 메뉴는 **커스텀 CSS 클래스(회색 글자/아이콘)로만 구분**하고 **노드 선택은 그대로 가능**하게 둔다. (`jstree` `state.disabled`는 클릭 선택을 막아 재활성화 흐름과 충돌하므로 사용하지 않는다. `data.useYn` 값에 따라 노드 렌더링 후 `li_attr`/`a_attr`의 class로 회색 처리)
- **생성/수정 모드를 명시적으로 분리한다**(단일 저장 버튼이 선택 상태에 따라 생성/수정을 겸하지 않는다).
  - **노드 선택 = 상세/수정 대상**으로만 해석한다. 선택 시 단건 API로 폼을 채우고, `저장` 버튼은 **선택된 메뉴 수정(PATCH)** 만 수행한다.
  - 생성은 **`새 최상위` · `선택 아래 하위 추가`** 두 버튼으로 진입한다. 이 버튼을 누르면 폼을 비우고 **생성 모드**로 전환하며, 부모(`upMenuNo`)는 별도 상태로 고정한다(`새 최상위`=null, `선택 아래 하위 추가`=현재 선택 노드). 생성 모드에서 `저장`은 POST를 호출한다.
  - `초기화` 버튼은 폼을 비우고 **선택 해제 + 생성/수정 모드 리셋**(어느 쪽도 아닌 초기 상태)한다.
  - 비활성 메뉴를 선택(수정 모드)한 뒤 `useYn` 스위치를 켜고 저장하면 재활성화(PATCH)된다. 활성 부모 규칙 위반(400) 시 안내 메시지를 노출한다.
- 삭제 버튼은 선택된 메뉴를 비활성화하고 트리를 다시 로드한다. 활성 하위 메뉴가 있어 409가 반환되면 안내 메시지를 노출한다.
- CSRF 토큰은 기존 `meta[name="_csrf"]`, `meta[name="_csrf_header"]` 패턴을 사용한다.

### 사이드바 노출

- `admin/fragments/sidebar.html`의 주석 처리된 "메뉴 관리" 링크를 활성화한다. 메뉴 API가 ADMIN 전용이므로 `sec:authorize="hasRole('ROLE_ADMIN')"`로 노출한다(기존 시스템 섹션 패턴에 맞춤).

## Public Types and Responses

### 요청 DTO

- `MenuCreateRequest`: `menuName`(필수), `menuUrl`, `menuIcon`, `useYn`(선택, 누락 시 서비스에서 `true`로 기본화), `ord`(선택, 누락 시 서비스에서 형제 `max(ord)+1`로 자동 배치), `upMenuNo`, `menuDesc`. Bean Validation(`@NotBlank`, `@Min(0)` 등) 적용. **`useYn`·`ord`는 `@NotNull`을 걸지 않고 null 허용** → 서비스에서 기본값 처리.
- `MenuUpdateRequest`: `menuName`, `menuUrl`, `menuIcon`, `useYn`, `ord`, `menuDesc`. **`upMenuNo`는 제외**(부모 변경 불가 정책). 각 필드는 `null`이면 기존값 유지(부분 수정 시맨틱)하되, **non-null이면 생성 시와 동일한 검증**이 적용된다: `menuName`은 `@Size(max=100)` + `@Pattern(regexp=".*\\S.*")`(빈 문자열·공백만 입력 모두 거부), 나머지 문자열 필드는 컬럼 길이에 맞는 `@Size(max=N)`. `useYn`이 누락/null이면 기존값을 유지하되, false→true로 재활성화할 때는 **활성 부모 검증**이 적용된다(서비스 규칙 참고).
- 트리 필터는 `@RequestParam useYn`(문자열 `true|all`, 기본 `true`)으로 받는다. 허용 외 값은 400으로 거부한다.

### 응답 DTO

- `MenuResponse`: 단건 상세 응답.
- `MenuTreeResponse`: jstree 연동에 필요한 `id`, `text`, `children`, `state`, `data` 형태로 제공한다.
- `data`에는 상세 조회 없이도 기본 표시가 가능하도록 `menuNo`, `menuUrl`, `menuIcon`, `useYn`, `ord`, `upMenuNo`와 계산된 `menuLevel`·`topMenuNo`를 포함한다.
- 엔티티를 직접 노출하지 않고 정적 팩터리(`from(Menu)`)로 변환한다.

## Test Plan

### Service 테스트 (`MenuServiceTest`) — 순수 Mockito, DB 불필요

- 메뉴 생성 성공.
- 존재하지 않는 부모 지정 생성 실패(404).
- **활성 메뉴를 비활성 부모 아래 생성 시 400 거부** (활성 부모 규칙).
- **PATCH로 비활성 부모 아래 자식을 `useYn=true`로 재활성화 시 400 거부** (활성 부모 규칙 — 수정 경로).
- 삭제가 row 제거가 아니라 `useYn=false` 변경임을 확인.
- **활성 하위 메뉴가 있는 메뉴 비활성화 시 409 거부**.
- 하위가 없거나 모두 비활성인 메뉴는 비활성화 성공. **비활성화 서비스 메서드가 `menuNo`를 담은 `MenuResponse`를 반환**하는지 확인 (감사 로그 targetId 추출 보장).
- **생성 시 `useYn` 누락은 `true`로 기본화**되고, **PATCH 시 `useYn` 누락/null은 기존값을 유지**함을 확인 (useYn null 정책).
- **생성 시 `ord` 누락은 형제 `max(ord)+1`로 자동 배치**되고(형제 없으면 0), **PATCH 시 `ord` 누락/null은 기존값을 유지**함을 확인 (ord null 정책).
- 트리 조회가 필터·정렬·계층 구조(계산된 menuLevel/topMenuNo 포함)를 올바르게 구성.
- **순환 데이터 방어**: 순환 데이터가 주입되면 (1) 무한 재귀 없이 조립되고 (2) 순환 가지가 트리에서 제외되며 (3) 순환이 로그로 탐지 가능함을 확인.
- **미방문 노드 방어**: (1) 루트 없는 순환(A↔B 서로 부모)과 (2) 고아 노드(존재하지 않는 `upMenuNo` 참조)가 주입되면 트리에서 제외되고 미방문 노드로 WARN 로그에 기록됨을 확인.
- **동시성 방어**: 생성/재활성화/비활성화 경로가 `findByIdForUpdate`(락 메서드)를 호출하는지 `verify`로 확인. (실제 DB 경합 재현은 별도 `MenuConcurrencyIntegrationTest`에서 검증 — 아래 "동시성 통합 테스트" 절 참고)

### Controller MockMvc 테스트 (`MenuControllerTest`) — `@WebMvcTest`, 서비스 mock, DB 불필요

- ADMIN은 생성/조회/수정/삭제 성공.
- 비로그인은 401, MANAGER/USER는 403.
- 생성 성공은 `201 Created`와 `Location` 확인.
- 검증 실패는 `400 VALIDATION_ERROR`, 활성 부모 규칙 위반은 `400 INVALID_REQUEST` (생성 경로 및 PATCH 재활성화 경로 모두 테스트).
- **PATCH non-null 검증**: `menuName=""`(빈 문자열) → `400 VALIDATION_ERROR`. `menuName` 100자 초과 → `400 VALIDATION_ERROR`. `menuName`이 공백만(`"   "`)인 경우 → `400 VALIDATION_ERROR`.
- 존재하지 않는 메뉴/부모는 `404 RESOURCE_NOT_FOUND`.
- 활성 하위 메뉴 보유 메뉴 삭제 시도는 `409 RESOURCE_CONFLICT`.
- 삭제 성공은 `204 No Content`.

### 동시성 통합 테스트 (`MenuConcurrencyIntegrationTest`) — `@SpringBootTest` + MariaDB, DB 필요

- **목적**: Mockito 기반 서비스 테스트는 `findByIdForUpdate` 호출 여부만 검증할 뿐, `@Lock(PESSIMISTIC_WRITE)`이 실제 트랜잭션에서 경합을 직렬화하는지는 증명하지 못한다(Codex 적대적 리뷰 지적). 이를 실제 DB로 검증하는 통합 테스트 1건을 1차 범위에 포함한다.
- **시나리오**: 활성 부모 메뉴 P를 준비한 뒤, 두 스레드가 동시에 (T1) P를 비활성화하는 요청과 (T2) P 아래에 활성 자식 메뉴를 생성(또는 기존 비활성 자식을 재활성화)하는 요청을 실행한다. `ExecutorService` + `CyclicBarrier`로 두 스레드의 실행 시점을 맞춰 경합을 유도한다.
- **불변식 기반 단정(플래키 방지)**: 스레드 인터리빙 순서와 무관하게, 테스트가 끝난 시점의 DB 상태가 항상 **"비활성(useYn=false) 부모 아래에 활성(useYn=true) 자식이 존재하지 않는다"**는 불변식을 만족하는지 확인한다. (두 요청 중 하나가 409/400으로 거부되거나, 순서에 따라 양쪽 모두 성공하더라도 최종 상태가 불변식을 위반하지 않아야 한다.)
- **구현 제약**: 기존 패턴대로 `@SpringBootTest(classes = CmsTestApplication.class)`를 재사용한다(전체 컨텍스트, 메일 stub 없음 — `CmsApplicationTests`와 동일 컨벤션). **테스트 클래스/메서드에 `@Transactional`을 붙이지 않는다** — 두 스레드가 각자 독립 트랜잭션과 비관적 락을 획득해야 하며, 테스트 레벨 트랜잭션은 롤백·가시성 문제로 락 직렬화 검증 자체를 무력화한다. 롤백이 없으므로 테스트가 생성/변경한 `menu` row는 **`@AfterEach`에서 수동 정리**한다.
- **실행 환경**: CI는 `dev` 프로파일 + MariaDB service container로 `./gradlew test` 시 자동 실행된다(`.github/workflows/ci.yml`이 `DB_PASS`·`MAIL_USER`·`MAIL_PASS`·`DB_URL`을 주입함을 확인 완료). **로컬 실행 시에는 `make dev-db`(DB 기동)만으로는 부족하다.** `application-dev.yml`의 `spring.datasource.password`(`${DB_PASS}`), `spring.mail.username`/`password`(`${MAIL_USER}`/`${MAIL_PASS}`)에 기본값이 없어, 이 환경변수들을 로컬에서 직접 설정하지 않으면 `@SpringBootTest` 컨텍스트 로딩 자체가 실패한다(`src/test/resources`에 별도 test yml 없음). 로컬 실행 시 DB 기동 + 위 3개 환경변수 설정이 모두 필요하다 — 기존 `docs/troubleshooting.md`의 "@DataJpaTest 슬라이스" 항목이 지적한 것과 동일한 env 의존성이다.

### 회귀 확인

- `AdminActionTypeSyncTest`: 신규 `MENU_*` actionType이 `AdminActionTypes.ALL`에 등록돼 통과하는지 확인(등록 누락 시 실패).

### 최종 확인

- `.\gradlew.bat compileJava` (QMenu 재생성) 후 `.\gradlew.bat test` 실행. **로컬 실행 시 `make dev-db`로 DB를 기동하고, `DB_PASS`·`MAIL_USER`·`MAIL_PASS` 환경변수를 설정**해야 `MenuConcurrencyIntegrationTest` 등 DB 의존 테스트가 컨텍스트 로딩에 실패하지 않는다(CI는 `ci.yml`에서 자동 주입).
- 가능하면 `bootRun`(또는 `make dev-up`) 후 `admin`/`1234` 로그인 → `/admin/menu/manage`에서 트리 조회, 생성, 수정, 비활성화 흐름을 수동 확인한다.
- playwright로 화면 렌더링·폼 검증·API 갱신·타 화면 회귀 없음을 확인한다.
- 비자명 이슈 해결 시 `docs/troubleshooting.md`의 알맞은 카테고리에 기록한다.

## 알려진 한계 (Known Limitations)

1차 버전에서 의도적으로 남겨두는 한계로, 후속 과제로 관리한다.

- **동시성**: 부모 비활성화와 같은 부모 아래 활성 자식 생성/재활성화의 경쟁 조건은 **1차에서 부모 row 비관적 락(`PESSIMISTIC_WRITE`)으로 방어**한다(위 서비스 규칙 참고). 다만 이는 단일 DB 트랜잭션 직렬화에 의존하므로, 분산 락은 도입하지 않는다. 락 경합이 잦아지면(대량 배치 등) 성능 저하 가능성이 있으나 관리자 저빈도 작업 전제로 수용한다. 실제 경합이 직렬화되는지는 **불변식 단정 기반 통합 테스트 1건(`MenuConcurrencyIntegrationTest`)** 로 검증하고, 단위 테스트에서는 락 메서드 호출 여부만 `verify`로 확인한다(Test Plan "동시성 통합 테스트" 참고).
- **부모 이동**: 전용 이동 API가 없어 이동은 삭제 후 재생성으로만 가능하다. 이 경우 (1) `menuNo`가 바뀌어 로그 `targetId` 등 참조가 끊길 수 있고, (2) 활성 하위 메뉴가 있는 비리프 메뉴는 삭제가 409로 막혀 사실상 이동할 수 없다. 이동 요구가 확정되면 `PATCH /admin/api/menus/{id}/parent` 전용 API + 순환 검증으로 후속 구현한다.
- **순서(ord) 재조정**: 순서 변경은 1차에서 생성 시 자동 배치(`max(ord)+1`)와 개별 PATCH 수정만 제공한다. 동시 생성 시 `ord` 중복이 발생할 수 있으며(표시 순서는 `menuNo` tie-breaker로 결정적), 형제 전체를 일괄 재정렬하거나 드래그로 옮기는 기능은 없다. 필요 시 후속으로 **명시적 reorder API(`PATCH /admin/api/menus/reorder` 등)** 와 드래그 앤 드롭을 도입한다.
- **스키마 관리**: 타입 변경 반영을 버전 관리 마이그레이션이 아닌 dev 로컬 테이블 재생성으로 처리한다. dev 외 환경이 생기면 마이그레이션 도구 도입이 필요하다.

## Assumptions

- 브랜치는 `feat/menu-management`를 사용한다.
- 정리 커밋(`README.md`, `.editorconfig`)은 이미 브랜치에 반영되어 있어, 이번 작업은 메뉴 기능 커밋만 추가한다.
- 메뉴 삭제 정책은 비활성화로 고정하되, 활성 하위 메뉴가 있으면 409로 거부한다.
- 활성 메뉴는 활성 부모 아래에만 생성한다.
- 메뉴 트리는 `useYn` 필터를 제공하고 기본값은 사용 메뉴만 조회한다.
- 부모(`upMenuNo`)는 생성 시에만 결정되며 수정으로 변경하지 않는다(이동은 후속 과제). `urlTarget`은 엔티티·DTO·화면 모두에서 완전 제거한다.
- dev 로컬 DB만 사용하며 공유/스테이징/운영 유사 DB가 없어, 타입 변경은 dev 로컬 테이블 재생성으로 처리한다. 단, 착수 전 `menu` 테이블 row 수를 확인해(0건이면 drop 후 재생성, 1건 이상이면 보존 필요 여부 재확인 후 백업/`ALTER`로 전환) 데이터 손실을 방지한다(위 "스키마 처리" 절차 참고).
- 동시성 방어는 부모 row 비관적 락(`PESSIMISTIC_WRITE`)으로 1차에 포함한다(분산 락은 제외). 락 메서드 호출 여부는 `verify`로 확인하고, 실제 경합 직렬화는 불변식 단정 기반 통합 테스트 1건(`MenuConcurrencyIntegrationTest`, `@SpringBootTest`+MariaDB)으로 검증한다. 드래그 앤 드롭 순서 변경 API는 1차 구현 범위에서 제외한다.
- `menuName`·`menuUrl`은 유니크 제약/중복 검증을 두지 않는다(중복 허용).
- `SecurityConfig`의 `/admin/**` 규칙이 이미 메뉴 관련 신규 경로를 ADMIN 전용으로 보호하므로, 인가 정책 자체는 변경하지 않는다.
- `useYn`은 생성 시 누락되면 `true`로 기본화하고, PATCH 시 누락/null이면 기존값을 유지한다(미정의 상태 저장 불가).
- `ord`는 생성 시 누락되면 형제 `max(ord)+1`로 자동 배치하고(형제 없으면 0), PATCH 시 누락/null이면 기존값을 유지한다. 동시 생성 시 `ord` 중복은 1차에서 허용하며 표시 순서는 `menuNo` tie-breaker로 결정한다(reorder는 후속).
- 트리 조회 필터는 `true`(기본)와 `all`만 지원하고, `false`(비활성만) 단독 필터는 지원하지 않는다.
- 순환 데이터·미방문 노드(루트 없는 순환·고아 노드)는 트리 조립 시 감지해 해당 노드를 제외하고 WARN 로그로 남긴다(정상 경로에서는 발생하지 않음).
- 비활성 트리 노드는 커스텀 CSS로만 회색 구분하고 선택은 가능하게 유지한다(`jstree` `state.disabled` 미사용).
- 메뉴 관리 화면은 생성/수정 모드를 명시적으로 분리한다(선택=수정, `새 최상위`·`선택 아래 하위 추가` 버튼=생성).
- 모든 REST 컨트롤러에 SpringDoc Swagger 어노테이션(`@Tag`/`@Operation`/`@ApiResponse`)을 부착한다(기존 컨트롤러 관례 준수).
- `MenuServiceTest`=순수 Mockito(DB 불필요), `MenuControllerTest`=`@WebMvcTest`(서비스 mock, DB 불필요). H2·testcontainers 없음. 예외적으로 `MenuConcurrencyIntegrationTest`는 `@SpringBootTest(classes = CmsTestApplication.class)`+실제 MariaDB를 사용하며, `@Transactional` 없이 `@AfterEach` 수동 정리로 동작한다(로컬은 `make dev-db` + `DB_PASS`/`MAIL_USER`/`MAIL_PASS` 환경변수 설정 선행 필요, CI는 `ci.yml`에서 자동 주입).
- PATCH 요청은 `null`이면 기존값 유지하되, non-null 값에는 생성 시와 동일한 길이·공백 검증을 적용한다(빈 문자열·공백만 입력·길이 초과는 400).
