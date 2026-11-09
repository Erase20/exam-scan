package com.examscan.modules.paper.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 自定义组卷请求：用户在题库中逐题勾选并设定分值
 */
@Data
public class CustomPaperRequest {
    private String title;
    private String subject;
    private String grade;
    private Integer duration = 90;
    /** 试卷备注 */
    private String remark;
    /** 逐题明细，按提交顺序决定卷内顺序 */
    private List<Item> items;

    @Data
    public static class Item {
        /** 题目ID */
        private Long questionId;
        /** 分值 */
        private BigDecimal score;
    }
}
