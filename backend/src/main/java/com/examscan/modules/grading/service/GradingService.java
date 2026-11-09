package com.examscan.modules.grading.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.examscan.common.BizException;
import com.examscan.modules.answer.entity.AnswerSheet;
import com.examscan.modules.answer.mapper.AnswerSheetMapper;
import com.examscan.modules.grading.entity.GradeSheet;
import com.examscan.modules.grading.mapper.GradeSheetMapper;
import com.examscan.modules.paper.entity.Paper;
import com.examscan.modules.paper.entity.PaperItem;
import com.examscan.modules.paper.mapper.PaperItemMapper;
import com.examscan.modules.paper.mapper.PaperMapper;
import com.examscan.modules.question.entity.Question;
import com.examscan.modules.question.mapper.QuestionMapper;
import com.examscan.modules.scan.service.OcrClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * 答题卡阅卷服务
 *
 * 流程：
 *  1. 扫描件 -> 依据四角定位标记做透视变换，把图像"拉正"回标准 A4 坐标系
 *  2. 在客观题区域，对每个涂点计算"填充率"（暗像素占比）
 *  3. 取填充率最高的涂点作为作答；若多个涂点都超过阈值 -> 判为多涂，记 0 分并标记异常
 *  4. 与标准答案比对，按每题分值得分
 *  5. 主观题按答题框裁剪出图片，交给人工/后续 OCR 复核，先给 0 分占位
 *  6. 汇总得分，输出逐题判分明细
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GradingService {

    private final GradeSheetMapper gradeSheetMapper;
    private final AnswerSheetMapper sheetMapper;
    private final PaperMapper paperMapper;
    private final PaperItemMapper itemMapper;
    private final QuestionMapper questionMapper;
    private final ObjectMapper json = new ObjectMapper();
    private final OcrClient ocrClient;

    @Value("${exam-scan.storage.root:./data/storage}")
    private String storageRoot;

    /** 涂点判定阈值：暗像素占比（未涂点因印刷字母约 0.2~0.45，涂黑约 0.8+；铅笔轻涂手机拍照后偏浅，阈值放宽） */
    private static final double FILL_THRESHOLD = 0.45;
    /** 多涂判定：第二高涂点 ≥50% 且 ≥ 最高涂点的 60% 才算多涂，否则视为误碰 */
    private static final double MULTI_THRESHOLD = 0.5;
    /** 相对判定兜底：某涂点比同题其他涂点平均灰度暗 ≥40，且自身平均灰度 ≤190（排除脏污背景），认定为轻涂 */
    private static final int REL_GRAY_DIFF = 40;
    private static final int REL_MAX_MEAN = 190;

    /** 兼容旧调用：默认全部 OCR 模式 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> grade(byte[] imageBytes, Long paperId, Long sheetId) {
        return grade(imageBytes, paperId, sheetId, "ocr", null, null);
    }

    /**
     * @param mode "ocr"=全部 OCR（客观题涂点 + 主观题手写 OCR + 信息区 OCR）；
     *             "fast"=快速截图模式（主观题作答区不做 OCR，只保留截图人工判分）。
     *             两种模式都会对姓名/班级/考号三字段做 OCR，考生信息识别与模式无关。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> grade(byte[] imageBytes, Long paperId, Long sheetId, String mode,
                                  Integer pageIndexParam, Long mergeInto) {
        boolean useOcr = !"fast".equalsIgnoreCase(mode);
        Paper paper = paperMapper.selectById(paperId);
        if (paper == null) {
            throw new BizException("试卷不存在");
        }
        AnswerSheet sheet;
        if (sheetId != null) {
            sheet = sheetMapper.selectById(sheetId);
        } else {
            List<AnswerSheet> list = sheetMapper.selectList(new LambdaQueryWrapper<AnswerSheet>()
                    .eq(AnswerSheet::getPaperId, paperId).orderByDesc(AnswerSheet::getId).last("limit 1"));
            sheet = list.isEmpty() ? null : list.get(0);
        }
        if (sheet == null) {
            throw new BizException("该试卷还没有答题卡模板，请先生成答题卡");
        }

        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(imageBytes));
        } catch (Exception e) {
            throw new BizException("图片读取失败: " + e.getMessage());
        }
        if (img == null) {
            throw new BizException("无法解析答题卡图片");
        }

        Map<?, ?> layout;
        try {
            layout = json.readValue(sheet.getLayoutJson(), Map.class);
        } catch (Exception e) {
            throw new BizException("答题卡布局解析失败");
        }

        int pageW = num(layout.get("pageWidth"), 2480);
        int pageH = num(layout.get("pageHeight"), 3508);

        // 1. 透视校正到标准尺寸
        BufferedImage norm = PerspectiveCorrector.normalize(img, pageW, pageH, layout);

        // 2. 逐题识别涂点
        List<PaperItem> items = itemMapper.selectList(new LambdaQueryWrapper<PaperItem>()
                .eq(PaperItem::getPaperId, paperId).orderByAsc(PaperItem::getSortNo));
        Map<Long, Question> qMap = new HashMap<>();
        for (PaperItem it : items) {
            Question q = questionMapper.selectById(it.getQuestionId());
            if (q != null) {
                qMap.put(q.getId(), q);
            }
        }

        // 判定当前扫描图是第几页：显式传入优先，否则读取每页机器可读页码标识
        int pageIndex = pageIndexParam != null ? pageIndexParam
                : PerspectiveCorrector.detectPage(norm, layout);
        if (pageIndex < 0) {
            pageIndex = 0;
        }
        // 只处理属于当前页的题，并把全局 y 坐标减去 pageIndex*pageH，
        // 使涂点/作答框落在校正后的单页图范围内（否则第二页及以后的题会判到图外）
        List<?> cells = pageCellsOf(layout, pageIndex, pageH);
        Map<String, Object> pageLayout = new LinkedHashMap<>();
        pageLayout.put("cells", cells);

        // 信息区（姓名/班级/手写考号）每页都绘制：先识别本页考生信息，
        // 用于「根据姓名/班级/考号识别考生」与多页答题卡的自动归并。
        // 信息区布局坐标是首页坐标系，当前页要按页号平移
        boolean ocrReady = ocrClient.isAvailable();
        int infoYOff = pageIndex * pageH;
        String[] pageStudent = ocrStudentInfo(layout, norm, ocrReady, infoYOff);

        // 未显式指定合并目标时，按考号/姓名自动归并：同一考生已有多页阅卷记录
        // 且尚未包含本页题目时，把本页明细合并进去，避免同一考生被拆成多条记录
        if (mergeInto == null) {
            Long autoTarget = findAutoMergeTarget(sheet.getId(), pageStudent, cells);
            if (autoTarget != null) {
                log.info("第 {} 页自动归并到阅卷记录 {}：考号={}，姓名={}",
                        pageIndex + 1, autoTarget, pageStudent[0], pageStudent[1]);
                mergeInto = autoTarget;
            }
        }

        List<Map<String, Object>> details = new ArrayList<>();
        BigDecimal objectiveScore = BigDecimal.ZERO;
        BigDecimal fullScore = BigDecimal.ZERO;
        int correctCount = 0;
        int wrongCount = 0;
        int blankCount = 0;
        int multiCount = 0;

        for (PaperItem it : items) {
            fullScore = fullScore.add(it.getScore() == null ? BigDecimal.ZERO : it.getScore());
        }

        for (Object o : cells) {
            Map<?, ?> cell = (Map<?, ?>) o;
            if (!isChoiceOrJudge(cell)) {
                continue;
            }
            long paperItemId = num(cell.get("paperItemId"), 0);
            PaperItem it = items.stream().filter(i -> i.getId().longValue() == paperItemId).findFirst().orElse(null);
            if (it == null) {
                continue;
            }
            Question q = qMap.get(it.getQuestionId());

            // 计算每个涂点的暗像素占比与平均灰度（铅笔涂色经手机拍照灰度偏高，需辅助相对比较）
            List<?> bubbles = (List<?>) cell.get("bubbles");
            Map<String, Double> fills = new LinkedHashMap<>();
            Map<String, double[]> stats = new LinkedHashMap<>();
            if (bubbles != null) {
                for (Object bo : bubbles) {
                    Map<?, ?> b = (Map<?, ?>) bo;
                    double[] st = bubbleStat(norm, num(b.get("cx"), 0), num(b.get("cy"), 0), num(b.get("r"), 20));
                    stats.put(String.valueOf(b.get("key")), st);
                    fills.put(String.valueOf(b.get("key")), Math.round(st[0] * 1000) / 1000.0);
                }
            }

            // 按填充率降序，取最高的两个涂点
            List<Map.Entry<String, Double>> sorted = new ArrayList<>(fills.entrySet());
            sorted.sort(Map.Entry.<String, Double>comparingByValue().reversed());
            double topFill = sorted.isEmpty() ? 0 : sorted.get(0).getValue();
            double secondFill = sorted.size() > 1 ? sorted.get(1).getValue() : 0;

            // 认定涂了：绝对填充率达标且「显著高于次高涂点」（防阴影/脏污把未涂点抬过阈值）；
            // 或相对兜底——整题都涂得浅时，取平均灰度明显比其余更暗的那个涂点
            String pickKey = sorted.isEmpty() ? null : sorted.get(0).getKey();
            boolean marked = pickKey != null && topFill >= FILL_THRESHOLD
                    && (topFill >= 0.6 || topFill - secondFill >= 0.18);
            if (!marked && stats.size() >= 2) {
                double sumMean = 0;
                String minKey = null;
                double minMean = Double.MAX_VALUE;
                for (Map.Entry<String, double[]> en : stats.entrySet()) {
                    sumMean += en.getValue()[1];
                    if (en.getValue()[1] < minMean) {
                        minMean = en.getValue()[1];
                        minKey = en.getKey();
                    }
                }
                double avgOthers = (sumMean - minMean) / (stats.size() - 1);
                if (avgOthers - minMean >= REL_GRAY_DIFF && minMean <= REL_MAX_MEAN) {
                    marked = true;
                    pickKey = minKey;   // 轻涂时暗像素占比可能为 0，按灰度最暗的涂点为准
                }
            }

            String studentAns = "";
            String standard = normalizeAnswer(q == null ? "" : q.getAnswer());
            // 判断题标准答案 √/× 映射为涂点 A/B
            if ("JUDGE".equals(it.getQType())) {
                if (standard.contains("√") || standard.contains("对") || standard.contains("正确")) {
                    standard = "A";
                } else if (standard.contains("×") || standard.contains("错")) {
                    standard = "B";
                }
            }
            String verdict;
            BigDecimal got;

            if (!marked) {
                verdict = "BLANK";
                got = BigDecimal.ZERO;
                blankCount++;
            } else {
                // 一律以认定的涂点（正常=填充率最高，轻涂=灰度最暗）作为学生答案。
                // 多涂/少涂不影响计分：答案对即计分；多涂仅在 multi 计数中提示，供教师留意
                studentAns = pickKey;
                boolean ok = standard.equals(studentAns) || standard.contains(studentAns);
                verdict = ok ? "CORRECT" : "WRONG";
                got = ok ? it.getScore() : BigDecimal.ZERO;
                if (ok) {
                    correctCount++;
                } else {
                    wrongCount++;
                }
                if (secondFill >= MULTI_THRESHOLD && secondFill >= topFill * 0.6) {
                    multiCount++;   // 仅提示，不扣分
                }
            }
            objectiveScore = objectiveScore.add(got);

            Map<String, Object> d = new LinkedHashMap<>();
            d.put("paperItemId", it.getId());
            d.put("questionId", it.getQuestionId());
            // 题号取答题卡布局里算好的（大题内序号，与试卷一致），旧布局没有该字段时退回原卷题号
            d.put("qNo", cellNo(cell, q));
            d.put("sectionName", it.getSectionName() == null ? "" : it.getSectionName());
            d.put("qType", it.getQType());
            d.put("standardAnswer", standard);
            d.put("studentAnswer", studentAns);
            d.put("fills", fills);
            d.put("verdict", verdict);
            d.put("score", it.getScore());
            d.put("gotScore", got);
            details.add(d);
        }

        // 3. 主观题占位（待人工/OCR 复核）
        Map<?, ?> subj = (Map<?, ?>) layout.get("subjective");
        List<?> subjCells = cells;
        BigDecimal subjectiveFull = BigDecimal.ZERO;
        for (Object o : subjCells) {
            Map<?, ?> cell = (Map<?, ?>) o;
            if (isChoiceOrJudge(cell)) {
                continue;
            }
            long paperItemId = num(cell.get("paperItemId"), 0);
            PaperItem it = items.stream().filter(i -> i.getId().longValue() == paperItemId).findFirst().orElse(null);
            if (it == null) {
                continue;
            }
            Question q = qMap.get(it.getQuestionId());
            subjectiveFull = subjectiveFull.add(it.getScore() == null ? BigDecimal.ZERO : it.getScore());
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("paperItemId", it.getId());
            d.put("questionId", it.getQuestionId());
            d.put("qNo", cellNo(cell, q));
            d.put("sectionName", it.getSectionName() == null ? "" : it.getSectionName());
            d.put("qType", it.getQType());
            d.put("standardAnswer", q == null ? "" : q.getAnswer());
            d.put("studentAnswer", "");
            d.put("verdict", "MANUAL");
            d.put("score", it.getScore());
            d.put("gotScore", BigDecimal.ZERO);
            details.add(d);
        }

        BigDecimal total = objectiveScore.setScale(1, RoundingMode.HALF_UP);

        // 多页答题卡：后续页逐页上传后合并到同一条阅卷记录（mergeInto=已建阅卷记录 id）
        if (mergeInto != null) {
            GradeSheet existing = gradeSheetMapper.selectById(mergeInto);
            if (existing != null) {
                List<Map<String, Object>> prev = new ArrayList<>();
                try {
                    List<?> raw = json.readValue(
                            existing.getDetailJson() == null ? "[]" : existing.getDetailJson(),
                            List.class);
                    for (Object o : raw) {
                        prev.add((Map<String, Object>) o);
                    }
                    prev.addAll(details);
                    existing.setDetailJson(json.writeValueAsString(prev));
                    BigDecimal objSum = BigDecimal.ZERO, subjSum = BigDecimal.ZERO;
                    for (Map<String, Object> d : prev) {
                        BigDecimal got = toBigDecimal(d.get("gotScore"));
                        if ("MANUAL".equals(String.valueOf(d.get("verdict")))) {
                            subjSum = subjSum.add(got);
                        } else {
                            objSum = objSum.add(got);
                        }
                    }
                    existing.setObjectiveScore(objSum.setScale(1, RoundingMode.HALF_UP));
                    existing.setSubjectiveScore(subjSum.setScale(1, RoundingMode.HALF_UP));
                    existing.setTotalScore(objSum.add(subjSum).setScale(1, RoundingMode.HALF_UP));
                    gradeSheetMapper.updateById(existing);
                } catch (Exception e) {
                    throw new BizException("合并阅卷结果失败：" + e.getMessage());
                }
                try {
                    cropAnswerCells(pageLayout, norm, existing, details, useOcr);
                } catch (Exception ignored) {
                }
                // 姓名/班级/学号信息区每页都有：首页保存选区截图；
                // 合并页缺失的考生信息字段用本页识别结果补全（不覆盖人工确认过的内容）
                if (pageIndex == 0) {
                    try {
                        applyStudentInfo(layout, norm, existing, ocrReady, pageStudent, true, 0);
                    } catch (Exception ignored) {
                    }
                }
                fillMissingStudentInfo(existing, pageStudent);
                return gradeOut(existing, prev, useOcr);
            }
        }

        GradeSheet gs = new GradeSheet();
        gs.setSheetId(sheet.getId());
        gs.setPaperId(paperId);
        // 考生识别：考号优先用旧版涂卡识别结果（完整 6-8 位数字），否则以手写 OCR 为准；
        // 姓名/班级直接采用本页信息区识别结果，识别不出时待人工补录
        String bubbleNo = layoutStudentNo(layout, norm);
        gs.setStudentNo(bubbleNo != null && bubbleNo.matches("\\d{6,8}") ? bubbleNo
                : (pageStudent[0] == null ? "" : pageStudent[0]));
        gs.setStudentName(pageStudent[1] == null || pageStudent[1].isBlank() ? "待确认" : pageStudent[1]);
        gs.setClassName(pageStudent[2] == null ? "" : pageStudent[2]);
        gs.setObjectiveScore(objectiveScore);
        gs.setSubjectiveScore(BigDecimal.ZERO);
        gs.setTotalScore(total);
        gs.setFullScore(fullScore);
        gs.setStatus("AUTO");
        gs.setDeleted(0);
        gs.setMode(useOcr ? "ocr" : "fast");
        try {
            gs.setDetailJson(json.writeValueAsString(details));
        } catch (Exception ignored) {
        }
        gradeSheetMapper.insert(gs);

        // 保存答题卡原图，供阅卷记录回看与导出
        if (imageBytes != null && imageBytes.length > 0) {
            try {
                Path dir = Paths.get(storageRoot, "grade");
                Files.createDirectories(dir);
                Path imgPath = dir.resolve(gs.getId() + ".jpg");
                Files.write(imgPath, imageBytes);
                gs.setImagePath(imgPath.toString());
                gradeSheetMapper.updateById(gs);
            } catch (Exception e) {
                log.warn("答题卡原图保存失败: {}", e.getMessage());
            }
        }

        // 姓名/班级/学号：保存选区截图供人工辨认（识别结果已由每页信息区 OCR 得出）
        try {
            applyStudentInfo(layout, norm, gs, ocrReady, pageStudent, false, infoYOff);
        } catch (Exception e) {
            log.warn("姓名/班级识别失败: {}", e.getMessage());
        }

        // 每题作答区截图（客观题涂点位置 / 主观题答题框），供人工核对与打分
        try {
            cropAnswerCells(pageLayout, norm, gs, details, useOcr);
        } catch (Exception e) {
            log.warn("作答区截图失败: {}", e.getMessage());
        }

        return gradeOut(gs, details, useOcr);
    }

    private Map<String, Object> gradeOut(GradeSheet gs, List<Map<String, Object>> details, boolean useOcr) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gradeId", gs.getId());
        out.put("mode", useOcr ? "ocr" : "fast");
        out.put("paperId", gs.getPaperId());
        out.put("studentNo", gs.getStudentNo());
        out.put("studentName", gs.getStudentName());
        out.put("className", gs.getClassName());
        out.put("nameCrop", cropExists(gs.getId(), "name") ? "/api/grading/" + gs.getId() + "/crop/name" : null);
        out.put("classCrop", cropExists(gs.getId(), "class") ? "/api/grading/" + gs.getId() + "/crop/class" : null);
        out.put("idCrop", cropExists(gs.getId(), "id") ? "/api/grading/" + gs.getId() + "/crop/id" : null);
        out.put("infoCrop", cropExists(gs.getId(), "info") ? "/api/grading/" + gs.getId() + "/crop/info" : null);
        out.put("objectiveScore", gs.getObjectiveScore());
        out.put("totalScore", gs.getTotalScore());
        out.put("fullScore", gs.getFullScore());
        out.put("details", details);
        return out;
    }

    private static boolean isChoiceOrJudge(Map<?, ?> cell) {
        Object qt = cell.get("qType");
        return "CHOICE".equals(String.valueOf(qt)) || "JUDGE".equals(String.valueOf(qt));
    }

    /** JSON 反序列化后的数值可能是 Integer/Double/BigDecimal，统一转 BigDecimal */
    private static BigDecimal toBigDecimal(Object o) {
        if (o == null) {
            return BigDecimal.ZERO;
        }
        if (o instanceof BigDecimal bd) {
            return bd;
        }
        if (o instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        try {
            return new BigDecimal(o.toString());
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    /** 取指定页的 cell，并把全局 y 坐标减去 pageIndex*pageH，便于在单页校正图上识别 */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> pageCellsOf(Map<?, ?> layout, int pageIndex, int pageH) {
        List<Map<String, Object>> out = new ArrayList<>();
        Object cells = layout.get("cells");
        if (!(cells instanceof List<?> list)) {
            return out;
        }
        int dy = -pageIndex * pageH;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            int pg = m.get("page") instanceof Number n ? n.intValue() : 0;
            if (pg != pageIndex) {
                continue;
            }
            Map<String, Object> c = new LinkedHashMap<>((Map<String, Object>) m);
            shiftY(c, dy);
            out.add(c);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void shiftY(Map<String, Object> c, int dy) {
        shiftBoxY(c, "box", dy);
        shiftBoxY(c, "scoreBox", dy);
        if (c.get("lineStartY") instanceof Number n) {
            c.put("lineStartY", n.intValue() + dy);
        }
        Object b = c.get("bubbles");
        if (b instanceof List<?> list) {
            List<Map<String, Object>> nb = new ArrayList<>();
            for (Object bo : list) {
                if (bo instanceof Map<?, ?> bm) {
                    Map<String, Object> mm = new LinkedHashMap<>((Map<String, Object>) bm);
                    if (mm.get("cy") instanceof Number n) {
                        mm.put("cy", n.intValue() + dy);
                    }
                    nb.add(mm);
                }
            }
            c.put("bubbles", nb);
        }
    }

    private static void shiftBoxY(Map<String, Object> c, String key, int dy) {
        Object b = c.get(key);
        if (b instanceof Map<?, ?> m) {
            Map<String, Object> mm = new LinkedHashMap<>((Map<String, Object>) m);
            if (mm.get("y") instanceof Number n) {
                mm.put("y", n.intValue() + dy);
            }
            c.put(key, mm);
        }
    }

    /** 涂点统计：[0]=暗像素占比（灰度<150），[1]=平均灰度。采样圆内接于正方形涂点 */
    private double[] bubbleStat(BufferedImage img, int cx, int cy, int r) {
        int x1 = Math.max(0, cx - r), y1 = Math.max(0, cy - r);
        int x2 = Math.min(img.getWidth() - 1, cx + r), y2 = Math.min(img.getHeight() - 1, cy + r);
        if (x2 <= x1 || y2 <= y1) {
            return new double[]{0, 255};
        }
        int dark = 0, total = 0;
        long sum = 0;
        for (int y = y1; y <= y2; y++) {
            for (int x = x1; x <= x2; x++) {
                double dx = x - cx, dy = y - cy;
                if (dx * dx + dy * dy > r * r) {
                    continue;
                }
                total++;
                int rgb = img.getRGB(x, y);
                int gray = ((rgb >> 16 & 0xFF) * 299 + (rgb >> 8 & 0xFF) * 587 + (rgb & 0xFF) * 114) / 1000;
                sum += gray;
                if (gray < 150) {
                    dark++;
                }
            }
        }
        return total == 0 ? new double[]{0, 255} : new double[]{(double) dark / total, (double) sum / total};
    }

    private String normalizeAnswer(String ans) {
        if (ans == null) {
            return "";
        }
        return ans.replaceAll("[\\s,，、;；]", "").replace("（", "").replace("）", "").toUpperCase();
    }

    private String layoutStudentNo(Map<?, ?> layout, BufferedImage norm) {
        // 考号涂卡区识别：对每位数字取填充率最高的位置。
        // 新版答题卡不再生成涂卡区（info 里没有 idBubbleStartX），考号以手写 OCR 为准
        try {
            Map<?, ?> info = (Map<?, ?>) layout.get("info");
            if (info == null || info.get("idBubbleStartX") == null) {
                return "";
            }
            int digits = num(info.get("idDigits"), 8);
            int startY = num(info.get("idStartY"), 0);
            int bx0 = num(info.get("idBubbleStartX"), 0);
            int gx = num(info.get("idBubbleGapX"), 76);
            int gy = num(info.get("idBubbleGapY"), 48);
            int r = num(info.get("idBubbleR"), 15);
            StringBuilder sb = new StringBuilder();
            for (int d = 0; d < digits; d++) {
                int x = bx0 + d * gx;
                int best = -1;
                double bestFill = 0;
                for (int n = 0; n < 10; n++) {
                    double f = bubbleStat(norm, x, startY + n * gy, r)[0];
                    if (f > bestFill) {
                        bestFill = f;
                        best = n;
                    }
                }
                sb.append(bestFill >= FILL_THRESHOLD ? String.valueOf(best) : "_");
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public List<GradeSheet> list(Long paperId) {
        LambdaQueryWrapper<GradeSheet> w = new LambdaQueryWrapper<>();
        if (paperId != null) {
            w.eq(GradeSheet::getPaperId, paperId);
        }
        w.orderByDesc(GradeSheet::getId);
        return gradeSheetMapper.selectList(w);
    }

    /** 阅卷结果详情（含逐题明细） */
    public Map<String, Object> detail(Long gradeId) {
        GradeSheet gs = gradeSheetMapper.selectById(gradeId);
        if (gs == null) {
            throw new BizException("阅卷记录不存在");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("grade", gs);
        try {
            out.put("details", json.readValue(gs.getDetailJson() == null ? "[]" : gs.getDetailJson(), List.class));
        } catch (Exception e) {
            out.put("details", List.of());
        }
        // 姓名/班级/学号选区截图 + 完整信息区整图（存在才返回）
        out.put("nameCrop", cropExists(gs.getId(), "name") ? "/api/grading/" + gs.getId() + "/crop/name" : null);
        out.put("classCrop", cropExists(gs.getId(), "class") ? "/api/grading/" + gs.getId() + "/crop/class" : null);
        out.put("idCrop", cropExists(gs.getId(), "id") ? "/api/grading/" + gs.getId() + "/crop/id" : null);
        out.put("infoCrop", cropExists(gs.getId(), "info") ? "/api/grading/" + gs.getId() + "/crop/info" : null);
        return out;
    }

    private boolean cropExists(Long id, String type) {
        try {
            return Files.exists(Paths.get(storageRoot, "grade", id + "-" + type + ".jpg"));
        } catch (Exception e) {
            return false;
        }
    }

    /** 读取姓名/班级选区截图字节；不存在返回 null */
    public byte[] loadCrop(Long id, String type) {
        try {
            Path p = Paths.get(storageRoot, "grade", id + "-" + type + ".jpg");
            return Files.exists(p) ? Files.readAllBytes(p) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 人工修改得分（可改任意题：客观题改判、主观题给分），按明细重算客观/主观/总分 */
    @Transactional(rollbackFor = Exception.class)
    public GradeSheet adjust(Long gradeId, List<Map<String, Object>> manualScores) {
        GradeSheet gs = gradeSheetMapper.selectById(gradeId);
        if (gs == null) {
            throw new BizException("阅卷记录不存在");
        }
        try {
            List<Map<String, Object>> details = json.readValue(
                    gs.getDetailJson() == null ? "[]" : gs.getDetailJson(), List.class);
            Map<Long, BigDecimal> fix = new HashMap<>();
            for (Map<String, Object> m : manualScores) {
                fix.put(((Number) m.get("paperItemId")).longValue(),
                        new BigDecimal(m.get("score").toString()));
            }
            BigDecimal objSum = BigDecimal.ZERO;
            BigDecimal subjSum = BigDecimal.ZERO;
            for (Map<String, Object> d : details) {
                long pid = ((Number) d.get("paperItemId")).longValue();
                BigDecimal s = fix.containsKey(pid) ? fix.get(pid)
                        : new BigDecimal(d.get("gotScore") == null ? "0" : d.get("gotScore").toString());
                d.put("gotScore", s);
                if (fix.containsKey(pid)) {
                    d.put("verdict", "MANUAL");
                }
                if (isSubjective((String) d.get("qType"))) {
                    subjSum = subjSum.add(s);
                } else {
                    objSum = objSum.add(s);
                }
            }
            gs.setObjectiveScore(objSum.setScale(1, RoundingMode.HALF_UP));
            gs.setSubjectiveScore(subjSum.setScale(1, RoundingMode.HALF_UP));
            gs.setTotalScore(objSum.add(subjSum).setScale(1, RoundingMode.HALF_UP));
            gs.setStatus("CONFIRMED");
            gs.setDetailJson(json.writeValueAsString(details));
            gradeSheetMapper.updateById(gs);
            return gs;
        } catch (Exception e) {
            throw new BizException("调整失败: " + e.getMessage());
        }
    }

    /** 主观题题型（给分计入主观题得分；其余为客观题自动判分） */
    private static final Set<String> SUBJECTIVE_TYPES = Set.of("FILL", "CALC", "ANSWER", "EXPERIMENT", "DRAW", "OTHER");

    private static boolean isSubjective(String qType) {
        return qType != null && SUBJECTIVE_TYPES.contains(qType);
    }

    /**
     * 信息区处理：保存姓名 / 班级 / 手写考号选区截图（单字段图 + 整块信息区图）供人工辨认；
     * 考生字段写入调用方预识别的 ocrText。
     *
     * @param fillMissing true=只补缺失字段（多页合并时，不覆盖人工确认/已识别内容）；
     *                    false=直接写入识别结果（新建记录）
     * @param ocrAvailable OCR 服务是否可用；不可用时仅保存截图
     */
    private void applyStudentInfo(Map<?, ?> layout, BufferedImage norm, GradeSheet gs,
                                  boolean ocrAvailable, String[] ocrText, boolean fillMissing, int yOff) {
        Map<?, ?> info = infoShifted((Map<?, ?>) layout.get("info"), yOff);
        if (info == null) {
            return;
        }
        try {
            Path dir = Paths.get(storageRoot, "grade");
            Files.createDirectories(dir);
            String[] t = ocrText == null ? new String[]{"", "", ""} : ocrText;
            boolean changed = false;
            Map<?, ?> nb = (Map<?, ?>) info.get("nameBox");
            Map<?, ?> cb = (Map<?, ?>) info.get("classBox");
            Map<?, ?> hb = (Map<?, ?>) info.get("idHandBox");
            if (nb != null && cropRegion(norm, nb, dir.resolve(gs.getId() + "-name.jpg"), 24)
                    && !blank(t[1])
                    && (!fillMissing || blank(gs.getStudentName()) || "待确认".equals(gs.getStudentName()))) {
                gs.setStudentName(t[1]);
                changed = true;
            }
            if (cb != null && cropRegion(norm, cb, dir.resolve(gs.getId() + "-class.jpg"), 24)
                    && !blank(t[2])
                    && (!fillMissing || blank(gs.getClassName()))) {
                gs.setClassName(t[2]);
                changed = true;
            }
            if (hb != null && cropRegion(norm, hb, dir.resolve(gs.getId() + "-id.jpg"), 24)
                    && !blank(t[0])
                    // 涂点考号已完整识别（8 位全数字）时保留，否则用手写 OCR 结果补全
                    && (gs.getStudentNo() == null || !gs.getStudentNo().matches("\\d{6,8}"))) {
                gs.setStudentNo(t[0]);
                changed = true;
            }
            // 整块信息区截图：姓名、班级、考号手写格完整包含，人工一次看全
            Map<String, Object> whole = unionInfoBox(info);
            if (whole != null) {
                cropRegion(norm, whole, dir.resolve(gs.getId() + "-info.jpg"), 24);
            }
            if (changed) {
                gradeSheetMapper.updateById(gs);
            }
        } catch (Exception e) {
            log.warn("姓名/班级/学号选区处理失败: {}", e.getMessage());
        }
    }

    /** 合并页：已有记录缺失的考生信息字段用本页识别结果补全（不覆盖人工确认/已识别内容） */
    private void fillMissingStudentInfo(GradeSheet gs, String[] pinfo) {
        if (gs == null || pinfo == null) {
            return;
        }
        boolean changed = false;
        if (blank(gs.getStudentNo()) && !blank(pinfo[0])) {
            gs.setStudentNo(pinfo[0]);
            changed = true;
        }
        if ((blank(gs.getStudentName()) || "待确认".equals(gs.getStudentName())) && !blank(pinfo[1])) {
            gs.setStudentName(pinfo[1]);
            changed = true;
        }
        if (blank(gs.getClassName()) && !blank(pinfo[2])) {
            gs.setClassName(pinfo[2]);
            changed = true;
        }
        if (changed) {
            gradeSheetMapper.updateById(gs);
        }
    }

    /** 每页信息区（姓名/班级/手写考号）识别：返回 {考号, 姓名, 班级}，识别不出对应项为空串 */
    private String[] ocrStudentInfo(Map<?, ?> layout, BufferedImage norm, boolean ocrAvailable, int yOff) {
        String[] out = {"", "", ""};
        Map<?, ?> info = infoShifted((Map<?, ?>) layout.get("info"), yOff);
        if (info == null || !ocrAvailable) {
            return out;
        }
        try {
            if (info.get("idHandBox") instanceof Map<?, ?> hb) {
                out[0] = ocrIdRegion(cropBytes(norm, hb, 24));
            }
            if (info.get("nameBox") instanceof Map<?, ?> nb) {
                out[1] = ocrInfoRegion(cropBytes(norm, nb, 24), "姓名");
            }
            if (info.get("classBox") instanceof Map<?, ?> cb) {
                out[2] = ocrInfoRegion(cropBytes(norm, cb, 24), "班级");
            }
        } catch (Exception e) {
            log.warn("考生信息区识别失败: {}", e.getMessage());
        }
        return out;
    }

    /** 从校正后的答题卡图裁剪选区并返回字节（裁剪失败返回 null） */
    private byte[] cropBytes(BufferedImage norm, Map<?, ?> box, int pad) {
        try {
            int x = Math.max(0, num(box.get("x"), 0) - pad);
            int y = Math.max(0, num(box.get("y"), 0) - pad);
            int w = Math.min(num(box.get("w"), 0) + 2 * pad, norm.getWidth() - x);
            int h = Math.min(num(box.get("h"), 0) + 2 * pad, norm.getHeight() - y);
            if (w <= 10 || h <= 10) {
                return null;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(norm.getSubimage(x, y, w, h), "jpg", bos);
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** 考号手写 OCR：1~8 位数字都采纳（短学号也能识别）；普通 OCR 识别不出时用增强识别再试一次 */
    private String ocrIdRegion(byte[] img) {
        if (img == null || !ocrClient.isAvailable()) {
            return "";
        }
        try {
            String text = digitsOnly(flat(String.join("", ocrClient.recognizeText(img))));
            if (text.matches("\\d{1,8}")) {
                return text;
            }
            text = digitsOnly(flat(ocrClient.recognizeTextBest(img)));
            return text.matches("\\d{1,8}") ? text : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** 姓名/班级 OCR：剔除标签、冒号与印刷下划线；普通 OCR 识别不出时用增强识别再试一次（手写小字识别率更高） */
    private String ocrInfoRegion(byte[] img, String label) {
        if (img == null || !ocrClient.isAvailable()) {
            return "";
        }
        try {
            String text = cleanInfoText(String.join("", ocrClient.recognizeText(img)), label);
            if (validInfoText(text)) {
                return text;
            }
            text = cleanInfoText(flat(ocrClient.recognizeTextBest(img)), label);
            return validInfoText(text) ? text : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String flat(String s) {
        return s == null ? "" : String.join("", s.split("\\n"));
    }

    private static String digitsOnly(String s) {
        return s == null ? "" : s.replaceAll("[^0-9]", "");
    }

    private static String cleanInfoText(String raw, String label) {
        return (raw == null ? "" : raw).replace(label, "")
                .replaceAll("[：:\\s_\\-—－]", "").trim();
    }

    /** 中文需 2 字以上（防印刷下划线被误认成「一」），含字母/数字的混合短文本（六1、6A、三(2)班）也可 */
    private static boolean validInfoText(String text) {
        return !text.isEmpty() && text.length() <= 16
                && text.matches("[\\u4e00-\\u9fa5A-Za-z0-9()（）·]+")
                && (text.matches(".*[A-Za-z0-9].*") || text.length() >= 2);
    }

    /**
     * 多页答题卡自动归并目标：在同一答题卡模板的已有阅卷记录里，
     * 找「考号相同（或姓名相同）且尚未包含本页任何题目」的记录。
     * 找不到返回 null（新建记录）；本页题目与记录重复说明是重复扫描，不归并。
     */
    private Long findAutoMergeTarget(Long sheetId, String[] student, List<?> cells) {
        Set<Long> pageItems = new HashSet<>();
        for (Object o : cells) {
            if (o instanceof Map<?, ?> c && c.get("paperItemId") instanceof Number n) {
                pageItems.add(n.longValue());
            }
        }
        if (pageItems.isEmpty() || student == null) {
            return null;
        }
        String no = blank(student[0]) ? "" : student[0].trim();
        String name = blank(student[1]) || "待确认".equals(student[1]) ? "" : student[1].trim();
        if (no.isBlank() && name.isBlank()) {
            return null;
        }
        List<GradeSheet> candidates = gradeSheetMapper.selectList(new LambdaQueryWrapper<GradeSheet>()
                .eq(GradeSheet::getSheetId, sheetId)
                .orderByDesc(GradeSheet::getId));
        for (GradeSheet g : candidates) {
            boolean match = (!no.isBlank() && no.equals(trimToEmpty(g.getStudentNo())))
                    || (!name.isBlank() && name.equals(trimToEmpty(g.getStudentName())));
            if (!match) {
                continue;
            }
            if (!detailContainsAny(g.getDetailJson(), pageItems)) {
                return g.getId();
            }
        }
        return null;
    }

    /** 明细里是否已包含给定题目（判断该页是否已合并过 / 是否重复扫描） */
    private boolean detailContainsAny(String detailJson, Set<Long> itemIds) {
        try {
            List<?> raw = json.readValue(detailJson == null ? "[]" : detailJson, List.class);
            for (Object o : raw) {
                if (o instanceof Map<?, ?> d && d.get("paperItemId") instanceof Number n
                        && itemIds.contains(n.longValue())) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * 把信息区各选区的 y 坐标平移到指定页（多页答题卡的信息区每页都绘制，
     * 布局 JSON 里的坐标是首页坐标系，识别第 2 页及以后要按页号平移）。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> infoShifted(Map<?, ?> info, int yOff) {
        if (info == null || yOff == 0) {
            return info == null ? null : new LinkedHashMap<>((Map<String, Object>) info);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : info.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Map<?, ?> b) {
                Map<String, Object> nb = new LinkedHashMap<>((Map<String, Object>) b);
                if (nb.get("y") instanceof Number n) {
                    nb.put("y", n.intValue() + yOff);
                }
                out.put(String.valueOf(e.getKey()), nb);
            } else {
                out.put(String.valueOf(e.getKey()), v);
            }
        }
        return out;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    /** 合并信息区各选区（旧布局含考号涂点区）为一块完整矩形 */
    private Map<String, Object> unionInfoBox(Map<?, ?> info) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = 0, maxY = 0;
        for (String k : new String[]{"nameBox", "classBox", "idDigitBox", "idHandBox"}) {
            if (info.get(k) instanceof Map<?, ?> b) {
                int x = num(b.get("x"), 0), y = num(b.get("y"), 0);
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x + num(b.get("w"), 0));
                maxY = Math.max(maxY, y + num(b.get("h"), 0));
            }
        }
        // 旧版布局才有涂卡区几何参数，有才纳入合并范围
        if (info.get("idBubbleStartX") != null) {
            int digits = num(info.get("idDigits"), 8);
            int bx0 = num(info.get("idBubbleStartX"), 0);
            int gx = num(info.get("idBubbleGapX"), 76);
            int gy = num(info.get("idBubbleGapY"), 56);
            int r = num(info.get("idBubbleR"), 20);
            int startY = num(info.get("idStartY"), 0);
            minX = Math.min(minX, bx0 - r);
            minY = Math.min(minY, startY - r - 40);
            maxX = Math.max(maxX, bx0 + (digits - 1) * gx + r);
            maxY = Math.max(maxY, startY + 9 * gy + r);
        }
        if (minX >= maxX || minY >= maxY) {
            return null;
        }
        Map<String, Object> box = new LinkedHashMap<>();
        box.put("x", minX);
        box.put("y", minY);
        box.put("w", maxX - minX);
        box.put("h", maxY - minY);
        return box;
    }

    /** 按布局坐标从校正后的答题卡图上裁剪选区 */
    private boolean cropRegion(BufferedImage norm, Map<?, ?> box, Path out) {
        return cropRegion(norm, box, out, 0);
    }

    /** pad 为四周留白，保证标签、填写内容与手写边框完整不被裁切 */
    private boolean cropRegion(BufferedImage norm, Map<?, ?> box, Path out, int pad) {
        try {
            int x = Math.max(0, num(box.get("x"), 0) - pad);
            int y = Math.max(0, num(box.get("y"), 0) - pad);
            int w = Math.min(num(box.get("w"), 0) + 2 * pad, norm.getWidth() - x);
            int h = Math.min(num(box.get("h"), 0) + 2 * pad, norm.getHeight() - y);
            if (w <= 10 || h <= 10) {
                return false;
            }
            ImageIO.write(norm.getSubimage(x, y, w, h), "jpg", out.toFile());
            return true;
        } catch (Exception e) {
            log.warn("选区截取失败: {}", e.getMessage());
            return false;
        }
    }

    /** 修改阅卷记录的班级/姓名（识别结果只有考号，班级姓名由老师补录） */
    @Transactional(rollbackFor = Exception.class)
    public GradeSheet updateInfo(Long id, String className, String studentName) {
        GradeSheet gs = gradeSheetMapper.selectById(id);
        if (gs == null) {
            throw new BizException("阅卷记录不存在");
        }
        if (className != null) {
            gs.setClassName(className.trim());
        }
        if (studentName != null) {
            gs.setStudentName(studentName.trim());
        }
        gradeSheetMapper.updateById(gs);
        return gs;
    }

    /** 读取答题卡原图字节；不存在返回 null */
    public byte[] loadImage(Long id) {
        GradeSheet gs = gradeSheetMapper.selectById(id);
        if (gs == null || gs.getImagePath() == null || gs.getImagePath().isBlank()) {
            return null;
        }
        try {
            Path p = Paths.get(gs.getImagePath());
            return Files.exists(p) ? Files.readAllBytes(p) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 删除阅卷记录（逻辑删除），并清理原图与姓名/班级选区截图 */
    @Transactional(rollbackFor = Exception.class)
    public int deleteRecords(List<Long> ids) {
        int n = 0;
        for (Long id : ids) {
            if (id == null) {
                continue;
            }
            GradeSheet gs = gradeSheetMapper.selectById(id);
            if (gs == null) {
                continue;
            }
            gradeSheetMapper.deleteById(id);
            n++;
            cleanupImages(gs);
        }
        return n;
    }

    /** 删除记录对应的所有图片文件：原图 + 各类选区/作答区截图（{id}-*.jpg） */
    private void cleanupImages(GradeSheet gs) {
        try {
            if (gs.getImagePath() != null && !gs.getImagePath().isBlank()) {
                Files.deleteIfExists(Paths.get(gs.getImagePath()));
            }
            Path dir = Paths.get(storageRoot, "grade");
            if (Files.isDirectory(dir)) {
                try (var stream = Files.list(dir)) {
                    stream.filter(p -> p.getFileName().toString().startsWith(gs.getId() + "-"))
                            .forEach(p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (Exception ignored) {
                                }
                            });
                }
            }
        } catch (Exception e) {
            log.warn("删除阅卷图片失败: {}", e.getMessage());
        }
    }

    /** 单题作答区 OCR：识别不出返回空串，人工仍可看完整截图 */
    public String ocrCellCrop(Long id, Long itemId) {
        byte[] b = loadCellCrop(id, itemId);
        if (b == null || !ocrClient.isAvailable()) {
            return "";
        }
        return ocrClient.recognizeTextBest(b);
    }

    /** 读取单题作答区截图；不存在返回 null */
    public byte[] loadCellCrop(Long id, Long itemId) {
        try {
            Path p = Paths.get(storageRoot, "grade", id + "-cell-" + itemId + ".jpg");
            return Files.exists(p) ? Files.readAllBytes(p) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 逐题裁剪作答区到 grade/{id}-cell-{paperItemId}.jpg：
     * 客观题为涂点格（含题号与涂点），主观题为答题框；并在明细里补 cellCrop 地址。
     */
    private void cropAnswerCells(Map<?, ?> layout, BufferedImage norm, GradeSheet gs,
                                 List<Map<String, Object>> details, boolean useOcr) throws Exception {
        Path dir = Paths.get(storageRoot, "grade");
        Files.createDirectories(dir);
        Map<Long, String> urlByItem = new HashMap<>();
        Map<Long, String> ocrByItem = new HashMap<>();
        boolean ocrReady = useOcr && ocrClient.isAvailable();
        List<?> cells = (List<?>) layout.get("cells");
        if (cells != null) {
            for (Object o : cells) {
                Map<?, ?> cell = (Map<?, ?>) o;
                long pid = num(cell.get("paperItemId"), 0);
                if (pid <= 0 || !(cell.get("box") instanceof Map<?, ?> bx)) {
                    continue;
                }
                Path out = dir.resolve(gs.getId() + "-cell-" + pid + ".jpg");
                if (cropRegion(norm, bx, out)) {
                    urlByItem.put(pid, "/api/grading/" + gs.getId() + "/cell/" + pid);
                    // 主观题（填空/解答）自动 OCR 学生手写答案；客观题用涂点判定结果，无需 OCR
                    if (ocrReady && !"bubble".equals(String.valueOf(cell.get("renderType")))) {
                        String t = ocrAnswerArea(norm, bx, out, String.valueOf(cell.get("qNo")));
                        if (!t.isBlank()) {
                            ocrByItem.put(pid, t);
                        }
                    }
                }
            }
        }
        boolean changed = false;
        for (Map<String, Object> d : details) {
            if (d.get("paperItemId") instanceof Number n) {
                long pid = n.longValue();
                if (urlByItem.containsKey(pid)) {
                    d.put("cellCrop", urlByItem.get(pid));
                    changed = true;
                }
                String t = ocrByItem.get(pid);
                if (t != null) {
                    d.put("ocrAnswer", t);
                    // 主观题原本 studentAnswer 为空，填入 OCR 结果供人工判分参考；不自动给分
                    if (d.get("studentAnswer") == null || String.valueOf(d.get("studentAnswer")).isBlank()) {
                        d.put("studentAnswer", t);
                    }
                    changed = true;
                }
            }
        }
        if (changed) {
            try {
                gs.setDetailJson(json.writeValueAsString(details));
                gradeSheetMapper.updateById(gs);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 主观题作答区 OCR：用去题头区域判断是否有手写墨迹（空白框不调 OCR，避免拖慢阅卷），
     * 识别时用完整作答区截图（含题头/横线，短文本 OCR 检测率明显更高）。
     */
    private String ocrAnswerArea(BufferedImage norm, Map<?, ?> box, Path fullCrop, String qNo) {
        try {
            int x = num(box.get("x"), 0), y = num(box.get("y"), 0);
            int w = num(box.get("w"), 0), h = num(box.get("h"), 0);
            int pad = 6;
            int header = Math.min(70, h / 3);   // 矮填空框也留出足够的作答区高度
            int rx = Math.max(0, x + pad), ry = Math.max(0, y + header);
            int rw = Math.min(w - 2 * pad, norm.getWidth() - rx);
            int rh = Math.min(h - header - pad, norm.getHeight() - ry);
            if (rw <= 30 || rh <= 20 || !hasInk(norm, rx, ry, rw, rh)) {
                return "";
            }
            String text = ocrClient.recognizeTextBest(Files.readAllBytes(fullCrop));
            // 增强识别会合并重复行；同时剔除印刷的「得分」与题号前缀，只保留学生手写内容
            String[] lines = text.split("\\n");
            StringBuilder sb = new StringBuilder();
            String prev = "";
            for (String line : lines) {
                String s = line.trim();
                if (s.isEmpty() || "得分".equals(s) || s.equals(prev)) {
                    continue;
                }
                if (qNo != null && qNo.matches("\\d{1,2}")) {
                    s = s.replaceFirst("^\\s*" + qNo + "\\s*[.、．:：]\\s*", "");
                }
                if (s.isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(s);
                prev = line.trim();
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 判断作答区内是否有手写墨迹：整行连续的暗像素视为印刷横线排除掉 */
    private boolean hasInk(BufferedImage img, int x, int y, int w, int h) {
        int dark = 0, total = 0;
        for (int yy = y; yy < y + h; yy += 2) {
            int rowDark = 0, rowTotal = 0;
            for (int xx = x; xx < x + w; xx += 2) {
                int rgb = img.getRGB(xx, yy);
                int gray = ((rgb >> 16 & 0xFF) * 299 + (rgb >> 8 & 0xFF) * 587 + (rgb & 0xFF) * 114) / 1000;
                rowTotal++;
                if (gray < 130) {
                    rowDark++;
                }
            }
            total += rowTotal;
            if (rowDark < rowTotal * 0.3) {
                dark += rowDark;   // 非整行横线，计入手写墨迹
            }
        }
        // 用绝对墨迹量：大答题框里一行手写占整框比例很低，用比例会漏判
        return dark >= 100;
    }

    /**
     * 题号：优先用答题卡布局里算好的「大题内序号」（与试卷一致），
     * 旧版布局没有该字段时退回题库里的原卷题号
     */
    private static String cellNo(Map<?, ?> cell, Question q) {
        Object v = cell == null ? null : cell.get("qNo");
        if (v != null && !String.valueOf(v).isBlank()) {
            return String.valueOf(v);
        }
        return q == null || q.getQNo() == null ? "" : q.getQNo();
    }

    private static int num(Object o, int def) {
        if (o == null) {
            return def;
        }
        return o instanceof Number n ? n.intValue() : def;
    }
}
