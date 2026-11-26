package com.examscan.modules.paper.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("paper")
public class Paper {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String subject;
    private String grade;
    @JsonProperty("totalScore")
    private BigDecimal totalScore;
    private Integer duration;
    @JsonProperty("questionCount")
    private Integer questionCount;
    @JsonProperty("paperType")
    private String paperType;
    @JsonProperty("configJson")
    private String configJson;
    private String status;
    private String remark;
    /** 所属文件夹（分类管理；null=未分类） */
    private Long folderId;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    @TableLogic
    private Integer deleted;
}
