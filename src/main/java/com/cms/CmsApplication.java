package com.cms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

@SpringBootApplication
public class CmsApplication {

    public static void main(String[] args) {
        // 통계 기준 시간대를 KST로 전역 고정한다.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        SpringApplication.run(CmsApplication.class, args);
    }

}
