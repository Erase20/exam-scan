package com.examscan.modules.answer.dto;

import lombok.Data;

import java.util.List;

/**
 * 答题卡快速设置答案请求：按题目批量写入标准答案
 */
@Data
public class AnswerSetRequest {
    private List<Item> items;

    @Data
    public static class Item {
        private Long questionId;
        private String answer;
    }
}
