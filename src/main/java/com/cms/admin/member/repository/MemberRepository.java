package com.cms.admin.member.repository;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long>, MemberRepositoryCustom {

    Optional<Member> findByUserId(String userId);
    Optional<Member> findByEmail(String email);
    Optional<Member> findByResetToken(String resetToken);
    boolean existsByUserId(String userId);
    boolean existsByEmail(String email);

    /**
     * 지정 역할·상태 제외 조건으로 이번 달 가입 회원 수를 반환한다.
     * 대시보드 신규회원 카운트에 사용한다(ROLE_ADMIN·ROLE_MANAGER, DELETED 제외).
     * 반열린구간 [start, end)으로 경계 중복을 방지한다.
     */
    long countByUserTypeInAndStatusNotAndCreateDateGreaterThanEqualAndCreateDateLessThan(
            Collection<Role> userTypes,
            MemberStatus status,
            LocalDateTime start,
            LocalDateTime end
    );
}
