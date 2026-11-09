package com.examscan.modules.scan.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("scan_doc")
public class ScanDoc {
    @TableId(type = IdType.AUTO)
    private Long id;
    @JsonProperty("docName")
    private String docName;
    @JsonProperty("docType")
    private String docType;
    private String subject;
    private String grade;
    @JsonProperty("sourceFile")
    private String sourceFile;
    @JsonProperty("pageCount")
    private Integer pageCount;
    private String status;
    @JsonProperty("ocrEngine")
    private String ocrEngine;
    @JsonProperty("parseMs")
    private Long parseMs;
    private String remark;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    @TableLogic
    private Integer deleted;
}
