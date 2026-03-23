package com.cms.admin.member.repository;

import com.cms.admin.member.domain.Member;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long>,MemberRepositoryCustom {
    Optional<Member> findByUserId(String userId);
    Optional<Member> findByEmail(String email);
    Optional<Member> findByResetToken(String resetToken);
    boolean existsByUserId(String userId);
    boolean existsByEmail(String email);
}
