package com.cms.admin.contentimage.repository;

import com.cms.admin.contentimage.domain.ContentImageUsage;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ContentImageUsageRepository extends JpaRepository<ContentImageUsage, Long> {

    /** 업로드 상한 집행용 카운터 행 잠금(쟁점 9). "ForUpdate"는 파생 쿼리 접미사가 아니라 명시 @Query + @Lock. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from ContentImageUsage u where u.id = :id")
    Optional<ContentImageUsage> findByIdForUpdate(@Param("id") Long id);
}
