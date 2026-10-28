package com.cms.publicweb.board.dto;

import org.springframework.data.domain.Page;

/**
 * 공개 목록 조회 결과. {@code keyword}는 서비스가 정규화한 검색어(검색하지 않았으면 {@code null})이며 화면이 검색 폼 값·페이지 링크에
 * 그대로 쓴다. {@code boardName}·{@code description}·{@code authorLabel}은 사용자 입력이라 템플릿이 {@code th:text}만 쓴다.
 * 설명(평문)은 없으면 null, 작성자 표시명은 게시판 단위 고정값(없으면 null = 표시 안 함)이고 목록 행마다 같은 값이라 한 번만 싣는다.
 */
public record PublicBoardListResult(Long boardId, String boardName, String description, String authorLabel,
                                    Page<PublicPostSummary> page, String keyword) {
}
