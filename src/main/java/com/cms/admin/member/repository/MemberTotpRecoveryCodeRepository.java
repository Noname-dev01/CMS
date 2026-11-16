package com.cms.admin.member.repository;

import com.cms.admin.member.domain.MemberTotpRecoveryCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** 복구 코드. 변경 경로는 {@code member} 행 잠금 아래에서만 호출한다. */
public interface MemberTotpRecoveryCodeRepository extends JpaRepository<MemberTotpRecoveryCode, Long> {

    Optional<MemberTotpRecoveryCode> findFirstByMemberIdAndCodeHashAndUsedAtIsNull(Long memberId, String codeHash);

    long countByMemberIdAndUsedAtIsNull(Long memberId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from MemberTotpRecoveryCode c where c.memberId = :memberId")
    int deleteAllByMemberId(@Param("memberId") Long memberId);
}
