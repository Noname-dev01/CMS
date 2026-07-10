package com.cms.admin.member.repository;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
     * 타 관리자 수정(PATCH) 시 대상 row를 PESSIMISTIC_WRITE로 잠근 뒤 조회한다.
     * Member에 @Version이 없고 Hibernate는 전체 컬럼을 UPDATE하므로, 같은 대상에 대한
     * 동시 PATCH가 낡은 status/userType을 되쓰는 lost update를 행 잠금으로 차단한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Member m where m.id = :id")
    Optional<Member> findByIdForUpdate(@Param("id") Long id);

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
