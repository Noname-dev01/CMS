package com.cms.admin.contentimage.repository;

import com.cms.admin.contentimage.domain.ContentImageRef;
import com.cms.admin.contentimage.domain.ContentImageRefId;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface ContentImageRefRepository extends JpaRepository<ContentImageRef, ContentImageRefId> {

    /**
     * 벌크 삭제. 영속성 컨텍스트를 비우지 않는다(clearAutomatically 금지) — 호출하는 공지 저장 트랜잭션의
     * {@code Post} 변경(더티 체킹)이 함께 사라지기 때문이다. 참조 엔티티는 조회해 두지 않으므로 비울 필요가 없다.
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from ContentImageRef r where r.ownerType = :ownerType and r.ownerId = :ownerId")
    int deleteByOwner(@Param("ownerType") String ownerType, @Param("ownerId") Long ownerId);

    List<ContentImageRef> findByOwnerTypeAndOwnerIdOrderByImageIdAsc(String ownerType, Long ownerId);

    /**
     * 소유자가 지금 참조 중인 이미지 ID만 읽는다(엔티티를 영속성 컨텍스트에 올리지 않는다) — {@code deleteByOwner}는 컨텍스트를 비우지 않으므로,
     * 참조 엔티티를 먼저 읽어 두면 같은 트랜잭션의 {@code saveAll}이 "이미 관리 중"으로 보고 INSERT를 건너뛰어 참조가 사라진다.
     */
    @Query("select r.imageId from ContentImageRef r where r.ownerType = :ownerType and r.ownerId = :ownerId")
    List<Long> findImageIdsByOwner(@Param("ownerType") String ownerType, @Param("ownerId") Long ownerId);

    /** 배너·팝업 일괄 조회용 — 소유자 ID 집합의 참조를 한 번에 읽는다(N+1 방지, PLAN-media-library.md D4). */
    List<ContentImageRef> findByOwnerTypeAndOwnerIdIn(String ownerType, Collection<Long> ownerIds);

    /** 사용처 목록·사용 여부 표시용 — 이미지 ID 집합의 모든 참조(소유자 종류 무관)를 한 번에 읽는다. */
    List<ContentImageRef> findByImageIdIn(Collection<Long> imageIds);

    /**
     * 이미지를 지우기 전 "아직 참조가 남았는지" 확인용 <b>잠금 읽기</b>(FOR UPDATE). 호출자는 먼저 이미지 행을 {@code FOR UPDATE}로 잡고
     * {@code READ_COMMITTED} 트랜잭션이어야 한다 — REPEATABLE READ의 비고유 인덱스 잠금 읽기는 빈 범위에 갭 잠금을 걸어 다른 이미지에 대한
     * 참조 INSERT를 막을 수 있다(PLAN-media-library.md D5·R3-1). 일반 읽기로 바꾸면 낡은 스냅샷을 볼 수 있다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ContentImageRef r where r.imageId = :imageId")
    List<ContentImageRef> lockByImageId(@Param("imageId") Long imageId);

    /**
     * 공개 게시글이 이 이미지를 참조하는지(PLAN-board.md 쟁점 9·D-6). 공개 조건은 {@code PublicBoardService}의 공개 불변식과 같아야 한다 —
     * 게시판 {@code publicYn ∧ ¬deleted ∧ (공통 ∨ 소속 사이트 활성 — PR ④, 이미지 경로는 사이트 무관이라 사이트 비활성만 거른다)}, 게시글 {@code useYn ∧ ¬deleted}, 게시글이 그 게시판 소속. 노출 기간 {@code [publishStart, publishEnd)}도 만족해야 한다(예약·만료 글의 이미지가 글보다 먼저/오래 공개되지 않게). 이미지 출처는 {@code BOARD}이고 참조한
     * 게시글의 게시판과 같아야 한다(롤백 중 출처 검증 없이 생긴 참조로는 공개되지 않는다). 라이브러리 계열 출처({@code LIBRARY}·{@code BANNER}·{@code POPUP})는
     * 게시판과 무관하게 공개 글이 참조하면 공개된다 — 저장 시점에 MEDIA 읽기 권한을 요구한다(PLAN-media-library.md D3). {@code r.imageId = :imageId}가 빠지면 다른 공개 글의
     * 참조가 아무 이미지나 공개하므로 반드시 요청 이미지와 연결한다.
     */
    @Query("select count(r) > 0 from ContentImageRef r, Post p, Board b, ContentImage i"
            + " where r.imageId = :imageId and r.ownerType = 'POST' and p.id = r.ownerId"
            + " and p.useYn = true and p.deleted = false"
            + " and (p.publishStart is null or p.publishStart <= :now) and (p.publishEnd is null or p.publishEnd > :now)"
            + " and b.id = p.boardId and b.publicYn = true and b.deleted = false"
            + " and (b.siteId is null or exists (select 1 from Site s where s.id = b.siteId and s.useYn = true))"
            + " and i.id = r.imageId"
            + " and ((i.scopeType = 'BOARD' and i.scopeId = p.boardId) or i.scopeType in ('LIBRARY', 'BANNER', 'POPUP'))")
    boolean existsPublishedPostRef(@Param("imageId") Long imageId, @Param("now") LocalDateTime now);
}
