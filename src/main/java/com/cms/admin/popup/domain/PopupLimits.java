package com.cms.admin.popup.domain;

/** 팝업 배치(px) 입력 범위(PLAN-popup.md 쟁점 7). 요청 DTO의 Bean Validation과 서비스 검증이 같은 값을 쓴다. */
public final class PopupLimits {

    public static final int POS_MIN = 0;
    public static final int POS_MAX = 2000;
    public static final int WIDTH_MIN = 200;
    public static final int WIDTH_MAX = 1000;
    /** 머리 40 + 내용 최소 48 + 바닥 48 = 136px에 여유 8px — 이보다 낮으면 flex:none인 머리·바닥이 팝업을 넘어 바닥 버튼이 잘린다(코드 리뷰 R1). */
    public static final int HEIGHT_MIN = 144;
    public static final int HEIGHT_MAX = 1000;

    /** 등록 폼이 값을 비웠을 때의 기본 배치. */
    public static final int DEFAULT_POS = 100;
    public static final int DEFAULT_WIDTH = 400;

    private PopupLimits() {
    }
}
