package com.cms.admin.board.repository;

/** 게시글 본문의 앞부분 — {@link PostRepository#findContentHeads}의 결과 한 줄. */
public interface PostContentHead {

    Long getId();

    String getHead();
}
