package com.examscan.modules.question.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class QuestionQuery {
    private Integer page = 1;
    private Integer size = 10;
    private String keyword;
    /** 仅导出/操作指定的题目（为空表示按条件全部） */
    private List<Long> ids;
    /** 排序字段（题库表头点击排序）：qType / subject / grade / knowledge / seq / createTime */
    private String sortBy;
    /** 排序方向：asc / desc */
    private String sortOrder;
    @JsonProperty("qType")
    private String qType;
    private String subject;
    private String grade;
    private String knowledge;
    private Integer difficulty;
    private Integer minDifficulty;
    private Integer maxDifficulty;
    private Integer hasImage;
    /** 课序筛选：单选=单个元素，多选=多个元素；为空表示不按课序筛选 */
    private List<Integer> lessonOrder;
    /** 仅筛选「课序未设置」的题目 */
    private Boolean lessonOrderEmpty;
}
