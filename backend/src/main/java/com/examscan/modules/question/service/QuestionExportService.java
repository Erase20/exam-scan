package com.examscan.modules.question.service;

import com.examscan.common.QuestionType;
import com.examscan.modules.question.dto.QuestionQuery;
import com.examscan.modules.question.entity.Question;
import com.examscan.modules.question.mapper.QuestionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 题库导出为 Excel。
 *
 * 导出的列与「题库导入模板」完全一致，导出 → 编辑 → 再导入可直接闭环。
 * 支持按当前筛选条件导出，或只导出指定的若干道题。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuestionExportService {

    /** 导出列：与导入模板表头一致 */
    private static final String[] HEADERS = {
            "题号", "题型", "题干", "选项A", "选项B", "选项C", "选项D",
            "答案", "解析", "难度", "分值", "知识点", "学科", "年级", "大题标题", "来源", "课序"
    };
    private static final int[] WIDTHS = {
            8, 10, 50, 18, 18, 18, 18, 10, 30, 6, 6, 14, 8, 8, 12, 8, 8
    };

    private final QuestionMapper questionMapper;
    private final QuestionSearchService searchService;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * 导出题库
     *
     * @param q   筛选条件（关键词/题型/学科/年级/难度/知识点）
     * @param ids 指定题目 ID；为空则按筛选条件导出全部
     */
    public byte[] export(QuestionQuery q, List<Long> ids) {
        List<Question> list = collect(q, ids);
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("题库");
            CellStyle head = headStyle(wb);
            CellStyle body = bodyStyle(wb);

            Row hr = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell c = hr.createCell(i);
                c.setCellValue(HEADERS[i]);
                c.setCellStyle(head);
                sheet.setColumnWidth(i, WIDTHS[i] * 256);
            }

            int r = 1;
            for (Question item : list) {
                Row row = sheet.createRow(r++);
                Map<String, String> opts = optionMap(item.getOptions());
                put(row, body, 0, item.getQNo());
                put(row, body, 1, typeName(item.getQType()));
                put(row, body, 2, item.getStem());
                put(row, body, 3, opts.get("A"));
                put(row, body, 4, opts.get("B"));
                put(row, body, 5, opts.get("C"));
                put(row, body, 6, opts.get("D"));
                put(row, body, 7, item.getAnswer());
                put(row, body, 8, item.getAnalysis());
                put(row, body, 9, item.getDifficulty() == null ? "" : String.valueOf(item.getDifficulty()));
                put(row, body, 10, item.getScore() == null ? "" : item.getScore().stripTrailingZeros().toPlainString());
                put(row, body, 11, item.getKnowledge());
                put(row, body, 12, item.getSubject());
                put(row, body, 13, item.getGrade());
                put(row, body, 14, item.getSectionTitle());
                put(row, body, 15, sourceName(item.getSource()));
                put(row, body, 16, item.getLessonOrder() == null ? "" : String.valueOf(item.getLessonOrder()));
            }
            sheet.createFreezePane(0, 1);
            wb.write(out);
            log.info("导出题库 {} 道", list.size());
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("生成导出文件失败: " + e.getMessage());
        }
    }

    /** 取要导出的题目：优先指定 ID，其次筛选条件 */
    private List<Question> collect(QuestionQuery q, List<Long> ids) {
        if (ids != null && !ids.isEmpty()) {
            List<Question> out = new ArrayList<>();
            for (Question item : questionMapper.selectBatchIds(ids)) {
                out.add(item);
            }
            // 保持与页面勾选一致的顺序
            out.sort((a, b) -> Integer.compare(ids.indexOf(a.getId()), ids.indexOf(b.getId())));
            return out;
        }
        if (q == null || q.getPage() == null || q.getPage() < 1) {
            q.setPage(1);
        }
        q.setSize(100000);
        return searchService.listAll(q);
    }

    /** 选项 JSON → key → content */
    private Map<String, String> optionMap(String raw) {
        Map<String, String> m = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return m;
        }
        try {
            var arr = json.readTree(raw);
            if (arr != null && arr.isArray()) {
                for (var n : arr) {
                    m.put(n.path("key").asText("").trim(), n.path("content").asText("").trim());
                }
            }
        } catch (Exception ignored) {
            // 选项不是标准 JSON 时忽略，题干仍可导出
        }
        return m;
    }

    private String typeName(String code) {
        return QuestionType.of(code).getLabel();
    }

    private String sourceName(String code) {
        if (code == null) {
            return "";
        }
        return switch (code) {
            case "SCAN" -> "扫描";
            case "IMPORT" -> "导入";
            case "MANUAL" -> "手动";
            default -> code;
        };
    }

    private void put(Row row, CellStyle style, int idx, String value) {
        Cell c = row.createCell(idx);
        c.setCellValue(value == null ? "" : value);
        c.setCellStyle(style);
    }

    private CellStyle headStyle(XSSFWorkbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        f.setFontHeightInPoints((short) 11);
        s.setFont(f);
        s.setAlignment(HorizontalAlignment.CENTER);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        return s;
    }

    private CellStyle bodyStyle(XSSFWorkbook wb) {
        CellStyle s = wb.createCellStyle();
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        s.setWrapText(true);
        return s;
    }
}
