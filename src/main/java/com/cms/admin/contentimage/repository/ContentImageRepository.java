package com.cms.admin.contentimage.repository;

import com.cms.admin.contentimage.domain.ContentImage;
import org.springframework.data.jpa.repository.JpaRepository;

/** 참조 교체의 존재·출처 확인은 {@code findAllById}로 이미지 행을 읽어 서비스가 비교한다(PLAN-board.md 쟁점 9). */
public interface ContentImageRepository extends JpaRepository<ContentImage, Long> {
}
