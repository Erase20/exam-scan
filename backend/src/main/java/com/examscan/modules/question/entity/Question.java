package com.examscan.modules.question.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("question")
public class Question {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long docId;
    private Long blockId;
    private Integer pageNo;
    @JsonProperty("qNo")
    private String qNo;
    @JsonProperty("qType")
    private String qType;
    /** 大题序号（一/二/三 → 1/2/3），0 表示未识别到题型大类 */
    private Integer sectionOrder;
    /** 大题标题原文，如「填空题」「判断题」 */
    private String sectionTitle;
    /** 课号：课程编号（字符串，如 K03 / 第3课 / 3），可为空，随题保存并可编辑，用于按课号筛选 */
    @TableField("course_no")
    @JsonProperty("courseNo")
    private String courseNo;
    /** 卷内连续序号：按「大题顺序 → 小题号」排序后的 1..N */
    private Integer seq;
    private String stem;
    private String options;
    private String answer;
    private String analysis;
    private Integer difficulty;
    private BigDecimal score;
    private String subject;
    private String grade;
    private String knowledge;
    private Integer hasImage;
    private String imagePath;
    private String source;
    private String status;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    @TableLogic
    private Integer deleted;
}
