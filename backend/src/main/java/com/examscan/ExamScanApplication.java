package com.examscan;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.examscan.modules.**.mapper")
public class ExamScanApplication {
    public static void main(String[] args) {
        SpringApplication.run(ExamScanApplication.class, args);
    }
}
