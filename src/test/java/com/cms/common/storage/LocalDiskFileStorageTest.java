package com.cms.common.storage;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * adversarial-review/plan/PLAN-notice-attachment.md 쟁점 2·12(v6 — 적대적 리뷰 5라운드 ship) 참조.
 * 순수 단위 테스트 — Spring 컨텍스트 없이 {@link FileStorageProperties}를 직접 생성한다.
 */
class LocalDiskFileStorageTest {

    private LocalDiskFileStorage newStorage(Path root) {
        FileStorageProperties properties = new FileStorageProperties();
        properties.setRoot(root.toString());
        return new LocalDiskFileStorage(properties);
    }

    @Test
    @DisplayName("store→load 왕복 시 원본 바이트가 그대로 반환된다")
    void storeAndLoad_roundTrip(@TempDir Path tempDir) {
        LocalDiskFileStorage storage = newStorage(tempDir);
        byte[] content = "공지 첨부 테스트".getBytes();

        String storageKey = storage.store(content, "report.pdf");
        byte[] loaded = storage.load(storageKey);

        assertArrayEquals(content, loaded);
    }

    @Test
    @DisplayName("같은 원본 파일명으로 두 번 저장해도 서로 다른 storageKey가 생성되고 내용이 섞이지 않는다")
    void store_sameFilenameTwice_distinctKeysAndContent() {
        LocalDiskFileStorage storage = newStorage(tempDirForTest());
        String key1 = storage.store("first".getBytes(), "a.txt");
        String key2 = storage.store("second".getBytes(), "a.txt");

        assertNotEquals(key1, key2);
        assertArrayEquals("first".getBytes(), storage.load(key1));
        assertArrayEquals("second".getBytes(), storage.load(key2));
    }

    @Test
    @DisplayName("delete 후에는 load가 실패한다")
    void delete_removesFile(@TempDir Path tempDir) {
        LocalDiskFileStorage storage = newStorage(tempDir);
        String storageKey = storage.store("content".getBytes(), "a.txt");

        storage.delete(storageKey);

        assertThrows(IllegalStateException.class, () -> storage.load(storageKey));
    }

    @Test
    @DisplayName("존재하지 않는 키를 삭제해도 예외 없이 no-op이다")
    void delete_missingKey_noop(@TempDir Path tempDir) {
        LocalDiskFileStorage storage = newStorage(tempDir);

        storage.delete("2026/01/01/does-not-exist.txt");
        // 예외가 나지 않으면 통과.
    }

    @Test
    @DisplayName("경로 탈출(../) 시도는 거부된다")
    void load_pathTraversal_rejected(@TempDir Path tempDir) throws IOException {
        Path root = tempDir.resolve("storage-root");
        Files.createDirectories(root);
        Path outsideFile = tempDir.resolve("secret.txt");
        Files.writeString(outsideFile, "secret");

        LocalDiskFileStorage storage = newStorage(root);

        // storageKey는 항상 서버가 생성하므로 실제로는 발생하지 않지만, 방어 코드 자체를
        // 직접 검증한다(설계 결정 — 쟁점 2).
        assertThrows(IllegalStateException.class, () -> storage.load("../secret.txt"));
    }

    @Test
    @DisplayName("심볼릭 링크를 통한 루트 탈출은 거부된다")
    void load_symlinkEscape_rejected(@TempDir Path tempDir) throws IOException {
        Path root = tempDir.resolve("storage-root");
        Files.createDirectories(root);
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("secret.txt"), "secret");

        Path linkDir = root.resolve("2020");
        try {
            Files.createSymbolicLink(linkDir, outside);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.assumeTrue(false, "이 환경은 심볼릭 링크 생성을 지원하지 않아 테스트를 건너뜁니다: " + e.getMessage());
            return;
        }

        LocalDiskFileStorage storage = newStorage(root);

        assertThrows(IllegalStateException.class, () -> storage.load("2020/secret.txt"));
    }

    @Test
    @DisplayName("최종 대상이 이미 존재하면(CREATE_NEW 충돌) 기존 바이트를 절대 변경·삭제하지 않고 예외를 던진다 (무덮어쓰기 보장, 적대적 리뷰 3·4라운드)")
    void writeNewFile_neverOverwritesOrDeletesExisting(@TempDir Path tempDir) throws IOException {
        LocalDiskFileStorage storage = newStorage(tempDir);
        Path target = tempDir.resolve("existing.txt");
        Files.write(target, "original".getBytes());

        assertThrows(IllegalStateException.class,
                () -> storage.writeNewFile(target, "attacker-controlled".getBytes(), "test-key"));

        // CREATE_NEW 자체의 실패(대상 이미 존재)에는 이 호출이 만든 파일이 아니므로 정리하지 않는다
        // — 존재 여부와 내용 둘 다 보존되어야 한다.
        assertTrue(Files.exists(target), "이 호출이 만들지 않은 기존 파일은 삭제되면 안 된다");
        assertArrayEquals("original".getBytes(), Files.readAllBytes(target));
    }

    /** {@code @TempDir}는 파라미터 주입 전용이라, 메서드 내부에서 여러 번 storage를 만들 때는 직접 생성한다. */
    private Path tempDirForTest() {
        try {
            return Files.createTempDirectory("notice-attachment-test");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
