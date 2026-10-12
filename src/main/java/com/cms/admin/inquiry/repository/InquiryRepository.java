package com.cms.admin.inquiry.repository;

import com.cms.admin.inquiry.domain.Inquiry;
import com.cms.admin.inquiry.domain.InquiryStatus;
import com.cms.admin.inquiry.dto.response.InquirySummaryResponse;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface InquiryRepository extends JpaRepository<Inquiry, Long> {

    /** 상태 변경·삭제 전용 — 대상 행 하나를 비관적 락으로 잡아 같은 문의의 PATCH와 DELETE를 직렬화한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Inquiry i where i.id = :id")
    Optional<Inquiry> findByIdForUpdate(@Param("id") Long id);

    /** 목록 — 본문은 싣지 않는다. 최신순(id 내림차순)이라 정렬 파라미터를 받지 않는다. */
    @Query(value = "select new com.cms.admin.inquiry.dto.response.InquirySummaryResponse("
            + "i.id, i.name, i.email, i.title, i.status, i.createDate, i.updateDate) from Inquiry i order by i.id desc",
            countQuery = "select count(i) from Inquiry i")
    Page<InquirySummaryResponse> findSummaries(Pageable pageable);

    @Query(value = "select new com.cms.admin.inquiry.dto.response.InquirySummaryResponse("
            + "i.id, i.name, i.email, i.title, i.status, i.createDate, i.updateDate) from Inquiry i "
            + "where i.status = :status order by i.id desc",
            countQuery = "select count(i) from Inquiry i where i.status = :status")
    Page<InquirySummaryResponse> findSummariesByStatus(@Param("status") InquiryStatus status, Pageable pageable);

    /** 미처리(답변 완료가 아닌) 건수 — 공개 폼의 소프트 상한 판정용. 잠금 없는 일관 읽기다. */
    long countByStatusNot(InquiryStatus status);
}
