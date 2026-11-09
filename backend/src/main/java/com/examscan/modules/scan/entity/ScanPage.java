package com.examscan.modules.scan.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("scan_page")
public class ScanPage {
    @TableId(type = IdType.AUTO)
    private Long id;
    @JsonProperty("docId")
    private Long docId;
    @JsonProperty("pageNo")
    private Integer pageNo;
    @JsonProperty("imagePath")
    private String imagePath;
    private Integer width;
    private Integer height;
    private LocalDateTime createTime;
}
