package com.cms.admin.member.repository;

import com.cms.admin.member.domain.MemberPasskeyUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 패스키 user handle. 쓰기 경로는 먼저 {@code member} 행 잠금({@link MemberRepository#findByIdForUpdate})을 잡는다. */
public interface MemberPasskeyUserRepository extends JpaRepository<MemberPasskeyUser, Long> {

    Optional<MemberPasskeyUser> findByUserHandle(byte[] userHandle);
}
