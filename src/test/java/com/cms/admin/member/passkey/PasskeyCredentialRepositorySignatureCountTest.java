package com.cms.admin.member.passkey;

import com.cms.admin.member.domain.MemberPasskey;
import com.cms.admin.member.repository.MemberPasskeyRepository;
import com.cms.admin.member.repository.MemberPasskeyUserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCose;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 서명 횟수 역행 거부(PLAN-passkey-login.md 결정 8) — 라이브러리가 검증 성공 뒤 부르는 어댑터 {@code save}의 기존 행 갱신 갈래가
 * 저장된 최신 횟수와 비교한다(라이브러리는 등록 당시 횟수와만 비교한다). 둘 다 0이면 횟수를 구현하지 않는 인증기라 통과.
 */
class PasskeyCredentialRepositorySignatureCountTest {

    private static final byte[] CREDENTIAL_ID = {1, 2, 3};
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 11, 19, 9, 0);

    private final MemberPasskeyRepository passkeyRepository = mock(MemberPasskeyRepository.class);
    private final PasskeyCredentialRepository adapter = new PasskeyCredentialRepository(
            mock(MemberPasskeyUserRepository.class), passkeyRepository, Clock.fixed(Instant.parse("2026-11-19T01:00:00Z"), ZoneId.of("Asia/Seoul")));

    private MemberPasskey stored(long signatureCount) {
        MemberPasskey passkey = MemberPasskey.registration()
                .memberId(1L).credentialId(CREDENTIAL_ID).publicKey(new byte[]{9}).signatureCount(signatureCount)
                .uvInitialized(true).backupEligible(false).backupState(false).transports("")
                .attestationObject(new byte[]{7}).attestationClientDataJson(new byte[]{8}).label("키").createdAt(CREATED)
                .build();
        when(passkeyRepository.findByCredentialId(CREDENTIAL_ID)).thenReturn(Optional.of(passkey));
        return passkey;
    }

    private static CredentialRecord used(long signatureCount) {
        return ImmutableCredentialRecord.builder()
                .credentialType(PublicKeyCredentialType.PUBLIC_KEY)
                .credentialId(new Bytes(CREDENTIAL_ID))
                .userEntityUserId(new Bytes(new byte[]{5}))
                .publicKey(new ImmutablePublicKeyCose(new byte[]{9}))
                .signatureCount(signatureCount)
                .uvInitialized(true)
                .transports(Set.of())
                .backupEligible(false)
                .backupState(false)
                .label("키")
                .created(Instant.now())
                .lastUsed(Instant.now())
                .build();
    }

    @ParameterizedTest(name = "저장 {0} → 제출 {1}: 거부")
    @CsvSource({"100, 1", "5, 5", "5, 0"})
    @DisplayName("저장된 최신 횟수 이하(둘 중 하나라도 0이 아님)면 예외를 던지고 행을 바꾸지 않는다")
    void regressionRejected(long storedCount, long submitted) {
        MemberPasskey passkey = stored(storedCount);

        assertThatThrownBy(() -> adapter.save(used(submitted))).isInstanceOf(PasskeySignatureCountException.class);

        assertThat(passkey.getSignatureCount()).isEqualTo(storedCount);
        assertThat(passkey.getLastUsedAt()).isNull();
    }

    @ParameterizedTest(name = "저장 {0} → 제출 {1}: 갱신")
    @CsvSource({"0, 0", "5, 6", "0, 3"})
    @DisplayName("둘 다 0(횟수 미구현 인증기)이거나 증가하면 갱신한다")
    void advanceAccepted(long storedCount, long submitted) {
        MemberPasskey passkey = stored(storedCount);

        adapter.save(used(submitted));

        assertThat(passkey.getSignatureCount()).isEqualTo(submitted);
        assertThat(passkey.getLastUsedAt()).isNotNull();
    }
}
