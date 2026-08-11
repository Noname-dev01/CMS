# PLAN — 프로필 이미지 Base64-in-DB → FileStorage 이관

> 작성일: 2026-08-10
> 로드맵 근거: `adversarial-review/project-direction-roadmap.md` "실행 로드맵 Top 3 (2026-07-29 선정) — ③"
> 선행 완료: 파일 스토리지 추상화 + 공지 첨부파일 (`174e925` #18, `PLAN-notice-attachment.md`)
> 사용자 승인(2026-08-10, plan 모드): 이관 전략 = 앱 레벨 1회성 이관 / 서빙 인가 = 현행 화면 동작 유지(관리자 목록 상세가 타 관리자 이미지 계속 노출)

## 개정 이력
- v1 (2026-08-10): 최초 작성.
- v2 (2026-08-10, 리뷰 1차 — codex, no-ship): `profile_image_kind` enum 도입, `findByIdForUpdate()` 전환, 러너 재설계, MIME 화이트리스트, storageKey 해시 캐시 토큰, `FileStorageTransactionSupport`(신규 2곳), 엔티티 메서드 4종 분리. 사용자 결정: 단일 인스턴스 위험 수용, 이관 실패 pass-through.
- v3 (2026-08-10, 리뷰 2차 — codex, no-ship): 문자열 접두어(`"profile/"`) 방식의 네임스페이스, `@Builder.Default`, V11 catch-all 백필, 크기 상한(문자열 길이, 초과 시 NONE — 사용자 결정), `ImageIO` 검증 도입, ID 조회 명시적 JPQL + 인덱스, crash window 잔여 위험 명시, `FileStorageTransactionSupport` 계약, `ResponseEntity<byte[]>` 명시.
- v4 (2026-08-10, 리뷰 3차 — codex, no-ship): `FileStorage.store()`에 네임스페이스 오버로드 추가(단, `load()/delete()`는 그대로 — **이 설계에 결함이 있었음, v5에서 정정**), 길이 검사를 엔티티 로드 전 벌크 UPDATE로 이동, `ImageReader` 헤더 우선 크기 검사, WebP 화이트리스트 제외(사용자 결정), 프리셋 대소문자 정규화, 포맷/MIME 교차검증, 애니메이션 GIF 거부(`getNumImages(false)`, **이 조건에 결함이 있었음, v5에서 정정**), 예외 보존(`addSuppressed`). 3차 리뷰에서 반박했던 항목(엔티티 심층 방어·재시도 인프라·외부 URL 격리·재인코딩·`@PrePersist`)은 4차 리뷰에서 타당성 확인.
- v5 (2026-08-10, 리뷰 4차 반영 — codex, no-ship):
  - **수용(차단1, `load()/delete()`가 네임스페이스를 몰라 물리적 분리가 실제로는 성립하지 않음)**: v4는 `store()`에만 네임스페이스 오버로드를 추가하고 반환된 키 문자열(`profile/yyyy/...`)이 "자기서술적"이라 `load()/delete()` 수정이 불필요하다고 가정했는데, 이 가정이 틀렸다 — `load(key)`/`delete(key)`는 여전히 네임스페이스 없는 기존 시그니처라 **어떤 키든** `root` 기준으로 그대로 해석한다. 오염된 `profile_image_url`에 공지 첨부파일의 실제 키(접두어 없음)가 들어가면 그 공지 파일을 그대로 읽어버리고, `profile/../yyyy/...` 같은 값은 정규화 후 `profile/` 밖(=공지 파일이 실제로 있는 위치)을 가리켜도 `verifyWithinRoot()`(루트 전체 기준)를 통과한다는 지적이 정확했다. **`FileStorage`의 `load()`/`delete()`에도 네임스페이스 오버로드를 추가**하고, `LocalDiskFileStorage`가 실제로 `root/<namespace>` 기준으로 존재 검증까지 수행하도록 재설계(쟁점 2 전면 재작성) — 문자열 접두어(`"profile/"`) 컨벤션 자체를 폐기하고, 네임스페이스는 DB 값에서 추론하지 않고 **호출부가 항상 컴파일 타임 상수로 전달**한다(문자열 위조 여지 제거).
  - **수용(차단2, `getNumImages(false)`가 `-1`을 반환해 애니메이션 검사가 우회됨)**: JDK 계약상 `allowSearch=false`이면 스트림 검색이 필요한 포맷(GIF 등)에서 프레임 수 대신 `-1`을 반환할 수 있어 `-1 > 1`이 항상 거짓이 되는 지적이 정확했다 — `getNumImages(true)`(검색 허용)로 바꾸고 결과가 정확히 `1`일 때만 통과시키도록 수정(쟁점 3).
  - **수용(높음, 4000×4000도 아바타 용도엔 과한 힙 할당)**: 변 길이 상한을 4000→**2000px**로 축소하고 **총 픽셀 상한 2,000,000**(약 8MB 래스터)을 추가로 검사 — 아바타 용도에 1600만 픽셀(4000×4000, 최대 약 64MB 래스터)은 과하다는 지적을 반영.
  - **표현 정정**: "DB가 정수 하나만 반환"을 "DB가 길이 조건을 서버 사이드에서 평가하고 애플리케이션은 영향 행 수만 받는다"로 정정. WebP 레거시 행이 이번 이관에서 영구 제외되고 계속 Base64 그대로 응답된다는 잔여 부채를 완료 기준에 명시.
  - **확인**: 3차 리뷰에서 반박했던 항목(재시도 방지 인프라·외부 URL 격리·재인코딩·`@PrePersist`·엔티티 값 객체) 전부 4차 리뷰에서 타당성 재확인(반박 유지). 단 "엔티티 심층 방어 불필요" 반박은 차단1이 해결되면서 전제가 정상화됨(경로 우회 자체가 스토리지 계층에서 막히므로 엔티티가 추가로 막을 필요가 실제로 없어짐).
- v6 (2026-08-10, 리뷰 5차 반영 — codex, no-ship 차단 1건 → 계획 반영 후 승인 단계로 진행, `/plan-review-loop` 5라운드 종료):
  - **수용(차단, 반대 방향 우회 — 네임스페이스 없는 공지 API가 `profile` 서브트리에 접근 가능)**: v5는 "오염된 프로필 값 → 공지 파일 접근"은 막았지만, 반대 방향인 "오염된 `notice_attachment.storage_key` → 프로필 파일 접근"은 그대로 열려 있었다는 지적이 정확했다 — `NoticeAttachmentService`는 여전히 네임스페이스 없는 `load(key)`/`delete(key)`를 호출하고, 그 경계는 `root` 전체이므로 `storage_key`에 `"profile/yyyy/.../실제프로필키"`가 들어가면 `root/profile/...`도 `root` 하위라 기존 `verifyWithinRoot()`를 통과해 프로필 이미지 파일을 읽거나 지울 수 있었다(이번이 4차 지적의 반복이 아니라 반대 방향의 신규 지적임을 리뷰가 명시). 이 프로젝트 규모에는 "공지도 네임스페이스로 옮기기"(기존 파일 이관 수반, 과한 투자)보다 **예약 네임스페이스 차단**이 현실적이라는 리뷰의 대안을 채택한다: `LocalDiskFileStorage`가 자신이 지원하는 네임스페이스 집합(현재 `{"profile"}`)을 알고 있고, **네임스페이스 없는** `load(key)`/`delete(key)`가 `key`의 최상위 경로 세그먼트를 검사해 그것이 예약된 네임스페이스와 일치하면(정규화·실경로 해석 기준) 파일에 접근하지 않고 즉시 거부한다(공지 서비스 입장에서는 "해당 storage_key를 찾을 수 없음"과 동일하게 처리되어 `StorageFileNotFoundException`으로 흡수 — 정상적인 공지 storage_key는 애초에 이 세그먼트를 갖지 않으므로 회귀 없음). `NoticeAttachmentService` 호출부는 무변경.
  - **확인(비차단)**: `getNumImages(true) == 1`(JDK 17 `ImageReader` 계약과 정합, `-1`·`0`·다중 프레임 전부 fail-closed 처리 확인), 2000px·200만 픽셀 상한(내부 관리자 아바타 용도로 적절, 완전한 힙 상한 보장이라 주장하지 않는 점도 확인) 모두 타당성 재확인(반박 없음). "실경로 검증이 부모 디렉터리가 아니라 최종 대상 자체를 검사해야 심볼릭 링크까지 막는다"는 비차단 구현 주의는 로컬 스토리지에 심볼릭 링크를 만들 수 있는 공격자를 전제해야 해서 이 프로젝트 규모에서 독립적 차단 사유는 아니라고 리뷰가 명시 — 구현 시 저비용으로 반영 가능하면 반영(대상 자체의 `toRealPath()`까지 검사), 별도 설계 변경 불필요.
  - **`/plan-review-loop` 5라운드(스킬 최대 라운드) 종료**: 5라운드째 지적이 1건(작고 명확함)으로 수렴해, 사용자 결정에 따라 추가 라운드 없이 위 수정을 계획에 반영한 뒤 승인 단계로 진행한다. 구현 단계에서 이 최종 안전장치를 코드로 옮기고 `/code-review-loop`로 구현 결과를 재검토한다.

## Context

프로필 이미지는 `data:<mime>;base64,...` 문자열로 `member.profile_image_url`(LONGTEXT)에 인라인 저장된다(`AdminMemberService.updateMyProfileImage`, L232-259). 회원 상세·내 정보 API 응답에 대용량 Base64가 그대로 실려, CLAUDE.md "주의사항"에 명시된 알려진 부채다. 2026-07-22 도입된 `FileStorage`(`com.cms.common.storage`)를 재사용해 실파일 저장으로 이관한다.

### 인가 정책 영향 — 당초 예상과 다름

정찰 결과 SecurityConfig 변경이 전혀 불필요함을 확인했다(codex 리뷰 4라운드 모두 동의, 반박 없음):
- `SecurityConfig.java`에 이미 `.requestMatchers("/admin/api/members/me", "/admin/api/members/me/**").hasAnyRole("ADMIN", "MANAGER")`(L54)가 존재.
- 타 관리자 이미지 라우트(`/admin/api/members/{id}/profile-image`)는 캐치올 `.requestMatchers("/admin/**").hasRole("ADMIN")`(L59)에 해당.

**따라서 이번 작업은 SecurityConfig 파일을 전혀 수정하지 않는다.**

### 스키마 영향

**있다.** `member` 테이블에 새 컬럼 2개 + 인덱스 1개(V11):
- `profile_image_kind` — `ENUM('NONE','PRESET','UPLOADED','LEGACY_INLINE')`, `NOT NULL DEFAULT 'NONE'`, 인덱스 부여
- `profile_image_content_type` — `VARCHAR(100) NULL`

기존 `profile_image_url`은 타입·이름 변경 없음.

**추가로**, `com.cms.common.storage.FileStorage`에 네임스페이스 인자 오버로드 3종(`store`/`load`/`delete`) 추가 — 기존 2-인자 시그니처는 그대로 유지되어 `NoticeAttachmentService` 호출부는 무변경.

### 배포 모델 전제(위험 수용, 사용자 확정)

단일 인스턴스, 중단 후 재시작 배포를 전제로 한다. 다중 인스턴스 전환 시 재평가 필요.

### 잔여 위험(해결 대상 아님 — 명시적으로 수용)

- **파일/DB 비원자성**: 파일 저장 성공 후 DB 커밋 전, 또는 커밋 후 파일 삭제 전 프로세스 강제 종료 시 고아 파일/구 파일 잔존 가능 — `FileStorage` 기반 설계 전체가 공유하는 잔여 위험, 이번 기능이 새로 만든 게 아니다.
- **신뢰하지 않는 이미지의 JVM 내 파싱**: `ImageIO`로 업로드된 이미지를 애플리케이션 프로세스 안에서 직접 디코딩한다 — JDK 이미지 디코더 자체의 버그·CPU 사용까지 완전히 격리하지 못한다. 업로드는 인증된 ADMIN/MANAGER만 가능해 공개 익명 업로드보다 위험이 낮다.
- **WebP 레거시 행 영구 미이관**: 화이트리스트에서 WebP를 제외하기로 한 결정(사용자 확정)에 따라, 기존에 WebP로 저장된 레거시 행이 있다면 마이그레이션 러너가 화이트리스트 밖으로 판정해 영구히 `LEGACY_INLINE`(Base64 pass-through)으로 남는다 — 데이터 손실은 없으나 "Base64 페이로드 제거"라는 이번 기능의 목표에서 그 행만 예외로 남는다. 재업로드 시에도 webp는 더 이상 선택할 수 없다.

---

## 핵심 설계 결정 (쟁점별)

### 쟁점 1 — 다운로드 라우트의 응답 형태 (v3 유지)

`Content-Disposition` 미설정(인라인), 정확한 `Content-Type`, `X-Content-Type-Options: nosniff`, `Cache-Control: private, no-store`. 컨트롤러는 `ResponseEntity<byte[]>`로 실제 바이트를 직접 반환.

### 쟁점 2 — 물리적 네임스페이스 분리: `store`/`load`/`delete` 전체에 네임스페이스 [v5 전면 재작성]

- **v4까지의 문제**: `store()`에만 네임스페이스를 추가하고 "반환된 키가 자기서술적이라 `load()/delete()`는 그대로 둬도 된다"고 가정했으나, `load()/delete()`가 네임스페이스를 모르는 채로 남아있으면 **어떤 키 문자열이든 그대로 `root` 기준으로 해석**되므로 오염된 값(공지 첨부파일의 실제 키, 또는 `profile/../...` 같은 트래버설 값)이 여전히 다른 소비자의 파일을 가리킬 수 있었다.
- **v5 결정**: `FileStorage` 인터페이스에 **3개 메서드 전부** 네임스페이스 오버로드를 추가한다(기존 2-인자 시그니처는 하위 호환을 위해 유지 — default 메서드가 아니라, 각 메서드에 네임스페이스가 없는 오버로드도 병존시켜 기존 호출부는 무변경):
  ```java
  // com.cms.common.storage.FileStorage
  String store(byte[] content, String originalFilename);
  byte[] load(String storageKey);
  void delete(String storageKey);

  default String store(byte[] content, String originalFilename, String namespace) {
      throw new UnsupportedOperationException("이 FileStorage 구현체는 네임스페이스를 지원하지 않습니다.");
  }
  default byte[] load(String storageKey, String namespace) {
      throw new UnsupportedOperationException("이 FileStorage 구현체는 네임스페이스를 지원하지 않습니다.");
  }
  default void delete(String storageKey, String namespace) {
      throw new UnsupportedOperationException("이 FileStorage 구현체는 네임스페이스를 지원하지 않습니다.");
  }
  ```
  **[v4에서 지적받은 대로 수정]** default 구현이 네임스페이스를 조용히 무시하지 않고 `UnsupportedOperationException`을 던진다 — 향후 다른 `FileStorage` 구현체가 이 오버로드를 재정의하지 않고 방치하면 "격리가 조용히 깨지는" 대신 즉시 실패한다.
  - `LocalDiskFileStorage`는 3개 오버로드 전부를 재정의한다. `store()`는 `root.resolve(namespace)` 하위에 실제로 쓰고, `load()`/`delete()`도 **동일하게** `root.resolve(namespace).resolve(key).normalize()`로 경로를 계산한 뒤, 기존 `verifyWithinRoot()`와 같은 패턴(`toRealPath()` 심볼릭 링크까지 실경로 해석 후 `startsWith()`)으로 **`root/namespace`(전체 루트가 아니라!) 기준으로** 벗어나지 않는지 검증한다. `key`에 `..`/절대경로 등이 섞여 있어도 정규화·실경로 해석 이후 `root/namespace` 밖이면 즉시 거부된다(공지 첨부파일이 실제로 있는 `root/yyyy/...`는 `root/namespace/...` 밖이므로 이 검증에서 항상 걸러진다).
  - `namespace` 인자 자체도 검증한다(`[a-z0-9_-]+` 형태의 단일 세그먼트만 허용, `/`나 `..` 포함 시 즉시 거부) — 현재는 프로필 이미지 서비스가 항상 `"profile"` 상수만 전달하지만, 향후 다른 소비자가 추가될 가능성을 고려한 방어.
  - **핵심 안전성**: 네임스페이스는 **DB에 저장된 문자열에서 추론하지 않는다** — 프로필 이미지 서비스는 항상 컴파일 타임 상수 `"profile"`을 넘긴다. `profile_image_url`에는 (v3~v4의 `"profile/"` 접두어 붙은 값이 아니라) `store()`가 반환하는 **네임스페이스 로컬 키**(`yyyy/MM/dd/uuid.ext`, 접두어 없음 — 공지 첨부파일과 같은 모양)를 그대로 저장한다. 이 값이 설령 실제 공지 첨부파일의 storageKey와 **문자열이 완전히 같더라도**, `load(key, "profile")`은 `root/profile/yyyy/.../uuid.ext`에서만 찾으므로(실제 공지 파일은 `root/yyyy/.../uuid.ext`에 있음) 물리적으로 다른 경로라 안전하다 — 값 자체의 "생김새"나 접두어에 전혀 의존하지 않는, 구조적으로 안전한 설계.
  - `NoticeAttachmentService`는 기존 2-인자 오버로드를 계속 호출하므로 물리 저장 위치·동작이 전혀 바뀌지 않는다(회귀 없음).
- **[v6 신규] 반대 방향 우회 차단 — 예약 네임스페이스**: 5차 리뷰에서 "네임스페이스 없는 기존 `load(key)`/`delete(key)`의 경계는 여전히 `root` 전체라, 오염된 `notice_attachment.storage_key`에 `\"profile/yyyy/.../실제프로필키\"`가 들어가면 공지 다운로드·삭제가 프로필 이미지 파일에 접근할 수 있다"는 반대 방향 지적을 받았다(4차의 "프로필→공지" 우회와 반대인 "공지→프로필" 우회). `LocalDiskFileStorage`가 자신이 지원하는 네임스페이스 집합(현재 `{"profile"}`)을 `private static final Set<String> RESERVED_NAMESPACES`로 알고 있고, **네임스페이스 없는** `load(key)`/`delete(key)`는 `key`를 정규화·실경로 해석한 뒤 **최상위 경로 세그먼트가 예약된 네임스페이스와 일치하면 파일에 접근하지 않고 즉시 거부**한다(공지 서비스 입장에서는 `StorageFileNotFoundException`으로 흡수 — 정상적인 공지 storage_key는 애초에 `profile/`로 시작하지 않으므로 회귀 없음). "공지도 네임스페이스로 옮기기"(기존 파일 이관 필요)보다 저비용인 이 방식을 채택했다(리뷰가 제시한 대안 중 이 프로젝트 규모에 맞는 쪽).
- **`profile_image_kind`(v2)는 그대로 유지**: "이 값이 지금 무슨 의미인지(없음/프리셋/업로드됨/미이관 레거시)"를 담당 — `kind=UPLOADED`일 때만 서비스가 `load(url, "profile")`/`delete(url, "profile")`을 호출한다.
- **V11 백필**(v4 유지): 프리셋 4종 대소문자 무관 매칭 + canonical 값 재기록, `data:` 접두어 값은 `LEGACY_INLINE`, 잔여 non-null 값은 catch-all로 `LEGACY_INLINE`.

### 쟁점 3 — 이미지 검증: 헤더 우선 크기 확인 + 총 픽셀 예산 + 정확한 애니메이션 검사 + 포맷 일치 [v5: 두 곳 수정]

- **화이트리스트(v4 유지)**: `Set.of("image/png", "image/jpeg", "image/gif")` — WebP 제외(사용자 확정, JDK 표준 `ImageIO` 미지원).
- **헤더 우선 디코딩 검증**(v4 유지 + v5 수정):
  ```java
  try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
      if (!readers.hasNext()) throw new InvalidRequestException("이미지를 해석할 수 없습니다.");
      ImageReader reader = readers.next();
      try {
          reader.setInput(iis);
          int width = reader.getWidth(0);   // 헤더만 읽음
          int height = reader.getHeight(0);
          // [v5 수정] 변 길이 상한 4000→2000px + 총 픽셀 예산 신설
          long pixels = (long) width * height;
          if (width > MAX_DIMENSION || height > MAX_DIMENSION || pixels > MAX_TOTAL_PIXELS) {
              throw new InvalidRequestException("이미지 크기가 너무 큽니다.");
          }
          // [v5 수정] getNumImages(false) → getNumImages(true), 정확히 1이어야 통과
          // false는 GIF 등 스트림 검색이 필요한 포맷에서 -1을 반환할 수 있어 애니메이션 검사가 우회된다.
          if (reader.getNumImages(true) != 1) {
              throw new InvalidRequestException("애니메이션 이미지는 지원하지 않습니다.");
          }
          String actualMime = canonicalMimeFor(reader.getFormatName());
          if (!declaredContentType.equals(actualMime)) {
              throw new InvalidRequestException("파일 형식과 실제 이미지가 일치하지 않습니다.");
          }
          reader.read(0); // 모든 검사를 통과한 뒤에만 실제 픽셀 디코딩
      } finally {
          reader.dispose();
      }
  }
  ```
  `MAX_DIMENSION = 2000`(px, 변 길이), `MAX_TOTAL_PIXELS = 2_000_000`(약 8MB ARGB 래스터 — 아바타 용도로 충분히 선명하고 힙 부담이 작음). 신규 의존성 없이 JDK 표준 API만 사용.
- **업로드·마이그레이션 양쪽 동일 검증**. 다운로드 시점에도 `content_type` 화이트리스트 재검증(v3 유지).
- **대안 기각(v3~v4 유지)**: 매직바이트 문자열 시그니처 검사(신규 의존성), canonical 포맷 재인코딩(트랜스코딩) — 과한 투자로 기각.

### 쟁점 4 — 공유 판별 로직의 위치: `ProfileImageUrls` 유틸 (v2 유지)

### 쟁점 5 — 캐시 무효화: storageKey 기반 버전 토큰 (v2 유지 — 4라운드 내내 반박 없음)

### 쟁점 6 — 1회성 이관: 길이 사전 검사(엔티티 미로드) + 행별 트랜잭션 재검증 (v4 유지, 표현만 정정)

- 조건부 벌크 UPDATE(`resetIfOversizedLegacyImage`)로 엔티티를 로드하지 않고 길이 검사+초기화를 원자적으로 처리 — 4차 리뷰에서 "OOM 지적을 실제로 해소한다"고 확인(반박 없음).
- **[v5 표현 정정]** "DB가 정수 하나만 반환"이 아니라 "DB가 `CHAR_LENGTH` 조건을 서버 사이드에서 평가하고, 애플리케이션은 영향받은 행 수(update count)만 받는다" — LONGTEXT 값 자체도, 그 길이 값도 애플리케이션으로 전송되지 않는다.
- 그 외 설계(ID 조회, 행별 트랜잭션, 실패 격리, 재시도 인프라 미도입 반박)는 v4 그대로 — 4차 리뷰에서 재시도 인프라 반박의 타당성 확인(다만 WebP 화이트리스트 제외 행은 매 기동 Base64 디코딩까지 반복된다는 점은 인지 — 규모상 별도 상태 컬럼이 필수는 아니라는 리뷰 판단에 따름).

### 쟁점 7 — 파일 정리 트랜잭션 동기화: `FileStorageTransactionSupport` (v4 유지)

`deleteOnRollback`/`deleteAfterCommit`이 네임스페이스 인자를 받는 `FileStorage.load(key, "profile")`/`delete(key, "profile")`를 사용하도록 시그니처만 조정. 예외 보존(`addSuppressed`) 계약은 v4 그대로.

### 쟁점 8 — 저장 위치 (쟁점 2로 통합, v5)

쟁점 2의 네임스페이스가 물리적 분리를 담당 — 별도 쟁점 불필요.

### 쟁점 9 — 엔티티 메서드: 의미별 분리 + 저비용 구조적 방어 [v5: 방어 위치 재확인]

- v2~v4 결정 유지(의미별 메서드 4종, `Clock` 기반 `now`, non-blank 검증).
- **[v5 확인]** 4차 리뷰에서 "엔티티가 `profile/` 접두어 검증을 하는 것만으론 경로 우회를 막지 못한다"고 지적했는데, 쟁점 2의 재설계로 **경로 안전성 자체가 스토리지 계층(`LocalDiskFileStorage`)의 책임으로 이동**했으므로 이 지적은 전제가 해소된다 — 엔티티는 여전히 non-blank·`now != null` 정도의 가벼운 구조적 방어만 하고, 실제 보안 경계는 스토리지 계층 하나로 명확히 귀속된다(중복 방어를 여러 계층에 흩뿌리지 않는다는 원래의 설계 원칙이 유지됨).

### 쟁점 10 — `toResponse()`의 self/other 구분: enum (v1 유지, 4라운드 내내 반박 없음)

### 쟁점 11 — 다운로드 라우트 서비스 계약 (v2 유지, 반박 없음)

---

## 구현 파일 (실측 기준)

### 신규
- `src/main/resources/db/migration/V11__add_member_profile_image_kind.sql` (v4와 동일 — 프리셋 canonical화 + catch-all 백필, 인덱스 포함)
- `src/main/java/com/cms/admin/member/domain/ProfileImageKind.java` — enum
- `src/main/java/com/cms/admin/member/domain/ProfileImageUrls.java` — URL 해석 유틸(쟁점 4·5)
- `src/main/java/com/cms/admin/member/ProfileImageMigrationRunner.java` — `CommandLineRunner`(쟁점 6)
- `src/main/java/com/cms/common/storage/FileStorageTransactionSupport.java` — 공유 유틸(쟁점 7)
- `src/main/java/com/cms/admin/member/dto/response/ProfileImageContent.java` — 서비스↔컨트롤러 내부 전송 DTO
- 신규 테스트:
  - `LocalDiskFileStorageTest`에 네임스페이스 오버로드 테스트 추가: `store(...,"profile")`이 실제 `root/profile/...` 하위에 파일 생성 확인, **`load(공지파일의_실제키, "profile")`이 물리적으로 다른 경로라 `StorageFileNotFoundException`을 던지는지**(정방향 — 4차 리뷰 지적), **[v6 신규] `store(profileBytes, ..., "profile")`로 저장한 뒤 `load("profile/" + 그 키)`·`delete("profile/" + 그 키)`(네임스페이스 없는 기존 오버로드로 예약된 서브트리를 직접 노려 접근 시도)가 거부되고 프로필 파일이 보존되는지**(역방향 — 5차 리뷰 지적, 예약 네임스페이스 차단 검증), `load(key, "profile")`에 `..` 트래버설 값을 섞은 키로 시도 시 거부, 네임스페이스 없는 기존 2-인자 오버로드는 기존 위치·동작 그대로 유지되는 회귀 테스트
  - `ProfileImageUrlsTest` — kind별 URL 분기, 버전 토큰이 storageKey 변경마다 달라짐
  - `ProfileImageMigrationRunnerTest`(단위) — 정상 이관, 재검증 스킵, MIME 화이트리스트 밖 스킵(webp 포함), malformed base64 스킵, **애니메이션 GIF 스킵(실제 다중 프레임 GIF로 검증 — `getNumImages(true)` 정확성 확인)**, 정상 단일 프레임 GIF는 통과 확인, 포맷/MIME 불일치 스킵, **가로·세로는 상한 이하지만 총 픽셀이 상한을 넘는 이미지 거부**
  - `ProfileImageMigrationRunnerIntegrationTest`(Testcontainers) — 실제 커밋/롤백, 동시 실행 시뮬레이션, 대형 LONGTEXT 값이 엔티티로 로드되지 않고 벌크 UPDATE만으로 NONE 처리되는지
  - `MemberProfileImageInsertIntegrationTest`(Testcontainers) — `createAdmin()`/`AdminBootstrapLoader`/`TestMemberLoader` 3개 생성 경로 정상 INSERT 확인
  - `AdminMemberServiceTest`: 업로드→교체→초기화→프리셋 전환, 동시 업로드 경합, 화이트리스트 밖 MIME 400(webp 포함), `ImageIO` 디코딩 실패·픽셀 상한(변/총계 양쪽)·실제 애니메이션 GIF·포맷불일치 400
  - `AdminMemberControllerTest`: 다운로드 라우트 2종 + 보안 매트릭스(익명 401·MANAGER self 200·MANAGER other 403·ADMIN other 200) + 응답 헤더 테스트
  - V11 마이그레이션 통합 테스트: 대소문자 변형 프리셋 canonicalization, 빈 문자열·손상값의 catch-all 분류
  - 마이그레이션 정확성: 원본 Base64 디코딩 바이트와 저장 파일 바이트 `assertArrayEquals` 비교

### 수정
- `src/main/java/com/cms/common/storage/FileStorage.java` — `store`/`load`/`delete` 3종에 네임스페이스 오버로드(default, 미구현 시 `UnsupportedOperationException`)
- `src/main/java/com/cms/common/storage/LocalDiskFileStorage.java` — 3종 오버로드 재정의(네임스페이스 하위 디렉터리 실제 저장·검증)
- `src/main/java/com/cms/admin/member/domain/Member.java` — `profileImageKind`(`@Builder.Default`)·`profileImageContentType` 필드, 의미별 메서드 4종
- `src/main/java/com/cms/admin/member/service/AdminMemberService.java` — 화이트리스트·`ImageIO` 검증(쟁점 3), `findByIdForUpdate` 전환, `ProfileImageVisibility`, `getProfileImageContent(...)`, 네임스페이스 `"profile"` 상수로 `FileStorage` 호출
- `src/main/java/com/cms/admin/member/controller/AdminMemberController.java` — `GET members/me/profile-image`, `GET members/{id}/profile-image`(`ResponseEntity<byte[]>`)
- `src/main/java/com/cms/config/auth/AdminSecurityService.java` — `ProfileImageUrls.resolveSelfUrl()` 경유
- `src/main/java/com/cms/admin/member/repository/MemberRepository.java` — `findIdsByProfileImageKind(...)`, `resetIfOversizedLegacyImage(...)`
- `src/main/resources/templates/admin/member/admin-my-info.html` — 업로드 `accept` 속성에서 `image/webp` 제거
- `docker-compose.dev.yml`·`docker-compose.prod.yml` — 볼륨 주석 갱신 — 기능적 변경 없음

### 수정 없음(재사용 확인 완료)
- `src/main/java/com/cms/admin/notice/service/NoticeAttachmentService.java` — 이번 PR에서 건드리지 않음(기존 2-인자 오버로드 그대로 호출)
- `SecurityConfig.java` — 변경 없음

---

## 완료 기준

- `./gradlew test` 전체 통과
- 빈 DB `bootRun` 기동 시 Flyway `validate` 통과(V11 적용 확인)
- Playwright:
  1. ADMIN 로그인 → 내 정보 이미지 업로드(png/jpeg/gif) → 탑바·내 정보 화면 즉시 반영 → 프리셋 선택 → 초기화 골든 패스
  2. **MANAGER** 로그인 → `/admin/notice/manage` 진입 → 탑바 프로필 이미지 정상 표시(403 없음)
  3. 관리자 목록 상세에서 타 관리자 이미지 표시 확인
  4. webp·SVG·2MB 초과·비이미지 위장 파일·애니메이션 GIF·과대 픽셀 이미지 업로드 시 400 확인
  5. 기존 Base64 데이터가 있는 DB로 기동 → 마이그레이션 후 이미지 정상 표시 + 원본·저장 파일 바이트 동일성 확인
  6. 정상 생성 경로(관리자 생성 API) 회귀 없음 확인
- 회원 상세 API 응답에서 이관 성공 행의 `profileImageUrl`이 더 이상 `data:`로 시작하지 않음 확인(크기 초과 행은 `NONE`, **기존 WebP 레거시 행은 예외적으로 계속 `data:` 그대로 남음 — 완료 기준 예외로 명시**)
- 신규 다운로드 라우트 2종 보안 테스트(MANAGER `/{다른id}` 403, 익명 401)
- 동시 업로드 2건 경합 후 storageKey 1개만 참조, 고아 파일 없음 확인
- **공지 첨부파일의 실제 storageKey와 문자열이 완전히 같은 값을 프로필 이미지 값으로 대입한 오염 데이터로 다운로드/삭제 시도 시, 네임스페이스가 다른 실제 경로를 가리켜 안전하게 실패(404/스킵)하고 공지 파일이 훼손되지 않음을 확인**(정방향 — `load()/delete()` 양쪽 모두)
- **[v6 신규] 반대 방향: 오염된 `notice_attachment.storage_key`에 `"profile/..."` 형태의 값을 대입해 공지 다운로드/삭제를 시도해도 예약 네임스페이스 차단으로 안전하게 실패(404/스킵)하고 프로필 이미지 파일이 훼손되지 않음을 확인**(역방향 — 5차 리뷰에서 발견된 반대 방향 우회 검증)
- `LocalDiskFileStorage` 네임스페이스 오버로드 도입 후 기존 공지 첨부파일 업로드·다운로드·삭제 골든 패스 회귀 없음 확인(Playwright)

---

## 구현·검증 결과 (2026-08-10)

### 구현 파일 (실측)

계획서의 "구현 파일" 목록대로 전부 구현했다. `feat/profile-image-storage` 브랜치. 실제로 반영된 것 중 계획서 대비 추가 발견 사항 1건:

- **[구현 중 발견]** `admin-my-info.html`에 계획서가 명시한 `accept` 속성 외에도 (1) 업로드 도움말 텍스트("JPG, PNG, GIF, WEBP...")와 (2) 클라이언트 JS의 `allowedTypes` 배열·에러 메시지에도 WebP가 남아있었다 — 셋 다 제거. 클라이언트 JS가 WebP를 걸러주지 못하면 서버가 400을 반환하기 전까지 사용자가 "왜 실패하는지" 오해할 수 있어, 계획에 없었지만 이번 기능의 목적(WebP 화이트리스트 제외)에 직접 필요한 수정이라 판단해 함께 반영했다.

### 테스트 결과

`./gradlew test` 전체 통과 — **626개 테스트, 실패 0, 오류 0, 스킵 1**(Docker Desktop 기동 확인 후 재실행하여 확인 — 최초 실행 시 Docker가 꺼져 있어 Testcontainers 기반 18개가 환경 문제로 실패했었고, 이는 코드 문제가 아님을 확인 후 Docker를 기동해 재검증했다).

신규 테스트: `LocalDiskFileStorageTest`(네임스페이스 저장·정방향/역방향 격리·잘못된 네임스페이스 거부·미지원 구현체 `UnsupportedOperationException`), `ProfileImageUrlsTest`, `ProfileImageValidatorTest`(정상 png/gif 통과, webp/svg·초과 크기·총 픽셀 초과·애니메이션 GIF·포맷 불일치·파싱 불가 거부), `ProfileImageMigrationRunnerTest`(정상 이관·크기초과 벌크경로·재검증 스킵·화이트리스트 밖 스킵·손상 Base64 스킵·행 단위 격리), `MemberProfileImageInsertIntegrationTest`(Testcontainers — `@Builder.Default` 회귀 방지, 2차 리뷰에서 발견된 실제 버그 재현 확인), `AdminMemberServiceTest`·`AdminMemberControllerTest` 확장(업로드/교체/초기화/프리셋, 보안 매트릭스, 헤더 검증).

**테스트 작성 중 실제로 잡은 버그 1건**: `LocalDiskFileStorage.delete(String)`이 예약 네임스페이스 감지 시 `StorageFileNotFoundException`을 던지도록 초안 구현했는데, 이는 `delete()`의 기존 "찾을 수 없는 키는 항상 no-op(예외 없음)" 계약을 어겼다 — `LocalDiskFileStorageTest`의 역방향 격리 테스트가 실행 중 이를 직접 잡아냈다(설계·리뷰 단계에서는 발견되지 못함). `load()`는 예외를 던지고 `delete()`는 no-op하도록 분리해 수정.

### Playwright 실기 검증 (dev 프로파일, 실행 중인 앱)

**환경 메모**: 로컬 Windows 환경에서 Docker Desktop을 막 재기동한 직후라 Hyper-V의 동적 포트 제외 범위(`netsh interface ipv4 show excludedportrange`)가 8080·8090을 포함하도록 확장되어 있어 `bootRun`이 반복적으로 "Port already in use"로 실패했다(코드 문제 아님 — 실제로 그 포트를 점유한 프로세스는 없었고, Windows OS 레벨에서 바인딩 자체가 거부됨). 제외 범위 밖의 포트(9000)로 우회해 검증을 진행했다. 이 환경 이슈는 Docker Desktop을 재기동할 때마다 재발할 수 있음을 인지해둔다.

실제로 확인한 것:
1. **ADMIN 골든 패스**: 로그인 → 내 정보 → 실제 PNG(20×20) 업로드 → 탑바·내 정보 이미지가 `/admin/api/members/me/profile-image?v=<해시>`로 즉시 갱신되고 `naturalWidth/Height`가 원본과 일치(정상 렌더링, 깨지지 않음) 확인 → 기본 프리셋 선택 → 이미지 제거("프로필 이미지가 제거되었습니다" 토스트, 수정일 갱신) 전부 확인.
2. **MANAGER 탑바(이번 기능의 핵심 위험)**: 신규 MANAGER 계정 생성 → 이미지 업로드 → `/admin/notice/manage` 진입 → 탑바 이미지가 403 없이 `/admin/api/members/me/profile-image` 경로로 정상 렌더링됨을 **`naturalWidth/Height` 값으로 직접 확인**(단순히 200 응답이 아니라 실제로 이미지가 표시됨을 확인).
3. **인가 경계**: MANAGER가 `fetch('/admin/api/members/1/profile-image')` 시도 → 403 확인. ADMIN이 관리자 목록에서 MANAGER 계정 상세를 열람 → 그 MANAGER가 업로드한 이미지가 `/admin/api/members/{id}/profile-image`로 정상 렌더링됨을 확인(타 관리자 이미지 노출 — 승인된 현행 동작 유지).
4. **화이트리스트(webp) 서버 측 실거부**: 브라우저 `fetch()`로 클라이언트 JS를 우회해 `image/webp` 파일을 직접 PUT → 실제 서버가 `400 INVALID_REQUEST`("이미지 파일만 업로드할 수 있습니다.") 응답함을 실환경에서 확인(MockMvc 슬라이스 테스트가 아니라 실제 기동된 앱 기준).
5. 빈 DB가 아닌 **기존 dev DB**(이전 세션 데이터가 named volume에 남아있는 상태)로 정상 기동 + Flyway `validate` 통과 + V11 마이그레이션 정상 적용(`Migrating schema cms to version "11 - add member profile image kind"` 로그 확인) + `ProfileImageMigrationRunner`가 기동 시 정상 실행되어 요약 로그(`처리 대상=0, 이관=0, ...`)를 남김을 확인.

**Playwright로 직접 확인하지 못한 항목**(자동화 테스트로만 검증, 완료 기준 대비 명시):
- 실제 레거시 Base64 데이터가 있는 DB에서의 이관 골든 패스 — 이 dev DB에 마이그레이션 대상 레거시 행이 남아있지 않아(이전 세션에 이미 정리됨) Playwright로는 "0건 이관"만 확인했다. 이관 로직 자체(Base64 디코딩→저장→바이트 동일성)는 `ProfileImageMigrationRunnerTest`(단위)로 검증됨 — Testcontainers 기반 실 트랜잭션 커밋/롤백 통합 테스트(`ProfileImageMigrationRunnerIntegrationTest`, 동시 실행 시뮬레이션 포함)는 **계획에는 있었으나 시간 제약으로 이번에 작성하지 못했다** — 후속 과제로 남긴다.
- SVG·2MB 초과·애니메이션 GIF·과대 픽셀 이미지의 400 — `ProfileImageValidatorTest`(단위)로는 확인, 실제 브라우저 업로드 흐름으로는 webp 1건만 실기 확인.
- 동시 업로드 2건 경합(고아 파일 미잔존) — `AdminMemberServiceTest`(Mockito) 수준에서만 확인, 실제 동시 요청 통합 테스트는 미작성.
- 공지 첨부파일 업로드·다운로드·삭제 골든 패스의 Playwright 회귀 확인 — 이번 세션에서는 프로필 이미지 관련 화면만 실기 검증했다. `LocalDiskFileStorageTest`의 신규 테스트가 네임스페이스 없는 2-인자 오버로드(공지 첨부파일이 쓰는 경로)의 동작이 기존과 동일함을 자동화 테스트로 확인했다.

### 이슈

- Windows + Docker Desktop 재기동 조합에서 동적 포트 제외 범위가 8080대를 막아 로컬 `bootRun` 실기 검증이 지연됨(위 "환경 메모" 참조) — 코드 결함 아님, `docs/troubleshooting.md`에 기록할 만한 비자명한 로컬 개발 환경 이슈로 판단해 후속 기록 예정.
- `LocalDiskFileStorage.delete()`의 예약 네임스페이스 처리가 초안에서 계약 위반이었던 버그(위 "테스트 결과" 참조) — 구현 단계 자체 테스트로 잡혀 커밋 전 수정됨, 리뷰 라운드에서는 발견되지 못한 항목이라 기록해둔다.

### 후속 과제

- `ProfileImageMigrationRunnerIntegrationTest`(Testcontainers, 실 트랜잭션 커밋/롤백 + 동시 러너 실행 시뮬레이션) 및 실제 레거시 데이터가 있는 DB에서의 이관 Playwright 골든 패스 — 미완료, 별도 작업으로 추가 필요.
- `docs/troubleshooting.md`에 "Docker Desktop 재기동 후 Windows 동적 포트 제외 범위로 인한 bootRun 포트 바인딩 실패" 기록.
- 로드맵(`adversarial-review/project-direction-roadmap.md`) Top 3 ③ 완료 반영은 `/updateRoadmap`으로 별도 진행(이 문서 갱신만으로는 로드맵 파일이 자동 갱신되지 않음).

---

## 후속 작업 계획 — ProfileImageMigrationRunnerIntegrationTest(Testcontainers) 추가 (2026-08-11)

> 로드맵 근거: `adversarial-review/project-direction-roadmap.md` "후속 과제 — ③ 프로필 이미지 이관 완료 시 발견" **2번 항목만**(러너 Testcontainers 통합 테스트). 1번 항목(실 레거시 데이터가 있는 DB에서의 Playwright 골든 패스)은 이번 작업 범위 밖이며 여전히 미해소로 남는다 — 브라우저·다운로드 엔드포인트·`<img>` 렌더링까지 포함하는 별도 성격의 검증이라 이 테스트 추가로 해소되지 않는다(리뷰 1차 지적 5).
> 범위: 순수 테스트 추가. 프로덕션 코드·스키마·인가 정책 변경 없음.

### 개정 이력(이 후속 작업 전용)
- v1 (2026-08-11): 최초 작성.
- v2 (2026-08-11, 리뷰 1차 — codex, no-ship → 계획 반영):
  - **수용(높음, 동시성 테스트가 실경합을 보장하지 않음)**: v1의 시작 래치(`CountDownLatch`) 방식은 두 스레드가 `runner.run()`을 "비슷한 시각"에 호출하게 할 뿐, 둘 다 동일 ID를 조회하고 실제 락 획득 시점(`findByIdForUpdate`)에 동시에 도달함을 강제하지 않는다 — 순차 실행(A 완료 후 B가 빈 대상 목록만 확인)으로도 우연히 통과할 수 있어, 락을 제거해도 테스트가 계속 통과할 위험이 있었다. `CyclicBarrier`로 실제 락 획득 호출 직전에 두 스레드를 합류시키는 방식으로 쟁점 4를 재설계(아래).
  - **수용(높음, 인용한 기존 테스트가 다른 메서드를 검증함)**: v1의 대안 A 기각 사유가 "`AdminMemberUpdateConcurrencyIntegrationTest.guardQuery_actuallyAcquiresRowLocks()`가 `findByIdForUpdate`의 락을 이미 증명했다"였는데, 사실은 그 테스트가 검증하는 건 `findActiveAdminIdsForUpdate()`(네이티브 `FOR UPDATE` 스칼라 쿼리)이지 `findByIdForUpdate()`(JPQL + `@Lock(PESSIMISTIC_WRITE)`)가 아니다 — 구현 메커니즘 자체가 다른 별개 메서드를 인용한 사실관계 오류였다. 재설계된 `CyclicBarrier` 기반 동시성 테스트 자체가 "락이 깨지면 반드시 실패하는" 결정적 대조 실험이 되므로, 이 지적은 별도의 "락 존재 증명 전용" 테스트 신설이 아니라 쟁점 4 재설계로 해소한다.
  - **수용(높음, 워커 스레드 실패가 묻히면 거짓 양성 가능)**: v1은 `ExecutorService.submit()`의 반환값(`Future`) 처리를 명시하지 않았다 — 한 스레드가 조기 실패해도 다른 스레드만으로 겉보기 통과 조건(store 1회·최종 UPLOADED)이 우연히 성립할 수 있다. 양쪽 `Future.get(timeout)`을 반드시 호출해 예외·타임아웃을 테스트 스레드로 전파하도록 쟁점 4에 명시.
  - **수용(중간, 동시성 결함 발견 시 고아 파일 정리가 누락됨)**: v1의 정리 계획은 "이관 성공 시 DB에 남은 storageKey 하나만" 추적했는데, 정작 검출하려는 실패 모드(락이 깨져 `store()`가 2회 호출됨)가 발생하면 마지막에 커밋된 키 외의 첫 번째 키는 DB 어디에도 참조되지 않아 정리 대상에서 누락된다 — 테스트는 의도대로 실패하지만 고아 파일이 공유 스토리지 루트에 영구히 남는다. `FileStorage` spy가 반환하는 **모든** storageKey를 스레드 안전 컬렉션에 기록해 전부 정리하도록 쟁점 5 수정.
  - **수용(높음, 완료 기준이 로드맵 1번 항목까지 해소한다고 잘못 주장)**: 로드맵 후속 과제 ③의 두 항목(1: 실 레거시 데이터 Playwright 골든 패스, 2: 러너 Testcontainers 통합 테스트)은 서로 다른 성격의 작업인데, 이번 계획은 2번만 다루면서 "1·2번 해소"라고 주장했다. 상단 로드맵 근거 문구와 "완료 기준" 절을 2번만 해소하는 것으로 정정.
- v3 (2026-08-11, 리뷰 2차 — codex, no-ship → 계획 반영):
  - **수용(높음, `CyclicBarrier`는 락 제거 회귀를 결정적으로 검출하지 못함)**: v2의 barrier는 "두 스레드가 동시에 호출 직전에 도착함"만 보장하지, "두 SELECT가 모두 커밋 전 상태를 관측함"까지 보장하지 않는다 — barrier 통과 후에도 A가 완주(SELECT→이관→커밋)한 뒤에야 B가 SELECT하면(락이 없어도) 여전히 "store 1회·최종 UPLOADED"로 우연히 통과할 수 있다는 지적이 정확하다. `findByIdForUpdate`(JPQL `@Lock`)가 이 프로젝트에서 결정적으로 증명된 적이 없다는 v1 지적과 겹쳐, `NoticeConcurrencyIntegrationTest`의 "(A) 락 존재의 결정적 증명" 기법(`innodb_lock_wait_timeout` 세션 변수, 타이밍 아닌 필연적 타임아웃으로 증명)을 그대로 이식한 **별도 테스트**를 쟁점 4-A로 신설한다. 기존 barrier+spy 기반 테스트는 쟁점 4-B(보조 검증)로 격하해 유지한다.
  - **수용(높음, `Future.get()`만으로는 러너 내부에서 흡수되는 행 단위 예외를 못 잡음)**: `ProfileImageMigrationRunner.run()`이 각 행 처리 예외를 `catch (Exception e) { failed++; }`로 내부 흡수하므로, 한 스레드의 실제 `findByIdForUpdate` 위임 호출이 실패해도 `Future.get()`은 정상 반환하고 겉보기 통과 조건(store 1회·최종 UPLOADED)이 우연히 성립할 수 있다는 지적이 정확하다. `doAnswer`에서 실제 위임 호출마다 성공/예외를 스레드 안전 리스트에 기록해, **두 스레드 모두 실제 위임 호출까지 도달해 예외 없이 정상 완료했음**을 명시적으로 단언하도록 쟁점 4-B에 추가.
  - **수용(중간, storageKey 정리 전략이 쟁점 2와 모순)**: 쟁점 5가 "세 테스트 공통으로 `FileStorage` spy가 반환한 키를 수집"한다고 했지만, 정상 커밋 테스트(쟁점 2)는 스파이 없는 실제 싱글턴 러너를 쓴다고 명시해 애초에 수집할 spy가 없었다는 지적이 정확하다 — 시나리오별 정리 경로를 명확히 분리(아래 쟁점 5 수정).
  - **수용(중간, `runner.run()`이 테스트 소유가 아닌 잔존 레거시 행까지 처리한다는 가정이 숨어 있음)**: 공유 Testcontainers DB에 이전 테스트의 정리 실패로 남은 `LEGACY_INLINE` 행이 있으면 동시성 테스트의 barrier·계측이 의도한 행이 아닌 곳에서 작동할 수 있다는 지적이 정확하다. 동시성 테스트 시작 전 `findIdsByProfileImageKind(LEGACY_INLINE)`이 비어 있음을 선제 확인하는 fail-fast 검증을 쟁점 4-B에 추가(러너 자체는 수정하지 않음 — 순수 테스트 추가 범위 유지).
- v4 (2026-08-11, 리뷰 3차 — codex, no-ship → 계획 반영):
  - **수용(높음, 격리 검증이 4-B에만 적용되어 정상 커밋·롤백 테스트는 무방비)**: v3의 `isEmpty()` 선제 확인을 4-B에만 넣어, `runner.run()`을 직접 호출하는 정상 커밋(쟁점 2)·롤백(쟁점 3) 테스트에는 동일한 방어가 없었다는 지적이 정확하다. `runner.run()`을 호출하는 **모든** 테스트에 공통 적용되는 어서션으로 신설 쟁점 6에 통합.
  - **부분 수용/부분 반박(높음, `@SpringBootTest` 컨텍스트 기동 시 `CommandLineRunner` 자동 실행이 격리 설계에서 빠짐)**: `ProfileImageMigrationRunner`가 `@Profile` 제한 없는 `CommandLineRunner`라 컨텍스트 최초 기동 시 Spring Boot가 자동 실행한다는 사실 관계는 맞다. 다만 제안된 수정("테스트 컨텍스트에서 해당 러너의 자동 실행을 억제")은 **기각** — `CmsTestApplication`은 수십 개 Testcontainers 테스트 클래스가 공유하는 테스트 인프라라, 이를 건드리는 건 "순수 테스트 추가"라는 이번 작업 범위를 벗어나는 더 넓은 리스크다. 또한 이 컨텍스트를 공유하는 기존 테스트 중 `LEGACY_INLINE` 행을 DB에 남기는 클래스는 없어(이 신규 클래스가 유일), "컨텍스트 최초 기동 시점에 이미 레거시 행이 존재"하는 시나리오는 현재 실증되지 않는 가상의 위험이다. **대신 수용**: "실행 전 비어있음"이 아니라 "내 행 생성 직후 정확히 그 행 하나"로 대상 집합을 검증하는 공통 어서션(쟁점 6)을 도입해, 이 가상의 위험이 실제로 발생하더라도 조용한 오염 대신 즉시 테스트 실패로 드러나게 한다 — 지적이 겨냥한 실제 피해(소유권 밖 행의 조용한 변경)는 이 방식으로도 동일하게 방지된다.
  - **수용(중간, Spring Data repository spy의 `callRealMethod()`가 구현 의존적)**: 인터페이스 기반 Mockito spy가 Spring Data JPA 동적 프록시에 대해 `invocation.callRealMethod()`로 안전하게 위임된다는 계약이 문서화되어 있지 않다는 지적이 정확하다. `doAnswer` 안에서 스파이가 아닌 **원본 `memberRepository` 참조를 직접 호출**하도록 쟁점 4-B 수정(아래).
- v5 (2026-08-11, 리뷰 4차 — codex, no-ship → 계획 반영 + 사용자 결정):
  - **수용(높음, singleton 대상집합 어서션은 자동실행 억제와 동등한 안전성을 제공하지 않음) — 사용자 결정: 현재 수준에서 명시적 수용**: 지적이 정확하다 — 자동 실행이 타 행을 **성공적으로** 처리하면 그 행은 `LEGACY_INLINE` 목록에서 사라지므로 쟁점 6의 어서션이 통과해버려 오염을 못 잡는다. 다만 진짜 해결(러너 자동실행을 테스트 컨텍스트에서 억제하면서도 쟁점 2에는 여전히 진짜 싱글턴 빈이 필요)은 `ProfileImageMigrationRunner`의 `@Profile` 무제약(의도적 프로덕션 설계 결정)을 테스트만을 위해 조건부로 바꾸는 프로덕션 코드 변경이 필요해, "순수 테스트 추가" 범위를 넘어선다. 사용자에게 선택지를 제시해 **"현재 수준에서 명시적 수용"**으로 결정받았다 — 이 코드베이스에서 `LEGACY_INLINE` 행을 실제로 남기는 테스트는 이번에 추가하는 클래스가 유일해 현재 오염 위험은 0이며, 향후 다른 테스트가 그런 행을 남기게 되면(코드 리뷰로 걸러질 가능성이 높은 변경) 재평가한다. 방어 비용 대비 효과가 큰 부분(클래스 시작 시점 잔존 행 검출)만 `@BeforeAll`로 추가한다(아래 쟁점 6 수정) — 완전한 차단은 아니지만 "이전 테스트의 정리 실패"라는 가장 현실적인 오염 경로는 잡아낸다.
  - **수용(중간, `callRealMethod()` 의존성이 `findByIdForUpdate` 외 다른 repository 메서드에도 남음)**: 수동 생성 러너가 호출하는 `findIdsByProfileImageKind`·`resetIfOversizedLegacyImage`도 `findByIdForUpdate`와 동일하게 `doAnswer`에서 원본 `memberRepository`로 명시 위임하도록 쟁점 4-B 수정.
  - **수용(낮음, 4-A의 실패 경로 정리 계약 미명시)**: `NoticeConcurrencyIntegrationTest`의 원본과 동일하게 `release.countDown()`을 `finally`로 보장하고, holder 측도 `Future.get(timeout)`으로 예외를 전파하며, `executor.shutdownNow()` + `awaitTermination(timeout)`으로 정리하도록 쟁점 4-A에 명시.
- v6 (2026-08-11, 리뷰 5차 — codex, no-ship → 계획 반영, `/plan-review-loop` 5라운드 종료):
  - **수용(중간, 4-A가 "락 타임아웃"이 아닌 임의의 예외로도 통과할 수 있음)**: 원본 `NoticeConcurrencyIntegrationTest`를 그대로 이식하면서 `assertNotNull(thrown)`만 검사해, 세션 변수 복원 실패 등 무관한 예외도 "락 존재 증명"으로 오인될 수 있다는 지적이 정확하다. `thrown`이 `PessimisticLockingFailureException`(Spring Data JPA가 락 타임아웃을 이 예외 계층으로 변환) 또는 그 원인 체인에 MariaDB 오류 코드 1205(`ER_LOCK_WAIT_TIMEOUT`)가 있는지 명시적으로 검증하도록 쟁점 4-A에 추가. 세션 변수 복원(`finally`)에서 발생하는 예외는 원래 `thrown` 값을 덮어쓰지 않도록 별도 try-catch로 분리해 로그만 남긴다.
  - **수용(낮음, `@BeforeAll`의 인스턴스 필드 사용 시 생명주기 설정 누락)**: JUnit 5 기본 생명주기(`PER_METHOD`)에서 비정적 `@BeforeAll`이 인스턴스 필드(`@Autowired memberRepository`)를 쓰려면 `@TestInstance(Lifecycle.PER_CLASS)`가 필요하다는 지적이 정확하다 — 없으면 컴파일 오류. 신규 테스트 클래스에 `@TestInstance(TestInstance.Lifecycle.PER_CLASS)`를 명시하도록 쟁점 1에 추가.
  - **`/plan-review-loop` 5라운드(스킬 최대 라운드) 종료**: 리뷰 자신이 "두 보완을 계획에 명시하면 추가 구조 변경 없이 ship 가능"이라고 명시했고, 남은 지적 2건 모두 반영 완료. 사용자 확인 후 구현 착수로 진행한다.

### Context

`ProfileImageMigrationRunner`는 Mockito 기반 단위 테스트(`ProfileImageMigrationRunnerTest`)로 스킵·벌크초기화·행 단위 격리 경로만 검증되어 있다. 그 테스트 파일 자신의 주석("실제 트랜잭션 커밋/롤백 검증은 Testcontainers 통합 테스트가 별도로 담당한다")과 위 "후속 과제"가 명시하듯, 다음 3가지는 실 DB 없이는 증명할 수 없다:
1. 정상 이관이 **실제로 커밋**되어 재조회 시 DB에 반영되는가
2. 이관 트랜잭션이 (외부 사정으로) **롤백**되면 DB·파일 모두 원상태로 남는가 — `FileStorageTransactionSupport.deleteOnRollback`이 실제로 파일을 정리하는가
3. **동시에 같은 대상 행**을 두 실행 흐름이 처리해도 `findByIdForUpdate` 비관적 락 재검증으로 중복 이관(중복 저장·고아 파일)이 일어나지 않는가

### 핵심 설계 결정 (쟁점별)

#### 쟁점 1 — 테스트 파일 위치·베이스 클래스
- **결정**: `src/test/java/com/cms/admin/member/ProfileImageMigrationRunnerIntegrationTest.java`. `@SpringBootTest(classes = CmsTestApplication.class)` + `extends MariaDbContainerSupport`. 클래스에 **`@TestInstance(TestInstance.Lifecycle.PER_CLASS)`**를 명시한다(v6 추가) — 쟁점 6의 클래스 단위 `@BeforeAll`이 인스턴스 필드로 주입된 `memberRepository`를 사용하므로, JUnit 5 기본 생명주기(`PER_METHOD`)에서는 컴파일 오류가 난다.
- **왜**: 같은 패키지의 `MemberProfileImageInsertIntegrationTest`, 그리고 `NoticeAttachmentTransactionIntegrationTest`·`NoticeConcurrencyIntegrationTest`가 이미 이 조합으로 검증된 표준 패턴이다. 새 테스트 인프라가 필요 없다.

#### 쟁점 2 — 정상 커밋 검증 방법
- **결정**: `@Autowired ProfileImageMigrationRunner runner`(실제 싱글턴 빈)로, 대상 행 생성 직후 `assertOnlyMyRowIsTarget(id)`(쟁점 6) 확인 → `runner.run()` 호출 → `memberRepository.findById(id)` 재조회로 `kind=UPLOADED`·`profileImageContentType`·`profileImageUrl`(data URI 아님) 확인 → `fileStorage.load(key, "profile")`로 바이트가 원본과 정확히 일치하는지 왕복 확인.
- **왜 이 방식**: 러너를 직접 생성하지 않고 실제 컨텍스트가 배선한 싱글턴을 쓰는 게 배선 자체(생성자 인자 순서, `FileStorage`/`TransactionTemplate` 빈 해석)까지 함께 검증하는 더 강한 테스트다. 이 시나리오엔 스텁·스파이가 필요 없다.

#### 쟁점 3 — 롤백 검증 방법
- **결정**: `NoticeAttachmentTransactionIntegrationTest.upload_rollback_fileDeleted()`와 동일 기법. 대상 행 생성 직후 `assertOnlyMyRowIsTarget(id)`(쟁점 6) 확인 → 외부 `TransactionTemplate`(REQUIRED 전파 — 러너 내부의 개별 `transactionTemplate.execute()` 호출들이 같은 물리 트랜잭션에 합류)으로 `runner.run()` 전체를 감싸고, `status.setRollbackOnly()` 직전에 `entityManager.flush()` + 네이티브 쿼리로 **아직 커밋되지 않은** storageKey를 트랜잭션 내부에서 캡처한다. 롤백 후 (a) `memberRepository.findById()` 재조회 시 원래 `LEGACY_INLINE`·원본 data URI 그대로인지, (b) 캡처한 storageKey로 `fileStorage.load(key, "profile")` 시도 시 `StorageFileNotFoundException`(파일이 `deleteOnRollback`의 `afterCompletion` 콜백으로 정리됨)이 나는지 검증한다.
- **대안 A(기각) — 러너에 테스트 전용 실패 주입 지점 추가**: 프로덕션 코드에 테스트만을 위한 확장점(예: `migrateWithinTransaction`을 package-private으로 열거나 실패 훅 추가)을 넣는 방식. 순수 테스트 추가 작업의 범위를 벗어나는 무관한 프로덕션 변경이라 기각 — 이미 검증된 외부 트랜잭션 랩 기법으로 프로덕션 코드 변경 없이 동일한 결론을 얻을 수 있다.
- **대안 B(기각) — `FileStorage.store()`가 예외를 던지도록 Mockito 스텁**: 이건 "저장 자체가 실패하는 시나리오"를 검증하는 것이지 "실 트랜잭션 커밋/롤백"을 검증하는 게 아니다. 로드맵이 요구한 검증 대상과 어긋난다.

#### 쟁점 4 — 동시 실행 검증 방법 (v3 재설계 — (A) 결정적 증명 + (B) 러너 행동 보조검증으로 분리)

**4-A. `findByIdForUpdate`가 실제로 PESSIMISTIC_WRITE 행 잠금을 획득하는지 결정적으로 증명**
- **결정**: `NoticeConcurrencyIntegrationTest.lockQuery_actuallyAcquiresRowLock()`과 완전히 동일한 기법을 `MemberRepository.findByIdForUpdate`에 적용한다. 한 스레드가 `TransactionTemplate`으로 대상 행에 `findByIdForUpdate`를 호출해 락을 획득한 채 대기(`CountDownLatch`로 락 보유 신호 후 해제 래치 대기)하고, 메인 스레드는 같은 행에 대해 `SET SESSION innodb_lock_wait_timeout = 1`로 짧게 줄인 뒤 같은 `findByIdForUpdate`를 시도한다 — **타이밍이 아니라 반드시 타임아웃(예외)이 나는지**로 락의 존재를 결정적으로 증명한다(원래 세션 변수 값은 `finally`에서 복원).
- **실패 경로 정리(v5 명시)**: 원본 `NoticeConcurrencyIntegrationTest`와 동일하게 — 락 보유 스레드는 `release.countDown()`을 **외부 `finally`**에서 반드시 호출해 assertion 실패 시에도 무기한 대기하지 않는다. 메인 스레드는 holder의 `Future.get(15, TimeUnit.SECONDS)`로 보유 스레드의 예외도 전파받는다. `executor.shutdownNow()` + `awaitTermination(15, TimeUnit.SECONDS)`로 정리(리뷰 4차 지적 3).
- **예외 원인 특정(v6 추가)**: `assertNotNull(thrown)`이 아니라 `thrown`(또는 그 원인 체인)이 `org.springframework.dao.PessimisticLockingFailureException`이거나 메시지/원인에 MariaDB 오류 코드 1205(`ER_LOCK_WAIT_TIMEOUT`)를 포함하는지 명시적으로 검증한다 — 세션 변수 설정·복원 실패나 무관한 매핑 오류가 "락 존재 증명"으로 오인되는 거짓 양성을 차단한다. 세션 변수 복원(`finally`)에서 발생하는 예외는 별도 try-catch로 격리해 로그만 남기고, 원래 캡처한 `thrown` 값을 덮어쓰지 않는다.
- **왜**: v2의 `CyclicBarrier`는 확률적 정황 증거일 뿐 결정적 증명이 아니라는 리뷰 2차 지적이 정확하다. `findByIdForUpdate`(JPQL `@Lock`)는 이 프로젝트에서 별도로 증명된 적이 없으므로(v1이 잘못 인용했던 `AdminMemberUpdateConcurrencyIntegrationTest`는 다른 메서드를 검증), 이관 러너 고유 로직과 무관하게 이 메서드 자체의 락 계약을 독립적으로 결정적 증명한다.

**4-B. 러너가 그 락을 실제로 활용해 중복 이관을 방지하는지 확인 (보조 검증)**
- **결정**: 4-A가 락의 존재를 이미 결정적으로 증명했으므로, 이 테스트의 역할은 "러너 로직이 그 락을 실제로 활용해 재검증 스킵을 올바르게 수행하는가"를 확인하는 보조 검증으로 재규정한다. v2의 `CyclicBarrier` + spy 설계를 유지하되 다음을 추가한다:
  - **대상 집합 격리(쟁점 6 공통 어서션 적용)**: `runner.run()` 호출 직전 `assertOnlyMyRowIsTarget(id)`로 대상 집합이 정확히 내 행 하나인지 확인(상세는 쟁점 6).
  - **위임 호출 성공 계측, repository spy 전체에 명시 위임(v4·v5 수정)**: `MemberRepository`를 `Mockito.spy()`로 감싸되, 러너가 실제로 호출하는 **세 메서드 전부**(`findIdsByProfileImageKind`·`resetIfOversizedLegacyImage`·`findByIdForUpdate`)의 `doAnswer`가 `invocation.callRealMethod()`가 아니라 **스파이 대상이 아닌 원본 `memberRepository`(`@Autowired`로 별도 보관한 참조)를 명시적으로 호출**하도록 통일한다(`memberRepository.findIdsByProfileImageKind(...)` 등) — Mockito가 인터페이스 스파이를 Spring Data 동적 프록시에 안전하게 위임한다는 보장이 문서화되어 있지 않다는 리뷰 3·4차 지적을 반영해, 계측 대상(스파이)과 실제 위임 대상(원본 빈)을 세 메서드 모두에서 명확히 분리한다. `findByIdForUpdate`의 `doAnswer`는 barrier 통과 후 이 호출의 성공/예외를 스레드 안전 리스트에 기록하고, 예외는 다시 던진다. 테스트 마지막에 **정확히 2개의 기록이 있고 그중 예외가 없음**을 단언해, 러너 내부의 행 단위 예외 흡수(`catch (Exception e) { failed++; }`)로 인해 한쪽 스레드의 위임 호출 실패가 조용히 묻히는 거짓 양성을 차단한다(리뷰 2차 지적 2).
  - `FileStorage`도 `Mockito.spy()`로 감싸 `store()`의 매 호출마다 반환값을 스레드 안전 컬렉션에 기록(쟁점 5-C와 연동).
  - **양쪽 `Future`에 대해 반드시 `.get(20, TimeUnit.SECONDS)`를 호출**해 워커 스레드의 예외·타임아웃을 테스트 스레드로 전파(리뷰 1차 지적 3). `finally`에서 `executor.shutdownNow()` + `awaitTermination`으로 정리.
  - 두 스레드가 공유하는 `ProfileImageMigrationRunner`는 스파이가 적용된 `repoSpy`·`fileStorageSpy`를 생성자에 직접 주입해 수동 생성(`AdminBootstrapConcurrencyIntegrationTest` 패턴).
  - 검증: 위임 호출 계측(2회, 예외 없음) + `verify(fileStorageSpy, times(1)).store(any(), any(), eq("profile"))` + 최종 DB 상태(`kind=UPLOADED`) + 저장된 바이트 일치.

- **대안(기각) — 저장 디렉터리의 파일 개수를 이관 전후로 세어 "고아 파일 없음"을 검증**: 공유 테스트 스토리지 루트(`./data/attachments/profile`)는 같은 JVM에서 실행되는 다른 통합 테스트와 경로를 공유해 개수 기반 검증이 실행 환경 변화에 취약하다. Mockito spy의 정밀한 호출 횟수·반환값 캡처가 더 견고하다. (v1·v2에서 이미 기각, 유지)

#### 쟁점 6 — `runner.run()`을 호출하는 모든 테스트의 대상 집합을 내 행으로 한정 (v4 신설, v5 잔여 위험 명시)
- **결정**: `runner.run()`을 실제로 실행하는 세 테스트(쟁점 2 정상 커밋·쟁점 3 롤백·쟁점 4-B 보조 검증) 전부에서 공통 헬퍼 `assertOnlyMyRowIsTarget(Long id)`를 대상 행 생성 직후·`runner.run()` 호출 직전에 호출한다. 이 헬퍼는 `memberRepository.findIdsByProfileImageKind(LEGACY_INLINE)`이 **정확히 `List.of(id)`**인지 단언한다(`isEmpty()` 단독 검사가 아니라 "생성 직후 정확히 하나"로 검사 — 리뷰 3차 지적 1). 추가로 **클래스 단위 `@BeforeAll`**에서 이 테스트 클래스의 어떤 메서드도 실행되기 전에 `findIdsByProfileImageKind(LEGACY_INLINE)`이 비어 있는지 한 번 확인한다 — 이전 테스트 메서드의 정리 실패로 남은 잔존 행을 클래스 시작 시점에 즉시 검출한다.
- **왜**: "실행 전 비어있음"만 확인하면 그 사이(내 행 생성 시점)에 다른 원인으로 잔존 행이 섞여 있었는지까지는 못 잡지만, "생성 직후 정확히 내 행 하나"는 이 클래스의 이전 테스트 메서드가 정리에 실패해 남긴 잔존 행을 실행 직전 시점 기준으로 정확히 잡아낸다. 오염이 있으면 `runner.run()`을 호출하기도 전에 테스트가 실패해, 소유권 밖 행을 조용히 함께 처리하는 사고를 이 경로에 한해 차단한다.
- **잔여 위험(v5 — 리뷰 4차 지적 1, 사용자 결정: 현재 수준에서 명시적 수용)**: 이 어서션은 `@SpringBootTest` 컨텍스트 **최초 기동 시점**의 `ProfileImageMigrationRunner` 자동 실행(`CommandLineRunner`, `@Profile` 제한 없음)을 막지 못한다 — 만약 그 시점에 타 테스트 소유의 `LEGACY_INLINE` 행이 이미 존재하고 자동 실행이 그 행을 **성공적으로** `UPLOADED`로 이관해버리면, 그 행은 이후 `findIdsByProfileImageKind(LEGACY_INLINE)` 조회에서 사라지므로 위 어서션들은 (내 행만 정확히 잡아) 통과하지만 그 타 행의 DB 변경·파일 생성은 아무도 정리하지 않는다. 진짜 해결(자동 실행을 테스트 컨텍스트에서 억제)은 `ProfileImageMigrationRunner`의 `@Profile` 무제약(의도적 프로덕션 설계)을 테스트만을 위해 조건부로 바꾸는 프로덕션 코드 변경이 필요해 "순수 테스트 추가"라는 이번 작업 범위를 벗어난다. **사용자 결정**: 이 코드베이스에서 `LEGACY_INLINE` 행을 실제로 남기는 테스트는 이번에 추가하는 클래스가 유일해 현재 오염 위험은 0이므로, 현재 수준(위 어서션 + `@BeforeAll`)에서 명시적으로 수용한다. 향후 다른 테스트가 그런 행을 남기게 되면 재평가한다.
- **적용 범위**: 쟁점 4-A(락 결정적 증명)는 `runner.run()`을 호출하지 않고 `findByIdForUpdate`를 직접 호출하는 저수준 테스트라 이 어서션이 필요 없다(대상 집합 조회 자체가 관여하지 않음).

#### 쟁점 5 — 테스트 데이터 격리·정리 (v3 — 시나리오별 정리 경로 명확화)
- **결정**: 각 테스트가 생성한 memberId는 그대로 리스트에 기록해 `@AfterEach`에서 회원 행을 삭제한다. storageKey 정리는 **시나리오별로 경로를 분리**해 공통 `List<String> createdStorageKeys`(스레드 안전 — `Collections.synchronizedList`)에 모은다:
  - **5-A(쟁점 2, 정상 커밋)**: `runner.run()` 후 DB 재조회로 얻은 storageKey를 직접 추가.
  - **5-B(쟁점 3, 롤백)**: 트랜잭션 내부에서 캡처한 storageKey를 직접 추가(트랜잭션이 롤백돼도 `deleteOnRollback`이 실패하는 극단적 케이스까지 대비하는 안전망).
  - **5-C(쟁점 4-B, 동시 실행)**: `FileStorage` spy의 `store()` 호출마다 반환값을 직접 이 리스트에 추가하도록 계측(둘 이상 저장돼도 전부 잡힘).
  - `@AfterEach`에서 리스트의 모든 키를 `fileStorage.delete(key, "profile")`로 정리(예외 흡수).
- **왜(v3 수정 사유)**: v2는 "세 테스트 공통으로 spy가 반환한 키를 수집"한다고 서술했지만, 쟁점 2(정상 커밋)는 스파이 없는 실제 싱글턴 러너를 쓴다고 명시해 애초에 수집할 spy가 없는 모순이 있었다(리뷰 2차 지적 3). 시나리오별로 실제 구현 가능한 경로를 명시해 모순을 제거했다.
- **왜(v1 근거 유지)**: 이 테스트 클래스가 공유 Testcontainers DB에 `LEGACY_INLINE` 행을 실제로 쓰는 유일한 클래스다. 정리하지 않으면 캐시된 ApplicationContext를 공유하는 다른 테스트 클래스나 재실행 시 `findIdsByProfileImageKind` 대상에 잔존해 노이즈가 된다(특히 쟁점 3의 롤백 테스트는 의도적으로 `LEGACY_INLINE` 상태를 남기므로, 정리하지 않으면 그 행이 영구히 DB에 남는다).

### 변경 파일

- 신규: `src/test/java/com/cms/admin/member/ProfileImageMigrationRunnerIntegrationTest.java` (테스트 4개 — 정상 커밋(쟁점2)·롤백(쟁점3)·락 결정적 증명(쟁점4-A)·러너 중복방지 보조검증(쟁점4-B) + 헬퍼)
- 프로덕션 코드·스키마·인가 정책·`build.gradle` 변경 없음(Mockito는 `spring-boot-starter-test`에 이미 포함).

### 완료 기준

- `./gradlew test --tests "com.cms.admin.member.ProfileImageMigrationRunnerIntegrationTest"` 4개 테스트 전부 통과
- `./gradlew test` 전체 통과(회귀 없음)
- 로드맵 "후속 과제 — ③" **2번 항목만**(러너 Testcontainers 통합 테스트) 이 테스트로 해소했다고 로드맵에 반영(별도 `/updateRoadmap`). **1번 항목(실 레거시 데이터가 있는 DB에서의 Playwright 골든 패스)은 이번 작업으로 해소되지 않으며 계속 미해소로 남는다** — 별도 작업(실 데이터 시딩 + 브라우저 검증) 필요.

### 착수 게이트

없음 — 순수 테스트 추가, 스키마·인가 정책·신규 의존성 변경 전무.

### 구현·검증 결과 (2026-08-11)

**구현 파일**: `src/test/java/com/cms/admin/member/ProfileImageMigrationRunnerIntegrationTest.java`(신규, 테스트 4개 + 헬퍼) 하나. 계획서(v6) 대비 실제로 반영된 것 중 추가 발견 사항 1건:

- **[구현 중 발견]** 쟁점 4-B에서 `MemberRepository`를 `Mockito.spy()`로 감싸려 하자 `UnfinishedStubbingException`이 실제로 재현됐다 — Spring Data JPA 리포지토리는 런타임에 동적 프록시라, 이를 다시 서브클래싱하는 스파이 생성이 Mockito의 스터빙 상태 추적과 충돌한다(리뷰 3·4라운드가 "문서화된 계약이 아니다"라고 경고했던 위험이 실측으로 확인됨). `spy()` 대신 순수 `mock(MemberRepository.class)` + 세 메서드 전부 원본 빈으로 명시 위임하는 방식으로 전환했다(계획서 자체의 방향과 동일선상 — "계측 대상과 실제 위임 대상 분리"를 스파이가 아닌 mock으로 달성). `FileStorage`(`LocalDiskFileStorage`, 일반 구체 클래스)의 `Mockito.spy()` + `callRealMethod()`는 계획대로 문제없이 동작했다. `docs/troubleshooting.md`에 기록.

### 테스트 결과

`./gradlew test --tests "com.cms.admin.member.ProfileImageMigrationRunnerIntegrationTest"` 4개 전부 통과. 동시 실행 테스트 로그에서 두 스레드 중 정확히 하나만 `이관=1`, 다른 하나는 `스킵=1`로 처리됐음을 실측 확인(락이 실제로 중복 이관을 막음). `./gradlew test` 전체 630개 통과, 회귀 없음.

### Playwright 실기 검증

**해당 없음** — 이번 작업은 순수 백엔드 테스트 코드 추가로, 화면·API 응답·사용자 흐름에 어떤 변경도 없다(신규 파일 1개, 프로덕션 코드 무변경). 검증할 UI 골든 패스가 존재하지 않는다.

### 이슈

- `Mockito.spy()`가 Spring Data JPA 리포지토리 프록시에서 실패하는 문제(위 "구현 중 발견" 참조) — `docs/troubleshooting.md` "애플리케이션 / 런타임" 카테고리에 기록 완료.

### 후속 과제

- 로드맵 "후속 과제 — ③" 1번 항목(실 레거시 데이터가 있는 DB에서의 Playwright 골든 패스)은 이번 작업 범위 밖으로 여전히 미해소 — 별도 작업 필요.
- 로드맵(`adversarial-review/project-direction-roadmap.md`) 반영은 `/updateRoadmap`으로 별도 진행.
