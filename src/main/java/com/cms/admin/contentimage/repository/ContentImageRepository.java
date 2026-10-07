package com.cms.admin.contentimage.repository;

import com.cms.admin.contentimage.domain.ContentImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ContentImageRepository extends JpaRepository<ContentImage, Long> {

    /** 주어진 ID 중 실제로 있는 것만 — 본문에 남은 없는 이미지 ID는 참조에서 제외한다(쟁점 6). */
    @Query("select i.id from ContentImage i where i.id in :ids")
    List<Long> findExistingIds(@Param("ids") Collection<Long> ids);
}
