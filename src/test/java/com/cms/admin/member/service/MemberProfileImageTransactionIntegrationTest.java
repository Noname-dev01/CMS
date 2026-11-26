package com.cms.admin.member.service;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.common.storage.FileStorage;
import com.cms.common.storage.StorageFileNotFoundException;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.TestMembers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 프로필 이미지 쓰기 3종(업로드·초기화·프리셋)의 실제 트랜잭션 계약
 * (adversarial-review/plan/PLAN-member-profile-image-service.md 결정 5-c).
 *
 * <p>커밋되면 옛 업로드 파일이 지워지고, 롤백되면 새 파일은 지워지되 옛 파일과 DB 값은 남는다.
 * 단위 시험(목 리포지토리·수동 동기화)으로는 정리 콜백이 실제 커밋·롤백에 맞춰 도는지 볼 수 없어 실제 MariaDB로 확인한다.
 * 롤백은 바깥 트랜잭션에 합류시킨 뒤 {@code setRollbackOnly()}로 만든다(서비스 메서드는 {@code REQUIRED}).
 */
@SpringBootTest(classes = CmsTestApplication.class)
class MemberProfileImageTransactionIntegrationTest extends MariaDbContainerSupport {

    private static final String NAMESPACE = "profile";

    @Autowired MemberProfileImageService profileImageService;
    @Autowired MemberRepository memberRepository;
    @Autowired FileStorage fileStorage;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired JdbcTemplate jdbc;

    private Long memberId;
    /** 시험이 만든 파일 키 — 정리 대상. 서비스 호출 직후(롤백 시험은 트랜잭션 안에서) 기록한다. */
    private final List<String> storedKeys = new ArrayList<>();

    @BeforeEach
    void setUp() {
        memberId = TestMembers.save(memberRepository, "profile-tx", Role.ROLE_MANAGER).getId();
    }

    /** 키마다 따로 지우고(없는 파일은 no-op) 회원 삭제는 반드시 실행한 뒤, 모은 오류를 던진다 — 정리가 중간에 멈춰 누수가 생기지 않게. */
    @AfterEach
    void cleanUp() {
        RuntimeException failure = null;
        try {
            for (String key : storedKeys) {
                try {
                    fileStorage.delete(key, NAMESPACE);
                } catch (RuntimeException e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
        } finally {
            TestMembers.delete(jdbc, List.of(memberId));
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Test
    @DisplayName("업로드 교체가 커밋되면 옛 파일은 지워지고 새 파일만 남는다")
    void uploadCommitDeletesPreviousFile() throws IOException {
        String firstKey = upload(10);
        byte[] second = pngBytes(12);

        profileImageService.updateMyProfileImage(memberId, pngFile(second));
        String secondKey = recordCurrentKey();

        assertThat(secondKey).isNotEqualTo(firstKey);
        assertMissing(firstKey);
        assertThat(fileStorage.load(secondKey, NAMESPACE)).isEqualTo(second);
        assertThat(profileRow()).containsEntry("profile_image_kind", "UPLOADED");
    }

    @Test
    @DisplayName("업로드 교체가 롤백되면 새 파일은 지워지고 옛 파일과 DB 값은 남는다")
    void uploadRollbackDeletesNewFileAndKeepsPrevious() throws IOException {
        String firstKey = upload(10);
        MockMultipartFile next = pngFile(pngBytes(12));

        String newKey = transactionTemplate.execute(status -> {
            profileImageService.updateMyProfileImage(memberId, next);
            String key = memberRepository.findById(memberId).orElseThrow().getProfileImageUrl();
            storedKeys.add(key);
            status.setRollbackOnly();
            return key;
        });

        assertThat(newKey).isNotEqualTo(firstKey);
        assertMissing(newKey);
        assertThat(fileStorage.load(firstKey, NAMESPACE)).isNotEmpty();
        assertThat(profileRow())
                .containsEntry("profile_image_kind", "UPLOADED")
                .containsEntry("profile_image_url", firstKey);
    }

    @Test
    @DisplayName("초기화가 롤백되면 업로드 파일과 DB 값이 남고, 커밋되면 파일이 지워지고 NONE이 된다")
    void resetRollbackKeepsFileAndCommitDeletesIt() throws IOException {
        String key = upload(10);

        transactionTemplate.executeWithoutResult(status -> {
            profileImageService.resetMyProfileImage(memberId);
            status.setRollbackOnly();
        });

        assertThat(fileStorage.load(key, NAMESPACE)).isNotEmpty();
        assertThat(profileRow())
                .containsEntry("profile_image_kind", "UPLOADED")
                .containsEntry("profile_image_url", key);

        profileImageService.resetMyProfileImage(memberId);

        assertMissing(key);
        assertThat(profileRow())
                .containsEntry("profile_image_kind", "NONE")
                .containsEntry("profile_image_url", null)
                .containsEntry("profile_image_content_type", null);
    }

    @Test
    @DisplayName("프리셋 선택이 롤백되면 업로드 파일과 DB 값이 남고, 커밋되면 파일이 지워지고 PRESET이 된다")
    void presetRollbackKeepsFileAndCommitDeletesIt() throws IOException {
        String key = upload(10);

        transactionTemplate.executeWithoutResult(status -> {
            profileImageService.applyDefaultProfileImage(memberId, "profile-1");
            status.setRollbackOnly();
        });

        assertThat(fileStorage.load(key, NAMESPACE)).isNotEmpty();
        assertThat(profileRow())
                .containsEntry("profile_image_kind", "UPLOADED")
                .containsEntry("profile_image_url", key);

        profileImageService.applyDefaultProfileImage(memberId, "profile-1");

        assertMissing(key);
        assertThat(profileRow())
                .containsEntry("profile_image_kind", "PRESET")
                .containsEntry("profile_image_url", "/img/undraw_profile_1.svg");
    }

    /** 커밋되는 업로드 1회 — 저장된 키를 기록해 돌려준다. */
    private String upload(int size) throws IOException {
        profileImageService.updateMyProfileImage(memberId, pngFile(pngBytes(size)));
        return recordCurrentKey();
    }

    private String recordCurrentKey() {
        String key = (String) profileRow().get("profile_image_url");
        storedKeys.add(key);
        return key;
    }

    private Map<String, Object> profileRow() {
        return jdbc.queryForMap(
                "SELECT profile_image_kind, profile_image_url, profile_image_content_type FROM member WHERE id = ?",
                memberId);
    }

    private void assertMissing(String key) {
        assertThatThrownBy(() -> fileStorage.load(key, NAMESPACE)).isInstanceOf(StorageFileNotFoundException.class);
    }

    private static MockMultipartFile pngFile(byte[] content) {
        return new MockMultipartFile("file", "avatar.png", "image/png", content);
    }

    private static byte[] pngBytes(int size) throws IOException {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
