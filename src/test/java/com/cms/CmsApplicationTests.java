package com.cms;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// WebEnvironment.NONE: 웹 레이어 없이 빈 생성만 검증한다.
// 웹 환경을 켜면 requestMappingHandlerMapping 초기화 시 슬라이스 테스트용 스텁 컨트롤러와
// 실제 컨트롤러의 URL이 충돌한다. 웹 동작은 @WebMvcTest 슬라이스에서 검증한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CmsApplicationTests {

    @Test
    void contextLoads() {
    }

}
