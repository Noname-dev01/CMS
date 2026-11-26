# PLAN-member-profile-image-service — 프로필 이미지 서비스 분리 (SOLID 검토 2번)

> 상태: **구현·실기·코드 리뷰 완료(2026-11-26), PR 머지 대기** — 설계 v2(리뷰 2라운드 ship) · 경로: **정식**(`admin/member` 서비스·행 잠금·파일 정리 경로)

## 개정 이력
- v1 (2026-11-26): 최초 작성. 정찰(기준 `origin/master` `72ec929`) + 설계 질문 2라운드(D1~D5) 결과.
- v2 변경 (1라운드 codex 리뷰 3건 — 수용 2, 부분 수용 1): ① 통합 시험에 초기화·프리셋의 커밋·롤백 경로를 추가한다(업로드만으로는 두 메서드의 `@Transactional`·`deleteAfterCommit` 누락을 못 잡는다) ② 정리를 키별 개별 시도 + 회원 삭제 `finally` + 오류 모아 재던지기로 바꾸고, 키는 서비스 호출 직후(롤백 시험은 트랜잭션 안에서) 기록한다 — **기각: `store()` 반환을 `@MockitoSpyBean`으로 잡거나 시험 전용 저장 루트를 쓰는 안**(둘 다 컨텍스트를 하나 더 만들고, 막으려는 경우는 시험이 이미 실패하는 경우이며 그 잔여는 병행 계획 `test-data-leak`의 실행 전 비우기가 지운다) ③ `@Transactional` 제거 변이의 기대 결과를 "업로드 실패(실패 지점은 변이 실행에서 기록)"로 고친다 — 잠금 조회(`:335`)가 동기화 등록(`:342`)보다 먼저다. 동기화 비활성 시 `IllegalStateException` 출처를 Spring Framework 7.0.9(Boot 4.0.8 BOM, 로컬 Gradle 캐시 `spring-tx/7.0.9` 확인) `TransactionSynchronizationManager.registerSynchronization` 소스로 고정. 등록 실패 시 파일 회수는 기존 `FileStorageTransactionSupportTest`가 다룬다.
- v2 리뷰(2라운드 codex): **ship** — 추가 지적 없음. 컨텍스트 캐시 공유는 미확인·실측 대상으로 유지.

## Context

2026-11-25~26 SOLID 검토에서 `AdminMemberService`(504줄)의 SRP 위반이 "높음"으로 지적됐다 — 회원 관리·내 정보·비밀번호·프로필 이미지가 한 클래스에 있다. `updateAdminMember()`는 하나의 트랜잭션·잠금 순서로 묶여 있어 그대로 두고, **프로필 이미지 메서드만 `MemberProfileImageService`로 분리한다**(검토의 제안). 사용자가 개선 순서 1→2→3→4를 승인했고 이 작업이 2번이다.

**동작 보존 리팩터링이다**: API 경로·요청·응답·상태 코드·오류 문구·잠금·파일 정리 시점이 모두 그대로다. 스키마·인가·의존성 변경 없음.

위험 변경: 행 잠금(`findByIdForUpdate`)·트랜잭션 경계(`@Transactional` 프록시가 다른 빈으로 이동)·파일 저장·삭제(`FileStorageTransactionSupport` 정리 경로 이동) — 세 경로가 **새 빈으로 옮겨질 뿐 순서·내용은 바뀌지 않는다**.

### 정찰 사실 (기준 `72ec929`)

| 항목 | 사실 |
|---|---|
| 이동 대상 메서드 | `AdminMemberService` `updateMyProfileImage`(:318)·`resetMyProfileImage`(:356)·`applyDefaultProfileImage`(:409)·`getMyProfileImageContent`(:433)·`getProfileImageContent`(:444)·`loadProfileImageContent`(:451, private) |
| 이동 대상 상수 | `DEFAULT_PROFILE_IMAGE_MAP`(:56)·`PROFILE_IMAGE_NAMESPACE`(:64)·`MAX_UPLOAD_FILE_SIZE`(:66) — 셋 다 프로필 메서드에서만 쓴다 |
| 이동 뒤 남는 의존성 | `AdminMemberService`의 `FileStorage` 필드는 프로필 메서드에서만 쓴다 → 제거된다. `Clock`·`MemberRepository`는 양쪽 모두 필요 |
| 공유 private | `toResponse(member, ProfileImageVisibility)`(:486) — 회원 관리 6곳 + 프로필 2곳. `validateAdminTarget`(:468) — `getAdminMember`·`updateAdminMember` + 프로필 1곳 |
| `ProfileImageVisibility` | `AdminMemberService`의 public 중첩 enum(:69). 다른 파일 참조 0건(`grep`) |
| 호출자 | `AdminMemberController` 하나(:82·:129·:140·:151·:164). 응답 변환 `profileImageResponse`(:190)는 컨트롤러 private |
| 기존 DTO 관례 | 응답 DTO static 팩토리 `from(...)`(`BannerResponse`·`PostResponse`·`FaqResponse` 등). `AdminMemberResponse`에는 없음 |
| 시험 파급 | `AdminMemberServiceTest` 프로필 단위 시험 13개(:973~1163, Mockito `@InjectMocks`), `AdminMemberControllerTest`(`@WebMvcTest` + `MockConfig`에서 `Mockito.mock` 빈 수동 등록 — 새 서비스 빈이 없으면 컨텍스트 생성 실패) |
| 영향 없음 | `MemberProfileImageInsertIntegrationTest`(주석에서만 `AdminMemberService.createAdmin` 언급)·`ProfileImageValidatorTest`·`ProfileImageUrlsTest`·`ProfileImageUrlShrinkUpgradeTest`. 다른 컨트롤러 슬라이스의 `ProfileImage` 언급은 상단바 `getCurrentAdminProfileImageUrl` 스텁 |
| **시험 공백** | `AdminMemberServiceTest`:1015 주석이 가리키는 `AdminMemberProfileImageTransactionIntegrationTest`는 **존재하지 않는다**. 실제 트랜잭션으로 프로필 정리 경로(커밋 후 옛 파일 삭제·롤백 시 새 파일 삭제)를 보는 시험이 0건 |
| 낡게 될 참조 | `Member.java`:147(`AdminMemberService.applyDefaultProfileImage`), `ProfileImageValidator.java`:9, `ProfileImageUrls.java`:9(`AdminMemberService.toResponse()`), `admin/member/CLAUDE.md`:18(`updateMyProfileImage`를 "이 클래스의" 쓰기 메서드로 나열)·:65(검증 주체 `AdminMemberService`), `FileStorageTransactionSupport` Javadoc "회원 서비스"(일반 표현이라 유지 가능 — 구현 때 확인) |
| GitHub 이슈 | "프로필 이미지"·"SOLID"·"AdminMemberService" 검색 결과 0건 |

### 사용자 결정 (2026-11-26 설계 질문)
- **경로**: 정식(위험 지점 표 — 세션·계정, 행 잠금·버전, 파일 저장·삭제).
- D1 응답 변환 = DTO static 팩토리, D2 대상 검증 = 새 서비스에 복제, D3 컨트롤러 유지, D4 실제 트랜잭션 통합 시험 1개 추가(잠금 관측 제외), D5 단위 시험을 새 클래스로 이동. 근거는 아래 각 결정.

## 핵심 확정 사항

### 결정 1 — 응답 변환: `AdminMemberResponse.from(member, visibility)` + enum 최상위 이동
- 선택지: (A) DTO static 팩토리 (B) `service` 패키지 package-private 정적 변환 클래스 (C) 새 서비스에 builder 복제.
- **A 채택(사용자 결정)**. 이유: 프로젝트의 DTO `from()` 관례와 같고 파일이 늘지 않으며, 두 서비스가 서로를 의존하지 않는다. B는 이 프로젝트에 없던 형태, C는 필드 9개를 두 곳에서 맞춰야 한다.
- `ProfileImageVisibility`는 `com.cms.admin.member.dto.response.ProfileImageVisibility`(최상위 public enum)로 옮긴다. 이유: 두 서비스와 DTO가 함께 쓰는 값이라 한 서비스의 중첩 타입으로 두면 새 서비스가 `AdminMemberService`를 import해야 한다. 값(`HIDDEN`·`SELF`·`OTHER`)과 Javadoc("쟁점 10")은 그대로.
- `from()` 본문은 기존 `toResponse` 본문을 그대로 옮긴다(`switch` → `ProfileImageUrls.resolveSelfUrl`/`resolveTargetUrl(member.getId(), member)`/`null`). DTO가 도메인 유틸 `ProfileImageUrls`(`admin/member/domain`)를 참조하게 된다 — `BannerResponse.from`이 도메인 타입을 받는 것과 같은 방향(dto → domain)이라 의존 방향 규칙에 맞는다.
- `AdminMemberService`의 private `toResponse`는 지우고 8곳 호출을 `AdminMemberResponse.from(...)`으로 바꾼다.

### 결정 2 — 관리자 대상 검증: 새 서비스에 private 메서드 복제
- 선택지: (A) 3줄 복제 (B) `Member`에 판정 메서드 추가.
- **A 채택(사용자 결정)**. 이유: 도메인을 바꾸지 않고 동작 보존이 가장 분명하다. 예외 종류(`ResourceNotFoundException`)·문구("관리자 대상만 조회할 수 있습니다.")를 글자 그대로 복제한다. 동작은 옮겨지는 단위 시험 `getProfileImageContent_nonAdminTarget_notFound`가 고정한다.
- 수용하는 비용: 역할 조건이 두 곳에 생긴다. 역할이 추가되면 두 곳을 함께 고쳐야 한다 — `admin/member/CLAUDE.md`에 한 줄 적는다.

### 결정 3 — 새 서비스의 모양
- `com.cms.admin.member.service.MemberProfileImageService`, `@Service` + `@RequiredArgsConstructor`, 필드 `MemberRepository`·`FileStorage`·`Clock`(생성자 주입 — CLAUDE.md 코딩 컨벤션).
- 공개 메서드 5개는 **시그니처·어노테이션·본문을 그대로** 옮긴다: 쓰기 3개 `@Transactional`, 다운로드 2개 `@Transactional(readOnly = true)`. `@AdminActionLogged`는 원래 프로필 메서드에 없으므로 추가하지 않는다(동작 보존).
- 메서드 안의 순서를 바꾸지 않는다 — 특히 `updateMyProfileImage`의 "빈 파일·크기·바이트 읽기·`ProfileImageValidator.validate` → `findByIdForUpdate` → `store` → `deleteOnRollback` 등록 → 도메인 변경 → (옛 값이 UPLOADED면) `deleteAfterCommit` 등록". 검증이 잠금보다 먼저라 무효 입력이 행을 잠그지 않는다(옮겨지는 시험 `tooLarge`·`webp`가 `never().findByIdForUpdate` 단언).
- 메서드 순서는 원래 파일의 상대 순서(업로드 → 초기화 → 프리셋 → 내 다운로드 → 타인 다운로드 → private)로 둔다. 기존 Javadoc(행 잠금 이유 등)도 함께 옮긴다.
- 클래스 Javadoc에 분리 이유(SOLID 2번)와 잠금 계약("쓰기 3개는 첫 DB 접근이 회원 행 `findByIdForUpdate` — 비밀번호 변경·자동 잠금 벌크 UPDATE와 직렬화")을 적는다.
- 잠금 순서 영향: 각 쓰기 메서드는 이전과 같이 **회원 행 하나만** 잠근다. 다른 잠금을 새로 얻거나 순서를 바꾸지 않으므로 교착 가능성은 변하지 않는다. 두 서비스가 서로 호출하지 않으므로 트랜잭션 전파 조합도 새로 생기지 않는다.

### 결정 4 — 컨트롤러: 그대로 두고 새 서비스 주입
- `AdminMemberController`에 `private final MemberProfileImageService memberProfileImageService;`를 추가하고, 프로필 엔드포인트 5개의 호출 대상만 바꾼다. 경로·`@PreAuthorize`·`consumes`·`refreshAuthentication` 호출·`profileImageResponse`는 그대로.
- 이유(사용자 결정): SOLID 지적 범위가 서비스이고 인가 선언이 이동하지 않는다. `AdminEndpointAuthorizationConventionTest` 영향 없음.
- 외부 계약 변경 없음 — Swagger 어노테이션도 그대로.

### 결정 5 — 시험
- **5-a 단위 시험 이동**(사용자 결정): `AdminMemberServiceTest`의 프로필 시험 13개와 그것만 쓰는 도우미(`pngBytes`, 동기화 `@BeforeEach`/`@AfterEach`)를 새 `MemberProfileImageServiceTest`(`@ExtendWith(MockitoExtension.class)`, `@Mock MemberRepository`·`FileStorage`, `@Spy Clock` 고정 시계, `@InjectMocks MemberProfileImageService`)로 옮긴다. **단언·스텁은 바꾸지 않고** 대상 변수만 바꾼다. 원래 파일에서 13개와 이제 안 쓰는 `@Mock FileStorage`·import·동기화 설정(다른 시험이 쓰지 않는지 구현 때 확인)을 지운다. `adminMember()` 도우미는 양쪽에 필요하면 새 파일에 복제한다(시험 도우미 복제는 허용 — 기존 관례).
- :1015의 존재하지 않는 시험 이름 주석은 5-c의 새 통합 시험 이름으로 고친다.
- **5-b 컨트롤러 슬라이스**: `AdminMemberControllerTest.MockConfig`에 `MemberProfileImageService` 목 빈을 추가하고 `reset(...)`에 포함한다. 프로필 시험(:987~1240 구간)의 `given(adminMemberService.<프로필 메서드>)`·`verify`·`inOrder.verify`를 새 목으로 바꾼다. 단언은 그대로.
- **5-c 실제 트랜잭션 통합 시험 1개 추가**(사용자 결정, D4): `src/test/java/com/cms/admin/member/service/MemberProfileImageTransactionIntegrationTest` — `@SpringBootTest(classes = CmsTestApplication.class)` + `MariaDbContainerSupport` 상속(기존 통합 시험과 같은 구성 → 컨텍스트 캐시 공유 기대, 미확인 — 실측).
  - 회원: `TestMembers.save(memberRepository, "profile-tx", Role.ROLE_MANAGER)`.
  - **시험 ① 커밋 경로**: 서비스 `updateMyProfileImage`로 PNG를 두 번 올린다(각각 독립 트랜잭션 — 시험 메서드에 `@Transactional` 없음). 첫 업로드 뒤 DB의 `profile_image_url`(키1)을 `JdbcTemplate`으로 읽고, 두 번째 업로드 뒤 키2를 읽는다. 단언: `fileStorage.load(키1, "profile")`이 `StorageFileNotFoundException`(커밋 후 삭제됨), `load(키2, "profile")`은 올린 바이트와 같음, DB `profile_image_kind = 'UPLOADED'`.
  - **시험 ② 롤백 경로**: 첫 업로드로 키1을 만든 뒤, 바깥 `TransactionTemplate` 안에서 `updateMyProfileImage`를 호출하고(`REQUIRED`라 바깥에 합류) 같은 트랜잭션에서 `memberRepository.findById`로 새 키2를 읽은 다음 `status.setRollbackOnly()`. 단언: `load(키2)` → `StorageFileNotFoundException`(롤백 시 새 파일 삭제), `load(키1)`은 그대로 있음(커밋되지 않았으니 `afterCommit` 삭제 없음), DB의 `profile_image_url`은 여전히 키1.
  - **시험 ③ 초기화**(v2, 리뷰 1-1): 업로드로 키1을 만든 뒤 (a) 바깥 트랜잭션 안에서 `resetMyProfileImage` + `setRollbackOnly` → 키1 파일 남음, DB `profile_image_kind = 'UPLOADED'`·`profile_image_url = 키1` 그대로 (b) 그냥 `resetMyProfileImage`(커밋) → 키1 파일 삭제, DB `kind = 'NONE'`·`url`·`content_type` null.
  - **시험 ④ 프리셋**(v2, 리뷰 1-1): 업로드로 키1을 만든 뒤 (a) 바깥 트랜잭션 안에서 `applyDefaultProfileImage(id, "profile-1")` + `setRollbackOnly` → 키1 파일 남음, DB 그대로 (b) 커밋 → 키1 파일 삭제, DB `kind = 'PRESET'`·`url = '/img/undraw_profile_1.svg'`.
  - 이 시험들이 고정하는 것: 새 빈의 쓰기 메서드 3개 각각에 `@Transactional` 프록시가 걸려 있고, 정리 콜백이 커밋·롤백 결과에 맞게 돈다는 것. 프록시가 없으면 잠금 조회(`findByIdForUpdate`, `:335`)나 그 뒤의 정리 동기화 등록(`registerSynchronization` — 동기화 비활성 시 `IllegalStateException`, 출처: Spring Framework 7.0.9 `spring-tx/src/main/java/org/springframework/transaction/support/TransactionSynchronizationManager.java` — Boot 4.0.8 BOM, 로컬 Gradle 캐시 `spring-tx/7.0.9`)에서 실패한다. **어느 지점에서 실패하는지는 변이 실행에서 확인해 기록한다**(v2, 리뷰 1-3 — 이 변이는 "등록 실패 시 파일 회수"를 증명하지 않는다. 그것은 기존 `FileStorageTransactionSupportTest`의 몫).
  - **키 기록**(v2, 리뷰 1-2): 각 서비스 호출 직후 DB(`JdbcTemplate`)에서 `profile_image_url`을 읽어 정리 목록에 넣는다. 롤백 시험에서는 롤백 전 같은 트랜잭션 안에서 `memberRepository.findById`로 읽어 넣는다.
  - **정리(필수, v2 강화)**: `@AfterEach`에서 기록한 키마다 `fileStorage.delete(key, "profile")`를 **각각 try/catch로** 시도하고(없는 파일은 no-op — `LocalDiskFileStorage.delete`의 부모 부재·`NoSuchFileException` 분기, `:240~247`), 예외는 모아 둔다. 회원 삭제 `TestMembers.delete(jdbc, List.of(memberId))`는 `finally`에서 반드시 실행하고, 모은 예외가 있으면 마지막에 첫 예외에 나머지를 `addSuppressed`로 붙여 던진다. 이유: 병행 계획 `PLAN-test-data-leak.md`(메인 체크아웃 `test/test-data-leak`, 미머지)가 시험 종료 후 저장소에 파일이 남으면 빌드를 실패시키고, 회원 행 잔여도 같은 계획의 정리 대상이다. 그 계획이 머지되기 전에는 이 시험이 `./data/attachments/profile`에 쓰므로 정리가 더 중요하다.
  - 수용 한계: 서비스 안에서 `store()` 뒤 커밋 결과가 불명확해지는 경우(정리 유틸이 의도적으로 파일 보존)나 호출 직후 JDBC 조회 실패 시에는 키를 못 얻어 파일이 남을 수 있다 — 그때는 시험이 이미 실패한 상태이고, 잔여는 `test-data-leak` 머지 후 다음 실행의 비우기가 지운다.
  - 잠금 직렬화 관측(`INNODB_LOCK_WAITS`)은 넣지 않는다(사용자 결정 — 잠금 조회 자체는 옮기기만 하고 기존 단위 시험이 `findByIdForUpdate` 호출을 스텁으로 강제한다).
- **5-d `AdminMemberResponse.from` 단위 시험**: `AdminMemberResponseTest`(새 파일, 순수 JUnit)로 세 값을 고정한다 — `HIDDEN` → `null`, `SELF` + UPLOADED → `/admin/api/members/me/profile-image?v=`로 시작, `OTHER` + UPLOADED → `/admin/api/members/{id}/profile-image?v=`로 시작, 나머지 필드 8개 복사. 이유: 변환이 private 메서드에서 public 팩토리로 바뀌어 직접 시험할 수 있게 됐고, 지금은 `OTHER`를 단언하는 서비스 시험이 있는지 불확실하다(구현 때 확인 — 있으면 이 시험은 `HIDDEN`·필드 복사만 남긴다).
- TDD 적용: 동작 보존 이동이라 "먼저 실패하는 시험"은 5-c·5-d다. 5-c는 현재 코드(`AdminMemberService`)를 대상으로 먼저 써서 **통과**함을 보이고(현 동작의 기준선), 이동 뒤 대상만 바꿔 다시 통과시킨다. 그리고 새 서비스의 쓰기 메서드 3개 `@Transactional`을 하나씩 잠시 지워 각각 해당 시험(①·③·④)이 실패하는지 확인하고 실패 지점을 기록한 뒤 복원한다(변이 확인, v2). 5-d는 `from()`이 없어 컴파일 실패 → 구현.

### 결정 6 — 문서·주석
- 낡은 참조를 고친다: `Member.java`:147 → `MemberProfileImageService.applyDefaultProfileImage`, `ProfileImageValidator.java`:9 → `MemberProfileImageService`, `ProfileImageUrls.java`:9 → `AdminMemberResponse.from()`.
- `admin/member/CLAUDE.md`: ":18"의 "이 클래스의 다른 모든 쓰기 메서드(… `updateMyProfileImage` …)" 문장에서 프로필 메서드는 `MemberProfileImageService`로 옮겨졌다고 고치고, "프로필 이미지" 절의 검증 주체를 `MemberProfileImageService`로 바꾼다. 같은 절에 "서비스 분리(2026-11-26, 이 계획)·대상 검증 복제(결정 2)·잠금은 회원 행 하나" 한 줄을 넣는다.
- 루트 `CLAUDE.md`는 고치지 않는다(프로필 이미지는 패키지 문서로 위임돼 있다).
- 계획 인덱스 `plan/README.md`에 행을 더한다 — 번호는 PR 직전에 최신 `master`의 마지막 번호 + 1로 정한다(병행 세션 `test-data-leak`·`solid-small-cleanup`과 번호가 겹칠 수 있음).

### 범위 밖
- `updateAdminMember` 등 회원 관리 메서드 분리(SOLID 검토가 그대로 두라고 했다).
- 컨트롤러 분리(결정 4).
- 잠금 직렬화 관측 시험(D4).
- `validateAdminTarget`의 도메인 이동(결정 2).

## 성공 기준
1. `AdminMemberService`에 프로필 메서드 6개·상수 3개·`FileStorage` 필드·`toResponse`·중첩 enum이 없다(`grep -n "ProfileImage\|fileStorage\|FileStorage" AdminMemberService.java`가 `AdminMemberResponse.from`/`ProfileImageVisibility` 사용만 남김).
2. `grep -rn "AdminMemberService.ProfileImageVisibility\|adminMemberService\.\(updateMyProfileImage\|resetMyProfileImage\|applyDefaultProfileImage\|getMyProfileImageContent\|getProfileImageContent\)" src` 0건.
3. 옮긴 단위 시험 13개가 **단언 무변경**으로 통과(diff에서 단언 줄이 그대로임을 확인), 컨트롤러 슬라이스 시험 전부 통과.
4. 5-c 통합 시험(①~④)이 이동 전(기준선)·이동 후 모두 통과하고, 새 서비스의 쓰기 메서드별 `@Transactional` 제거 변이에서 해당 시험이 각각 실패한다(v2).
5. 시험 실행 뒤 `data/attachments/profile`에 이 시험이 만든 파일이 남지 않는다(실행 전후 파일 목록 비교).
6. 실기(7단계): ADMIN·MANAGER로 내 정보 화면에서 업로드 → 표시 → 프리셋 선택 → 초기화 → 상단바 갱신, ADMIN의 타 관리자 상세에서 프로필 이미지 표시, MANAGER의 `GET /admin/api/members/{id}/profile-image` 403. 업로드 교체 뒤 옛 파일이 디스크에서 사라짐.
7. 로컬 전체 시험 1회(`./gradlew test -PbrowserTests=exclude`) — 정식 경로라 `code-review-loop` 마무리에서. 화면을 바꾸지 않으므로 브라우저 시험은 PR CI에 맡긴다.

## 실패 모드와 고정 시험
| # | 놓치기 쉬운 실패 | 고정 시험 |
|---|---|---|
| F1 | 새 빈의 쓰기 메서드에서 `@Transactional`이 빠지거나 효과가 없음(예: 내부 호출) → 잠금 조회·정리 동기화 등록 실패로 500, 또는 커밋 후 옛 파일이 안 지워짐 | 5-c 시험 ①·③(b)·④(b) + 메서드별 `@Transactional` 제거 변이 확인 |
| F2 | 롤백 시 새 파일이 남거나, 커밋되지 않았는데 옛 파일이 지워짐(업로드·초기화·프리셋) | 5-c 시험 ②·③(a)·④(a) |
| F3 | 응답 변환 이동 중 `SELF`/`OTHER`/`HIDDEN` 분기가 바뀌어 다른 URL이 나감(내 정보에 타인 경로, 목록에 URL 노출 등) | 5-d `AdminMemberResponseTest` + 옮긴 `updateMyProfileImage_success`(me 경로)·`applyDefaultProfileImage_success`(프리셋 경로) |
| F4 | 검증이 잠금 뒤로 밀려 무효 파일이 회원 행을 잠금, 또는 대상 검증 복제 누락으로 ROLE_USER 대상 이미지가 노출 | 옮긴 `tooLarge`·`webp`(`never().findByIdForUpdate`), `getProfileImageContent_nonAdminTarget_notFound` |
| F5 | 컨트롤러가 일부 엔드포인트에서 여전히 옛 서비스를 부르거나 새 빈이 컨텍스트에 없음 | 5-b 슬라이스 시험(새 목에 스텁·`verify`), 5-c 통합 시험(전체 컨텍스트 기동) |

## 작업 단계 (승인 뒤)
1. 5-c 통합 시험을 현재 코드 대상으로 작성·통과(기준선). 5-d 시험 작성(컴파일 실패 확인).
2. `ProfileImageVisibility` 최상위 이동 + `AdminMemberResponse.from` 추가 → `AdminMemberService`가 `from` 사용 → 5-d 통과. `./gradlew compileJava`.
3. `MemberProfileImageService` 생성·이동, `AdminMemberService`에서 제거(`FileStorage` 필드 포함). `compileJava`.
4. 컨트롤러 호출 대상 교체.
5. 시험 이동(5-a)·슬라이스 수정(5-b)·통합 시험 대상 교체(5-c) → 변이 확인.
6. 주석·`admin/member/CLAUDE.md` 갱신.
7. 관련 시험: `./gradlew test --tests 'com.cms.admin.member.*' --tests '*ConventionTest'`(+ 이름에 `AdminMemberService`를 쓰는 다른 패키지 통합 시험 5개 — `NotificationGenerationIntegrationTest`·`MemberPermissionConcurrencyIntegrationTest`·`MemberRoleChangePermissionIntegrationTest`·`AdminSessionRevocationIntegrationTest`·`AdminMemberEmailResetTokenConcurrencyIntegrationTest`(member 패키지)).
8. 실기 검증(일회용 DB, 빈 포트 — 8083 제외).

## 리스크
- **병행 작업 충돌**: `test/test-data-leak`(미머지)이 시험 저장소 격리와 누수 검사를 넣는다. 파일 충돌은 없다(그 계획은 `MemberProfileImageInsertIntegrationTest`·`LocalDiskFileStorageTest`·`build.gradle`을 고치고 이 작업은 셋 다 건드리지 않는다). 그 PR이 먼저 머지되면 이 PR의 새 통합 시험도 누수 검사를 받는다 — 정리가 완전해야 한다(5-c). 계획 인덱스 번호 충돌 가능성(결정 6).
- `solid-small-cleanup`(1번)은 `FileStorage` 네임스페이스 메서드를 추상 메서드로 바꾼다 — 이 작업은 이미 네임스페이스 메서드만 쓰므로 영향 없음. 단위 시험의 `FileStorage` 목도 Mockito라 영향 없음.
- 슬라이스 시험이 수동 `MockConfig`라 목 빈 누락은 컨텍스트 생성 실패로 즉시 드러난다(조용한 실패 아님).

## 구현 파일 (예정)
- 새: `service/MemberProfileImageService.java`, `dto/response/ProfileImageVisibility.java`, 시험 `service/MemberProfileImageServiceTest.java`·`service/MemberProfileImageTransactionIntegrationTest.java`·`dto/response/AdminMemberResponseTest.java`(시험 패키지 위치는 구현 때 기존 `dto` 시험 폴더 관례 확인)
- 수정: `AdminMemberService.java`, `AdminMemberResponse.java`, `AdminMemberController.java`, `Member.java`(주석), `ProfileImageValidator.java`(주석), `ProfileImageUrls.java`(주석), `AdminMemberServiceTest.java`, `AdminMemberControllerTest.java`, `admin/member/CLAUDE.md`, `plan/README.md`

## 구현 결과 (2026-11-26)
- 계획과 다르게 간 결정: 없음. 새 서비스 Javadoc의 메서드 참조 2곳(`changeMyPassword()` → `AdminMemberService.changeMyPassword()`, `getAdminMember` → `AdminMemberService.getAdminMember`)만 클래스가 달라져 이름을 붙였다 — 본문·순서는 그대로.
- `AdminMemberService` 504줄 → 339줄. `FileStorage` 필드·프로필 상수·중첩 enum·`toResponse` 제거.
- 5-d: 서비스 시험 중 `OTHER` URL을 단언하는 것이 없음을 확인해 세 분기 모두 고정했다.
- `FileStorageTransactionSupport` Javadoc의 "회원 서비스"는 일반 표현이라 유지했다.

### 구현 파일
- 새: `service/MemberProfileImageService.java`, `dto/response/ProfileImageVisibility.java`, 시험 `service/MemberProfileImageServiceTest.java`(옮긴 13건)·`service/MemberProfileImageTransactionIntegrationTest.java`(4건)·`dto/response/AdminMemberResponseTest.java`(3건)
- 수정: `AdminMemberService.java`, `AdminMemberResponse.java`(`from`), `AdminMemberController.java`(5개 엔드포인트 호출 대상), `Member.java`·`ProfileImageValidator.java`·`ProfileImageUrls.java`(주석), `AdminMemberServiceTest.java`(13건·프로필 전용 설정 제거 — 46건 남음, 46+13=이동 전 59건), `AdminMemberControllerTest.java`(목 빈 추가, 프로필 스텁·검증·`verifyNoInteractions` 대상 교체), `admin/member/CLAUDE.md`

## 검증 결과
- **기준선**(TDD 1단계): 통합 시험 4건을 이동 전 `AdminMemberService` 대상으로 실행 → 4/4 통과, 저장소 잔여 파일 0.
- **RED**: `AdminMemberResponseTest`가 `from`·`ProfileImageVisibility` 부재로 컴파일 실패 → 구현 뒤 3/3.
- **관련 시험**(최종 코드): `./gradlew test --tests 'com.cms.admin.member.*' --tests '*ConventionTest' --tests NotificationGenerationIntegrationTest --tests MemberPermissionConcurrencyIntegrationTest --tests MemberRoleChangePermissionIntegrationTest --tests AdminSessionRevocationIntegrationTest -PbrowserTests=exclude` → 70개 클래스 573건, 실패 0. 실행 뒤 `data/attachments/profile` 파일 0개(성공 기준 5). 전체 시험은 `code-review-loop` 마무리에서 1회(정식 경로).
- **변이**(성공 기준 4): 쓰기 메서드별 `@Transactional` 제거 →
  | 제거 대상 | 실패한 시험 | 실패 지점 |
  |---|---|---|
  | `updateMyProfileImage` | 4건 전부(모두 업로드로 시작) | `InvalidDataAccessApiUsageException: No active transaction` |
  | `resetMyProfileImage` | 초기화 시험만 | 같음 |
  | `applyDefaultProfileImage` | 프리셋 시험만 | 같음 |
  실패 지점은 리뷰 1-3의 예측대로 정리 동기화 등록이 아니라 그보다 앞선 잠금 조회(`findByIdForUpdate`)다. 원본은 바이트 단위로 복원 확인.
- **성공 기준 1·2**: `AdminMemberService`에 남은 프로필 흔적은 `AdminMemberResponse.from`/`ProfileImageVisibility` 사용뿐. 옛 참조(`AdminMemberService.toResponse`·프로필 메서드·`AdminMemberService.ProfileImageVisibility`·없는 시험 이름) `src`·`docs`·`CLAUDE.md` 0건.
- **실기**(일회용 MariaDB 3398 + 앱 8085 PID 70360, 저장 루트 scratchpad — 끝난 뒤 앱·컨테이너 종료):
  - MANAGER(`mgr-split`): 업로드 200 → 미리보기·상단바 `/me/profile-image?v=…`, DB `UPLOADED`·키·`image/png`, 파일 1개 / 교체 200 → 옛 파일 삭제·새 파일 1개, 다운로드 `image/png`·`no-store, private`·`nosniff` / 프리셋 `profile-2` → DB `PRESET`·`/img/undraw_profile_2.svg`·null, 파일 0개, 상단바 프리셋 / 재업로드 뒤 [이미지 제거] DELETE 204 → DB `NONE`·null·null, 파일 0개, 상단바 기본 이미지, 내 이미지 GET 404.
  - 권한 경계: MANAGER의 `GET /admin/api/members/1/profile-image` 403, `GET /admin/api/members/1` 403.
  - ADMIN(TOTP 강제 등록 → 복구 코드 로그인): 타 관리자 상세 `profileImageUrl` = `/admin/api/members/2/profile-image?v=…`(OTHER), 이미지 200 `image/png`, 목록 URL 전부 null(HIDDEN), 없는 회원 이미지 404. 관리자 조회 상세 모달에 MANAGER 업로드 이미지 표시(스크린샷).
  - 콘솔 오류는 favicon 404와 의도한 경계 요청(403·404) 뿐.

## 이슈
- 정리 스크립트가 `AdminMemberServiceTest`의 `ExtendWith`·`MockitoExtension` import를 미사용으로 오판해 지웠다(클래스 어노테이션이 검색 범위 밖) — 컴파일 전에 발견해 원위치로 복원.
- 실기 중 모달 열림 전환 도중 찍힌 스크린샷은 버리고 `.modal.show` 뒤 다시 찍었다.
- 통합 시험이 `data/attachments/profile/yyyy/MM/dd` 빈 디렉터리를 남긴다(파일은 0). `test-data-leak` 머지 뒤에는 `build/test-file-storage`로 간다.

## 후속
- 없음. SOLID 검토 3·4번은 원래 순서대로 별도 작업.
