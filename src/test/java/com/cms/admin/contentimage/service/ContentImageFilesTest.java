package com.cms.admin.contentimage.service;

import com.cms.common.storage.FileStorageProperties;
import com.cms.common.storage.LocalDiskFileStorage;
import com.cms.common.storage.StorageFileNotFoundException;
import com.cms.common.storage.StoredFileStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 본문 이미지 파일 위치 규칙(빈 문자열·null = 루트) — 실제 {@link LocalDiskFileStorage}로 루트와 네임스페이스에 같은 이름의 키가 아닌
 * 서로 다른 내용을 두고, 헬퍼가 고른 영역의 내용이 돌아오는지로 분기를 확인한다(PLAN-solid-small-cleanup.md F1·F2).
 */
class ContentImageFilesTest {

    private static LocalDiskFileStorage storage(Path root) {
        FileStorageProperties properties = new FileStorageProperties();
        properties.setRoot(root.toString());
        return new LocalDiskFileStorage(properties);
    }

    @Test
    @DisplayName("빈 문자열과 null은 루트, 그 밖의 값은 네임스페이스다")
    void isRoot() {
        assertThat(ContentImageFiles.isRoot("")).isTrue();
        assertThat(ContentImageFiles.isRoot(null)).isTrue();
        assertThat(ContentImageFiles.isRoot("banner")).isFalse();
        assertThat(ContentImageFiles.isRoot(" ")).isFalse();
    }

    @Test
    @DisplayName("load: 빈 문자열·null은 루트 영역의 파일을, banner는 banner 영역의 파일을 읽는다")
    void loadPicksArea(@TempDir Path root) {
        LocalDiskFileStorage storage = storage(root);
        String rootKey = storage.store("root-bytes".getBytes(), "a.png");
        String bannerKey = storage.store("banner-bytes".getBytes(), "a.png", "banner");

        assertThat(ContentImageFiles.load(storage, rootKey, "")).isEqualTo("root-bytes".getBytes());
        assertThat(ContentImageFiles.load(storage, rootKey, null)).isEqualTo("root-bytes".getBytes());
        assertThat(ContentImageFiles.load(storage, bannerKey, "banner")).isEqualTo("banner-bytes".getBytes());
        // 영역을 잘못 고르면 그 영역에는 파일이 없다 — 분기가 뒤집히면 여기서 드러난다
        assertThatThrownBy(() -> ContentImageFiles.load(storage, bannerKey, "")).isInstanceOf(StorageFileNotFoundException.class);
        assertThatThrownBy(() -> ContentImageFiles.load(storage, rootKey, "banner")).isInstanceOf(StorageFileNotFoundException.class);
    }

    @Test
    @DisplayName("open: 같은 규칙으로 영역을 고르고 스트림 전량·크기가 원본과 같다")
    void openPicksArea(@TempDir Path root) throws IOException {
        LocalDiskFileStorage storage = storage(root);
        String rootKey = storage.store("root-bytes".getBytes(), "a.png");
        String popupKey = storage.store("popup-bytes".getBytes(), "a.png", "popup");

        try (StoredFileStream opened = ContentImageFiles.open(storage, rootKey, "")) {
            assertThat(opened.inputStream().readAllBytes()).isEqualTo("root-bytes".getBytes());
            assertThat(opened.size()).isEqualTo("root-bytes".length());
        }
        try (StoredFileStream opened = ContentImageFiles.open(storage, popupKey, "popup")) {
            assertThat(opened.inputStream().readAllBytes()).isEqualTo("popup-bytes".getBytes());
        }
    }

    @Test
    @DisplayName("파일이 없으면 StorageFileNotFoundException을 감싸지 않고 그대로 던진다(호출자의 404 매핑 유지)")
    void missingFilePropagatesNotFound(@TempDir Path root) {
        LocalDiskFileStorage storage = storage(root);

        assertThatThrownBy(() -> ContentImageFiles.load(storage, "2026/01/01/none.png", "")).isExactlyInstanceOf(StorageFileNotFoundException.class);
        assertThatThrownBy(() -> ContentImageFiles.load(storage, "2026/01/01/none.png", "banner")).isExactlyInstanceOf(StorageFileNotFoundException.class);
        assertThatThrownBy(() -> ContentImageFiles.open(storage, "2026/01/01/none.png", "")).isExactlyInstanceOf(StorageFileNotFoundException.class);
        assertThatThrownBy(() -> ContentImageFiles.open(storage, "2026/01/01/none.png", "popup")).isExactlyInstanceOf(StorageFileNotFoundException.class);
    }
}
