package com.cms.support;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

// @SpringBootTest 전체 스캔 시 테스트 전용 스텁 컨트롤러가 실제 컨트롤러와 URL 충돌하는 문제를 방지한다.
// com.cms.support 하위 패키지에 위치해 @WebMvcTest가 com.cms 상위 탐색 시 이 클래스를 발견하지 못하도록 격리한다.
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(
        basePackages = "com.cms",
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
                @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
                @ComponentScan.Filter(type = FilterType.REGEX,
                        pattern = "com\\.cms\\.config\\..*(?:Stub|Test)Controller")
        }
)
public class CmsTestApplication {
}
