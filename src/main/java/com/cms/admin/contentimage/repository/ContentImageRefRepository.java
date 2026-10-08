package com.cms.admin.contentimage.repository;

import com.cms.admin.contentimage.domain.ContentImageRef;
import com.cms.admin.contentimage.domain.ContentImageRefId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ContentImageRefRepository extends JpaRepository<ContentImageRef, ContentImageRefId> {

    /**
     * 벌크 삭제. 영속성 컨텍스트를 비우지 않는다(clearAutomatically 금지) — 호출하는 공지 저장 트랜잭션의
     * {@code Notice} 변경(더티 체킹)이 함께 사라지기 때문이다. 참조 엔티티는 조회해 두지 않으므로 비울 필요가 없다.
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from ContentImageRef r where r.ownerType = :ownerType and r.ownerId = :ownerId")
    int deleteByOwner(@Param("ownerType") String ownerType, @Param("ownerId") Long ownerId);

    List<ContentImageRef> findByOwnerTypeAndOwnerIdOrderByImageIdAsc(String ownerType, Long ownerId);

    /**
     * 공개 공지(노출·미삭제)가 이 이미지를 참조하는지. 공개 조건은 {@code PublicNoticeService}의 공지 공개 조건
     * ({@code useYn=true AND deleted=false})과 같아야 한다. 연관관계 매핑이 없어 세타 조인으로 쓴다.
     */
    @Query("select count(r) > 0 from ContentImageRef r, Notice n, ContentImage i"
            + " where r.imageId = :imageId and r.ownerType = 'NOTICE' and n.id = r.ownerId"
            + " and n.useYn = true and n.deleted = false"
            // 출처 일치(PLAN-board.md 리뷰 R2-1): 공지 출처 이미지만 공지 참조로 공개된다 — 롤백 중 출처 검증 없이 생긴 참조로는 공개되지 않는다
            + " and i.id = r.imageId and i.scopeType = 'NOTICE'")
    boolean existsPublishedNoticeRef(@Param("imageId") Long imageId);
}
