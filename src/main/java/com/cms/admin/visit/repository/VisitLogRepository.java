package com.cms.admin.visit.repository;

import com.cms.admin.visit.domain.VisitLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface VisitLogRepository extends JpaRepository<VisitLog, Long> {

    /**
     * 반열린구간 [start, end) 내 방문 수를 반환한다.
     * 오늘/이번달 카운트에 사용하며, 경계 중복을 방지한다.
     */
    long countByVisitAtGreaterThanEqualAndVisitAtLessThan(LocalDateTime start, LocalDateTime end);

    /**
     * 반열린구간 [start, end)의 일별 방문 수를 날짜 오름차순으로 반환한다.
     * 방문이 없는 날은 행이 없다 — 0 채움은 서비스 책임.
     *
     * <p>row[0]의 실제 타입은 JDBC 드라이버·Hibernate 함수 타입 해석에 좌우된다
     * (LocalDate/java.sql.Date/String 가능) — 변환 방어는 서비스가 담당한다.
     */
    @Query("select function('date', v.visitAt), count(v) "
            + "from VisitLog v "
            + "where v.visitAt >= :start and v.visitAt < :end "
            + "group by function('date', v.visitAt) "
            + "order by function('date', v.visitAt)")
    List<Object[]> countDailyVisits(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}
