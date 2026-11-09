package com.examscan.modules.paper.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("paper_item")
public class PaperItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    @JsonProperty("paperId")
    private Long paperId;
    @JsonProperty("questionId")
    private Long questionId;
    @JsonProperty("sectionNo")
    private Integer sectionNo;
    @JsonProperty("sectionName")
    private String sectionName;
    @JsonProperty("qType")
    private String qType;
    @JsonProperty("sortNo")
    private Integer sortNo;
    private BigDecimal score;
}
