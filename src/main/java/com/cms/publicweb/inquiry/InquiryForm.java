package com.cms.publicweb.inquiry;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 공개 문의 폼 바인딩 객체. 검증은 Bean Validation이 아니라 {@code InquiryInputPolicy}가 한다(원문 크기 방어 포함) — {@code publicweb}의 예외 advice가
 * 검증 예외를 500으로 바꾸므로 컨트롤러가 폼을 직접 다시 그린다(PLAN-inquiry.md 쟁점 3). 뷰에는 이스케이프되는 {@code th:value}/{@code th:text}로만 되돌린다.
 */
@Getter
@Setter
@NoArgsConstructor
public class InquiryForm {

    private String name;
    private String email;
    private String title;
    private String body;
    private boolean consent;
}
