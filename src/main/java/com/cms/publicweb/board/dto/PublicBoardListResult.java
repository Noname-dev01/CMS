package com.cms.publicweb.board.dto;

import org.springframework.data.domain.Page;

/**
 * 공개 목록 조회 결과. {@code keyword}는 서비스가 정규화한 검색어(검색하지 않았으면 {@code null})이며 화면이 검색 폼 값·페이지 링크에
 * 그대로 쓴다. {@code boardName}은 화면 머리에 표시한다(사용자 입력 — 템플릿은 {@code th:text}만 쓴다).
 */
public record PublicBoardListResult(Long boardId, String boardName, Page<PublicPostSummary> page, String keyword) {
}
