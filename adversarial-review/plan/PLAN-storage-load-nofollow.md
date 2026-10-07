# PLAN — `LocalDiskFileStorage` 읽기·네임스페이스 경로의 심볼릭 링크 탈출 차단

> 상태: ✅ 구현·검증 완료 (2026-10-07, 커밋·PR 전) — v3 승인(적대적 리뷰 3라운드 ship) → 구현 → 테스트(Windows 전체 + Linux 컨테이너 링크 테스트) → dev Docker 스택 실기 검증. 결과는 문서 끝 "구현·검증 결과" 참조
> 출처: 로드맵 "후속 과제 — ② 공개 첨부 다운로드 완료 시 기록"의 잔여 한계(`PLAN-public-notice-attachment.md` v6 리뷰 4 → 후속 "기존 `load()`에도 최종 링크 거부를 적용할지 검토"). `/suggestRoadmap` 2026-10-07 선택.
> 유형: security(심층 방어) · 브랜치 `security/storage-load-nofollow` · **스키마 변경 없음 · 인가 정책 변경 없음 · 신규 의존성 없음**

## 개정 이력

- v1 (2026-10-07): 최초 작성.
- v2 변경(1라운드 codex): (1) **수용 — 원자성 서술 정정**: 쟁점 1의 "검사와 열기가 원자적"은 최종 파일 링크 판정에만 맞다(`O_NOFOLLOW`는 마지막 경로 요소에만 적용). 부모 디렉터리 실경로 검증(`toRealPath`)과 `openChannel(target)` 사이에 부모를 외부 링크로 바꾸는 경합은 남는다 → 보장 범위 문구를 고치고 리스크 표에 잔여 위험으로 추가했다. 디렉터리 핸들 기준 열기(`SecureDirectoryStream`)는 기각: 저장 볼륨 쓰기 권한과 경합 타이밍이 함께 필요한 경로이고, `open()`도 같은 수준이다. 이 정도 위협에 열기 구조 전체를 바꾸는 것은 과하다. (2) **수용 — 범위 확장(사용자 결정 2026-10-07 "이번 범위에 포함")**: `verifyWithinRoot()`가 네임스페이스 루트(`root/profile`) **자체의 실경로**를 기준으로 삼기 때문에 `root/profile`이 외부 디렉터리 링크이면 경합 없이 기준 루트가 외부로 옮겨간다. 그러면 `store/load/delete(key, "profile")` 모두 루트 밖을 다룬다. 특히 `delete`는 루트 밖 파일을 지운다. 이것을 쟁점 6으로 신설해 기준 루트를 "설정 루트의 실경로 + 네임스페이스 이름(문자 그대로)"으로 바꾸고, 테스트 4건을 추가했다.
- v3 변경(2라운드 codex): **수용 — 기준 루트 실경로 계산 정정**: v2 코드는 설정 루트를 `normalize()`한 **뒤** `toRealPath()`를 호출했다. 그래서 `/srv/link/../attachments`처럼 링크 뒤에 `..`가 오는 설정에서는 기준이 원래 실경로(`/mnt/volume/attachments`)가 아닌 `/srv/attachments`로 바뀐다. 지금은 거부되는 경로가 허용되는 퇴행이다(Java `Path.normalize()` 문서가 링크 앞뒤 `..` 정규화의 위험을 명시). 실경로는 정규화 전 원본 설정값으로 구하고, `normalize()`는 상대 경로 계산에만 쓰도록 고쳤다. 회귀 테스트 8번을 추가했다. 기각: "그런 설정을 정상 지원하도록 대상 경로 구성도 실경로 기준으로 바꾸라"는 부분. 지금 동작이 이미 전부 거부하는 fail-closed이고, 이번 목적(링크 탈출 차단)과 무관한 기능 추가다.

## 1. Context

`LocalDiskFileStorage`의 읽기 경로는 두 개다.

- `open()`(무인증 공개 첨부 다운로드, 2026-09-29 #63): 부모 디렉터리 실경로 검증(`resolveVerifiedTarget`) 후 `openChannel()` = `Files.newByteChannel(target, READ, NOFOLLOW_LINKS)`로 연다 → **최종 파일 자체가 링크이면 거부**.
- `load()`·`load(key, namespace)`(관리자 첨부 다운로드·프로필 이미지 다운로드): 같은 부모 검증 후 `Files.readAllBytes(target)` → **최종 파일이 링크이면 그대로 따라가 루트 밖 파일을 읽는다.**

`FileStorage.open()` Javadoc은 "구현체는 최종 파일 자체가 링크인 경우도 거부해야 한다(`IllegalStateException`)"고 계약하지만 `load()`에는 이 계약이 없다. 두 읽기 경로의 검증 강도가 다른 것을 `PLAN-public-notice-attachment.md` 리스크 표가 "의도된 차이(범위 밖, 후속)"로 남겼고, 이 계획이 그 후속이다.

### 위협 모델 (과장하지 않기)

storageKey는 항상 서버가 `UUID`로 만들고 파일은 항상 `CREATE_NEW`로 쓴 일반 파일이다. 저장 루트 안에 링크가 생기려면 **저장 볼륨에 대한 쓰기 권한**(운영자 실수, 다른 프로세스·컨테이너 공유, 오염된 백업 복구 등)이 먼저 필요하다. 즉 이 작업은 원격 공격 경로를 막는 것이 아니라 "저장 볼륨이 오염돼도 앱이 루트 밖 파일(`/etc/passwd`, 시크릿 마운트 등)을 응답으로 내보내지 않는다"는 **심층 방어**이며, 부모 디렉터리 링크는 이미 `toRealPath` 검증으로 막고 있으니 남은 마지막 구멍을 `open()`과 같은 수준으로 닫는 것이다.

### 정찰에서 확인한 사실 (2026-10-07)

| 항목 | 확인 결과 | 근거 |
|---|---|---|
| `load()` 구현 | `loadUnder()` → `resolveVerifiedTarget()`(부모 실경로 검증) → `Files.readAllBytes(target)`. `NoSuchFileException`→`StorageFileNotFoundException`, 그 외 `IOException`→`IllegalStateException("첨부파일을 읽을 수 없습니다")` | `LocalDiskFileStorage.java:134-142` |
| `open()` 구현 | 같은 사전 검증 후 `openChannel(target)`(패키지 접근 이음새, `NOFOLLOW_LINKS`) | `LocalDiskFileStorage.java:160-181` |
| `load()` 호출부(운영 코드) | ① `AdminMemberService.loadProfileImageContent()`(`load(key, "profile")`) ② `NoticeAttachmentService.download()`(`load(key)`). 둘 다 `StorageFileNotFoundException`만 `ResourceNotFoundException`(404)으로 바꾸고 나머지는 전파(500) | `AdminMemberService.java:428`, `NoticeAttachmentService.java:127` |
| 프로필 이관 러너 | `ProfileImageMigrationRunner`는 `store()`만 쓰고 `load()`를 쓰지 않는다 | `grep "\.load(" src/main/java` |
| 테스트 파급 | 서비스 테스트(`AdminMemberServiceTest`·`NoticeAttachmentServiceTest`·`PublicNoticeServiceTest`)는 `FileStorage`를 mock으로 둔다. 실제 `LocalDiskFileStorage`를 쓰는 통합 테스트(`NoticeAttachmentTransactionIntegrationTest`·`ProfileImageMigrationRunnerIntegrationTest`·`PublicNoticeAttachmentIntegrationTest`·`PublicAttachmentStreamingServerTest`·`AdminPermissionMatrixIntegrationTest`)는 `store()`로 만든 일반 파일만 읽는다 → 동작 변화 없음. 직접 영향은 `LocalDiskFileStorageTest`뿐 | `grep -l FileStorage src/test/java` |
| 기존 링크 테스트 | `load_symlinkEscape_rejected`(부모 링크)·`open_parentSymlinkEscape_rejected`·`open_finalFileSymlink_rejected` — 링크 생성 실패 시 `Assumptions`로 건너뛴다. Windows 로컬(권한 없음)에서는 건너뛰고 Linux CI에서만 실행된다(Java 21 전환 때 전체 테스트 "스킵 3"이 이것) | `LocalDiskFileStorageTest.java:90-110, 290-328` |
| 저장 데이터 내 링크 | 로컬 dev 저장소 `data/attachments`: 파일 1개, reparse point 0개. prod는 아직 실배포 전 | PowerShell `Get-ChildItem -Recurse -Force` |
| 백업/복구 | `prod-backup.sh`/`prod-restore.sh`는 `tar czf`/`tar xzf`로 볼륨을 그대로 왕복한다 — 아카이브에 링크가 들어 있으면 링크로 복원된다(정상 백업에는 일반 파일만 있다) | `scripts/prod-backup.sh:114`, `scripts/prod-restore.sh:185` |
| 현재 동작(최종 링크) | 루트 밖을 가리키는 링크 → 내용을 읽어 200 응답. 대상 없는(dangling) 링크 → `NoSuchFileException` → 404 | 코드 판독 |

## 2. 핵심 쟁점과 결정

### 쟁점 1 — `load()`가 최종 링크를 거부하는 방식

| 선택지 | 장점 | 단점 |
|---|---|---|
| **A. `open()`과 같은 `openChannel(target)`으로 채널을 열고 그 채널에서 전량 읽기** | 링크 거부 정책이 `openChannel()` 한 곳에만 존재 — 두 읽기 경로가 앞으로도 같은 열기 규칙을 공유. 검사와 열기가 원자적(같은 `open` 시스템 호출의 `O_NOFOLLOW`) | `Files.readAllBytes`의 크기 기반 사전 할당 대신 `InputStream.readAllBytes()` 버퍼 증가 방식 — 파일당 10MB 상한이라 무시 가능 |
| B. `Files.isSymbolicLink(target)` 사전 검사 후 `readAllBytes` | 변경 1줄 | 검사와 읽기 사이 TOCTOU(검사 후 링크로 교체 가능), 정책이 두 곳으로 갈라짐 |
| C. `Files.newInputStream(target, NOFOLLOW_LINKS).readAllBytes()` | 짧음 | A와 효과는 같지만 `open()`과 다른 열기 지점이 하나 더 생김(테스트 이음새 `openChannel` 미공유) |
| D. `load()`를 `openUnder()` 위임으로 구현(`try (StoredFileStream s = openUnder(..)) { return s.inputStream().readAllBytes(); }`) | 코드 최소, 사전 검증까지 완전 공유 | 불필요한 `channel.size()` 호출, 오류 메시지가 "열 수 없습니다"로 바뀌고 읽기 중 `IOException` 매핑을 따로 둬야 해 오히려 구조가 꼬임 |

**결정: A.** 이유 — 거부 정책의 단일 출처(`openChannel`)를 유지하는 것이 이 작업의 본질("두 경로의 검증 강도를 같게")이고, B는 최종 파일 판정 자체에 TOCTOU가 생겨 보안 수정으로서 불완전하다. **(v2 정정)** A가 원자적인 범위는 "최종 파일이 링크인가"의 판정뿐이다. 부모 디렉터리 실경로 검증(`resolveVerifiedTarget`)과 실제 열기 사이에 부모를 외부 링크로 바꾸는 경합은 `open()`과 똑같이 남는다(리스크 표). 구현 형태:

```java
private byte[] loadUnder(Path effectiveRoot, String storageKey) {
    try (SeekableByteChannel channel = openChannel(resolveVerifiedTarget(effectiveRoot, storageKey));
         InputStream in = Channels.newInputStream(channel)) {
        return in.readAllBytes();
    } catch (NoSuchFileException e) {
        throw new StorageFileNotFoundException("첨부파일을 찾을 수 없습니다: " + storageKey, e);
    } catch (IOException e) {
        throw new IllegalStateException("첨부파일을 읽을 수 없습니다: " + storageKey, e);
    }
}
```

(try-with-resources가 채널·스트림을 항상 닫으므로 `open()`의 `closeQuietly` 같은 수동 정리가 필요 없다.)

### 쟁점 2 — 링크 거부 시 응답 계약(500 vs 404)

| 선택지 | 장점 | 단점 |
|---|---|---|
| **A. `IllegalStateException` → 500(`open()`·`FileStorage.open()` Javadoc과 동일)** | 두 읽기 경로 계약 일치. 링크는 "저장소 무결성 이상"이라 서버 오류로 드러나야 운영자가 알 수 있다(서버 오류 로깅 #39 경로) | 대상 없는 링크가 기존 404 → 500으로 바뀜 |
| B. `StorageFileNotFoundException` → 404 | 사용자에게 존재 노출 최소 | 이상 징후가 "파일 없음"에 묻혀 로그로 드러나지 않음, `open()`과 계약 불일치 |

**결정: A.** 이유 — `open()`이 이미 같은 판단(fail-closed 500)을 했고 인터페이스 Javadoc에 계약으로 적혀 있다. 이 작업은 `load()`를 그 계약에 맞추는 것이므로 계약을 새로 정하지 않는다. 관리자 경로(인증 필요)라 500 응답 자체의 노출 영향도 없다. 대상 없는 링크의 404→500 변화는 정상 데이터에서 발생할 수 없는 상태라 수용한다.

> OS별 동작: Linux(운영 컨테이너)는 `O_NOFOLLOW` → `ELOOP` → `FileSystemException`. Windows JDK는 `FILE_FLAG_OPEN_REPARSE_POINT`로 연 뒤 링크면 "File is symbolic link" 예외를 낸다. 둘 다 `NoSuchFileException`이 아닌 `IOException`이라 결정 A의 `IllegalStateException`으로 귀결된다(`open()`도 같은 근거로 동작 중).

### 쟁점 3 — 범위

- **포함**: `load(storageKey)`·`load(storageKey, namespace)`(둘 다 `loadUnder` 공유), `FileStorage.load()` Javadoc에 링크 거부 계약 추가, 클래스 Javadoc·`openUnder` 주석 중 "load는 부모만 검증" 취지 문장 정정.
- **제외 — `delete()`**: `Files.deleteIfExists(link)`는 링크 자체만 지우고 대상은 건드리지 않는다(POSIX `unlink` 의미) → 루트 밖 파일 손상 경로가 없다. 오히려 오염된 링크를 정리하는 수단이 되므로 바꾸지 않는다.
- **제외 — `store()`**: `CREATE_NEW`는 대상 경로에 링크(대상 없는 링크 포함)가 있으면 "이미 존재"로 실패하고, 키는 매번 새 UUID라 해당 없음.
- **제외 — 하드 링크**: 일반 파일과 구분할 수 없고(`NOFOLLOW_LINKS` 대상 아님), 같은 파일시스템 안에서만 만들 수 있으며 저장 볼륨 쓰기 권한이 전제다. `open()`도 같은 한계 — 계획서 "후속"에 기록만 한다.
- **제외 — 기존 데이터 정리 도구**: 정상 경로는 링크를 만들지 않고 로컬 실측 0건, prod 미배포라 일회성 점검 명령(`find "$APP_FILE_STORAGE_ROOT" -type l`)을 문서에 남기는 것으로 충분하다(과설계 회피).

### 쟁점 6 — 네임스페이스 루트 자체가 링크인 경우 (v2 신설, 사용자 결정으로 범위 포함)

**문제**: `verifyWithinRoot(effectiveRoot, realParent)`는 `effectiveRoot.toRealPath()`를 기준 루트로 쓴다. 네임스페이스 API의 `effectiveRoot`는 `root/profile`이므로, 이 디렉터리가 `/outside` 링크이면 기준 루트도 `/outside`가 되어 `/outside/<key>` 아래 경로가 전부 "루트 안"으로 판정된다. 경합 없이 성립한다. 영향 범위는 다음과 같다.
- `load(key, "profile")`: 루트 밖 파일을 읽는다(관리자 프로필 이미지 다운로드).
- `store(.., "profile")`: 루트 밖에 쓴다.
- `delete(key, "profile")`: **루트 밖 파일을 지운다**(`deleteAfterCommit`·`deleteOnRollback`이 호출).

| 선택지 | 장점 | 단점 |
|---|---|---|
| **A. 기준 루트를 "설정 루트(`properties.getRoot()`)의 실경로 + 네임스페이스 상대 경로(문자 그대로)"로 계산** | `verifyWithinRoot` 한 곳만 바뀌고 store·load·open·delete 모든 경로에 적용된다. 네임스페이스 디렉터리가 아직 없을 때의 동작(load→404, delete→no-op)이 그대로다(네임스페이스 루트의 실경로를 구하지 않으므로) | 설정 루트와 `effectiveRoot`의 상대 경로 계산(`relativize`)이 생긴다. 두 경로 모두 `normalize()`해야 정확하다 |
| B. `resolveNamespaceRoot()`에서 `Files.isSymbolicLink(root/ns)` 검사 | 의도가 직관적 | 네임스페이스 디렉터리가 없을 때·생성 직후 분기가 늘고, 검사 시점과 사용 시점 사이에 경합이 생긴다 |
| C. 네임스페이스 메서드마다 `namespace` 문자열을 넘겨 기준 루트를 따로 계산 | 명시적 | 시그니처 변경이 store/load/delete 경로 전체에 번진다 |

**결정: A.** 이유 — "기준 루트는 운영자가 설정한 루트에서만 실경로로 해석하고, 그 아래 앱이 만든 이름은 링크를 따라가지 않는다"는 규칙 하나로 모든 경로가 닫히고, 변경 지점이 한 메서드다. 형태:

```java
private void verifyWithinRoot(Path effectiveRoot, Path realParent) {
    Path configuredRoot = Paths.get(properties.getRoot());
    Path expectedRealRoot;
    try {
        // 설정 루트만 실경로로 해석하고, 네임스페이스 이름은 문자 그대로 붙인다 —
        // root/profile 자체가 외부 링크여도 기준 루트가 따라가지 않는다.
        // 실경로는 정규화 전 원본 설정값으로 구한다(링크 뒤의 ".."를 어휘적으로 지우면 경계가 바뀜, v3).
        expectedRealRoot = configuredRoot.toRealPath()
                .resolve(configuredRoot.normalize().relativize(effectiveRoot.normalize()));
    } catch (IOException e) {
        throw new IllegalStateException("첨부파일 저장 루트 경로 확인에 실패했습니다.", e);
    }
    if (!realParent.startsWith(expectedRealRoot)) {
        throw new IllegalStateException("저장 경로가 허용된 루트를 벗어났습니다.");
    }
}
```

- 네임스페이스 없는 경로는 `relativize` 결과가 빈 경로라 기준이 `configuredRoot.toRealPath()`이다. 지금(`resolveRoot()`가 정규화 없이 돌려준 경로의 `toRealPath()`)과 **완전히 같다**. **(v3)** `normalize()`는 상대 경로 계산에만 쓴다. 실경로를 정규화된 경로로 구하면 `/srv/link/../attachments`(`link` → `/mnt/volume/sub`)처럼 링크 뒤에 `..`가 오는 설정에서 기준이 `/mnt/volume/attachments`에서 `/srv/attachments`로 바뀐다. 그러면 지금 거부되는 경로가 허용되는 퇴행이 생긴다. 그런 설정에서는 대상 경로(어휘 정규화)와 기준(실경로)이 어긋나 모든 접근이 거부된다. 이것은 지금 동작 그대로의 fail-closed이고, 그런 설정을 지원하는 일은 범위 밖이다. **설정 루트 자체가 링크인 운영 구성(예: `/data` → 마운트 지점)은 계속 허용된다.** 이것은 운영자 결정이므로 따라가는 것이 맞다.
- 결과: 네임스페이스 디렉터리가 링크이면 `realParent`(링크를 따라간 실경로)가 `realBase/profile`로 시작하지 않으므로 `IllegalStateException`(500)이 난다. `delete()`는 이 경우 지우지 않고 예외를 던진다. 부모 링크 탈출에서 이미 `verifyWithinRoot`가 예외를 던지던 기존 동작과 같다. `deleteAfterCommit`/`deleteOnRollback`은 예외를 로그로 흡수한다.
- **잔여(수용)**: `storeUnder()`는 검증보다 `Files.createDirectories(parent)`를 먼저 하므로, 네임스페이스 링크 상황에서는 루트 밖에 **빈 날짜 디렉터리**가 생길 수 있다(파일 쓰기는 검증에서 막힌다). 부모 링크 탈출에서도 지금 그대로인 기존 순서이고, 내용 유출·손상이 아니라서 순서를 바꾸지 않는다.

### 쟁점 4 — 테스트 전략과 Windows 한계

- `LocalDiskFileStorageTest`에 추가:
  1. `load_finalFileSymlink_rejected` — 루트 안 디렉터리에 루트 밖 파일을 가리키는 링크 → `load()`가 `IllegalStateException`, **그리고 `StorageFileNotFoundException`이 아님**(500 계약 확인).
  2. `load_namespace_finalFileSymlink_rejected` — `profile` 네임스페이스 아래 같은 구성 → `load(key, "profile")` 거부(두 공개 메서드가 같은 `loadUnder`를 타는지 고정).
  3. `load_danglingFinalFileSymlink_rejectedAsServerError` — 대상 없는 링크 → `IllegalStateException`이지만 `StorageFileNotFoundException`은 아님(쟁점 2의 의도된 동작 변화를 테스트로 명시).
  4. **(v2)** `load_namespaceDirSymlink_rejected` — `root/profile` → 외부 디렉터리(그 안에 `2020/x.png`) → `load("2020/x.png", "profile")`이 `IllegalStateException`(≠ `StorageFileNotFoundException`).
  5. **(v2)** `delete_namespaceDirSymlink_rejectedWithoutDeletingOutside` — 같은 구성에서 `delete("2020/x.png", "profile")`가 예외를 던지고 **외부 파일이 그대로 남는다**.
  6. **(v2)** `store_namespaceDirSymlink_rejected` — 같은 구성에서 `store(.., "profile")`가 `IllegalStateException`이고 외부 디렉터리에 일반 파일이 새로 생기지 않는다(빈 디렉터리는 쟁점 6 잔여로 허용).
  7. **(v2)** `rootItselfSymlink_stillWorks` — 설정 루트 자체가 링크(→ 실제 디렉터리)일 때 무네임스페이스·`profile` 네임스페이스 모두 store→load→delete 왕복이 정상이다(쟁점 6의 "설정 루트 링크는 허용" 회귀 고정).
  8. **(v3)** `rootWithDotDotAfterSymlink_boundaryUsesRealPath` — 설정 루트가 `<tmp>/link/../attachments`(`link` → `<tmp>/vol/sub`)이고 어휘 정규화 위치 `<tmp>/attachments/2020/x.txt`에 파일이 있을 때, `load("2020/x.txt")`가 거부(`IllegalStateException`)된다. 기준이 원본 설정의 실경로(`<tmp>/vol/attachments`)로 유지되는지 고정한다.
  - 모두 기존 관례대로 링크 생성 실패 시 `Assumptions`로 건너뛴다.
- 기존 회귀: `storeAndLoad_roundTrip`·`load_pathTraversal_rejected`·`load_symlinkEscape_rejected`·`load_noticeKeyUnderProfileNamespace_notFound`·`delete_removesFile`(삭제 후 load는 `StorageFileNotFoundException` 계열) 그대로 통과해야 한다.
- **Windows 한계 보완**: 로컬 Windows에서는 링크 테스트가 건너뛰어지므로, Docker Linux 컨테이너(`eclipse-temurin:21-jdk`, 운영 이미지와 같은 계열)에서 `LocalDiskFileStorageTest`만 실행해 링크 테스트 11건(기존 3 + 신규 8)이 **실제로 실행되어 통과**함을 확인한다(Testcontainers 불필요한 순수 단위 테스트). CI(ubuntu)에서도 실행된다. 로컬 Windows 결과 보고 시 "건너뜀"을 통과로 세지 않는다.

### 쟁점 5 — 실기 검증 범위

정상 경로 회귀가 핵심이다(링크 거부 자체는 단위 테스트가 실제 파일시스템으로 검증). dev `bootRun` + Playwright로:
1. 관리자 공지 첨부 업로드 → 다운로드(바이트 동일) → 삭제(원복).
2. 프로필 이미지 업로드 → 이미지 표시(`load(key, "profile")` 경로) → 원래 이미지로 원복.
3. 공개 첨부 다운로드(`open()` 경로) 회귀 1건.
4. 권한 경계: 비인증 관리자 첨부 다운로드 → 401/로그인 리다이렉트.
5. (가능하면) 링크 거부 실기: dev 저장 루트에 링크를 만들 수 있는 환경(Windows 개발자 모드/관리자 권한, 또는 Docker dev 스택)이면 DB 행의 storageKey가 가리키는 파일을 루트 밖 링크로 바꿔 관리자 다운로드가 500(내용 미노출)임을 확인하고 원복. 불가하면 그 사실을 명시하고 단위 테스트(Linux 컨테이너 실행) 근거로 갈음한다.

## 3. 작업 단계

1. 브랜치 `security/storage-load-nofollow` 생성.
2. `LocalDiskFileStorage.loadUnder()`를 쟁점 1-A, `verifyWithinRoot()`를 쟁점 6-A 형태로 교체, import 정리(`InputStream`), 클래스 Javadoc·`openUnder`·`resolveVerifiedTarget` 주석 정정. `./gradlew compileJava`.
3. `FileStorage.load()` Javadoc에 "최종 파일 자체가 링크이면 거부(`IllegalStateException`)" 추가.
4. `LocalDiskFileStorageTest` 신규 8건 추가.
5. `./gradlew test --tests "com.cms.common.storage.*"`(Windows) → Docker Linux 컨테이너에서 같은 클래스 실행(링크 테스트 실제 실행 확인) → `./gradlew test` 전체(Docker 필요).
6. 실기 검증(쟁점 5).
7. 기록: 루트 `CLAUDE.md`의 `common/storage` 문단에 "`load()`·`open()` 모두 최종 파일 링크를 거부" 반영, 이 계획서 구현·검증 결과, `plan/README.md` 인덱스 행 추가, (선택) `PLAN-public-notice-attachment.md` 후속 항목에 해소 포인터 — 이력 문서 본문은 다시 쓰지 않는다.

## 4. 리스크

| 리스크 | 영향 | 대응 |
|---|---|---|
| 운영 저장소에 이미 링크 형태 파일이 있으면 해당 다운로드가 500 | 해당 첨부·프로필 이미지 표시 실패 | 정상 경로는 링크를 만들지 않음(로컬 실측 0건, prod 미배포). 배포 전 `find "$APP_FILE_STORAGE_ROOT" -type l` 점검을 계획서 운영 주의에 기록 |
| 대상 없는 링크가 404 → 500으로 바뀜 | 이상 데이터에서만 발생 | 의도된 변화(쟁점 2), 테스트 3번으로 고정 |
| Windows 로컬에서 링크 테스트가 건너뛰어져 검증 착시 | 거부 로직 미검증 상태로 완료 주장 | Docker Linux 컨테이너 실행으로 실제 실행 확인(쟁점 4), CI에서도 실행 |
| `InputStream.readAllBytes()`로 바꾸며 성능 변화 | 파일당 ≤10MB, 관리자 경로 | 무시 가능. 왕복 테스트·실기 다운로드 바이트 비교로 정확성 확인 |
| 하드 링크는 여전히 막지 못함 | 저장 볼륨 쓰기 권한 전제 | 범위 밖으로 명시(쟁점 3), `open()`과 같은 수준 |
| **(v2)** 부모 디렉터리 검증과 열기 사이에 부모를 외부 링크로 교체하는 경합 | 외부에 같은 이름 파일이 있으면 읽힘 | 저장 볼륨 쓰기 권한 + 경합 타이밍 전제. `SecureDirectoryStream` 기반 열기는 과설계로 기각(v2 이력). `open()`과 같은 수준 |
| **(v2)** 네임스페이스 디렉터리 링크 상황에서 `store()`가 루트 밖에 빈 날짜 디렉터리를 만들 수 있음 | 빈 디렉터리만 생김(내용 유출·손상 없음) | 쟁점 6 잔여로 수용 |
| **(v2)** 네임스페이스 디렉터리가 링크인 운영 데이터가 있으면 프로필 이미지 업로드·표시·삭제가 500 | 프로필 이미지 기능 실패 | 앱은 `createDirectories`로 실디렉터리만 만든다. 배포 전 `find "$APP_FILE_STORAGE_ROOT" -type l` 점검에 포함 |

## 5. 완료 기준

- [x] `load()`·`load(key, ns)`가 최종 파일 링크(루트 밖 대상·대상 없음 모두)를 `IllegalStateException`(≠ `StorageFileNotFoundException`)으로 거부한다.
- [x] 링크 거부 로직이 `openChannel()` 한 곳에만 있다(`load`·`open` 공유).
- [x] **(v2)** 네임스페이스 디렉터리가 외부 링크이면 `store/load/delete(.., ns)`가 모두 `IllegalStateException`이고, 외부 파일은 읽히지도 지워지지도 않는다. 설정 루트 자체가 링크인 구성은 계속 동작한다.
- [x] 신규 테스트 8건이 Linux(Docker 컨테이너)에서 실제 실행되어 통과한다(건너뜀 아님).
- [x] `./gradlew test` 전체 통과.
- [x] 실기: 관리자 첨부 다운로드·프로필 이미지·공개 첨부 다운로드 정상 동작, 데이터 원복.
- [x] `FileStorage` Javadoc·루트 `CLAUDE.md`·계획 인덱스 현행화.

## 구현·검증 결과 (2026-10-07)

### Context
`load()`는 최종 파일 링크를 따라갔고, 네임스페이스 루트(`root/profile`)가 링크이면 경계 기준 자체가 루트 밖으로 옮겨갔다. v3 계획대로 두 구멍을 막았다. 브랜치는 `security/storage-load-nofollow`이고 커밋·PR 전이다.

### 핵심 확정 사항
- `loadUnder()`는 `open()`과 같은 `openChannel()`(`NOFOLLOW_LINKS`)로 열고 `InputStream.readAllBytes()`로 읽는다. 링크 거부 지점은 `openChannel()` 한 곳이다.
- `verifyWithinRoot()` 기준 = `Paths.get(설정 루트).toRealPath()` + `normalize()` 기준 상대 경로(네임스페이스 이름). 실경로는 정규화 전 원본 설정값으로 구한다(v3).
- 링크 거부는 `IllegalStateException`(500)이다. 대상 없는 최종 링크도 404가 아니라 500이다(의도된 변화).
- **계획과의 차이 1건**: 테스트 8번(`rootWithDotDotAfterSymlink_boundaryUsesRealPath`)에 `@DisabledOnOs(WINDOWS)`를 붙였다. Windows는 `..`를 링크 해석 전에 어휘적으로 처리해 POSIX와 실경로 의미가 다르다. 운영 컨테이너는 Linux이므로 검증 대상에는 영향이 없다.

### 구현 파일
- `src/main/java/com/cms/common/storage/LocalDiskFileStorage.java` — `loadUnder`·`verifyWithinRoot` 교체, 클래스·`openUnder`·`openChannel` 주석 정정
- `src/main/java/com/cms/common/storage/FileStorage.java` — `load()` Javadoc에 링크 거부 계약 추가
- `src/test/java/com/cms/common/storage/LocalDiskFileStorageTest.java` — 신규 8건(+ 링크 생성 실패 시 건너뛰는 헬퍼, 500 계약 단언 헬퍼)
- `CLAUDE.md` — `common/storage` 문단

### 검증 결과
| 검증 | 결과 |
|---|---|
| `./gradlew test`(Windows, Testcontainers) | 1368건, 실패 0, 건너뜀 11. 건너뛴 11건은 권한 없는 Windows에서 링크를 만들 수 없는 링크 테스트(기존 3 + 신규 8)다 |
| `LocalDiskFileStorageTest` — Linux 컨테이너(`eclipse-temurin:21-jdk@sha256:3e3c176f…`, 운영 빌더와 동일, JDK 21.0.12.1) | 30건, **건너뜀 0**, 실패 0. 링크 테스트 11건이 실제로 실행됐다 |
| 판별력 — 운영 코드만 master 버전으로 되돌린 Linux 실행 | 신규 6건(최종 링크 3·네임스페이스 링크 3)이 실패한다. 회귀 고정용 2건(설정 루트 링크·`..` 경계)은 master에서도 통과한다(원래 맞던 동작) |
| 판별력 — v2 버그 변형(`configuredRoot.normalize().toRealPath()`) | `..` 경계 테스트 1건만 실패한다 → 2라운드 지적을 실제로 잡는다 |
| 실기 — dev Docker 스택(현재 작업 트리 빌드, Linux, `appuser`) + Playwright | 아래 |

실기 상세(`admin` 세션, 화면 JS와 같은 CSRF 메타 + fetch):
1. 골든 패스: 공지 생성(201) → 첨부 업로드(201) → 관리자 다운로드(`load()`) 200, 5021바이트 **바이트 일치**. 프로필 PNG 업로드(200) → 다운로드(`load(key,"profile")`) 200 `image/png` **바이트 일치**. 공지 상세 화면에 첨부가 표시되고 상단바 아바타가 렌더링된다(스크린샷 `screenshots/storage-load-nofollow-01-notice-detail.png`, `.gitignore`로 커밋 제외).
2. 권한 경계: 비인증 관리자 첨부 다운로드 → 401. 비인증 공개 다운로드(`open()`) → 200·5021바이트(회귀 없음).
3. 최종 파일 링크: 첨부 실파일을 `/etc/passwd` 링크로 교체 → 관리자 `load()` **500 JSON `INTERNAL_ERROR`, 응답에 `root:` 없음**, 공개 `open()`도 500·미노출. 서버 로그 원인: `IOException: Too many levels of symbolic links (NOFOLLOW_LINKS specified)` → `LocalDiskFileStorage.loadUnder` `IllegalStateException`(계획 쟁점 2의 OS 동작 예상과 일치).
4. 네임스페이스 디렉터리 링크: `profile` → `/tmp/outside`(같은 파일 복사본) → 프로필 이미지 조회 **500**. `DELETE me/profile-image`는 204(DB 커밋)이고, 커밋 후 파일 삭제가 `저장 경로가 허용된 루트를 벗어났습니다`로 차단돼 `FileStorageTransactionSupport`가 "파일 삭제 실패 — 수동 정리 필요" 로그를 남긴다. **외부 파일은 그대로 남았다.**
5. 원복: 링크 제거·실파일·실디렉터리 복구 → 다운로드 200 재확인 → 첨부·공지 삭제(204) → 고아 프로필 파일·`/tmp/outside` 수동 정리 → 프로필 이미지 원래 상태(404, `NONE`), 저장 볼륨 파일 0·링크 0. dev 스택 종료(볼륨 보존).

### 이슈
- 브라우저 콘솔 오류는 모두 설명된다: 일부러 일으킨 500 3건, 이미지 없는 상태의 프로필 404, 기존 `favicon.ico` 404.
- 실기 중 Git Bash가 `docker exec … find /tmp/outside`의 `/tmp`를 Windows 경로로 바꿨다 → `MSYS_NO_PATHCONV=1`로 재실행했다(앱과 무관한 셸 동작).

### 후속 / 운영 주의
- **배포 전 점검**: `find "$APP_FILE_STORAGE_ROOT" -type l` 결과가 비어 있어야 한다. 링크가 있으면 해당 첨부·프로필 요청이 500이 된다.
- 잔여(수용): 하드 링크, 부모 디렉터리 검증과 열기 사이의 교체 경합(`SecureDirectoryStream` 미채택), 네임스페이스 링크 상황에서 `store()`가 루트 밖에 빈 날짜 디렉터리를 만들 수 있음. 모두 저장 볼륨 쓰기 권한이 전제다.
- 로드맵 반영은 `/updateRoadmap` 몫이다("후속 과제 — ②"의 `load()` 한계 항목).
