package com.cms.admin.member;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.ProfileImageKind;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.common.storage.FileStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * ProfileImageMigrationRunner 단위 테스트 — 실제 트랜잭션 커밋/롤백 검증은 Testcontainers
 * 통합 테스트가 별도로 담당한다(adversarial-review/plan/PLAN-profile-image-storage.md 쟁점 6).
 */
@ExtendWith(MockitoExtension.class)
class ProfileImageMigrationRunnerTest {

    @Mock
    MemberRepository memberRepository;

    @Mock
    FileStorage fileStorage;

    @Mock
    TransactionTemplate transactionTemplate;

    private ProfileImageMigrationRunner runner;

    @BeforeEach
    void setUp() {
        runner = new ProfileImageMigrationRunner(memberRepository, fileStorage, transactionTemplate);

        // TransactionTemplate 목이 실제로 콜백을 실행하도록 스텁(AdminBootstrapLoaderTest와 동일 패턴).
        given(transactionTemplate.execute(any())).willAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        // migrateWithinTransaction()이 FileStorageTransactionSupport(TransactionSynchronizationManager
        // 사용)를 호출한다 — 실제 트랜잭션 없이 이를 호출 가능하게 하려면 동기화를 수동 활성화해야
        // 한다(AdminMemberServiceTest·NoticeAttachmentServiceTest와 동일 패턴).
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.initSynchronization();
        }
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private byte[] validPngBytes() throws IOException {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    @DisplayName("정상 이관 — data URI를 디코딩해 FileStorage에 저장하고 kind를 UPLOADED로 바꾼다")
    void run_migratesLegacyInlineImage() throws IOException {
        byte[] png = validPngBytes();
        String dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
        Member member = Member.builder().id(1L).profileImageKind(ProfileImageKind.LEGACY_INLINE).profileImageUrl(dataUri).build();

        given(memberRepository.findIdsByProfileImageKind(ProfileImageKind.LEGACY_INLINE)).willReturn(List.of(1L));
        given(memberRepository.resetIfOversizedLegacyImage(eq(1L), anyInt())).willReturn(0);
        given(memberRepository.findByIdForUpdate(1L)).willReturn(Optional.of(member));
        given(fileStorage.store(eq(png), anyString(), eq("profile"))).willReturn("2026/08/10/migrated.png");

        runner.run();

        assertEquals(ProfileImageKind.UPLOADED, member.getProfileImageKind());
        assertEquals("2026/08/10/migrated.png", member.getProfileImageUrl());
        assertEquals("image/png", member.getProfileImageContentType());
    }

    @Test
    @DisplayName("크기 초과 행은 findByIdForUpdate 없이 벌크 UPDATE만으로 처리되고 이관을 시도하지 않는다")
    void run_oversizedRow_skipsEntityLoad() {
        given(memberRepository.findIdsByProfileImageKind(ProfileImageKind.LEGACY_INLINE)).willReturn(List.of(1L));
        given(memberRepository.resetIfOversizedLegacyImage(eq(1L), anyInt())).willReturn(1);

        runner.run();

        verify(memberRepository, never()).findByIdForUpdate(any());
        verify(fileStorage, never()).store(any(), any(), any());
    }

    @Test
    @DisplayName("재검증 — 락 획득 시점에 이미 다른 요청이 처리했다면(kind != LEGACY_INLINE) 스킵한다")
    void run_alreadyChangedByAnotherRequest_skips() {
        Member member = Member.builder().id(1L).profileImageKind(ProfileImageKind.PRESET)
                .profileImageUrl("/img/undraw_profile_1.svg").build();
        given(memberRepository.findIdsByProfileImageKind(ProfileImageKind.LEGACY_INLINE)).willReturn(List.of(1L));
        given(memberRepository.resetIfOversizedLegacyImage(eq(1L), anyInt())).willReturn(0);
        given(memberRepository.findByIdForUpdate(1L)).willReturn(Optional.of(member));

        runner.run();

        verify(fileStorage, never()).store(any(), any(), any());
        assertEquals(ProfileImageKind.PRESET, member.getProfileImageKind());
    }

    @Test
    @DisplayName("화이트리스트 밖 MIME(webp)은 스킵하고 LEGACY_INLINE으로 남긴다")
    void run_disallowedMime_skipsAndKeepsLegacyInline() {
        String dataUri = "data:image/webp;base64,AAAA";
        Member member = Member.builder().id(1L).profileImageKind(ProfileImageKind.LEGACY_INLINE).profileImageUrl(dataUri).build();
        given(memberRepository.findIdsByProfileImageKind(ProfileImageKind.LEGACY_INLINE)).willReturn(List.of(1L));
        given(memberRepository.resetIfOversizedLegacyImage(eq(1L), anyInt())).willReturn(0);
        given(memberRepository.findByIdForUpdate(1L)).willReturn(Optional.of(member));

        runner.run();

        assertEquals(ProfileImageKind.LEGACY_INLINE, member.getProfileImageKind());
        verify(fileStorage, never()).store(any(), any(), any());
    }

    @Test
    @DisplayName("손상된 Base64는 스킵하고 LEGACY_INLINE으로 남긴다")
    void run_malformedBase64_skipsAndKeepsLegacyInline() {
        String dataUri = "data:image/png;base64,***not-base64***";
        Member member = Member.builder().id(1L).profileImageKind(ProfileImageKind.LEGACY_INLINE).profileImageUrl(dataUri).build();
        given(memberRepository.findIdsByProfileImageKind(ProfileImageKind.LEGACY_INLINE)).willReturn(List.of(1L));
        given(memberRepository.resetIfOversizedLegacyImage(eq(1L), anyInt())).willReturn(0);
        given(memberRepository.findByIdForUpdate(1L)).willReturn(Optional.of(member));

        runner.run();

        assertEquals(ProfileImageKind.LEGACY_INLINE, member.getProfileImageKind());
    }

    @Test
    @DisplayName("data: 형태가 아닌 잔여값(catch-all로 분류된 손상값)은 스킵한다")
    void run_notDataUri_skips() {
        Member member = Member.builder().id(1L).profileImageKind(ProfileImageKind.LEGACY_INLINE)
                .profileImageUrl("garbage-value").build();
        given(memberRepository.findIdsByProfileImageKind(ProfileImageKind.LEGACY_INLINE)).willReturn(List.of(1L));
        given(memberRepository.resetIfOversizedLegacyImage(eq(1L), anyInt())).willReturn(0);
        given(memberRepository.findByIdForUpdate(1L)).willReturn(Optional.of(member));

        runner.run();

        assertEquals(ProfileImageKind.LEGACY_INLINE, member.getProfileImageKind());
    }

    @Test
    @DisplayName("한 행이 예외를 던져도 다른 행 처리를 계속한다(행 단위 격리)")
    void run_oneRowThrows_othersContinue() throws IOException {
        byte[] png = validPngBytes();
        String validDataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);

        given(memberRepository.findIdsByProfileImageKind(ProfileImageKind.LEGACY_INLINE)).willReturn(List.of(1L, 2L));
        given(memberRepository.resetIfOversizedLegacyImage(eq(1L), anyInt())).willThrow(new RuntimeException("DB 오류"));
        given(memberRepository.resetIfOversizedLegacyImage(eq(2L), anyInt())).willReturn(0);
        Member member2 = Member.builder().id(2L).profileImageKind(ProfileImageKind.LEGACY_INLINE).profileImageUrl(validDataUri).build();
        given(memberRepository.findByIdForUpdate(2L)).willReturn(Optional.of(member2));
        given(fileStorage.store(eq(png), anyString(), eq("profile"))).willReturn("2026/08/10/ok.png");

        assertDoesNotThrow(() -> runner.run());

        assertEquals(ProfileImageKind.UPLOADED, member2.getProfileImageKind());
    }
}
