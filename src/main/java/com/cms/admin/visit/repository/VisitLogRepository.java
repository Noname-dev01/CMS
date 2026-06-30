package com.cms.admin.visit.repository;

import com.cms.admin.visit.domain.VisitLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;

public interface VisitLogRepository extends JpaRepository<VisitLog, Long> {

    /**
     * 반열린구간 [start, end) 내 방문 수를 반환한다.
     * 오늘/이번달 카운트에 사용하며, 경계 중복을 방지한다.
     */
    long countByVisitAtGreaterThanEqualAndVisitAtLessThan(LocalDateTime start, LocalDateTime end);
}
