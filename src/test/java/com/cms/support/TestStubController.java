package com.cms.support;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.*;

/**
 * @WebMvcTest 슬라이스 테스트 전용 스텁 컨트롤러 마커.
 * CmsTestApplication의 FilterType.ANNOTATION 제외 필터로 full-context 스캔에서 자동 배제된다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@RestController
@Profile("webmvc-test")
public @interface TestStubController {
}
