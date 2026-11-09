package com.examscan.modules.answer.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("answer_sheet")
public class AnswerSheet {
    @TableId(type = IdType.AUTO)
    private Long id;
    @JsonProperty("paperId")
    private Long paperId;
    private String title;
    @JsonProperty("layoutJson")
    private String layoutJson;
    @JsonProperty("pageCount")
    private Integer pageCount;
    @JsonProperty("markerCount")
    private Integer markerCount;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableLogic
    private Integer deleted;
}
