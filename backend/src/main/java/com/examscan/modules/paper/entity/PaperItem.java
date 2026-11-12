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
    /**
     * 选项随机重排映射（JSON 数组，如 "[2,0,3,1]"）：
     * 新位置 i 显示原选项 order[i] 的内容；null = 未重排。
     * Word 导出、预览渲染与机器判分都按它同步映射，保证"卷面选项顺序 = 判分涂点顺序"。
     */
    @JsonProperty("optionOrder")
    private String optionOrder;
}
