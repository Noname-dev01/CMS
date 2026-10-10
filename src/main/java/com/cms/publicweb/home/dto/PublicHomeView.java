package com.cms.publicweb.home.dto;

import com.cms.publicweb.banner.dto.PublicBanner;

import java.util.List;

/** 공개 메인 화면 모델 — 배너, 공지(공지 게시판 최신 글), 새 글(공지 게시판 제외 공개 게시판 최신 글). */
public record PublicHomeView(List<PublicBanner> banners, List<PublicHomePost> notices, List<PublicHomePost> latestPosts) {
}
