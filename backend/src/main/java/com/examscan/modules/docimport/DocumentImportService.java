package com.examscan.modules.docimport;

import com.examscan.common.BizException;
import com.examscan.common.QuestionType;
import com.examscan.modules.question.entity.Question;
import com.examscan.modules.question.mapper.QuestionMapper;
import com.examscan.modules.scan.service.QuestionSplitter;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Word / Excel / PDF 文档批量导入题库
 *
 * 支持格式：
 *  - Word (.docx/.doc)：按段落提取文本，走通用切题流程
 *  - Excel (.xlsx/.xls)：优先识别"题干/题型/答案/解析/难度/分值"表头结构化导入；
 *                        无表头时把每行拼为文本走通用切题流程
 *  - PDF (.pdf)：PDFBox 提取文本，走通用切题流程
 *
 * 科目 / 年级由用户在前端指定，随题目一并入库（按科目、按年级归档题库）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentImportService {

    private final QuestionSplitter splitter;
    private final QuestionMapper questionMapper;
    private final ObjectMapper json = new ObjectMapper();

    private static final Set<String> SUPPORTED = Set.of(".docx", ".doc", ".xlsx", ".xls", ".pdf");

    /**
     * 解析文档 → 返回可编辑的题目草稿，不写题库。
     * 用户在前端核对、修改、删除后调用 confirmQuestions 才真正入库。
     */
    public ImportResult importDocument(MultipartFile file, String subject, String grade, String qTypeOverride) {
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要导入的文档");
        }
        if (subject == null || subject.isBlank()) {
            throw new BizException("请指定科目");
        }
        if (grade == null || grade.isBlank()) {
            throw new BizException("请指定年级");
        }
        String name = file.getOriginalFilename() == null ? "unnamed" : file.getOriginalFilename();
        String ext = extOf(name);
        if (!SUPPORTED.contains(ext)) {
            throw new BizException("不支持的文件格式: " + ext + "（支持 docx/doc/xlsx/xls/pdf）");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new BizException("读取文件失败: " + e.getMessage());
        }

        List<String> warnings = new ArrayList<>();
        List<QuestionSplitter.ParsedQuestion> parsed;
        String fileType;

        if (ext.equals(".xlsx") || ext.equals(".xls")) {
            fileType = ext.equals(".xlsx") ? "WORD_EXCEL" : "EXCEL";
            fileType = "EXCEL";
            parsed = parseExcel(bytes, warnings);
        } else {
            String text;
            if (ext.equals(".pdf")) {
                fileType = "PDF";
                text = parsePdf(bytes);
            } else if (ext.equals(".docx")) {
                fileType = "WORD";
                text = parseDocx(bytes);
            } else {
                fileType = "WORD";
                text = parseDoc(bytes);
            }
            if (text == null || text.isBlank()) {
                throw new BizException("未能从文档中提取到文本（可能是纯图片型 PDF，请改用扫描识别功能）");
            }
            parsed = splitter.split(text);
        }

        // 用户强制指定题型时覆盖自动判定
        if (qTypeOverride != null && !qTypeOverride.isBlank() && !"AUTO".equals(qTypeOverride)) {
            for (QuestionSplitter.ParsedQuestion pq : parsed) {
                pq.setQType(qTypeOverride);
            }
        }

        log.info("文档导入预览：{} -> 科目 {} 年级 {}，切分 {} 题（待确认入库）", name, subject, grade, parsed.size());

        ImportResult result = new ImportResult();
        result.setFileName(name);
        result.setFileType(fileType);
        result.setQuestionCount(parsed.size());
        result.setSavedCount(0);
        result.setWarnings(warnings);
        result.setSavedIds(List.of());
        result.setQuestions(parsed);
        return result;
    }

    /**
     * 确认入库：把人工核对/编辑（修改、复制、删除、调整分值题号）后的草稿写入题库。
     * 表格里自带的学科/年级优先于导入页所选，便于一份表混入多年级题目。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> confirmQuestions(String subject, String grade, List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) {
            throw new BizException("没有可入库的题目");
        }
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> it = items.get(i);
            if (it == null) {
                continue;
            }
            String stem = strOf(it.get("stem"));
            if (stem.isBlank()) {
                continue;   // 空题干行跳过，避免脏数据入库
            }
            Question q = new Question();
            q.setQNo(strOf(it.get("qNo")).isBlank() ? String.valueOf(i + 1) : strOf(it.get("qNo")).trim());
            q.setQType(strOf(it.get("qType")).isBlank() ? "OTHER" : strOf(it.get("qType")).trim());
            q.setStem(stem.trim());
            q.setOptions(optionsJson(it.get("options")));
            q.setAnswer(strOf(it.get("answer")));
            q.setAnalysis(strOf(it.get("analysis")));
            q.setDifficulty(Math.max(1, Math.min(5, intOf(it.get("difficulty"), 3))));
            q.setScore(BigDecimal.valueOf(dblOf(it.get("score"), 5.0)));
            q.setSubject(strOf(it.get("subject")).isBlank() ? subject : strOf(it.get("subject")).trim());
            q.setGrade(strOf(it.get("grade")).isBlank() ? grade : strOf(it.get("grade")).trim());
            q.setSectionOrder(intOf(it.get("sectionOrder"), 0));
            q.setSeq(intOf(it.get("seq"), 0));
            q.setKnowledge(strOf(it.get("knowledge")));
            q.setLessonOrder(intOrNull(it.get("lessonOrder")));
            String imagePath = strOf(it.get("imagePath"));
            q.setHasImage(imagePath.isBlank() ? 0 : 1);
            q.setImagePath(imagePath.isBlank() ? null : imagePath);
            q.setSource("IMPORT");
            q.setStatus("NORMAL");
            q.setDeleted(0);
            questionMapper.insert(q);
            ids.add(q.getId());
        }
        if (ids.isEmpty()) {
            throw new BizException("没有可入库的题目（题干均为空）");
        }
        log.info("文档导入确认入库 {} 道题", ids.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("imported", ids.size());
        out.put("ids", ids);
        return out;
    }

    /** 草稿选项可能是数组（编辑弹窗回传）或 JSON 字符串（题库实体），统一存成 JSON 字符串 */
    private String optionsJson(Object raw) {
        if (raw == null) {
            return "[]";
        }
        if (raw instanceof String s) {
            return s.isBlank() ? "[]" : s;
        }
        try {
            return json.writeValueAsString(raw);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static String strOf(Object o) {
        return o == null ? "" : o.toString();
    }

    private static int intOf(Object o, int def) {
        if (o == null) {
            return def;
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return (int) Double.parseDouble(o.toString().trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static double dblOf(Object o, double def) {
        if (o == null) {
            return def;
        }
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(o.toString().trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static Integer intOrNull(Object o) {
        if (o == null || o.toString().isBlank()) {
            return null;
        }
        try {
            return (int) Double.parseDouble(o.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** ================= Word .docx ================= */
    private String parseDocx(byte[] bytes) {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes));
             XWPFWordExtractor ex = new XWPFWordExtractor(doc)) {
            return ex.getText();
        } catch (Exception e) {
            throw new BizException("解析 Word(.docx) 失败: " + e.getMessage());
        }
    }

    /** ================= Word .doc ================= */
    private String parseDoc(byte[] bytes) {
        try (HWPFDocument doc = new HWPFDocument(new ByteArrayInputStream(bytes));
             WordExtractor ex = new WordExtractor(doc)) {
            return ex.getText();
        } catch (Exception e) {
            throw new BizException("解析 Word(.doc) 失败: " + e.getMessage());
        }
    }

    /** ================= PDF ================= */
    private String parsePdf(byte[] bytes) {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            StringBuilder sb = new StringBuilder();
            for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                sb.append(stripper.getText(doc));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new BizException("解析 PDF 失败: " + e.getMessage());
        }
    }

    // ================= 导入模板 =================

    /** 模板表头（与题库导出的列一致，保证导出后可原样再导入） */
    private static final String[] TEMPLATE_HEADERS = {
            "题号", "题型", "题干", "选项A", "选项B", "选项C", "选项D",
            "答案", "解析", "难度", "分值", "知识点", "学科", "年级", "课序"
    };
    private static final int[] TEMPLATE_WIDTHS = {8, 10, 50, 18, 18, 18, 18, 10, 30, 6, 6, 14, 8, 8, 8};

    /** 模板示例数据：三种常见题型各一题 */
    private static final String[][] TEMPLATE_ROWS = {
            {"1", "选择题", "下列算式中，结果最大的是（　　）", "12-5", "3×4", "18÷3", "7+6", "B", "3×4=12 最大", "2", "3", "数与代数", "数学", "三年级", ""},
            {"2", "填空题", "一个长方形的长是 8 厘米，宽是 5 厘米，它的面积是（　　）平方厘米", "", "", "", "", "40", "面积 = 长 × 宽", "2", "2", "图形与几何", "数学", "三年级", ""},
            {"3", "判断题", "所有的偶数都是合数。（　　）", "", "", "", "", "×", "2 是偶数但不是合数", "3", "2", "数与代数", "数学", "三年级", ""},
    };

    /** 题型取值说明 */
    private static final String[][] TYPE_HINTS = {
            {"题型取值", "说明"},
            {"选择题", "需填写选项A-D，答案填 A/B/C/D"},
            {"填空题", "题干中用（　　）表示空位，答案填具体值"},
            {"判断题", "答案填 √ 或 ×"},
            {"计算题 / 解答题 / 作图题 / 实验探究题", "按实际题型填写"},
            {"留空", "留空时系统按题干特征自动判定"},
    };

    private static final String[][] FIELD_HINTS = {
            {"列名", "必填", "说明"},
            {"题号", "否", "原卷题号，留空时按顺序自动编号"},
            {"题型", "否", "选择题/填空题/判断题/计算题/解答题/作图题/实验探究题"},
            {"题干", "是", "题目正文，必填；该行为空时跳过"},
            {"选项A-选项D", "选择题必填", "四个选项内容，非选择题留空"},
            {"答案", "否", "参考答案"},
            {"解析", "否", "答案解析"},
            {"难度", "否", "1-5 的整数，留空默认 3"},
            {"分值", "否", "数字，留空默认 5"},
            {"知识点", "否", "多个用逗号分隔"},
            {"学科 / 年级", "否", "填写时覆盖导入页所选的科目与年级"},
            {"课序", "否", "整数，留空表示未设置，可在题库按课序筛选"},
    };

    /**
     * 生成题库导入模板（Excel）：首表为待填写的题目表，次表为填写说明。
     */
    public byte[] buildTemplate() {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("题库模板");
            CellStyle head = headStyle(wb, true);
            CellStyle body = bodyStyle(wb);

            Row hr = sheet.createRow(0);
            for (int i = 0; i < TEMPLATE_HEADERS.length; i++) {
                Cell c = hr.createCell(i);
                c.setCellValue(TEMPLATE_HEADERS[i]);
                c.setCellStyle(head);
                sheet.setColumnWidth(i, TEMPLATE_WIDTHS[i] * 256);
            }
            for (int r = 0; r < TEMPLATE_ROWS.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < TEMPLATE_HEADERS.length; c++) {
                    Cell cell = row.createCell(c);
                    cell.setCellValue(TEMPLATE_ROWS[r][c]);
                    cell.setCellStyle(body);
                }
            }
            sheet.createFreezePane(0, 1);

            Sheet tip = wb.createSheet("填写说明");
            int tr = 0;
            for (String[] rowData : FIELD_HINTS) {
                Row row = tip.createRow(tr);
                for (int c = 0; c < rowData.length; c++) {
                    Cell cell = row.createCell(c);
                    cell.setCellValue(rowData[c]);
                    cell.setCellStyle(tr == 0 ? head : body);
                }
                tr++;
            }
            tr++;
            for (String[] rowData : TYPE_HINTS) {
                Row row = tip.createRow(tr);
                for (int c = 0; c < rowData.length; c++) {
                    Cell cell = row.createCell(c);
                    cell.setCellValue(rowData[c]);
                    cell.setCellStyle(tr == 6 ? head : body);
                }
                tr++;
            }
            tip.setColumnWidth(0, 18 * 256);
            tip.setColumnWidth(1, 10 * 256);
            tip.setColumnWidth(2, 42 * 256);

            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new BizException("生成导入模板失败: " + e.getMessage());
        }
    }

    private CellStyle headStyle(XSSFWorkbook wb, boolean bold) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(bold);
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

    /** ================= Excel ================= */
    private static final Set<String> STRUCT_HEADERS = Set.of("题干", "题目", "试题", "题目内容");
    /** 说明类工作表不参与导入 */
    private static final Set<String> SKIP_SHEETS = Set.of("填写说明", "说明", "使用说明", "字段说明");

    /**
     * Excel 导入：
     *  1) 首行含"题干/题目"表头 -> 结构化逐行导入（列：题干、题型、选项A-D、答案、解析、难度、分值、知识点）
     *  2) 否则 -> 每行拼成文本行，交给通用切题器
     */
    private List<QuestionSplitter.ParsedQuestion> parseExcel(byte[] bytes, List<String> warnings) {
        List<QuestionSplitter.ParsedQuestion> out = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            for (int si = 0; si < wb.getNumberOfSheets(); si++) {
                Sheet sheet = wb.getSheetAt(si);
                String sheetName = sheet.getSheetName();
                if (sheetName != null && SKIP_SHEETS.contains(sheetName.trim())) {
                    continue;
                }
                Row header = sheet.getRow(sheet.getFirstRowNum());
                if (isStructHeader(header)) {
                    out.addAll(parseStructSheet(sheet, warnings));
                } else {
                    out.addAll(parsePlainSheet(sheet, warnings, si));
                }
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("解析 Excel 失败: " + e.getMessage());
        }
        return out;
    }

    private boolean isStructHeader(Row header) {
        if (header == null) {
            return false;
        }
        for (int c = 0; c < 10 && c < header.getLastCellNum(); c++) {
            Cell cell = header.getCell(c);
            String v = cell == null ? "" : strCell(cell).trim();
            if (STRUCT_HEADERS.contains(v)) {
                return true;
            }
        }
        return false;
    }

    /** 结构化导入：题干 | 题型 | 选项A | 选项B | 选项C | 选项D | 答案 | 解析 | 难度 | 分值 | 知识点 */
    private List<QuestionSplitter.ParsedQuestion> parseStructSheet(Sheet sheet, List<String> warnings) {
        Map<String, Integer> col = new LinkedHashMap<>();
        Row header = sheet.getRow(sheet.getFirstRowNum());
        for (int c = 0; c < header.getLastCellNum(); c++) {
            Cell cell = header.getCell(c);
            if (cell != null) {
                col.put(strCell(cell).trim(), c);
            }
        }
        List<QuestionSplitter.ParsedQuestion> out = new ArrayList<>();
        for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            String stem = getCol(row, col, "题干", "题目", "试题", "题目内容");
            if (stem == null || stem.isBlank()) {
                continue;
            }
            QuestionSplitter.ParsedQuestion q = new QuestionSplitter.ParsedQuestion();
            String qno = getCol(row, col, "题号", "序号");
            q.setQNo(qno == null || qno.isBlank() ? String.valueOf(out.size() + 1) : qno.trim());
            q.setStem(stem.trim());

            List<Map<String, String>> options = new ArrayList<>();
            for (String k : new String[]{"A", "B", "C", "D"}) {
                String v = getCol(row, col, "选项" + k, k);
                if (v != null && !v.isBlank()) {
                    Map<String, String> opt = new LinkedHashMap<>();
                    opt.put("key", k);
                    opt.put("content", v.trim());
                    options.add(opt);
                }
            }
            q.setOptions(options);

            String typeStr = getCol(row, col, "题型", "类型");
            q.setQType(normalizeType(typeStr, q.getStem(), options.size()));

            String ans = getCol(row, col, "答案", "参考答案");
            q.setAnswer(ans == null ? "" : ans.trim());
            String ana = getCol(row, col, "解析", "分析", "详解");
            q.setAnalysis(ana == null ? "" : ana.trim());
            String diff = getCol(row, col, "难度");
            q.setDifficulty(diff == null || diff.isBlank() ? 3 : Math.max(1, Math.min(5, (int) parseNum(diff, 3))));
            String score = getCol(row, col, "分值", "分数", "分");
            q.setScore(score == null || score.isBlank() ? 5.0 : parseNum(score, 5.0));
            String know = getCol(row, col, "知识点", "考点");
            q.setKnowledge(know == null ? "" : know.trim());
            // 表格里自带的学科/年级优先于页面所选，便于一份表混入多年级题目
            String sub = getCol(row, col, "学科", "科目");
            if (sub != null && !sub.isBlank()) {
                q.setSubject(sub.trim());
            }
            String gr = getCol(row, col, "年级");
            if (gr != null && !gr.isBlank()) {
                q.setGrade(gr.trim());
            }
            String lo = getCol(row, col, "课序");
            if (lo != null && !lo.isBlank()) {
                try {
                    q.setLessonOrder(Integer.parseInt(lo.trim()));
                } catch (NumberFormatException ignore) {
                    // 非整数课序忽略，保持未设置
                }
            }
            out.add(q);
        }
        return out;
    }

    /** 非结构化：每行拼为文本，交给通用切题器 */
    private List<QuestionSplitter.ParsedQuestion> parsePlainSheet(Sheet sheet, List<String> warnings, int sheetIdx) {
        StringBuilder sb = new StringBuilder();
        for (int r = sheet.getFirstRowNum(); r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (int c = 0; c < row.getLastCellNum(); c++) {
                Cell cell = row.getCell(c);
                String v = cell == null ? "" : strCell(cell).trim();
                if (!v.isEmpty()) {
                    if (line.length() > 0) {
                        line.append(' ');
                    }
                    line.append(v);
                }
            }
            if (line.length() > 0) {
                sb.append(line).append('\n');
            }
        }
        List<QuestionSplitter.ParsedQuestion> parsed = splitter.split(sb.toString());
        if (parsed.isEmpty()) {
            warnings.add("Sheet " + (sheetIdx + 1) + "：未识别到题目（如为结构化表格请添加表头：题干/题型/选项A/选项B/选项C/选项D/答案/解析）");
        }
        return parsed;
    }

    private String normalizeType(String typeStr, String stem, int optionCount) {
        if (typeStr != null && !typeStr.isBlank()) {
            String t = typeStr.trim();
            if (t.contains("选择")) {
                return QuestionType.CHOICE.name();
            }
            if (t.contains("填空")) {
                return QuestionType.FILL.name();
            }
            if (t.contains("判断")) {
                return QuestionType.JUDGE.name();
            }
            if (t.contains("计算")) {
                return QuestionType.CALC.name();
            }
            if (t.contains("作图") || t.contains("画图")) {
                return QuestionType.DRAW.name();
            }
            if (t.contains("实验") || t.contains("探究")) {
                return QuestionType.EXPERIMENT.name();
            }
            if (t.contains("解答") || t.contains("应用") || t.contains("简答") || t.contains("综合")) {
                return QuestionType.ANSWER.name();
            }
        }
        if (optionCount >= 2) {
            return QuestionType.CHOICE.name();
        }
        // 带横线空格 -> 填空题
        if (stem.matches("(?s).*[_＿]{2,}.*")) {
            return QuestionType.FILL.name();
        }
        if (stem.contains("判断") && stem.length() < 80) {
            return QuestionType.JUDGE.name();
        }
        return QuestionType.OTHER.name();
    }

    private String getCol(Row row, Map<String, Integer> col, String... names) {
        for (String n : names) {
            Integer idx = col.get(n);
            if (idx != null) {
                Cell cell = row.getCell(idx);
                if (cell != null) {
                    return strCell(cell).trim();
                }
            }
        }
        return null;
    }

    private String strCell(Cell cell) {
        if (cell == null) {
            return "";
        }
        try {
            if (cell.getCellType() == CellType.NUMERIC) {
                double d = cell.getNumericCellValue();
                return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
            }
            if (cell.getCellType() == CellType.BOOLEAN) {
                return String.valueOf(cell.getBooleanCellValue());
            }
            return cell.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private double parseNum(String s, double def) {
        try {
            return Double.parseDouble(s.replaceAll("[^0-9.]", ""));
        } catch (Exception e) {
            return def;
        }
    }

    private static String extOf(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(i).toLowerCase() : "";
    }
}
