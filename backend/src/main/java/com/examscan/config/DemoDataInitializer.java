package com.examscan.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;

/**
 * 演示数据初始化：仅当 question 表为空时导入，避免重复启动产生重复数据
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DemoDataInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Value("${spring.sql.init.data-locations:}")
    private String dataLocations;

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        try {
            Integer cnt = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM question", Integer.class);
            if (cnt != null && cnt > 0) {
                log.info("题库已有 {} 道题，跳过演示数据导入", cnt);
                return;
            }
            ClassPathResource res = new ClassPathResource("db/data-demo.sql");
            if (!res.exists()) {
                return;
            }
            String sql = StreamUtils.copyToString(res.getInputStream(), StandardCharsets.UTF_8);
            // 按分号拆分成单条语句执行（避免 H2/MySQL 差异）
            for (String stmt : sql.split(";\\s*\\n")) {
                String s = stmt.trim();
                if (s.isEmpty() || s.startsWith("--")) {
                    continue;
                }
                // 去掉行内注释行
                StringBuilder sb = new StringBuilder();
                for (String line : s.split("\n")) {
                    if (!line.trim().startsWith("--")) {
                        sb.append(line).append('\n');
                    }
                }
                String cleaned = sb.toString().trim();
                if (cleaned.isEmpty()) {
                    continue;
                }
                jdbcTemplate.execute(cleaned);
            }
            Integer after = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM question", Integer.class);
            log.info("演示题库导入完成，共 {} 道题", after);
        } catch (Exception e) {
            log.warn("演示数据导入失败: {}", e.getMessage());
        }
    }
}
