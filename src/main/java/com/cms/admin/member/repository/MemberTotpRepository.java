package com.cms.admin.member.repository;

import com.cms.admin.member.domain.MemberTotp;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 2단계 인증 등록. 이 행을 바꾸는 모든 경로는 먼저 {@code member} 행 잠금({@link MemberRepository#findByIdForUpdate})을 잡는다.
 */
public interface MemberTotpRepository extends JpaRepository<MemberTotp, Long> {
}
