package com.cms.admin.faq.repository;

import com.cms.admin.faq.domain.FaqLock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface FaqLockRepository extends JpaRepository<FaqLock, Long> {

    /** 모든 FAQ·카테고리 쓰기의 첫 DB 조회 — 가드 행을 비관적 락으로 잡는다(명시 @Query + @Lock). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from FaqLock l where l.id = :id")
    Optional<FaqLock> findByIdForUpdate(@Param("id") Long id);
}
