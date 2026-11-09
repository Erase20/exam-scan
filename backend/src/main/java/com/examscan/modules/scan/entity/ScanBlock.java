package com.examscan.modules.scan.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("scan_block")
public class ScanBlock {
    @TableId(type = IdType.AUTO)
    private Long id;
    @JsonProperty("docId")
    private Long docId;
    @JsonProperty("pageId")
    private Long pageId;
    @JsonProperty("pageNo")
    private Integer pageNo;
    @JsonProperty("blockType")
    private String blockType;
    private Integer x;
    private Integer y;
    private Integer w;
    private Integer h;
    @JsonProperty("sortNo")
    private Integer sortNo;
    private String content;
    private BigDecimal confidence;
    @JsonProperty("imagePath")
    private String imagePath;
    private LocalDateTime createTime;
}
