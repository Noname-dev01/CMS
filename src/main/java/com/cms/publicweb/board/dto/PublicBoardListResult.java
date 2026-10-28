package com.cms.publicweb.board.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * 공개 목록 조회 결과. {@code keyword}는 서비스가 정규화한 검색어(검색하지 않았으면 {@code null})이며 화면이 검색 폼 값·페이지 링크에
 * 그대로 쓴다. {@code boardName}·{@code description}·{@code authorLabel}은 사용자 입력이라 템플릿이 {@code th:text}만 쓴다.
 * 설명(평문)은 없으면 null, 작성자 표시명은 게시판 단위 고정값(없으면 null = 표시 안 함)이고 목록 행마다 같은 값이라 한 번만 싣는다.
 * {@code pinned}는 상단 고정 영역(항상 non-null) — 검색하지 않은 1페이지에서 게시판 고정 상한이 0보다 클 때만 채워지고 그 밖에는 빈 목록이다.
 * 고정 글은 일반 목록 {@code page}에서도 빠지지 않는다(총 페이지 수 불변). {@code boardSlug}는 공개 링크용 현재 슬러그다.
 */
public record PublicBoardListResult(Long boardId, String boardSlug, String boardName, String description, String authorLabel,
                                    List<PublicPostSummary> pinned, Page<PublicPostSummary> page, String keyword) {
}
