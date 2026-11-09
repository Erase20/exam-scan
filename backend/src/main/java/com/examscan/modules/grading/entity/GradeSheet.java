package com.examscan.modules.grading.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("grade_sheet")
public class GradeSheet {
    @TableId(type = IdType.AUTO)
    private Long id;
    @JsonProperty("sheetId")
    private Long sheetId;
    @JsonProperty("paperId")
    private Long paperId;
    @JsonProperty("studentNo")
    private String studentNo;
    @JsonProperty("className")
    private String className;
    @JsonProperty("studentName")
    private String studentName;
    @JsonProperty("imagePath")
    private String imagePath;
    @JsonProperty("objectiveScore")
    private BigDecimal objectiveScore;
    @JsonProperty("subjectiveScore")
    private BigDecimal subjectiveScore;
    @JsonProperty("totalScore")
    private BigDecimal totalScore;
    @JsonProperty("fullScore")
    private BigDecimal fullScore;
    private String status;
    @JsonProperty("detailJson")
    private String detailJson;
    /** 阅卷模式：ocr=全部OCR / fast=快速截图（不调 OCR，只判客观题） */
    @JsonProperty("mode")
    private String mode;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableLogic
    private Integer deleted;
}
