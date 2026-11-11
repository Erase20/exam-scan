package com.examscan.modules.student.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.examscan.common.BizException;
import com.examscan.modules.grading.entity.GradeSheet;
import com.examscan.modules.grading.mapper.GradeSheetMapper;
import com.examscan.modules.paper.entity.Paper;
import com.examscan.modules.paper.entity.PaperItem;
import com.examscan.modules.paper.mapper.PaperItemMapper;
import com.examscan.modules.paper.mapper.PaperMapper;
import com.examscan.modules.student.entity.Student;
import com.examscan.modules.student.mapper.StudentMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.*;

/**
 * 考生管理：名单导入（Excel）、维护、阅卷自动补录、成绩汇总导出。
 * 考生表与阅卷记录（grade_sheet）通过考号关联。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StudentService {

    private final StudentMapper studentMapper;
    private final GradeSheetMapper gradeSheetMapper;
    private final PaperItemMapper itemMapper;
    private final PaperMapper paperMapper;
    private final ObjectMapper json = new ObjectMapper();

    /** 考生列表：keyword 模糊匹配考号/姓名，className 精确过滤 */
    public List<Student> list(String keyword, String className) {
        LambdaQueryWrapper<Student> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            String k = keyword.trim();
            w.and(x -> x.like(Student::getStudentNo, k).or().like(Student::getName, k));
        }
        if (className != null && !className.isBlank()) {
            w.eq(Student::getClassName, className.trim());
        }
        w.orderByAsc(Student::getClassName).orderByAsc(Student::getStudentNo);
        return studentMapper.selectList(w);
    }

    /** 新增考生；考号已存在时更新原记录 */
    @Transactional(rollbackFor = Exception.class)
    public Student add(String studentNo, String name, String className, String grade) {
        String no = trimToNull(studentNo);
        if (no == null) {
            throw new BizException("考号不能为空");
        }
        Student s = findByNo(no);
        if (s == null) {
            s = new Student();
            s.setStudentNo(no);
        }
        apply(s, name, className, grade);
        if (s.getId() == null) {
            studentMapper.insert(s);
        } else {
            studentMapper.updateById(s);
        }
        return s;
    }

    /** 修改考生信息（考号改动时校验唯一） */
    @Transactional(rollbackFor = Exception.class)
    public Student update(Long id, String studentNo, String name, String className, String grade) {
        Student s = studentMapper.selectById(id);
        if (s == null) {
            throw new BizException("考生不存在");
        }
        String no = trimToNull(studentNo);
        if (no != null) {
            Student dup = findByNo(no);
            if (dup != null && !dup.getId().equals(id)) {
                throw new BizException("考号 " + no + " 已被考生「" + dup.getName() + "」使用");
            }
            s.setStudentNo(no);
        }
        apply(s, name, className, grade);
        studentMapper.updateById(s);
        return s;
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        studentMapper.deleteById(id);
    }

    /**
     * Excel 名单导入。列顺序：考号、姓名、班级（可选）、年级（可选）；首行表头自动跳过。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> importExcel(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要导入的 Excel 文件");
        }
        int inserted = 0;
        int updated = 0;
        int skipped = 0;
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(file.getBytes()))) {
            Sheet sheet = wb.getSheetAt(0);
            for (int i = sheet.getFirstRowNum(); i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }
                String no = str(row.getCell(0));
                String name = str(row.getCell(1));
                String cls = str(row.getCell(2));
                String grade = str(row.getCell(3));
                // 表头行（含"考号/学号"字样）或无考号的行跳过
                if (no == null || no.contains("考号") || no.contains("学号")) {
                    skipped++;
                    continue;
                }
                Student exist = findByNo(no);
                if (exist == null) {
                    Student s = new Student();
                    s.setStudentNo(no);
                    s.setName(name == null ? "待确认" : name);
                    s.setClassName(cls);
                    s.setGrade(grade);
                    studentMapper.insert(s);
                    inserted++;
                } else {
                    apply(exist, name, cls, grade);
                    studentMapper.updateById(exist);
                    updated++;
                }
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("考生名单导入失败", e);
            throw new BizException("导入失败: " + e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("inserted", inserted);
        out.put("updated", updated);
        out.put("skipped", skipped);
        return out;
    }

    /**
     * 阅卷自动补录：阅卷保存成绩后按考号登记考生。
     * 已登记的只补缺失/占位信息；未识别的考号跳过；失败不影响阅卷主流程。
     */
    @Transactional(rollbackFor = Exception.class)
    public void upsertFromGrading(String studentNo, String studentName, String className) {
        String no = trimToNull(studentNo);
        if (no == null || "未识别".equals(no)) {
            return;
        }
        try {
            Student s = findByNo(no);
            if (s == null) {
                s = new Student();
                s.setStudentNo(no);
                s.setName(studentName == null || studentName.isBlank() ? "待确认" : studentName.trim());
                s.setClassName(trimToNull(className));
                studentMapper.insert(s);
            } else {
                boolean changed = false;
                if ((s.getName() == null || s.getName().isBlank() || "待确认".equals(s.getName()))
                        && studentName != null && !studentName.isBlank()
                        && !"待确认".equals(studentName.trim())) {
                    s.setName(studentName.trim());
                    changed = true;
                }
                if ((s.getClassName() == null || s.getClassName().isBlank()) && trimToNull(className) != null) {
                    s.setClassName(className.trim());
                    changed = true;
                }
                if (changed) {
                    studentMapper.updateById(s);
                }
            }
        } catch (Exception e) {
            log.warn("考生自动补录失败 no={}: {}", studentNo, e.getMessage());
        }
    }

    private void apply(Student s, String name, String className, String grade) {
        if (name != null && !name.isBlank()) {
            s.setName(name.trim());
        }
        if (className != null && !className.isBlank()) {
            s.setClassName(className.trim());
        }
        if (grade != null && !grade.isBlank()) {
            s.setGrade(grade.trim());
        }
        if (s.getName() == null || s.getName().isBlank()) {
            s.setName("待确认");
        }
    }

    private Student findByNo(String no) {
        List<Student> l = studentMapper.selectList(new LambdaQueryWrapper<Student>()
                .eq(Student::getStudentNo, no));
        return l.isEmpty() ? null : l.get(0);
    }

    private static String str(Cell cell) {
        if (cell == null) {
            return null;
        }
        try {
            String s = switch (cell.getCellType()) {
                case STRING -> cell.getStringCellValue();
                case NUMERIC -> {
                    double d = cell.getNumericCellValue();
                    yield d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
                }
                case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
                case FORMULA -> cell.getCellFormula();
                default -> null;
            };
            if (s == null) {
                return null;
            }
            s = s.trim();
            return s.isEmpty() ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    private static String trimToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    /**
     * 考生成绩汇总表（Excel）：考生表关联阅卷表，每行一条阅卷记录，
     * 列 = 考号、姓名、班级、试卷、总分、卷面满分、错题（得分低于满分的卷内题号列表）。
     */
    public byte[] exportGrades(Long paperId, String className) {
        List<GradeSheet> sheets = gradeSheetMapper.selectList(new LambdaQueryWrapper<GradeSheet>()
                .eq(paperId != null, GradeSheet::getPaperId, paperId)
                .orderByAsc(GradeSheet::getPaperId)
                .orderByAsc(GradeSheet::getId));
        if (sheets.isEmpty()) {
            throw new BizException("没有阅卷记录，请先完成阅卷");
        }
        // 卷内题号映射（错题列显示卷内题号）与题满分
        Map<Long, String> itemNo = new HashMap<>();
        Map<Long, BigDecimal> itemScore = new HashMap<>();
        Set<Long> paperIds = new LinkedHashSet<>();
        Map<Long, Paper> paperMap = new HashMap<>();
        for (GradeSheet gs : sheets) {
            paperIds.add(gs.getPaperId());
        }
        for (Long pid : paperIds) {
            Paper p = paperMapper.selectById(pid);
            if (p != null) {
                paperMap.put(pid, p);
            }
            List<PaperItem> items = itemMapper.selectList(new LambdaQueryWrapper<PaperItem>()
                    .eq(PaperItem::getPaperId, pid)
                    .orderByAsc(PaperItem::getSectionNo)
                    .orderByAsc(PaperItem::getSortNo));
            int prevSec = -1;
            int no = 0;
            for (PaperItem it : items) {
                if (it.getSectionNo() != prevSec) {
                    prevSec = it.getSectionNo();
                    no = 1;
                }
                itemNo.put(it.getId(), String.valueOf(no));
                itemScore.put(it.getId(), it.getScore());
                no++;
            }
        }
        // 考生名单（按考号补全姓名/班级；未登记考生直接用阅卷识别信息）
        Map<String, Student> stuMap = new LinkedHashMap<>();
        for (Student s : studentMapper.selectList(null)) {
            stuMap.put(s.getStudentNo(), s);
        }

        try (Workbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("考生成绩汇总");
            String[] headers = {"考号", "姓名", "班级", "试卷", "总分", "卷面满分", "错题(卷内题号)"};
            CellStyle bold = wb.createCellStyle();
            Font f = wb.createFont();
            f.setBold(true);
            bold.setFont(f);
            Row head = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell c = head.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(bold);
                sheet.setColumnWidth(i, i == 6 ? 9000 : 5000);
            }
            int row = 1;
            for (GradeSheet gs : sheets) {
                Student stu = stuMap.get(gs.getStudentNo());
                String name = stu != null ? stu.getName() : gs.getStudentName();
                String cls = stu != null && stu.getClassName() != null ? stu.getClassName() : gs.getClassName();
                // 空白考号跳过（未识别且未补录的记录无法关联考生）
                if (gs.getStudentNo() == null || gs.getStudentNo().isBlank() || "未识别".equals(gs.getStudentNo())) {
                    continue;
                }
                Paper p = paperMap.get(gs.getPaperId());
                Row r = sheet.createRow(row++);
                String paperTitle = p == null ? String.valueOf(gs.getPaperId()) : p.getTitle();
                r.createCell(0).setCellValue(gs.getStudentNo());
                r.createCell(1).setCellValue(name == null ? "" : name);
                r.createCell(2).setCellValue(cls == null ? "" : cls);
                r.createCell(3).setCellValue(paperTitle);
                r.createCell(4).setCellValue(gs.getTotalScore() == null ? "" : gs.getTotalScore().toPlainString());
                r.createCell(5).setCellValue(gs.getFullScore() == null ? "" : gs.getFullScore().toPlainString());
                r.createCell(6).setCellValue(wrongItems(gs, itemNo, itemScore));
            }
            wb.write(out);
            return out.toByteArray();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("导出考生成绩汇总失败", e);
            throw new BizException("导出失败: " + e.getMessage());
        }
    }

    /** 解析逐题判分明细，返回得分低于满分的卷内题号（逗号分隔） */
    private String wrongItems(GradeSheet gs, Map<Long, String> itemNo, Map<Long, BigDecimal> itemScore) {
        if (gs.getDetailJson() == null || gs.getDetailJson().isBlank()) {
            return "";
        }
        List<String> wrong = new ArrayList<>();
        try {
            List<Map<String, Object>> details = json.readValue(gs.getDetailJson(),
                    new TypeReference<List<Map<String, Object>>>() {
                    });
            for (Map<String, Object> d : details) {
                Object idv = d.get("paperItemId");
                if (idv == null) {
                    continue;
                }
                Long itemId = Long.valueOf(String.valueOf(idv));
                BigDecimal got = toBd(d.get("gotScore"));
                BigDecimal full = itemScore.get(itemId);
                if (got == null) {
                    got = BigDecimal.ZERO;
                }
                if (full != null && got.compareTo(full) < 0) {
                    wrong.add(itemNo.getOrDefault(itemId, String.valueOf(itemId)));
                }
            }
        } catch (Exception e) {
            log.warn("解析逐题明细失败 gradeId={}: {}", gs.getId(), e.getMessage());
        }
        return String.join("、", wrong);
    }

    private static BigDecimal toBd(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(o));
        } catch (Exception e) {
            return null;
        }
    }
}
