package com.examscan.modules.paper.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 组卷配置：按题型指定题量、每题分值，形成"命题双向细目表"
 */
@Data
public class PaperConfig {
    private String title;
    private String subject;
    private String grade;
    private Integer duration = 90;
    /** 总分要求（用于校验，可为空表示不校验） */
    private BigDecimal targetScore;
    /** 难度范围 1-5（用于自动选题时筛选） */
    private Integer minDifficulty;
    private Integer maxDifficulty;
    /** 知识点过滤（逗号分隔，命中任一即可） */
    private String knowledge;
    /** 章节配置 */
    private List<SectionConfig> sections;

    @Data
    public static class SectionConfig {
        /** 题型 code */
        @JsonProperty("qType")
        private String qType;
        /** 大题名称，为空则用题型默认名 */
        private String sectionName;
        /** 小题数量 */
        private Integer count;
        /** 每题分值 */
        private BigDecimal scorePerQuestion;
        /** 该大题小分合计（自动计算，可覆盖） */
        private BigDecimal subTotal;
        /** 手工指定的题目ID（不为空时优先使用） */
        private List<Long> questionIds;
    }
}
