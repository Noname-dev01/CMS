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
import java.util.UUID;

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

    private final FileStorageProperties properties;

    @Override
    public String store(byte[] content, String originalFilename) {
        Path root = resolveRoot();
        String extension = extractExtension(originalFilename);
        String storageKey = buildStorageKey(extension);

        Path target = root.resolve(storageKey).normalize();
        Path parent = target.getParent();
        Path realParent;
        try {
            Files.createDirectories(parent);
            realParent = parent.toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException("첨부파일 저장 디렉터리를 생성할 수 없습니다: " + storageKey, e);
        }
        verifyWithinRoot(root, realParent);

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
        Path root = resolveRoot();
        Path target = resolveTarget(root, storageKey);
        try {
            Path parentReal = realPathOrThrow(target.getParent(), storageKey);
            verifyWithinRoot(root, parentReal);
            return Files.readAllBytes(target);
        } catch (NoSuchFileException e) {
            throw new StorageFileNotFoundException("첨부파일을 찾을 수 없습니다: " + storageKey, e);
        } catch (IOException e) {
            throw new IllegalStateException("첨부파일을 읽을 수 없습니다: " + storageKey, e);
        }
    }

    @Override
    public void delete(String storageKey) {
        Path root = resolveRoot();
        Path target = resolveTarget(root, storageKey);
        Path parent = target.getParent();
        if (!Files.exists(parent)) {
            // 부모 디렉터리 자체가 없으면 지울 파일도 없다 — no-op 계약(이미 정리됐거나 애초에 없던 키).
            return;
        }
        try {
            verifyWithinRoot(root, realPathOrThrow(parent, storageKey));
            Files.deleteIfExists(target);
        } catch (NoSuchFileException e) {
            // 확인 사이에 다른 스레드/프로세스가 이미 정리한 경우 — no-op.
        } catch (IOException e) {
            throw new IllegalStateException("첨부파일 삭제에 실패했습니다: " + storageKey, e);
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
