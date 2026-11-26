package com.cms.support;

import com.cms.common.storage.FileStorageProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 시험의 파일 저장소가 개발 서버의 {@code ./data/attachments}가 아니라 Gradle이 정한 격리 경로({@code build/test-file-storage})인지 확인한다
 * (adversarial-review/plan/PLAN-test-data-leak.md 결정 4).
 *
 * <p>{@code build.gradle}의 {@code test} 태스크는 실행이 끝난 뒤 격리 경로에 파일이 남았는지 검사한다. 격리 설정이 빠지면 시험은 다시
 * {@code ./data/attachments}에 쓰고 격리 경로는 비어 있어 그 검사가 늘 통과하므로, 이 시험이 Spring이 실제로 바인딩한 경로를 확인한다.
 * Gradle 시험 워커에만 붙는 시스템 속성 {@code org.gradle.test.worker}가 없으면(IDE에서 Gradle 없이 실행) 건너뛴다.
 * 브라우저 전용 실행({@code -PbrowserTests=only})에서도 돌도록 전용 태그를 붙인다.
 */
@Tag("storage-isolation")
@SpringBootTest(classes = CmsTestApplication.class)
class TestFileStorageIsolationTest extends MariaDbContainerSupport {

    @Autowired
    FileStorageProperties storageProperties;

    @Test
    @DisplayName("Gradle로 실행한 시험은 파일 저장소로 build/test-file-storage를 쓴다")
    void storageRootIsIsolatedUnderBuildDirectory() {
        assumeTrue(System.getProperty("org.gradle.test.worker") != null, "Gradle 시험 워커가 아니면(IDE 단독 실행) 격리를 확인하지 않는다");

        String injected = System.getenv("APP_FILE_STORAGE_ROOT");
        assertThat(injected).as("build.gradle의 test 태스크가 APP_FILE_STORAGE_ROOT를 넘겨야 한다").isNotBlank();

        Path expected = Path.of(injected).toAbsolutePath().normalize();
        assertThat(expected.getFileName()).hasToString("test-file-storage");
        assertThat(expected.getParent().getFileName()).hasToString("build");

        Path actual = Path.of(storageProperties.getRoot()).toAbsolutePath().normalize();
        assertThat(actual).as("Spring이 실제로 쓰는 저장소 경로").isEqualTo(expected);
    }
}
