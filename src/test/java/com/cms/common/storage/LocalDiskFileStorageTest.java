package com.cms.common.storage;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    // ===================== 네임스페이스(프로필 이미지, PLAN-profile-image-storage.md 쟁점 2·v6) =====================

    @Test
    @DisplayName("네임스페이스로 저장하면 실제로 root/<namespace> 하위에 파일이 생성된다")
    void store_withNamespace_writesUnderNamespaceSubdirectory(@TempDir Path tempDir) {
        LocalDiskFileStorage storage = newStorage(tempDir);
        byte[] content = "프로필 이미지".getBytes();

        String storageKey = storage.store(content, "avatar.png", "profile");

        assertTrue(Files.exists(tempDir.resolve("profile").resolve(storageKey)),
                "네임스페이스 하위 디렉터리에 실제 파일이 있어야 한다");
        assertArrayEquals(content, storage.load(storageKey, "profile"));
    }

    @Test
    @DisplayName("네임스페이스 없는 기존 store()는 위치·동작이 그대로 유지된다(회귀 없음)")
    void store_withoutNamespace_behaviorUnchanged(@TempDir Path tempDir) {
        LocalDiskFileStorage storage = newStorage(tempDir);
        byte[] content = "공지 첨부".getBytes();

        String storageKey = storage.store(content, "report.pdf");

        assertTrue(Files.exists(tempDir.resolve(storageKey)));
        assertFalse(Files.exists(tempDir.resolve("profile").resolve(storageKey)));
        assertArrayEquals(content, storage.load(storageKey));
    }

    @Test
    @DisplayName("정방향: 공지 첨부파일의 실제 storageKey를 profile 네임스페이스로 읽으면 물리적으로 다른 경로라 찾지 못한다")
    void load_noticeKeyUnderProfileNamespace_notFound(@TempDir Path tempDir) {
        LocalDiskFileStorage storage = newStorage(tempDir);
        String noticeKey = storage.store("공지 첨부 원본".getBytes(), "report.pdf"); // 네임스페이스 없이 저장(공지 방식)

        assertThrows(StorageFileNotFoundException.class, () -> storage.load(noticeKey, "profile"));
    }

    @Test
    @DisplayName("역방향: 네임스페이스 없는 기존 load()/delete()는 예약된 profile 서브트리를 절대 해석하지 못한다")
    void load_and_delete_reservedNamespaceViaUnnamespacedApi_rejected(@TempDir Path tempDir) {
        LocalDiskFileStorage storage = newStorage(tempDir);
        String profileKey = storage.store("프로필 원본".getBytes(), "avatar.png", "profile");
        String pollutedNoticeStyleKey = "profile/" + profileKey; // 오염된 notice_attachment.storage_key 흉내

        assertThrows(StorageFileNotFoundException.class, () -> storage.load(pollutedNoticeStyleKey));

        // delete()는 no-op 계약이라 예외를 던지지 않지만, 실제로 프로필 파일이 삭제되면 안 된다.
        storage.delete(pollutedNoticeStyleKey);
        assertArrayEquals("프로필 원본".getBytes(), storage.load(profileKey, "profile"),
                "예약된 네임스페이스 우회 시도로 프로필 파일이 삭제되면 안 된다");
    }

    @Test
    @DisplayName("허용되지 않는 네임스페이스 형식은 즉시 거부된다")
    void store_invalidNamespace_rejected(@TempDir Path tempDir) {
        LocalDiskFileStorage storage = newStorage(tempDir);

        assertThrows(IllegalArgumentException.class,
                () -> storage.store("x".getBytes(), "a.txt", "../etc"));
        assertThrows(IllegalArgumentException.class,
                () -> storage.store("x".getBytes(), "a.txt", "profile/nested"));
    }

    @Test
    @DisplayName("네임스페이스를 지원하지 않는 FileStorage 구현체의 default 메서드는 UnsupportedOperationException을 던진다")
    void defaultNamespaceMethods_throwUnsupportedOperationException() {
        FileStorage unsupporting = new FileStorage() {
            @Override
            public String store(byte[] content, String originalFilename) {
                return "noop";
            }

            @Override
            public byte[] load(String storageKey) {
                return new byte[0];
            }

            @Override
            public StoredFileStream open(String storageKey) {
                throw new UnsupportedOperationException("테스트용 구현체");
            }

            @Override
            public void delete(String storageKey) {
                // no-op
            }
        };

        assertThrows(UnsupportedOperationException.class, () -> unsupporting.store("x".getBytes(), "a.txt", "profile"));
        assertThrows(UnsupportedOperationException.class, () -> unsupporting.load("key", "profile"));
        assertThrows(UnsupportedOperationException.class, () -> unsupporting.delete("key", "profile"));
    }

    // ===================== open (스트리밍 읽기, PLAN-public-notice-attachment.md 후속 작업) =====================

    @Test
    @DisplayName("store→open 왕복 시 스트림 전량이 원본 바이트와 같고 size가 원본 길이와 같다")
    void open_roundTrip(@TempDir Path tempDir) throws IOException {
        LocalDiskFileStorage storage = newStorage(tempDir);
        byte[] original = new byte[20_000];
        for (int i = 0; i < original.length; i++) {
            original[i] = (byte) (i % 251);
        }
        String key = storage.store(original, "big.bin");

        try (StoredFileStream opened = storage.open(key)) {
            assertEquals(original.length, opened.size());
            assertArrayEquals(original, opened.inputStream().readAllBytes());
        }
    }

    @Test
    @DisplayName("빈 파일도 open할 수 있고 size는 0이다")
    void open_emptyFile(@TempDir Path tempDir) throws IOException {
        LocalDiskFileStorage storage = newStorage(tempDir);
        String key = storage.store(new byte[0], "empty.txt");

        try (StoredFileStream opened = storage.open(key)) {
            assertEquals(0, opened.size());
            assertEquals(-1, opened.inputStream().read());
        }
    }

    @Test
    @DisplayName("존재하지 않는 키를 open하면 StorageFileNotFoundException")
    void open_missingKey_notFound(@TempDir Path tempDir) {
        LocalDiskFileStorage storage = newStorage(tempDir);

        assertThrows(StorageFileNotFoundException.class, () -> storage.open("2020/01/01/nope.txt"));
    }

    @Test
    @DisplayName("open도 경로 탈출(../) 시도를 거부한다")
    void open_pathTraversal_rejected(@TempDir Path tempDir) throws IOException {
        Path root = tempDir.resolve("storage-root");
        Files.createDirectories(root);
        Files.writeString(tempDir.resolve("secret.txt"), "secret");

        LocalDiskFileStorage storage = newStorage(root);

        assertThrows(IllegalStateException.class, () -> storage.open("../secret.txt"));
    }

    @Test
    @DisplayName("open도 예약된 profile 서브트리를 네임스페이스 없는 API로 해석하지 못한다")
    void open_reservedNamespace_rejected(@TempDir Path tempDir) {
        LocalDiskFileStorage storage = newStorage(tempDir);
        String profileKey = storage.store("img".getBytes(), "a.png", "profile");

        assertThrows(StorageFileNotFoundException.class, () -> storage.open(profileKey));
        assertThrows(StorageFileNotFoundException.class, () -> storage.open("profile/" + profileKey));
    }

    @Test
    @DisplayName("open은 부모 디렉터리 심볼릭 링크를 통한 루트 탈출을 거부한다")
    void open_parentSymlinkEscape_rejected(@TempDir Path tempDir) throws IOException {
        Path root = tempDir.resolve("storage-root");
        Files.createDirectories(root);
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("secret.txt"), "secret");
        try {
            Files.createSymbolicLink(root.resolve("2020"), outside);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.assumeTrue(false, "이 환경은 심볼릭 링크 생성을 지원하지 않아 테스트를 건너뜁니다: " + e.getMessage());
            return;
        }

        LocalDiskFileStorage storage = newStorage(root);

        assertThrows(IllegalStateException.class, () -> storage.open("2020/secret.txt"));
    }

    @Test
    @DisplayName("open은 최종 파일 자체가 외부를 가리키는 심볼릭 링크이면 거부한다 (부모 경로 검증만으로는 못 막는 경우)")
    void open_finalFileSymlink_rejected(@TempDir Path tempDir) throws IOException {
        Path root = tempDir.resolve("storage-root");
        Path dir = root.resolve("2020");
        Files.createDirectories(dir);
        Path outsideFile = tempDir.resolve("secret.txt");
        Files.writeString(outsideFile, "secret");
        try {
            Files.createSymbolicLink(dir.resolve("link.txt"), outsideFile);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.assumeTrue(false, "이 환경은 심볼릭 링크 생성을 지원하지 않아 테스트를 건너뜁니다: " + e.getMessage());
            return;
        }

        LocalDiskFileStorage storage = newStorage(root);

        assertThrows(IllegalStateException.class, () -> storage.open("2020/link.txt"));
    }

    // ===== load()의 최종 파일 링크 거부 + 네임스페이스 루트 링크 차단 =====
    // (adversarial-review/plan/PLAN-storage-load-nofollow.md 쟁점 4·6 — 링크를 만들 수 없는 환경(권한 없는 Windows)에서는 건너뛴다)

    private static void createSymlinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.assumeTrue(false, "이 환경은 심볼릭 링크 생성을 지원하지 않아 테스트를 건너뜁니다: " + e.getMessage());
        }
    }

    /** 링크 거부는 "파일 없음"(404)이 아니라 서버 오류(500) 계약이어야 한다 — open()과 동일(쟁점 2). */
    private static void assertRejectedAsServerError(org.junit.jupiter.api.function.Executable executable) {
        IllegalStateException e = assertThrows(IllegalStateException.class, executable);
        assertFalse(e instanceof StorageFileNotFoundException, "링크 거부가 not-found로 분류됐습니다: " + e);
    }

    @Test
    @DisplayName("load는 최종 파일 자체가 외부를 가리키는 심볼릭 링크이면 서버 오류로 거부한다")
    void load_finalFileSymlink_rejected(@TempDir Path tempDir) throws IOException {
        Path root = tempDir.resolve("storage-root");
        Path dir = root.resolve("2020");
        Files.createDirectories(dir);
        Path outsideFile = tempDir.resolve("secret.txt");
        Files.writeString(outsideFile, "secret");
        createSymlinkOrSkip(dir.resolve("link.txt"), outsideFile);

        LocalDiskFileStorage storage = newStorage(root);

        assertRejectedAsServerError(() -> storage.load("2020/link.txt"));
    }

    @Test
    @DisplayName("네임스페이스 load도 최종 파일 자체가 심볼릭 링크이면 거부한다")
    void load_namespace_finalFileSymlink_rejected(@TempDir Path tempDir) throws IOException {
        Path root = tempDir.resolve("storage-root");
        Path dir = root.resolve("profile").resolve("2020");
        Files.createDirectories(dir);
        Path outsideFile = tempDir.resolve("secret.png");
        Files.writeString(outsideFile, "secret");
        createSymlinkOrSkip(dir.resolve("link.png"), outsideFile);

        LocalDiskFileStorage storage = newStorage(root);

        assertRejectedAsServerError(() -> storage.load("2020/link.png", "profile"));
    }

    @Test
    @DisplayName("대상 없는(dangling) 최종 링크도 not-found가 아니라 서버 오류로 거부한다 (의도된 동작 변화 — 쟁점 2)")
    void load_danglingFinalFileSymlink_rejectedAsServerError(@TempDir Path tempDir) throws IOException {
        Path root = tempDir.resolve("storage-root");
        Path dir = root.resolve("2020");
        Files.createDirectories(dir);
        createSymlinkOrSkip(dir.resolve("dangling.txt"), tempDir.resolve("missing.txt"));

        LocalDiskFileStorage storage = newStorage(root);

        assertRejectedAsServerError(() -> storage.load("2020/dangling.txt"));
    }

    @Test
    @DisplayName("네임스페이스 디렉터리 자체가 외부 링크이면 네임스페이스 load가 거부된다")
    void load_namespaceDirSymlink_rejected(@TempDir Path tempDir) throws IOException {
        Path root = tempDir.resolve("storage-root");
        Files.createDirectories(root);
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(outside.resolve("2020"));
        Files.writeString(outside.resolve("2020").resolve("x.png"), "secret");
        createSymlinkOrSkip(root.resolve("profile"), outside);

        LocalDiskFileStorage storage = newStorage(root);

        assertRejectedAsServerError(() -> storage.load("2020/x.png", "profile"));
    }

    @Test
    @DisplayName("네임스페이스 디렉터리 자체가 외부 링크이면 네임스페이스 delete가 거부되고 외부 파일은 남는다")
    void delete_namespaceDirSymlink_rejectedWithoutDeletingOutside(@TempDir Path tempDir) throws IOException {
        Path root = tempDir.resolve("storage-root");
        Files.createDirectories(root);
        Path outside = tempDir.resolve("outside");
        Path outsideFile = outside.resolve("2020").resolve("x.png");
        Files.createDirectories(outsideFile.getParent());
        Files.writeString(outsideFile, "secret");
        createSymlinkOrSkip(root.resolve("profile"), outside);

        LocalDiskFileStorage storage = newStorage(root);

        assertThrows(IllegalStateException.class, () -> storage.delete("2020/x.png", "profile"));
        assertTrue(Files.exists(outsideFile), "루트 밖 파일이 삭제됐습니다");
    }

    @Test
    @DisplayName("네임스페이스 디렉터리 자체가 외부 링크이면 네임스페이스 store가 거부되고 외부에 파일이 생기지 않는다")
    void store_namespaceDirSymlink_rejected(@TempDir Path tempDir) throws IOException {
        Path root = tempDir.resolve("storage-root");
        Files.createDirectories(root);
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(outside);
        createSymlinkOrSkip(root.resolve("profile"), outside);

        LocalDiskFileStorage storage = newStorage(root);

        assertThrows(IllegalStateException.class, () -> storage.store("img".getBytes(), "a.png", "profile"));
        // 빈 날짜 디렉터리는 생길 수 있다(검증 전 createDirectories — 계획서 쟁점 6 잔여) — 일반 파일만 없으면 된다.
        try (var files = Files.walk(outside)) {
            assertEquals(0, files.filter(Files::isRegularFile).count());
        }
    }

    @Test
    @DisplayName("설정 루트 자체가 링크인 구성은 무네임스페이스·네임스페이스 모두 store→load→delete 왕복이 정상이다")
    void rootItselfSymlink_stillWorks(@TempDir Path tempDir) throws IOException {
        Path realRoot = tempDir.resolve("real-root");
        Files.createDirectories(realRoot);
        Path linkRoot = tempDir.resolve("link-root");
        createSymlinkOrSkip(linkRoot, realRoot);

        LocalDiskFileStorage storage = newStorage(linkRoot);

        String key = storage.store("plain".getBytes(), "a.txt");
        assertArrayEquals("plain".getBytes(), storage.load(key));
        String profileKey = storage.store("img".getBytes(), "a.png", "profile");
        assertArrayEquals("img".getBytes(), storage.load(profileKey, "profile"));

        storage.delete(key);
        storage.delete(profileKey, "profile");
        assertFalse(Files.exists(realRoot.resolve(key)));
        assertFalse(Files.exists(realRoot.resolve("profile").resolve(profileKey)));
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "Windows는 경로의 \"..\"를 링크 해석 전에 어휘적으로 처리해 POSIX와 실경로 의미가 다르다")
    @DisplayName("링크 뒤에 \"..\"가 오는 설정 루트는 원본 설정의 실경로를 경계로 삼는다 (어휘 정규화 위치로 경계가 옮겨가지 않음)")
    void rootWithDotDotAfterSymlink_boundaryUsesRealPath(@TempDir Path tempDir) throws IOException {
        Path volSub = tempDir.resolve("vol").resolve("sub");
        Files.createDirectories(volSub);
        Files.createDirectories(tempDir.resolve("vol").resolve("attachments"));
        createSymlinkOrSkip(tempDir.resolve("link"), volSub);
        // 어휘 정규화 위치(<tmp>/attachments)에만 파일을 둔다 — 실경로 기준(<tmp>/vol/attachments) 밖이다.
        Path lexicalFile = tempDir.resolve("attachments").resolve("2020").resolve("x.txt");
        Files.createDirectories(lexicalFile.getParent());
        Files.writeString(lexicalFile, "secret");

        LocalDiskFileStorage storage = newStorage(tempDir.resolve("link").resolve("..").resolve("attachments"));

        assertThrows(IllegalStateException.class, () -> storage.load("2020/x.txt"));
    }

    @Test
    @DisplayName("스트림을 연 채로 delete해도 남은 바이트를 끝까지 읽을 수 있다 (전송 중 관리자 삭제 경합 — 계획서 결정 S7)")
    void open_deleteWhileOpen_stillReadable(@TempDir Path tempDir) throws IOException {
        LocalDiskFileStorage storage = newStorage(tempDir);
        byte[] original = new byte[100_000];
        java.util.Arrays.fill(original, (byte) 7);
        String key = storage.store(original, "a.bin");

        try (StoredFileStream opened = storage.open(key)) {
            byte[] head = opened.inputStream().readNBytes(100);
            assertEquals(100, head.length);

            storage.delete(key);

            assertThrows(StorageFileNotFoundException.class, () -> storage.open(key));
            byte[] rest = opened.inputStream().readAllBytes();
            assertEquals(original.length - 100, rest.length);
        }
    }

    @Test
    @DisplayName("채널 open 이후 size() 조회가 실패하면 채널을 닫고 IllegalStateException을 던진다 (핸들 누수 방지 — 계획서 리뷰 2)")
    void open_sizeFailure_closesChannel(@TempDir Path tempDir) {
        FileStorageProperties properties = new FileStorageProperties();
        properties.setRoot(tempDir.toString());
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean(false);
        LocalDiskFileStorage storage = new LocalDiskFileStorage(properties) {
            @Override
            java.nio.channels.SeekableByteChannel openChannel(Path target) {
                return new java.nio.channels.SeekableByteChannel() {
                    @Override public int read(java.nio.ByteBuffer dst) { return -1; }
                    @Override public int write(java.nio.ByteBuffer src) { throw new UnsupportedOperationException(); }
                    @Override public long position() { return 0; }
                    @Override public java.nio.channels.SeekableByteChannel position(long newPosition) { return this; }
                    @Override public long size() throws IOException { throw new IOException("size 조회 실패 시뮬레이션"); }
                    @Override public java.nio.channels.SeekableByteChannel truncate(long size) { return this; }
                    @Override public boolean isOpen() { return !closed.get(); }
                    @Override public void close() { closed.set(true); }
                };
            }
        };
        String key = storage.store("x".getBytes(), "a.txt");

        assertThrows(IllegalStateException.class, () -> storage.open(key));
        assertTrue(closed.get(), "size() 실패 시 열어 둔 채널이 닫혀야 한다");
    }
}
