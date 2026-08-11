package com.cms.common.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 로컬 디스크 기반 {@link FileStorage} 구현. 루트 디렉터리 하위에 날짜 샤딩 + UUID 키로 저장한다
 * (adversarial-review/plan/PLAN-notice-attachment.md 쟁점 2·12 참조, v6 — 적대적 리뷰 5라운드 ship).
 *
 * <p><b>경로 탈출 방지</b>: {@code storageKey}는 항상 이 클래스가 직접 생성하므로 사용자 입력이
 * 경로에 직접 반영되지 않지만, 방어적으로 최종 경로가 스토리지 루트 하위인지
 * {@link Path#toRealPath}(심볼릭 링크까지 실제 해석)로 검증한다.
 *
 * <p><b>쓰기 알고리즘</b>: 최종 경로에 직접 {@link StandardOpenOption#CREATE_NEW}로 쓴다 —
 * 대상이 이미 존재하면 항상 실패하는 것을 Java API가 크로스플랫폼으로 보장하므로 무덮어쓰기가
 * 보장된다(적대적 리뷰 3라운드에서 "임시파일+ATOMIC_MOVE"안이 이 보장을 실제로 제공하지 못함이
 * 확인되어 폐기됨). 스트림이 성공적으로 열린 뒤에만 "이 호출이 파일을 생성했다"로 표시하고,
 * 쓰기·close 실패 시 그 표시가 true일 때만 즉시 정리한다(적대적 리뷰 4라운드 — 디스크 용량 누적 방지).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalDiskFileStorage implements FileStorage {

    private static final DateTimeFormatter DATE_PATH_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    /**
     * 네임스페이스 없는 {@link #load(String)}/{@link #delete(String)}가 절대 해석하면 안 되는
     * 예약된 최상위 세그먼트. 프로필 이미지({@code "profile"})가 첨부파일과 물리적으로 다른
     * 하위 디렉터리에 저장되므로, 오염된 첨부파일 storageKey가 우연히(또는 악의적으로) 이
     * 세그먼트로 시작해도 접근을 거부한다 — 반대로 프로필 서비스가 첨부파일 키를 가리키는
     * 문제는 네임스페이스 스코프 자체가 물리적으로 분리하므로 별도 방어가 필요 없다
     * (adversarial-review/plan/PLAN-profile-image-storage.md 쟁점 2, v6).
     */
    private static final Set<String> RESERVED_NAMESPACES = Set.of("profile");

    private static final Pattern NAMESPACE_PATTERN = Pattern.compile("[a-z0-9_-]+");

    private final FileStorageProperties properties;

    @Override
    public String store(byte[] content, String originalFilename) {
        return storeUnder(resolveRoot(), content, originalFilename);
    }

    @Override
    public String store(byte[] content, String originalFilename, String namespace) {
        // createIfMissing=true — store()는 처음 쓰는 네임스페이스라도 디렉터리를 만들어야 한다.
        return storeUnder(resolveNamespaceRoot(namespace, true), content, originalFilename);
    }

    private String storeUnder(Path effectiveRoot, byte[] content, String originalFilename) {
        String extension = extractExtension(originalFilename);
        String storageKey = buildStorageKey(extension);

        Path target = effectiveRoot.resolve(storageKey).normalize();
        Path parent = target.getParent();
        Path realParent;
        try {
            Files.createDirectories(parent);
            realParent = parent.toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException("첨부파일 저장 디렉터리를 생성할 수 없습니다: " + storageKey, e);
        }
        verifyWithinRoot(effectiveRoot, realParent);

        writeNewFile(target, content, storageKey);
        return storageKey;
    }

    /**
     * {@code target}에 {@link StandardOpenOption#CREATE_NEW}로 새로 쓴다 — 대상이 이미 존재하면
     * 항상 실패한다(무덮어쓰기 보장, 적대적 리뷰 3라운드). 스트림이 성공적으로 열린 뒤에만 "이
     * 호출이 파일을 생성했다"로 표시하고, 쓰기·close 실패 시 그 표시가 true일 때만 즉시 정리한다
     * (적대적 리뷰 4라운드 — 디스크 용량 누적 방지). CREATE_NEW 단계 자체의 실패(대상이 이미 존재)에는
     * 아무것도 삭제하지 않는다 — 이 호출이 만든 파일이 아닐 수 있어서다.
     *
     * <p>{@code store()}에서 계산한 target 경로를 그대로 받는 하위 단계로 분리했다 — storageKey는
     * 매 호출 UUID 기반이라 store()를 통해서는 "이미 대상이 존재하는" 충돌 상황을 재현할 수 없어,
     * 이 메서드를 패키지 접근으로 열어 LocalDiskFileStorageTest가 직접 충돌 시나리오를 검증한다.
     */
    void writeNewFile(Path target, byte[] content, String storageKeyForLogging) {
        boolean createdByThisCall = false;
        try {
            OutputStream out = Files.newOutputStream(target, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW);
            createdByThisCall = true;
            try (out) {
                out.write(content);
            }
        } catch (IOException e) {
            if (createdByThisCall) {
                try {
                    Files.deleteIfExists(target);
                } catch (IOException cleanupEx) {
                    log.error("첨부파일 부분 쓰기 정리 실패 — 수동 정리 필요. storageKey={}", storageKeyForLogging, cleanupEx);
                }
            }
            throw new IllegalStateException("첨부파일 저장에 실패했습니다: " + storageKeyForLogging, e);
        }
    }

    @Override
    public byte[] load(String storageKey) {
        if (isReservedNamespace(storageKey)) {
            log.warn("예약된 네임스페이스로 시작하는 storageKey에 대한 읽기 시도를 거부했습니다: {}", storageKey);
            throw new StorageFileNotFoundException("첨부파일을 찾을 수 없습니다: " + storageKey);
        }
        return loadUnder(resolveRoot(), storageKey);
    }

    @Override
    public byte[] load(String storageKey, String namespace) {
        // createIfMissing=false — 읽기는 디렉터리를 새로 만들 이유가 없다(없으면 곧 not-found로 귀결).
        return loadUnder(resolveNamespaceRoot(namespace, false), storageKey);
    }

    private byte[] loadUnder(Path effectiveRoot, String storageKey) {
        Path target = resolveTarget(effectiveRoot, storageKey);
        try {
            Path parentReal = realPathOrThrow(target.getParent(), storageKey);
            verifyWithinRoot(effectiveRoot, parentReal);
            return Files.readAllBytes(target);
        } catch (NoSuchFileException e) {
            throw new StorageFileNotFoundException("첨부파일을 찾을 수 없습니다: " + storageKey, e);
        } catch (IOException e) {
            throw new IllegalStateException("첨부파일을 읽을 수 없습니다: " + storageKey, e);
        }
    }

    @Override
    public void delete(String storageKey) {
        if (isReservedNamespace(storageKey)) {
            // delete()는 "찾을 수 없음/이미 정리됨"에 항상 no-op이어야 한다는 기존 계약을
            // 그대로 유지한다 — load()와 달리 예외를 던지지 않는다.
            log.warn("예약된 네임스페이스로 시작하는 storageKey에 대한 삭제 시도를 거부했습니다: {}", storageKey);
            return;
        }
        deleteUnder(resolveRoot(), storageKey);
    }

    @Override
    public void delete(String storageKey, String namespace) {
        deleteUnder(resolveNamespaceRoot(namespace, false), storageKey);
    }

    private void deleteUnder(Path effectiveRoot, String storageKey) {
        Path target = resolveTarget(effectiveRoot, storageKey);
        Path parent = target.getParent();
        if (!Files.exists(parent)) {
            // 부모 디렉터리 자체가 없으면 지울 파일도 없다 — no-op 계약(이미 정리됐거나 애초에 없던 키).
            return;
        }
        try {
            verifyWithinRoot(effectiveRoot, realPathOrThrow(parent, storageKey));
            Files.deleteIfExists(target);
        } catch (NoSuchFileException e) {
            // 확인 사이에 다른 스레드/프로세스가 이미 정리한 경우 — no-op.
        } catch (IOException e) {
            throw new IllegalStateException("첨부파일 삭제에 실패했습니다: " + storageKey, e);
        }
    }

    /**
     * 네임스페이스 없는 load/delete가 예약된 서브트리(예: {@code "profile"})를 절대 해석하지
     * 못하도록 최상위 세그먼트를 정규화 후 검사한다. {@code "profile/../yyyy/..."}처럼 정규화하면
     * 예약 세그먼트가 사라지는 값은 애초에 그 세그먼트를 실제로 가리키지 않으므로 통과해도 안전하다
     * (adversarial-review/plan/PLAN-profile-image-storage.md 쟁점 2, v6 — 5차 리뷰 반영).
     *
     * <p>판정만 하고 호출부가 각자의 계약대로(load는 예외, delete는 no-op) 처리한다 — 두 메서드의
     * 기존 예외 계약이 서로 다르기 때문에 여기서 예외를 던지지 않는다.
     */
    private boolean isReservedNamespace(String storageKey) {
        if (storageKey == null || storageKey.isEmpty()) {
            return false;
        }
        Path normalized = Paths.get(storageKey).normalize();
        if (normalized.getNameCount() == 0) {
            return false;
        }
        String firstSegment = normalized.getName(0).toString();
        return RESERVED_NAMESPACES.contains(firstSegment);
    }

    private Path resolveNamespaceRoot(String namespace, boolean createIfMissing) {
        validateNamespace(namespace);
        Path root = resolveRoot();
        Path namespaceRoot = root.resolve(namespace).normalize();
        if (createIfMissing) {
            try {
                Files.createDirectories(namespaceRoot);
            } catch (IOException e) {
                throw new IllegalStateException("네임스페이스 저장 루트를 생성할 수 없습니다: " + namespace, e);
            }
        }
        return namespaceRoot;
    }

    private void validateNamespace(String namespace) {
        if (namespace == null || !NAMESPACE_PATTERN.matcher(namespace).matches()) {
            throw new IllegalArgumentException("허용되지 않는 네임스페이스입니다: " + namespace);
        }
    }

    private Path resolveRoot() {
        Path root = Paths.get(properties.getRoot());
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("첨부파일 저장 루트를 생성할 수 없습니다: " + properties.getRoot(), e);
        }
        return root;
    }

    private Path resolveTarget(Path root, String storageKey) {
        return root.resolve(storageKey).normalize();
    }

    private Path realPathOrThrow(Path path, String storageKey) throws NoSuchFileException {
        try {
            return path.toRealPath();
        } catch (NoSuchFileException e) {
            throw e;
        } catch (IOException e) {
            throw new IllegalStateException("첨부파일 경로 확인에 실패했습니다: " + storageKey, e);
        }
    }

    private void verifyWithinRoot(Path root, Path realParent) {
        Path realRoot;
        try {
            realRoot = root.toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException("첨부파일 저장 루트 경로 확인에 실패했습니다.", e);
        }
        if (!realParent.startsWith(realRoot)) {
            throw new IllegalStateException("저장 경로가 허용된 루트를 벗어났습니다.");
        }
    }

    /**
     * 원본 파일명에서 물리 저장용 확장자만 추출한다(디렉터리 구조 가독성용 — 검증에는 쓰이지 않음,
     * 확장자·Content-Type 화이트리스트 검증은 NoticeAttachmentService가 담당한다).
     * 영숫자 1~10자를 벗어나면 안전하게 확장자 없이 저장한다.
     */
    private String extractExtension(String originalFilename) {
        if (originalFilename == null) {
            return "";
        }
        int dotIndex = originalFilename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == originalFilename.length() - 1) {
            return "";
        }
        String ext = originalFilename.substring(dotIndex + 1).toLowerCase();
        if (!ext.matches("[a-z0-9]{1,10}")) {
            return "";
        }
        return ext;
    }

    private String buildStorageKey(String extension) {
        String datePath = LocalDate.now().format(DATE_PATH_FORMAT);
        String uuid = UUID.randomUUID().toString();
        String filename = extension.isEmpty() ? uuid : uuid + "." + extension;
        return datePath + "/" + filename;
    }
}
