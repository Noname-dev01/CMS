package com.cms.admin.member.repository;

import com.cms.admin.member.domain.MemberPasskey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 패스키. 쓰기 경로는 먼저 {@code member} 행 잠금({@link MemberRepository#findByIdForUpdate})을 잡는다(PLAN-passkey-second-factor.md 결정 3).
 * "유효한" 조회는 현재 TOTP 등록 이후에 만든 행만 돌려준다(결정 1) — TOTP 행이 없으면 아무것도 없다.
 */
public interface MemberPasskeyRepository extends JpaRepository<MemberPasskey, Long> {

    Optional<MemberPasskey> findByCredentialId(byte[] credentialId);

    @Query("select p from MemberPasskey p, MemberTotp t where t.memberId = p.memberId and p.memberId = :memberId"
            + " and p.createdAt >= t.enabledAt order by p.id")
    List<MemberPasskey> findValidByMemberId(@Param("memberId") Long memberId);

    @Query("select count(p) from MemberPasskey p, MemberTotp t where t.memberId = p.memberId and p.memberId = :memberId"
            + " and p.createdAt >= t.enabledAt")
    long countValidByMemberId(@Param("memberId") Long memberId);

    /** 회원의 패스키를 모두 지운다(TOTP 해제·리셋 연동). 앞선 변경을 먼저 flush하고 영속성 컨텍스트를 비운다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from MemberPasskey p where p.memberId = :memberId")
    int deleteAllByMemberId(@Param("memberId") Long memberId);

    /** 유효 조건을 어긴 행(현재 TOTP가 없거나 그 등록보다 먼저 만든 것)이 있는 회원 ID — 기동 정리 대상. */
    @Query("select distinct p.memberId from MemberPasskey p where not exists ("
            + "select 1 from MemberTotp t where t.memberId = p.memberId and t.enabledAt <= p.createdAt)")
    List<Long> findMemberIdsWithStalePasskeys();

    /** 한 회원의 무효 행을 지운다(그 회원 행 잠금 아래에서 부른다). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from MemberPasskey p where p.memberId = :memberId and not exists ("
            + "select 1 from MemberTotp t where t.memberId = p.memberId and t.enabledAt <= p.createdAt)")
    int deleteStaleByMemberId(@Param("memberId") Long memberId);
}
