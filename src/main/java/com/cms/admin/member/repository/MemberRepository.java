package com.cms.admin.member.repository;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long>, MemberRepositoryCustom {

    Optional<Member> findByUserId(String userId);
    Optional<Member> findByEmail(String email);
    Optional<Member> findByResetToken(String resetToken);
    boolean existsByUserId(String userId);
    boolean existsByEmail(String email);

    /**
     * prod 초기 관리자 부트스트랩 트리거 판정 전용(단순 존재 확인 — 잠금 불필요).
     * 진짜 직렬화가 필요한 지점(동시 부트스트랩 저장 경합)은 {@code uk_member_user_id}
     * 유니크 제약이 맡는다(PLAN-prod-profile.md 결정 4).
     */
    boolean existsByUserTypeAndStatus(Role userType, MemberStatus status);

    /**
     * 타 관리자 수정(PATCH) 시 대상 row를 PESSIMISTIC_WRITE로 잠근 뒤 조회한다.
     * Member에 @Version이 없고 Hibernate는 전체 컬럼을 UPDATE하므로, 같은 대상에 대한
     * 동시 PATCH가 낡은 status/userType을 되쓰는 lost update를 행 잠금으로 차단한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Member m where m.id = :id")
    Optional<Member> findByIdForUpdate(@Param("id") Long id);

    /**
     * 비밀번호 재설정 발급 시 계정 row를 PESSIMISTIC_WRITE로 잠근 뒤 조회한다.
     * 쿨다운 검사~토큰 저장이 check-then-act 경합 없이 직렬화되어,
     * 동시 요청 2건이 모두 토큰을 발급·발송하는 중복을 차단한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Member m where m.email = :email")
    Optional<Member> findByEmailForUpdate(@Param("email") String email);

    /**
     * 재설정 토큰 해시로 회원 id만 조회한다 — 비밀번호 재설정 전용.
     *
     * <p>스칼라(id) 반환인 이유: 엔티티를 영속성 컨텍스트에 올리면 이후
     * {@link #findByIdForUpdate(Long)} 잠금 재조회가 1차 캐시의 낡은 resetToken을
     * 돌려줄 수 있다. List 반환은 같은 해시가 2행 이상인 데이터 오염 탐지 겸용.
     */
    @Query("select m.id from Member m where m.resetToken = :resetToken")
    List<Long> findIdsByResetToken(@Param("resetToken") String resetToken);

    /**
     * 재설정 토큰을 조건부로 클리어한다 — 메일 발송 실패 시 best-effort 정리 전용.
     * 현재 저장된 해시가 내가 발급한 해시와 일치할 때만 지워, 그 사이 다른 요청이
     * 발급한 최신 토큰을 덮어쓰지 않는다. 벌크 UPDATE는 영속성 컨텍스트를 우회하므로
     * clear/flush 옵션으로 stale entity를 방어한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Member m set m.resetToken = null, m.resetTokenExpiryAt = null "
            + "where m.id = :id and m.resetToken = :hashedToken")
    int clearResetTokenIfMatches(@Param("id") Long id, @Param("hashedToken") String hashedToken);

    /**
     * 활성(ACTIVE) ADMIN 회원의 id를 행 잠금과 함께 조회한다 — 최후 활성 ADMIN 가드 전용.
     *
     * <p>스칼라(id) 반환인 이유: 엔티티 반환 잠금 쿼리는 영속성 컨텍스트에 이미 로드된
     * 인스턴스의 낡은 필드를 돌려줄 수 있어(1차 캐시) 필드 기반 판정을 원천 봉쇄한다.
     * 네이티브 SQL인 이유: JPQL 스칼라 프로젝션 + @Lock은 JPA 표준 보장 밖이라
     * 프로바이더가 조용히 FOR UPDATE를 생략할 수 있다 — SQL에 명시해 재량을 제거한다.
     * ORDER BY id로 잠금 순서를 고정해 가드끼리는 첫 행에서 직렬화된다.
     */
    @Query(value = "SELECT id FROM member WHERE user_type = 'ROLE_ADMIN' AND status = 'ACTIVE' ORDER BY id FOR UPDATE",
            nativeQuery = true)
    List<Long> findActiveAdminIdsForUpdate();

    /**
     * ACTIVE인 관리자(ADMIN/MANAGER) 계정의 로그인 실패 카운트를 DB에서 원자적으로 1 증가시킨다.
     * 엔티티 조회 후 +1 저장은 동시 실패 시 lost update가 나므로 벌크 UPDATE로 고정.
     * 상태 조건은 상태 변경과 경합해도 비ACTIVE 계정에 카운트가 숨어 누적되지 않게 하고,
     * 역할 조건은 관리자 로그인 폼으로 일반 회원(ROLE_USER)이 오잠금되는 것을 차단한다.
     */
    @Modifying(clearAutomatically = true)
    @Query("update Member m set m.failedLoginCount = m.failedLoginCount + 1 "
            + "where m.userId = :userId and m.status = com.cms.admin.member.domain.MemberStatus.ACTIVE "
            + "and m.userType in (com.cms.admin.member.domain.Role.ROLE_ADMIN, com.cms.admin.member.domain.Role.ROLE_MANAGER)")
    int increaseFailedLoginCount(@Param("userId") String userId);

    /**
     * 실패 카운트가 임계값 이상인 ACTIVE 관리자 계정을 LOCKED로 전이하고 잠금 시각을 기록한다.
     * 조건부 UPDATE라 동시 실패에도 멱등(전이는 1회만 성공). 잠금 시각은 앱 시계(:now)로
     * 기록한다 — 자동 해제 cutoff와 동일한 시간 기준(DB 시계 사용 금지).
     */
    @Modifying(clearAutomatically = true)
    @Query("update Member m set m.status = com.cms.admin.member.domain.MemberStatus.LOCKED, "
            + "m.lockedAt = :now, m.updateDate = :now "
            + "where m.userId = :userId and m.status = com.cms.admin.member.domain.MemberStatus.ACTIVE "
            + "and m.failedLoginCount >= :threshold "
            + "and m.userType in (com.cms.admin.member.domain.Role.ROLE_ADMIN, com.cms.admin.member.domain.Role.ROLE_MANAGER)")
    int lockIfThresholdReached(@Param("userId") String userId, @Param("threshold") int threshold,
                               @Param("now") LocalDateTime now);

    /**
     * 성공 로그인 시 실패 카운트를 리셋한다 — ACTIVE 조건부 벌크 UPDATE.
     * 엔티티 더티체킹 저장은 전체/변경 컬럼 UPDATE가 경합 시 LOCKED를 되살릴 수 있어 금지.
     * 성공 직후 다른 요청이 먼저 잠갔다면 0행(no-op)으로 잠금이 보존된다.
     */
    @Modifying(clearAutomatically = true)
    @Query("update Member m set m.failedLoginCount = 0, m.lockedAt = null "
            + "where m.userId = :userId and m.status = com.cms.admin.member.domain.MemberStatus.ACTIVE "
            + "and m.failedLoginCount > 0 "
            + "and m.userType in (com.cms.admin.member.domain.Role.ROLE_ADMIN, com.cms.admin.member.domain.Role.ROLE_MANAGER)")
    int resetFailedLoginCountIfActive(@Param("userId") String userId);

    /**
     * 만료된 자동 잠금을 해제한다. 수동 잠금(locked_at null)은 대상이 아니며,
     * 조건부 UPDATE라 동시 로그인 경합에도 멱등. cutoff·now 모두 앱 시계에서 계산해 전달한다.
     */
    @Modifying(clearAutomatically = true)
    @Query("update Member m set m.status = com.cms.admin.member.domain.MemberStatus.ACTIVE, "
            + "m.failedLoginCount = 0, m.lockedAt = null, m.updateDate = :now "
            + "where m.userId = :userId and m.status = com.cms.admin.member.domain.MemberStatus.LOCKED "
            + "and m.lockedAt is not null and m.lockedAt <= :cutoff "
            + "and m.userType in (com.cms.admin.member.domain.Role.ROLE_ADMIN, com.cms.admin.member.domain.Role.ROLE_MANAGER)")
    int unlockIfLockExpired(@Param("userId") String userId, @Param("cutoff") LocalDateTime cutoff,
                            @Param("now") LocalDateTime now);

    /**
     * 비밀번호가 90일에 도달한 ACTIVE 관리자 계정을 PASSWORD_EXPIRED로 전이한다.
     * 조건부 벌크 UPDATE라 잠금·재설정 경합에서 최신 상태를 덮어쓰지 않고 동시 감지에도 멱등.
     * 역할 조건은 재설정 자격이 없는 ROLE_USER가 자가 복구 불가 상태에 빠지는 것을 차단한다.
     * cutoff·now 모두 앱 시계에서 계산해 전달한다(잠금 쿼리들과 동일 계약 — DB 시계 사용 금지).
     */
    @Modifying(clearAutomatically = true)
    @Query("update Member m set m.status = com.cms.admin.member.domain.MemberStatus.PASSWORD_EXPIRED, "
            + "m.updateDate = :now "
            + "where m.userId = :userId and m.status = com.cms.admin.member.domain.MemberStatus.ACTIVE "
            + "and m.passwordChangedAt <= :cutoff "
            + "and m.userType in (com.cms.admin.member.domain.Role.ROLE_ADMIN, com.cms.admin.member.domain.Role.ROLE_MANAGER)")
    int expirePasswordIfOutdated(@Param("userId") String userId, @Param("cutoff") LocalDateTime cutoff,
                                 @Param("now") LocalDateTime now);

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
