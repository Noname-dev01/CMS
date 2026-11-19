package com.cms.admin.member.passkey;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberPasskeyUser;
import com.cms.admin.member.repository.MemberPasskeyUserRepository;
import com.cms.admin.member.repository.MemberRepository;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Spring WebAuthn user entity 저장소의 JPA 어댑터(PLAN-passkey-second-factor.md 결정 3). 이름 = {@code member.userId}(불변),
 * id = {@code member_passkey_user.user_handle}. 호출자(서비스)의 트랜잭션·{@code member} 행 잠금 안에서만 쓰인다.
 */
class PasskeyUserEntityRepository implements PublicKeyCredentialUserEntityRepository {

    private final MemberRepository memberRepository;
    private final MemberPasskeyUserRepository userRepository;
    private final Clock clock;

    PasskeyUserEntityRepository(MemberRepository memberRepository, MemberPasskeyUserRepository userRepository, Clock clock) {
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    @Override
    public PublicKeyCredentialUserEntity findById(Bytes id) {
        if (id == null) {
            return null;
        }
        return userRepository.findByUserHandle(id.getBytes())
                .flatMap(user -> memberRepository.findById(user.getMemberId()).map(member -> toEntity(member, user)))
                .orElse(null);
    }

    @Override
    public PublicKeyCredentialUserEntity findByUsername(String username) {
        if (username == null) {
            return null;
        }
        return memberRepository.findByUserId(username)
                .flatMap(member -> userRepository.findById(member.getId()).map(user -> toEntity(member, user)))
                .orElse(null);
    }

    /** 라이브러리가 첫 등록 옵션에서 만든 핸들을 저장한다. 이미 있으면 바꾸지 않는다(회원 행 잠금 아래라 경합 없음 — PK가 두 번째 방어). */
    @Override
    public void save(PublicKeyCredentialUserEntity userEntity) {
        Member member = memberRepository.findByUserId(userEntity.getName())
                .orElseThrow(() -> new IllegalStateException("패스키 사용자 저장 대상 회원 없음"));
        Optional<MemberPasskeyUser> existing = userRepository.findById(member.getId());
        if (existing.isPresent()) {
            return;
        }
        byte[] handle = userEntity.getId().getBytes();
        if (handle.length == 0 || handle.length > MemberPasskeyUser.HANDLE_BYTES) {
            throw new IllegalStateException("패스키 user handle 길이가 범위를 벗어났다: " + handle.length);
        }
        userRepository.saveAndFlush(MemberPasskeyUser.create(member.getId(), handle, LocalDateTime.now(clock)));
    }

    /** 핸들은 지우지 않는다 — 패스키를 모두 지워도 재등록 때 같은 핸들을 쓴다(라이브러리는 이 메서드를 부르지 않는다). */
    @Override
    public void delete(Bytes id) {
        throw new UnsupportedOperationException("패스키 user handle은 지우지 않는다");
    }

    private static PublicKeyCredentialUserEntity toEntity(Member member, MemberPasskeyUser user) {
        return ImmutablePublicKeyCredentialUserEntity.builder()
                .id(new Bytes(user.getUserHandle()))
                .name(member.getUserId())
                .displayName(member.getUserId())
                .build();
    }
}
