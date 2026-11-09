package com.examscan.modules.docimport;

import lombok.Data;

import java.util.List;

/**
 * 文档批量导入结果
 */
@Data
public class ImportResult {
    /** 文件名 */
    private String fileName;
    /** 文件类型 */
    private String fileType;
    /** 提取的文本行数 */
    private int textLines;
    /** 切分出的题目数 */
    private int questionCount;
    /** 成功入库题目数：解析预览阶段恒为 0，确认入库数量由 /confirm 返回 */
    private int savedCount;
    /** 导入警告 */
    private List<String> warnings;
    /** 入库的题目 ID：解析预览阶段为空 */
    private List<Long> savedIds;
    /** 解析出的可编辑题目草稿，确认后才写入题库 */
    private List<?> questions;
}
