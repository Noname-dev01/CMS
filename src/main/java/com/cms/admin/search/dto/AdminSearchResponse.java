package com.cms.admin.search.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 통합 검색 응답. 현재 사용자에게 권한상 보이지 않는 섹션은 빈 배열이 아니라 <b>키 자체를 생략</b>한다(null + NON_NULL).
 */
@Getter
@Builder
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AdminSearchResponse {

    private String keyword;
    private Section<MenuItem> menus;
    private Section<NoticeItem> notices;
    private Section<PostItem> posts;
    private Section<MemberItem> members;

    /** 섹션당 표시 건수(items)와 사용자가 볼 수 있는 전체 건수(total). */
    @Getter
    @AllArgsConstructor
    public static class Section<T> {
        private long total;
        private List<T> items;
    }

    @Getter
    @AllArgsConstructor
    public static class MenuItem {
        private String name;
        /** "상위 > 하위" 표시 경로. */
        private String path;
        private String url;
        private String icon;
    }

    @Getter
    @AllArgsConstructor
    public static class NoticeItem {
        private Long id;
        private String title;
        private Boolean useYn;
        private LocalDateTime createDate;
    }

    /** 게시글은 본문을 담지 않는다. 게시판 이름은 사용자 입력이라 화면이 textContent로만 그린다. */
    @Getter
    @AllArgsConstructor
    public static class PostItem {
        private Long id;
        private Long boardId;
        private String boardName;
        private String title;
        private Boolean useYn;
        private LocalDateTime createDate;
    }

    /** 이메일·프로필 등은 담지 않는다. */
    @Getter
    @AllArgsConstructor
    public static class MemberItem {
        private Long id;
        private String userId;
        private String userName;
        private String userType;
        private String status;
    }
}
