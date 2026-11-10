package com.examscan.modules.answer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.examscan.common.BizException;
import com.examscan.modules.answer.dto.AnswerSetRequest;
import com.examscan.modules.answer.entity.AnswerSheet;
import com.examscan.modules.answer.mapper.AnswerSheetMapper;
import com.examscan.modules.paper.entity.Paper;
import com.examscan.modules.paper.entity.PaperItem;
import com.examscan.modules.paper.mapper.PaperItemMapper;
import com.examscan.modules.paper.mapper.PaperMapper;
import com.examscan.modules.question.entity.Question;
import com.examscan.modules.question.mapper.QuestionMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 答题卡生成服务
 *
 * 生成的答题卡包含：
 *  - 四角定位标记（实心方块，用于扫描时的透视校正）
 *  - 顶部信息区：姓名 / 班级 / 手写考号格（每页都绘制，多页扫描均可识别考生；旧版兼容涂卡考号）
 *  - 客观题区：按 5 题一组排列，每题 A/B/C/D 四个涂点
 *  - 主观题区：预留答题框，标注题号与分值；填空题每题独占一行
 *
 * 布局以 JSON 形式存储（坐标系按 300dpi A4 = 2480×3508 设计），
 * 阅卷端据此把识别到的涂点映射回题目。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnswerSheetService {

    private final AnswerSheetMapper sheetMapper;
    private final PaperMapper paperMapper;
    private final PaperItemMapper itemMapper;
    private final QuestionMapper questionMapper;
    private final ObjectMapper json = new ObjectMapper();

    // A4 @ 300dpi
    public static final int PAGE_W = 2480;
    public static final int PAGE_H = 3508;
    public static final int MARGIN = 140;
    public static final int MARKER_SIZE = 60;
    /** 信息区（姓名/班级/考号）起始 y：标题基线在 MARGIN+100，留出足够间距 */
    public static final int INFO_Y = MARGIN + MARKER_SIZE + 100;
    /** 解答/计算/实验等主观大题的作答横线最少条数（书写空间下限，紧凑排版也不得突破） */
    public static final int MIN_ANSWER_LINES = 4;

    /**
     * 依据试卷当前题目构建答题卡布局：坐标、页数、作答横线数都在这里确定，
     * 新建与重新排版共用，保证渲染图与阅卷裁剪坐标始终同源。
     */
    private Map<String, Object> buildLayout(Long paperId) {
        Paper paper = paperMapper.selectById(paperId);
        if (paper == null) {
            throw new BizException("试卷不存在");
        }
        // 与试卷详情页、Word 导出保持同一顺序：先大题、再卷内顺序
        List<PaperItem> items = itemMapper.selectList(new LambdaQueryWrapper<PaperItem>()
                .eq(PaperItem::getPaperId, paperId)
                .orderByAsc(PaperItem::getSectionNo)
                .orderByAsc(PaperItem::getSortNo));
        if (items.isEmpty()) {
            throw new BizException("试卷没有题目，无法生成答题卡");
        }

        // 题号以试卷为准：试卷里是「一、选择题 1. 2. 3.」，答题卡上也必须是同样的编号。
        // 题库里的 qNo 是原卷题号（换个组卷就对不上），不能直接拿来用。
        // noInSection = 大题内序号（与试卷一致），seq = 卷面连续号（全局唯一，便于系统定位）
        Map<Long, String> noInSection = new LinkedHashMap<>();
        Map<Long, Integer> seqOfItem = new LinkedHashMap<>();
        Map<Integer, List<PaperItem>> bySection = new LinkedHashMap<>();
        int seq = 0;
        for (PaperItem it : items) {
            int sec = it.getSectionNo() == null ? 0 : it.getSectionNo();
            List<PaperItem> g = bySection.computeIfAbsent(sec, k -> new ArrayList<>());
            g.add(it);
            noInSection.put(it.getId(), String.valueOf(g.size()));
            seqOfItem.put(it.getId(), ++seq);
        }

        Map<Long, Question> qMap = new HashMap<>();
        List<Long> qids = items.stream().map(PaperItem::getQuestionId).distinct().collect(Collectors.toList());
        if (!qids.isEmpty()) {
            for (Question q : questionMapper.selectBatchIds(qids)) {
                qMap.put(q.getId(), q);
            }
        }

        // 分成客观题（选择题/判断题）与主观题，均保持试卷的大题顺序
        List<PaperItem> objective = items.stream()
                .filter(i -> "CHOICE".equals(i.getQType()) || "JUDGE".equals(i.getQType()))
                .collect(Collectors.toList());
        List<PaperItem> subjective = items.stream()
                .filter(i -> !"CHOICE".equals(i.getQType()) && !"JUDGE".equals(i.getQType()))
                .collect(Collectors.toList());

        Map<String, Object> layout = new LinkedHashMap<>();
        layout.put("pageWidth", PAGE_W);
        layout.put("pageHeight", PAGE_H);
        layout.put("dpi", 300);
        layout.put("paperId", paperId);

        // 四角定位标记
        layout.put("markers", List.of(
                marker(MARGIN, MARGIN),
                marker(PAGE_W - MARGIN - MARKER_SIZE, MARGIN),
                marker(MARGIN, PAGE_H - MARGIN - MARKER_SIZE),
                marker(PAGE_W - MARGIN - MARKER_SIZE, PAGE_H - MARGIN - MARKER_SIZE)
        ));

        // 每页机器可读页码标识：以二进制方块编码 1-based 页码，画在每页顶部中央，
        // 阅卷时据此自动判定是第几页，使多页答题卡第二页及以后也能正确识别。
        int pageCodeBits = 6;            // 最多支持 2^6=64 页
        int pageCodeSize = 30;
        int pageCodeGap = 16;
        int pageCodeW = pageCodeBits * (pageCodeSize + pageCodeGap) - pageCodeGap;
        layout.put("pageCode", Map.of(
                "bits", pageCodeBits,
                "size", pageCodeSize,
                "gap", pageCodeGap,
                "x", PAGE_W / 2 - pageCodeW / 2,
                "y", MARGIN + 8
        ));

        // 信息区：姓名 / 班级 / 手写考号格。
        // 不再生成考号涂卡区（旧版布局里 idBubble* 字段仍兼容渲染与识别），
        // 考号统一以手写 + OCR 识别为准。
        int infoY = INFO_Y;
        layout.put("info", Map.of(
                "nameBox", box(MARGIN + 40, infoY, 700, 80),
                "classBox", box(MARGIN + 800, infoY, 500, 80),
                "idDigits", 8,
                "idHandBox", box(MARGIN + 40, infoY + 110, 900, 100)
        ));

        // 按试卷标准大题顺序（sectionNo 升序）统一排布所有大题：
        // 每道大题内部按其题型渲染——选择/判断题画涂卡格，其余画答题框。
        // 关键：不再把「客观题区」整体放在「主观题区」前面。
        // 否则填空(一)作为主观题会被压到主观题区末尾，最终顺序变成
        // 「判断、选择、填空、解答」，与「一、填空 二、判断 三、选择…」的标准顺序冲突。
        // 两遍排版：先按舒适密度排；若「末页内容偏少」（近乎空白页），
        // 再用紧凑密度重排——只有真正省出一页时才采用紧凑版。
        Map<String, Object> body = buildBody(bySection, noInSection, seqOfItem, false);
        if ((int) body.get("pageCount") > 1 && (double) body.get("lastPageUsed") < 0.85) {
            Map<String, Object> denseBody = buildBody(bySection, noInSection, seqOfItem, true);
            // 紧凑版要么少一页，要么同页数但末页利用率更高（消除接近空白的末页），才采用
            boolean fewerPages = (int) denseBody.get("pageCount") < (int) body.get("pageCount");
            boolean betterPacked = (int) denseBody.get("pageCount") == (int) body.get("pageCount")
                    && (double) denseBody.get("lastPageUsed") > (double) body.get("lastPageUsed");
            if (fewerPages || betterPacked) {
                body = denseBody;
            }
        }

        layout.put("objective", body.get("objective"));
        layout.put("subjective", body.get("subjective"));
        layout.put("cells", body.get("cells"));
        layout.put("sectionHeaders", body.get("sectionHeaders"));

        int pageCount = (int) body.get("pageCount");
        layout.put("pageCount", pageCount);
        log.info("答题卡布局完成: paperId={}, 页数={}, 客观题 {} 道, 主观题 {} 道",
                paperId, pageCount, objective.size(), subjective.size());
        return layout;
    }

    /** 新建一张答题卡 */
    @Transactional(rollbackFor = Exception.class)
    public AnswerSheet generate(Long paperId, String title) {
        Map<String, Object> layout = buildLayout(paperId);
        Paper paper = paperMapper.selectById(paperId);
        AnswerSheet sheet = new AnswerSheet();
        sheet.setPaperId(paperId);
        sheet.setTitle(title == null || title.isBlank() ? paper.getTitle() + " 答题卡" : title);
        sheet.setPageCount(Math.max(1, (int) layout.get("pageCount")));
        sheet.setMarkerCount(4);
        sheet.setDeleted(0);
        try {
            sheet.setLayoutJson(json.writeValueAsString(layout));
        } catch (Exception e) {
            throw new BizException("布局序列化失败");
        }
        sheetMapper.insert(sheet);
        log.info("答题卡生成完成: id={}", sheet.getId());
        return sheet;
    }

    /**
     * 按最新规则重新排版已有答题卡：保留 ID、标题和已设置的标准答案快照，
     * 让旧卡不必删除重建也能应用新的作答横线数量。
     */
    @Transactional(rollbackFor = Exception.class)
    public AnswerSheet regenerate(Long sheetId) {
        AnswerSheet sheet = get(sheetId);
        Map<String, Object> oldLayout = readLayout(sheet.getLayoutJson());
        Map<String, Object> layout = buildLayout(sheet.getPaperId());

        Map<Long, String> answers = new LinkedHashMap<>();
        for (Map<String, Object> c : collectCells(oldLayout)) {
            Long qid = asLong(c.get("questionId"));
            Object ans = c.get("answer");
            if (qid != null && ans != null) {
                answers.put(qid, String.valueOf(ans));
            }
        }
        answers.forEach((qid, ans) -> {
            syncCellAnswer(layout, qid, ans);
            syncCellAnswer(layout.get("objective"), qid, ans);
            syncCellAnswer(layout.get("subjective"), qid, ans);
        });

        try {
            sheet.setLayoutJson(json.writeValueAsString(layout));
        } catch (Exception e) {
            throw new BizException("布局序列化失败");
        }
        sheet.setPageCount(Math.max(1, (int) layout.get("pageCount")));
        sheetMapper.updateById(sheet);
        log.info("答题卡重新排版完成: id={}, 页数={}", sheetId, sheet.getPageCount());
        return sheet;
    }

    /**
     * 排版大题内容（可变密度）。
     *
     * @param dense false=舒适密度；true=紧凑密度（间距更小、判断题每行 6 题、答题框行数减一），
     *              用于把「只剩一点点内容的末页」省掉
     * @return cells / sectionHeaders / objective / subjective / pageCount / lastPageUsed(0~1)
     */
    private Map<String, Object> buildBody(Map<Integer, List<PaperItem>> bySection,
                                          Map<Long, String> noInSection,
                                          Map<Long, Integer> seqOfItem,
                                          boolean dense) {
        List<Map<String, Object>> allCells = new ArrayList<>();
        List<Map<String, Object>> objCells = new ArrayList<>();
        List<Map<String, Object>> subjCells = new ArrayList<>();
        List<Map<String, Object>> heads = new ArrayList<>();

        // 排版度量：dense 时全面收紧。
        // 信息区只占两行（姓名/班级 + 手写考号格），正文起始位置比旧版涂卡区（10 行涂点）大幅上移
        int startY = INFO_Y + (dense ? 320 : 360);
        int headBlock = dense ? 74 : 90;       // 大题标题占位高
        int colCount = 5;                      // 选择题每行列数
        int judgeCols = dense ? 6 : colCount;  // 判断题只需 √/× 两个涂点，可放更多列
        int cellH = dense ? 126 : 150;         // 涂卡格高
        int optionGap = 58;
        int bubbleR = 20;
        int bubbleCy = dense ? 90 : 100;       // 涂点相对格顶的 y
        int boxShrink = dense ? 12 : 20;       // 格框内收
        int gapBubble = dense ? 16 : 30;       // 涂卡大题后的间距
        int fillLineGap = dense ? 80 : 110;    // 填空横线行距
        int gapFill = dense ? 14 : 16;         // 填空行间距
        int ansHeaderH = dense ? 62 : 72;      // 解答题题头高
        int ansLineGap = dense ? 80 : 125;     // 解答题横线行距（舒适档加大行距，行数相应减少）
        int gapAns = dense ? 14 : 24;          // 解答题框间距
        int gapSection = dense ? 10 : 20;      // 大题之间间距
        // 续页正文起点：与首页一致，都在每页信息区下方（修复续页内容与每页复刻的信息区重叠）
        int pageStartOff = INFO_Y + (dense ? 320 : 360);
        String[] cnNums = {"一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};

        int y = startY;
        int secIdx = 0;
        int page = 0;

        for (Map.Entry<Integer, List<PaperItem>> e : bySection.entrySet()) {
            List<PaperItem> group = e.getValue();
            String secName = group.get(0).getSectionName() == null
                    ? "第 " + e.getKey() + " 部分" : group.get(0).getSectionName();
            // 换页：当前页放不下「标题 + 至少一道题」时开新页（每页自带定位标记）
            if (y % PAGE_H > 0 && (y % PAGE_H) + 120 > PAGE_H - MARGIN - MARKER_SIZE && !allCells.isEmpty()) {
                page++;
                y = page * PAGE_H + pageStartOff;
            }
            Map<String, Object> head = new LinkedHashMap<>();
            head.put("sectionNo", e.getKey());
            head.put("name", (secIdx < cnNums.length ? cnNums[secIdx] : String.valueOf(secIdx + 1))
                    + "、" + secName + "（共 " + group.size() + " 小题）");
            head.put("x", MARGIN + 40);
            head.put("y", y + 50);
            head.put("page", page);
            heads.add(head);
            secIdx++;
            y += headBlock;

            String qTypeOfSection = group.get(0).getQType();
            boolean bubbleSection = "CHOICE".equals(qTypeOfSection) || "JUDGE".equals(qTypeOfSection);
            boolean fillSection = "FILL".equals(qTypeOfSection);

            if (bubbleSection) {
                int cols = "JUDGE".equals(qTypeOfSection) ? judgeCols : colCount;
                int cw = (PAGE_W - 2 * MARGIN - 80) / cols;
                for (int i = 0; i < group.size(); i++) {
                    PaperItem it = group.get(i);
                    int row = i / cols;
                    int col = i % cols;
                    int cx0 = MARGIN + 40 + col * cw;
                    int cy0 = y + row * cellH;
                    Map<String, Object> cell = new LinkedHashMap<>();
                    cell.put("paperItemId", it.getId());
                    cell.put("questionId", it.getQuestionId());
                    cell.put("qNo", noInSection.getOrDefault(it.getId(), String.valueOf(i + 1)));
                    cell.put("seq", seqOfItem.getOrDefault(it.getId(), i + 1));
                    cell.put("sectionNo", e.getKey());
                    cell.put("sectionName", secName);
                    cell.put("qType", qTypeOfSection);
                    cell.put("renderType", "bubble");
                    cell.put("box", box(cx0, cy0, cw - 20, cellH - boxShrink));
                    int optCount = "JUDGE".equals(qTypeOfSection) ? 2 : 4;
                    List<Map<String, Object>> bubbles = new ArrayList<>();
                    for (int k = 0; k < optCount; k++) {
                        String key = String.valueOf((char) ('A' + k));
                        Map<String, Object> b = new LinkedHashMap<>();
                        b.put("key", key);
                        b.put("cx", cx0 + 30 + k * optionGap);
                        b.put("cy", cy0 + bubbleCy);
                        b.put("r", bubbleR);
                        bubbles.add(b);
                    }
                    cell.put("bubbles", bubbles);
                    cell.put("page", page);
                    allCells.add(cell);
                    objCells.add(cell);
                }
                int rows = (group.size() + cols - 1) / cols;
                y += rows * cellH + gapBubble;

            } else if (fillSection) {
                // 填空题：每题独占一整行 + 单/双行横线，作答空间更大、扫描裁剪更稳
                int fillCols = 1;
                int fillGap = 40;
                int fillCellW = (PAGE_W - 2 * MARGIN - 80 - fillGap) / fillCols;
                for (int r = 0; r < group.size(); r += fillCols) {
                    int rowH = 0;
                    for (int c = 0; c < fillCols && r + c < group.size(); c++) {
                        rowH = Math.max(rowH, fillCellHeight(group.get(r + c).getScore(), fillLineGap));
                    }
                    if ((y % PAGE_H) + rowH > PAGE_H - MARGIN - MARKER_SIZE) {
                        page++;
                        y = page * PAGE_H + pageStartOff;
                        heads.add(contHead(e.getKey(), secName, y, page));
                        y += headBlock;
                    }
                    for (int c = 0; c < fillCols && r + c < group.size(); c++) {
                        PaperItem it = group.get(r + c);
                        int cx = MARGIN + 40 + c * (fillCellW + fillGap);
                        int ch = fillCellHeight(it.getScore(), fillLineGap);
                        int lines = fillLineCount(it.getScore());
                        Map<String, Object> cell = new LinkedHashMap<>();
                        cell.put("paperItemId", it.getId());
                        cell.put("questionId", it.getQuestionId());
                        cell.put("qNo", noInSection.getOrDefault(it.getId(), String.valueOf(r + c + 1)));
                        cell.put("seq", seqOfItem.getOrDefault(it.getId(), 0));
                        cell.put("sectionNo", e.getKey());
                        cell.put("sectionName", secName);
                        cell.put("qType", qTypeOfSection);
                        cell.put("renderType", "fill");
                        cell.put("score", it.getScore());
                        cell.put("box", box(cx, y, fillCellW, ch));
                        cell.put("lineStartY", y + fillLineGap);
                        cell.put("lineCount", lines);
                        cell.put("lineGap", fillLineGap);
                        cell.put("page", page);
                        allCells.add(cell);
                        subjCells.add(cell);
                    }
                    y += rowH + gapFill;
                }

            } else {
                // 解答/计算/实验：整宽横线，行数随分值且不少于 4 条；作图题保留空白绘图框
                boolean drawType = "DRAW".equals(qTypeOfSection);
                int boxW = PAGE_W - 2 * MARGIN - 80;
                for (int i = 0; i < group.size(); i++) {
                    PaperItem it = group.get(i);
                    // 扣减紧凑档行数后仍然兜底到 4 条，保证任何大题的书写空间都够用
                    int lines = drawType ? 0
                            : Math.max(MIN_ANSWER_LINES,
                                    answerLineCount(qTypeOfSection, it.getScore()) - (dense ? 2 : 1));
                    int boxH = drawType ? (dense ? 480 : 560) : ansHeaderH + lines * ansLineGap + 16;
                    if ((y % PAGE_H) + boxH > PAGE_H - MARGIN - MARKER_SIZE) {
                        page++;
                        y = page * PAGE_H + pageStartOff;
                        heads.add(contHead(e.getKey(), secName, y, page));
                        y += headBlock;
                    }
                    Map<String, Object> cell = new LinkedHashMap<>();
                    cell.put("paperItemId", it.getId());
                    cell.put("questionId", it.getQuestionId());
                    cell.put("qNo", noInSection.getOrDefault(it.getId(), ""));
                    cell.put("seq", seqOfItem.getOrDefault(it.getId(), 0));
                    cell.put("sectionNo", e.getKey());
                    cell.put("sectionName", secName);
                    cell.put("qType", qTypeOfSection);
                    cell.put("renderType", "answer");
                    cell.put("score", it.getScore());
                    cell.put("box", box(MARGIN + 40, y, boxW, boxH));
                    cell.put("scoreBox", box(PAGE_W - MARGIN - 180, y + 8, 110, 52));
                    if (!drawType) {
                        cell.put("lineStartY", y + ansHeaderH + ansLineGap);
                        cell.put("lineCount", lines);
                        cell.put("lineGap", ansLineGap);
                        cell.put("page", page);
                    }
                    allCells.add(cell);
                    subjCells.add(cell);
                    y += boxH + gapAns;
                }
            }
            y += gapSection;
        }

        // 客观题区固定在第 1 页（仅阅卷端使用，顺序仍为 sectionNo 升序）
        Map<String, Object> objBlock = new LinkedHashMap<>();
        objBlock.put("cells", objCells);
        objBlock.put("sectionHeaders", heads);
        objBlock.put("startY", startY);

        // 主观题区（仅阅卷端使用，顺序仍为 sectionNo 升序）
        Map<String, Object> subjBlock = new LinkedHashMap<>();
        subjBlock.put("cells", subjCells);
        subjBlock.put("sectionHeaders", heads);
        subjBlock.put("startY", startY);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cells", allCells);
        out.put("sectionHeaders", heads);
        out.put("objective", objBlock);
        out.put("subjective", subjBlock);
        out.put("pageCount", page + 1);
        // 末页使用率：末页内容底部到页首的可用高度占比
        double usable = PAGE_H - MARGIN - MARKER_SIZE - pageStartOff;
        out.put("lastPageUsed", Math.max(0, (y - page * PAGE_H - pageStartOff)) / usable);
        return out;
    }

    /** 填空题横线条数：低分 1 行，4 分及以上 2 行 */
    private static int fillLineCount(BigDecimal score) {
        double s = score == null ? 2 : score.doubleValue();
        return s >= 4 ? 2 : 1;
    }

    /** 填空题单元高度 = 横线行距 + 余量（横线靠格底，上方即书写空间） */
    private static int fillCellHeight(BigDecimal score, int lineGap) {
        return fillLineCount(score) * lineGap + 16;
    }

    /** 解答/计算/实验的横线条数：按题型与分值给 4~9 行，最少 4 行 */
    private static int answerLineCount(String qType, BigDecimal score) {
        double s = score == null ? 6 : score.doubleValue();
        int lines;
        if ("CALC".equals(qType)) {
            lines = s <= 4 ? 4 : (s <= 8 ? 5 : 7);
        } else if ("EXPERIMENT".equals(qType)) {
            lines = s <= 6 ? 4 : (s <= 10 ? 6 : 8);
        } else {
            lines = s <= 4 ? 4 : (s <= 8 ? 5 : (s <= 12 ? 7 : 9));
        }
        return Math.max(MIN_ANSWER_LINES, Math.min(9, lines));
    }

    /** 续页的大题标题（保持题号与分值信息连续） */
    private static Map<String, Object> contHead(int sectionNo, String secName, int y, int page) {
        Map<String, Object> cont = new LinkedHashMap<>();
        cont.put("sectionNo", sectionNo);
        cont.put("name", "（续）" + secName);
        cont.put("x", MARGIN + 40);
        cont.put("y", y + 50);
        cont.put("page", page);
        return cont;
    }

    private static Map<String, Object> box(int x, int y, int w, int h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", x);
        m.put("y", y);
        m.put("w", w);
        m.put("h", h);
        return m;
    }

    private static Map<String, Object> marker(int x, int y) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", x);
        m.put("y", y);
        m.put("size", MARKER_SIZE);
        return m;
    }

    public Page<AnswerSheet> list(int page, int size) {
        return sheetMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<AnswerSheet>().orderByDesc(AnswerSheet::getId));
    }

    public AnswerSheet get(Long id) {
        AnswerSheet s = sheetMapper.selectById(id);
        if (s == null) {
            throw new BizException("答题卡不存在");
        }
        return s;
    }

    public void delete(Long id) {
        sheetMapper.deleteById(id);
    }

    /** 答题卡答案清单：按卷内顺序返回题目与题库当前答案，供快速设置 */
    public Map<String, Object> answerList(Long sheetId) {
        AnswerSheet sheet = get(sheetId);
        Map<String, Object> layout = readLayout(sheet.getLayoutJson());
        List<Map<String, Object>> cells = collectCells(layout);
        cells.sort(Comparator.comparingLong(c -> {
            Long s = asLong(c.get("seq"));
            return s == null ? 0L : s;
        }));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> c : cells) {
            Long qid = asLong(c.get("questionId"));
            if (qid == null) {
                continue;
            }
            Question q = questionMapper.selectById(qid);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("paperItemId", c.get("paperItemId"));
            r.put("questionId", qid);
            r.put("qNo", c.get("qNo"));
            r.put("seq", c.get("seq"));
            r.put("sectionNo", c.get("sectionNo"));
            r.put("sectionName", c.get("sectionName"));
            r.put("qType", c.get("qType"));
            r.put("score", c.get("score"));
            r.put("stem", q == null || q.getStem() == null ? "" : q.getStem());
            r.put("options", q == null ? "" : q.getOptions());
            r.put("answer", q == null || q.getAnswer() == null ? "" : q.getAnswer());
            rows.add(r);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sheetId", sheet.getId());
        out.put("paperId", sheet.getPaperId());
        out.put("title", sheet.getTitle());
        out.put("items", rows);
        return out;
    }

    /**
     * 批量保存标准答案：
     *  - 写入题库 question.answer，机器阅卷与「导出含答案」都以它为准
     *  - 同步答题卡布局里的答案快照，便于再次打开时核对
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveAnswers(Long sheetId, AnswerSetRequest req) {
        if (req == null || req.getItems() == null || req.getItems().isEmpty()) {
            throw new BizException("没有需要保存的答案");
        }
        AnswerSheet sheet = get(sheetId);
        Map<String, Object> layout = readLayout(sheet.getLayoutJson());

        Map<Long, String> answers = new LinkedHashMap<>();
        for (AnswerSetRequest.Item it : req.getItems()) {
            if (it.getQuestionId() == null) {
                continue;
            }
            Question q = questionMapper.selectById(it.getQuestionId());
            if (q == null) {
                continue;
            }
            String ans = normalizeAnswer(q.getQType(), it.getAnswer());
            Question upd = new Question();
            upd.setId(q.getId());
            upd.setAnswer(ans);
            questionMapper.updateById(upd);
            answers.put(q.getId(), ans);
        }
        if (answers.isEmpty()) {
            throw new BizException("没有可保存的题目");
        }

        answers.forEach((qid, ans) -> {
            syncCellAnswer(layout, qid, ans);
            syncCellAnswer(layout.get("objective"), qid, ans);
            syncCellAnswer(layout.get("subjective"), qid, ans);
        });
        try {
            sheet.setLayoutJson(json.writeValueAsString(layout));
        } catch (Exception e) {
            throw new BizException("答题卡布局保存失败");
        }
        sheetMapper.updateById(sheet);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sheetId", sheetId);
        out.put("updated", answers.size());
        return out;
    }

    /** 按题型规范化答案：选择题统一大写，判断题统一为 √ / ×（与答题卡涂点 A/B 对应） */
    private static String normalizeAnswer(String qType, String raw) {
        String a = raw == null ? "" : raw.trim();
        if (a.isEmpty()) {
            return "";
        }
        if ("CHOICE".equals(qType)) {
            return a.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        }
        if ("JUDGE".equals(qType)) {
            String s = a.toUpperCase(Locale.ROOT);
            if (s.equals("×") || s.equals("X") || s.contains("错") || s.contains("不正确")
                    || s.equals("F") || s.equals("FALSE") || s.equals("B")) {
                return "×";
            }
            if (s.equals("√") || s.contains("对") || s.contains("正确")
                    || s.equals("T") || s.equals("TRUE") || s.equals("A")) {
                return "√";
            }
            return a;
        }
        return a;
    }

    private Map<String, Object> readLayout(String layoutJson) {
        try {
            return json.readValue(layoutJson, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            throw new BizException("答题卡布局解析失败");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> collectCells(Map<String, Object> layout) {
        List<Map<String, Object>> out = new ArrayList<>();
        Object all = layout.get("cells");
        if (all instanceof List<?> list && !list.isEmpty()) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
            return out;
        }
        for (String key : List.of("objective", "subjective")) {
            Object block = layout.get(key);
            if (block instanceof Map<?, ?> bm && bm.get("cells") instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        out.add((Map<String, Object>) m);
                    }
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void syncCellAnswer(Object block, Long questionId, String answer) {
        if (!(block instanceof Map<?, ?> bm) || !(bm.get("cells") instanceof List<?> list)) {
            return;
        }
        for (Object o : list) {
            if (o instanceof Map<?, ?> c && questionId.equals(asLong(c.get("questionId")))) {
                ((Map<String, Object>) c).put("answer", answer);
            }
        }
    }

    private static Long asLong(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 在内存中绘制答题卡预览图（PNG），供前端直接展示/打印
     */
    /** 在每页顶部中央绘制机器可读页码（二进制方块，1-based 页码），供阅卷自动判定页码 */
    private void drawPageCode(Graphics2D g, Map<?, ?> layout, int page, int oy) {
        Map<?, ?> pc = (Map<?, ?>) layout.get("pageCode");
        if (pc == null) {
            return;
        }
        int bits = ((Number) pc.get("bits")).intValue();
        int size = ((Number) pc.get("size")).intValue();
        int gap = ((Number) pc.get("gap")).intValue();
        int x0 = ((Number) pc.get("x")).intValue();
        int y0 = ((Number) pc.get("y")).intValue() + oy;
        int value = page + 1;
        for (int i = 0; i < bits; i++) {
            int x = x0 + i * (size + gap);
            boolean on = ((value >> (bits - 1 - i)) & 1) == 1;
            if (on) {
                g.setColor(Color.BLACK);
                g.fillRect(x, y0, size, size);
            } else {
                g.setColor(new Color(190, 190, 190));
                g.drawRect(x, y0, size - 1, size - 1);
            }
        }
        g.setColor(Color.BLACK);
    }

    public BufferedImage renderPreview(AnswerSheet sheet, int scale) {
        // 主观题放不下时会续页，先把页数读出来才能确定画布高度
        int pageCount = 1;
        Map<?, ?> pre = null;
        try {
            pre = json.readValue(sheet.getLayoutJson(), Map.class);
            pageCount = Math.max(1, num(pre.get("pageCount")));
        } catch (Exception ignore) {
            // 解析失败时按单页绘制
        }
        int totalH = PAGE_H * pageCount;

        BufferedImage img = new BufferedImage(PAGE_W / scale, totalH / scale, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.scale(1.0 / scale, 1.0 / scale);

        try {
            Map<?, ?> layout = pre;

            // 定位标记 / 页码 / 信息区：每页都绘制（含首页），多页扫描时逐页都能归并为同一考生
            Map<?, ?> info = (Map<?, ?>) layout.get("info");
            List<?> markers = (List<?>) layout.get("markers");
            g.setColor(Color.BLACK);
            for (int p = 0; p < pageCount; p++) {
                int oy = p * PAGE_H;
                if (markers != null) {
                    for (Object o : markers) {
                        Map<?, ?> m = (Map<?, ?>) o;
                        g.fillRect(num(m.get("x")), num(m.get("y")) + oy,
                                num(m.get("size")), num(m.get("size")));
                    }
                }
                if (pageCount > 1) {
                    g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 28));
                    g.drawString("第 " + (p + 1) + " / " + pageCount + " 页",
                            PAGE_W - MARGIN - 220, PAGE_H - MARGIN - 40 + oy);
                    g.setColor(Color.BLACK);
                }
                // 机器可读页码标识：二进制方块编码 1-based 页码，位于每页顶部中央
                drawPageCode(g, layout, p, oy);

                // 信息区：姓名 / 班级 / 手写考号格（旧版布局兼容涂卡区），每页复刻，
                // 多页答题卡的任何一页扫描后都能识别考生
                if (info != null) {
                    g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 34));
                    Map<?, ?> nb = (Map<?, ?>) info.get("nameBox");
                    Map<?, ?> cb = (Map<?, ?>) info.get("classBox");
                    Map<?, ?> hb = (Map<?, ?>) info.get("idHandBox");
                    int digits = num(info.get("idDigits"));
                    if (nb != null) {
                        g.drawString("姓名：________________", num(nb.get("x")), num(nb.get("y")) + 60 + oy);
                    }
                    if (cb != null) {
                        g.drawString("班级：____________", num(cb.get("x")), num(cb.get("y")) + 60 + oy);
                    }

                    // 考号涂卡区：仅旧版布局才包含该字段，新版不再生成
                    if (info.get("idBubbleStartX") != null) {
                        int startY = num(info.get("idStartY"));
                        int bx0 = num(info.get("idBubbleStartX"));
                        int gx = num(info.get("idBubbleGapX"));
                        int gy = num(info.get("idBubbleGapY"));
                        int r = num(info.get("idBubbleR"));
                        Map<?, ?> db = (Map<?, ?>) info.get("idDigitBox");
                        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 28));
                        g.drawString("考号", num(db.get("x")), num(db.get("y")) - 10 + oy);
                        for (int d = 0; d < digits; d++) {
                            int x = num(db.get("x")) + d * gx;
                            for (int n = 0; n < 10; n++) {
                                int cy = startY + n * gy + oy;
                                g.drawRect(bx0 + d * gx - r, cy - r, r * 2, r * 2);
                                String numStr = String.valueOf(n);
                                g.setFont(new Font("Arial", Font.PLAIN, 22));
                                g.drawString(numStr, bx0 + d * gx - 6, cy + 8);
                            }
                        }
                    }

                    // 考号手写格：一排方框供手写考号，OCR 识别 + 人工核对都以它为准
                    if (hb != null) {
                        int hx = num(hb.get("x"));
                        int hy = num(hb.get("y"));
                        g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 28));
                        g.drawString("考号", hx, hy + 40 + oy);
                        for (int d = 0; d < digits; d++) {
                            int sqx = hx + 90 + d * 80;
                            g.drawRect(sqx, hy + oy, 64, 64);
                        }
                    }
                    g.setColor(Color.BLACK);
                }
            }

            // 标题
            g.setFont(new Font("Microsoft YaHei", Font.BOLD, 64));
            String title = sheet.getTitle();
            int tw = g.getFontMetrics().stringWidth(title);
            g.drawString(title, (PAGE_W - tw) / 2, MARGIN + 100);
            // 统一按 sectionNo 升序渲染所有大题（新布局用 layout.cells；
            // 旧布局没有该字段时回退到「先客观后主观」的两段式，保证兼容）
            List<?> renderCells;
            Object cellsField = layout.get("cells");
            if (cellsField instanceof List<?>) {
                renderCells = (List<?>) cellsField;
                drawSectionHeaders(g, layout.get("sectionHeaders"));
            } else {
                List<Object> merged = new ArrayList<>();
                Map<?, ?> obj = (Map<?, ?>) layout.get("objective");
                Map<?, ?> subj = (Map<?, ?>) layout.get("subjective");
                if (obj != null) {
                    drawSectionHeaders(g, obj.get("sectionHeaders"));
                    if (obj.get("cells") instanceof List<?>) {
                        merged.addAll((List<?>) obj.get("cells"));
                    }
                }
                if (subj != null) {
                    drawSectionHeaders(g, subj.get("sectionHeaders"));
                    if (subj.get("cells") instanceof List<?>) {
                        merged.addAll((List<?>) subj.get("cells"));
                    }
                }
                renderCells = merged;
            }

            for (Object o : renderCells) {
                if (!(o instanceof Map<?, ?> c)) {
                    continue;
                }
                Map<?, ?> box = (Map<?, ?>) c.get("box");
                if (box == null) {
                    continue;
                }
                // 渲染类型：新布局直接用 renderType 字段；旧布局按题型推断
                Object rt = c.get("renderType");
                String renderType = rt == null
                        ? ("CHOICE".equals(String.valueOf(c.get("qType")))
                                || "JUDGE".equals(String.valueOf(c.get("qType"))) ? "bubble" : "answer")
                        : String.valueOf(rt);
                boolean bubble = "bubble".equals(renderType);
                if (bubble) {
                    // 涂卡格
                    g.setColor(new Color(0xDD, 0xDD, 0xDD));
                    g.drawRect(num(box.get("x")), num(box.get("y")), num(box.get("w")), num(box.get("h")));
                    g.setColor(Color.BLACK);
                    g.setFont(new Font("Microsoft YaHei", Font.BOLD, 34));
                    g.drawString(c.get("qNo") + ".", num(box.get("x")) + 15, num(box.get("y")) + 62);
                    List<?> bubbles = (List<?>) c.get("bubbles");
                    if (bubbles != null) {
                        boolean judge = "JUDGE".equals(String.valueOf(c.get("qType")));
                        for (Object bo : bubbles) {
                            Map<?, ?> b = (Map<?, ?>) bo;
                            int cx = num(b.get("cx")), cy = num(b.get("cy")), rr = num(b.get("r"));
                            g.drawRect(cx - rr, cy - rr, rr * 2, rr * 2);
                            String label = judge
                                    ? ("A".equals(String.valueOf(b.get("key"))) ? "√" : "×")
                                    : String.valueOf(b.get("key"));
                            g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 24));
                            g.drawString(label, cx - 8, cy + 9);
                        }
                    }
                } else {
                    // 主观题：填空用两列横线；解答/计算/实验用整宽横线；旧布局无横线时回退为空白框
                    int bx = num(box.get("x"));
                    int by = num(box.get("y"));
                    int bw = num(box.get("w"));
                    int bh = num(box.get("h"));
                    boolean fill = "fill".equals(renderType);
                    int lines = c.get("lineCount") == null ? 0 : num(c.get("lineCount"));
                    int gap = c.get("lineGap") == null ? 100 : num(c.get("lineGap"));
                    int lineStart = c.get("lineStartY") == null ? by + 72 : num(c.get("lineStartY"));

                    g.setColor(new Color(0xDD, 0xDD, 0xDD));
                    g.drawRect(bx, by, bw, bh);

                    g.setColor(Color.BLACK);
                    g.setFont(new Font("Microsoft YaHei", Font.BOLD, fill ? 32 : 34));
                    Object score = c.get("score");
                    String scoreStr = fill || score == null ? ""
                            : ("（" + new BigDecimal(score.toString()).stripTrailingZeros().toPlainString() + "分）");
                    g.drawString(c.get("qNo") + "." + scoreStr, bx + 18, by + 46);

                    // 作答横线：浅灰细线，不干扰扫描与二值化
                    if (lines > 0) {
                        g.setColor(new Color(0xBB, 0xBB, 0xBB));
                        g.setStroke(new BasicStroke(2f));
                        int x1 = fill ? bx + 62 : bx + 16;
                        int x2 = bx + bw - 16;
                        for (int k = 0; k < lines; k++) {
                            int ly = lineStart + k * gap;
                            g.drawLine(x1, ly, x2, ly);
                        }
                        g.setStroke(new BasicStroke(1f));
                        g.setColor(Color.BLACK);
                    }

                    if (!fill) {
                        Map<?, ?> sb = (Map<?, ?>) c.get("scoreBox");
                        if (sb != null) {
                            g.setColor(new Color(0xDD, 0xDD, 0xDD));
                            g.drawRect(num(sb.get("x")), num(sb.get("y")), num(sb.get("w")), num(sb.get("h")));
                            g.setColor(Color.GRAY);
                            g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 24));
                            g.drawString("得分", num(sb.get("x")) + 8, num(sb.get("y")) + 36);
                            g.setColor(Color.BLACK);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("绘制答题卡失败", e);
        }
        g.dispose();
        return img;
    }

    /** 只渲染指定页（0 起），供浏览器按 A4 逐页打印 */
    public BufferedImage renderPage(AnswerSheet sheet, int pageIndex, int scale) {
        BufferedImage all = renderPreview(sheet, scale);
        int pageH = PAGE_H / scale;
        int y = pageIndex * pageH;
        if (pageIndex < 0 || y >= all.getHeight()) {
            return null;
        }
        int h = Math.min(pageH, all.getHeight() - y);
        return all.getSubimage(0, y, all.getWidth(), h);
    }

    /**
     * 生成按 A4 分页的多页 PDF：每页答题卡对应一张 A4 页，直接交给打印机即可逐页输出，
     * 避免把整张长图压进一页导致换行错位。
     */
    public byte[] renderPdf(AnswerSheet sheet) {
        int pageCount = 1;
        try {
            Map<?, ?> pre = json.readValue(sheet.getLayoutJson(), Map.class);
            pageCount = Math.max(1, num(pre.get("pageCount")));
        } catch (Exception ignore) {
            // 解析失败时按单页处理
        }
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pageCount; i++) {
                BufferedImage pageImg = renderPage(sheet, i, 1);
                if (pageImg == null) {
                    continue;
                }
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                PDImageXObject img = LosslessFactory.createFromImage(doc, pageImg);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    PDRectangle box = page.getMediaBox();
                    cs.drawImage(img, 0, 0, box.getWidth(), box.getHeight());
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new BizException("答题卡 PDF 生成失败：" + e.getMessage());
        }
    }

    /** 画出各分区标题（一、选择题 等），让答题卡与试卷的大题结构一一对应 */
    private static void drawSectionHeaders(Graphics2D g, Object headers) {
        if (!(headers instanceof List<?> list)) {
            return;
        }
        g.setColor(Color.BLACK);
        g.setFont(new Font("Microsoft YaHei", Font.BOLD, 36));
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            g.drawString(String.valueOf(m.get("name")), num(m.get("x")), num(m.get("y")));
        }
    }

    private static int num(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }
}
