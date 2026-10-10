package com.cms.admin.banner.repository;

import com.cms.admin.banner.domain.BannerLock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BannerLockRepository extends JpaRepository<BannerLock, Long> {

    /** 생성·삭제·순서 저장의 첫 DB 조회 — 가드 행을 비관적 락으로 잡는다(명시 @Query + @Lock). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from BannerLock l where l.id = :id")
    Optional<BannerLock> findByIdForUpdate(@Param("id") Long id);
}
