package com.cms.admin.notice.repository;

import com.cms.admin.notice.domain.Notice;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface NoticeRepository extends JpaRepository<Notice, Long>, NoticeRepositoryCustom {

    /** 단건 조회용 — 락 없음. 삭제된 공지는 대상에서 제외한다. */
    Optional<Notice> findByIdAndDeletedFalse(Long id);

    /**
     * PATCH·DELETE·첨부 업로드·첨부 삭제 전용 — 비관적 락으로 대상 row를 잠근다.
     * "ForUpdate"는 Spring Data 파생 쿼리의 예약 접미사가 아니므로 메서드명만으로
     * 선언하면 존재하지 않는 프로퍼티를 찾다가 애플리케이션 기동에 실패한다.
     * MenuRepository.findByIdForUpdate와 동일하게 명시적 @Query + @Lock으로 선언한다.
     * 첨부 업로드·삭제도 이 락을 재사용해 동일 notice의 첨부 개수 상한(5개) 검사와
     * 동시 삭제 경합을 직렬화한다(adversarial-review/plan/PLAN-notice-attachment.md 쟁점 4).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from Notice n where n.id = :id and n.deleted = false")
    Optional<Notice> findByIdAndDeletedFalseForUpdate(@Param("id") Long id);
}
