package com.cms.admin.popup;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.popup.repository.PopupRepository;
import com.cms.admin.popup.service.PopupService;
import com.cms.common.image.ImageFileValidatorTest;
import com.cms.common.storage.FileStorage;
import com.cms.common.storage.FileStorageProperties;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.TestMembers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * 팝업 이미지 파일의 트랜잭션 정리(PLAN-popup.md 쟁점 4): 등록은 파일을 먼저 저장하므로 저장 실패로 롤백되면 방금 쓴 파일을 지워야 하고(행 없는 파일을
 * 남기지 않는다), 삭제는 커밋 후에만 파일을 지우므로 삭제 트랜잭션이 롤백되면 행과 파일이 모두 남아야 한다. 저장소 계층에 실제 DB 예외를 주입한다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class PopupFileCleanupIntegrationTest extends MariaDbContainerSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberRepository memberRepository;
    @Autowired FileStorage fileStorage;
    @Autowired FileStorageProperties storageProperties;
    @MockitoSpyBean PopupRepository popupRepository;

    private Member admin;
    private long auditBaseline;

    @BeforeEach
    void setUp() {
        admin = TestMembers.save(memberRepository, "pf-admin", Role.ROLE_ADMIN);
        auditBaseline = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM admin_action_log", Long.class);
        cleanPopups();
    }

    @AfterEach
    void cleanUp() {
        Mockito.reset(popupRepository);
        cleanPopups();
        TestMembers.delete(jdbc, List.of(admin.getId()));
        jdbc.update("DELETE FROM admin_action_log WHERE id > ?", auditBaseline);
    }

    private void cleanPopups() {
        for (String key : jdbc.queryForList("SELECT storage_key FROM popup WHERE storage_key IS NOT NULL", String.class)) {
            fileStorage.delete(key, PopupService.STORAGE_NAMESPACE);
        }
        jdbc.update("DELETE FROM popup");
    }

    /** popup 네임스페이스 디렉터리 아래 일반 파일 수. 디렉터리가 아직 없으면 0. */
    private long storedFileCount() throws IOException {
        Path dir = Paths.get(storageProperties.getRoot()).toAbsolutePath().normalize().resolve(PopupService.STORAGE_NAMESPACE);
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    private MvcResult createImage() throws Exception {
        return mockMvc.perform(multipart("/admin/api/popups")
                .file(new MockMultipartFile("image", "p.png", "image/png", ImageFileValidatorTest.png(30, 12)))
                .param("title", "cleanup").param("popupType", "IMAGE")
                .with(TestMembers.asMember(admin)).with(csrf())).andReturn();
    }

    @Test
    @DisplayName("등록: 파일을 쓴 뒤 행 저장이 DB 예외로 실패하면 트랜잭션이 롤백되고 방금 쓴 파일도 지워진다(행 없는 파일을 남기지 않는다)")
    void createRollback_removesStoredFile() throws Exception {
        long filesBefore = storedFileCount();
        Mockito.doThrow(new DataAccessResourceFailureException("주입된 저장 실패")).when(popupRepository).save(Mockito.any());

        MvcResult result = createImage();

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isGreaterThanOrEqualTo(500);
        Mockito.verify(popupRepository).save(Mockito.any());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM popup", Integer.class)).as("행 없음").isZero();
        assertThat(storedFileCount()).as("롤백된 등록의 파일이 남지 않는다").isEqualTo(filesBefore);
    }

    @Test
    @DisplayName("삭제: 행 삭제 중 DB 예외로 롤백되면 행과 이미지 파일이 모두 남는다(파일 삭제는 커밋 후에만)")
    void deleteRollback_keepsRowAndFile() throws Exception {
        MvcResult created = createImage();
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        long id = Long.parseLong(created.getResponse().getContentAsString().replaceAll(".*\"id\":(\\d+).*", "$1"));
        long filesBefore = storedFileCount();
        assertThat(filesBefore).isGreaterThanOrEqualTo(1);
        Mockito.doThrow(new DataAccessResourceFailureException("주입된 삭제 실패")).when(popupRepository).delete(Mockito.any());

        MvcResult result = mockMvc.perform(delete("/admin/api/popups/{id}", id).with(TestMembers.asMember(admin)).with(csrf())).andReturn();

        assertThat(result.getResponse().getStatus()).isGreaterThanOrEqualTo(500);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM popup WHERE id = ?", Integer.class, id)).as("롤백되어 행이 남는다").isEqualTo(1);
        assertThat(storedFileCount()).as("커밋되지 않았으니 파일도 남는다").isEqualTo(filesBefore);
    }
}
