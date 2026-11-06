package com.cms.publicweb.support;

import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 새 공개 전송 경로가 동시 상한을 비껴가지 못하게 한다(PLAN-public-transfer-limit.md F2). 승인 목록이 틀어지면 새 경로에 {@link PublicFileTransfer}를 달고 목록을 고친다.
 * 표지 집합만 보면 기존 서비스를 재사용한 표지 없는 핸들러를 놓치므로, 응답 본문을 쓰는 지점을 소스에서 파일별 횟수까지 함께 맞춘다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
class PublicTransferConventionTest extends MariaDbContainerSupport {

    private static final Path PUBLIC_WEB = Path.of("src/main/java/com/cms/publicweb");
    /** 스트리머 자신은 호출부가 승인 목록에 있으므로 제외한다. */
    private static final String STREAMER_FILE = "PublicImageStreamer.java";
    private static final Pattern BODY_WRITE = Pattern.compile("\\.getOutputStream\\(|PublicImageStreamer\\.stream\\(");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @Test
    @DisplayName("표지가 달린 핸들러는 승인된 GET 4개뿐이다")
    void markedHandlersAreApprovedGets() {
        Set<String> marked = handlerMapping.getHandlerMethods().entrySet().stream()
                .filter(entry -> entry.getValue().hasMethodAnnotation(PublicFileTransfer.class))
                .peek(entry -> assertThat(entry.getKey().getMethodsCondition().getMethods())
                        .as("표지는 본문을 보내는 GET에만 단다: %s", entry.getValue())
                        .containsExactly(RequestMethod.GET))
                .map(entry -> entry.getValue().getBeanType().getSimpleName() + "#" + entry.getValue().getMethod().getName())
                .collect(Collectors.toSet());

        assertThat(marked).containsExactlyInAnyOrder(
                "PublicBoardController#attachment",
                "PublicContentImageController#image",
                "PublicBannerImageController#image",
                "PublicPopupImageController#image");
    }

    @Test
    @DisplayName("공개 코드에서 응답 본문을 쓰는 지점은 표지가 달린 핸들러의 것뿐이다")
    void bodyWritesMatchApprovedCounts() throws IOException {
        Map<String, Integer> counts = new TreeMap<>();
        for (Path file : javaFiles()) {
            String name = file.getFileName().toString();
            if (name.equals(STREAMER_FILE)) {
                continue;
            }
            Matcher matcher = BODY_WRITE.matcher(Files.readString(file));
            int count = 0;
            while (matcher.find()) {
                count++;
            }
            if (count > 0) {
                counts.put(name, count);
            }
        }

        assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of(
                "PublicBoardController.java", 1,
                "PublicContentImageController.java", 1,
                "PublicBannerImageController.java", 1,
                "PublicPopupImageController.java", 1));
    }

    @Test
    @DisplayName("공개 코드에서 FileStorage를 쓰는 클래스는 승인된 서비스 4개뿐이다")
    void fileStorageUsersAreApproved() throws IOException {
        Set<String> users = javaFiles().stream()
                .filter(file -> {
                    try {
                        return Files.readString(file).contains("import com.cms.common.storage.FileStorage;");
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .map(file -> file.getFileName().toString())
                .collect(Collectors.toSet());

        assertThat(users).containsExactlyInAnyOrder(
                "PublicBoardService.java",
                "PublicContentImageService.java",
                "PublicBannerService.java",
                "PublicPopupService.java");
    }

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> paths = Files.walk(PUBLIC_WEB)) {
            return paths.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }
}
