package com.examscan.modules.paper.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.examscan.common.BizException;
import com.examscan.common.QuestionType;
import com.examscan.modules.answer.entity.AnswerSheet;
import com.examscan.modules.answer.mapper.AnswerSheetMapper;
import com.examscan.modules.answer.service.AnswerSheetService;
import com.examscan.modules.paper.dto.CustomPaperRequest;
import com.examscan.modules.paper.dto.PaperConfig;
import com.examscan.modules.paper.entity.Paper;
import com.examscan.modules.paper.entity.PaperItem;
import com.examscan.modules.paper.mapper.PaperItemMapper;
import com.examscan.modules.paper.mapper.PaperMapper;
import com.examscan.modules.question.entity.Question;
import com.examscan.modules.question.mapper.QuestionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 组卷服务
 *
 * 核心能力：
 *  - 按题型配置题量与分值，自动选题（避免同卷重复相似题）
 *  - 分值设置与总分校验（可指定目标总分，自动微调）
 *  - 生成大题结构（一、二、三……）与卷内顺序
 *  - 试卷导出（Word）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaperService {

    private final PaperMapper paperMapper;
    private final PaperItemMapper itemMapper;
    private final QuestionMapper questionMapper;
    private final AnswerSheetService answerSheetService;
    private final AnswerSheetMapper answerSheetMapper;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * 自动组卷
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> autoBuild(PaperConfig cfg) {
        if (cfg.getSections() == null || cfg.getSections().isEmpty()) {
            throw new BizException("请至少配置一个大题");
        }

        Paper paper = new Paper();
        paper.setTitle(cfg.getTitle() == null || cfg.getTitle().isBlank() ? "自动生成试卷" : cfg.getTitle());
        paper.setSubject(cfg.getSubject());
        paper.setGrade(cfg.getGrade());
        paper.setDuration(cfg.getDuration() == null ? 90 : cfg.getDuration());
        paper.setPaperType("AUTO");
        paper.setStatus("DRAFT");
        paper.setDeleted(0);
        paper.setQuestionCount(0);
        paper.setTotalScore(BigDecimal.ZERO);
        try {
            paper.setConfigJson(json.writeValueAsString(cfg));
        } catch (Exception ignored) {
        }
        paperMapper.insert(paper);

        List<String> warnings = new ArrayList<>();
        Set<Long> usedIds = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        int count = 0;

        // 大题顺序固定为：填空 → 判断 → 选择 → 解答 → 实验探究 → 作图 → 计算 → 其他
        // （与试卷、答题卡的卷面习惯一致），不再跟随配置书写顺序
        List<PaperConfig.SectionConfig> sections = new ArrayList<>(cfg.getSections());
        sections.sort(Comparator.comparingInt(s -> QuestionType.of(s.getQType()).getDisplayOrder()));
        int sectionIdx = 0;

        for (PaperConfig.SectionConfig sec : sections) {
            sectionIdx++;
            QuestionType type = QuestionType.of(sec.getQType());
            int need = sec.getCount() == null ? 0 : sec.getCount();
            if (need <= 0 && (sec.getQuestionIds() == null || sec.getQuestionIds().isEmpty())) {
                continue;
            }
            BigDecimal perScore = sec.getScorePerQuestion() == null
                    ? defaultScore(type) : sec.getScorePerQuestion();

            List<Question> picked = new ArrayList<>();

            // 1) 手工指定优先
            if (sec.getQuestionIds() != null && !sec.getQuestionIds().isEmpty()) {
                for (Long id : sec.getQuestionIds()) {
                    Question q = questionMapper.selectById(id);
                    if (q != null && !usedIds.contains(id)) {
                        picked.add(q);
                        usedIds.add(id);
                    }
                }
            }

            // 2) 不足部分自动候选
            if (picked.size() < need) {
                int remain = need - picked.size();
                List<Question> candidates = pickCandidates(cfg, type, usedIds);
                for (Question q : candidates) {
                    if (remain <= 0) {
                        break;
                    }
                    if (usedIds.add(q.getId())) {
                        picked.add(q);
                        remain--;
                    }
                }
                if (remain > 0) {
                    warnings.add(String.format("%s 题量不足，需要 %d 道，实际仅凑到 %d 道",
                            type.getLabel(), need, picked.size()));
                }
            }

            // 3) 写入明细
            String sectionName = sec.getSectionName() == null || sec.getSectionName().isBlank()
                    ? type.getLabel() : sec.getSectionName();
            for (Question q : picked) {
                PaperItem item = new PaperItem();
                item.setPaperId(paper.getId());
                item.setQuestionId(q.getId());
                item.setSectionNo(sectionIdx);
                item.setSectionName(sectionName);
                item.setQType(type.name());
                item.setSortNo(count + 1);
                item.setScore(perScore);
                itemMapper.insert(item);
                total = total.add(perScore);
                count++;
            }
        }

        // 4) 总分校验与微调
        if (cfg.getTargetScore() != null && cfg.getTargetScore().compareTo(BigDecimal.ZERO) > 0
                && count > 0 && total.compareTo(cfg.getTargetScore()) != 0) {
            BigDecimal diff = cfg.getTargetScore().subtract(total);
            warnings.add(String.format("当前总分 %s 分，目标总分 %s 分，相差 %s 分",
                    strip(total), strip(cfg.getTargetScore()), strip(diff)));
        }

        paper.setTotalScore(total);
        paper.setQuestionCount(count);
        paperMapper.updateById(paper);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("paperId", paper.getId());
        out.put("totalScore", total);
        out.put("questionCount", count);
        out.put("warnings", warnings);
        return out;
    }

    /**
     * 候选题目挑选：按难度均衡抽取（避免整卷难度集中）
     */
    private List<Question> pickCandidates(PaperConfig cfg, QuestionType type, Set<Long> used) {
        LambdaQueryWrapper<Question> w = new LambdaQueryWrapper<Question>()
                .eq(Question::getQType, type.name());
        if (cfg.getSubject() != null && !cfg.getSubject().isBlank()) {
            w.eq(Question::getSubject, cfg.getSubject());
        }
        if (cfg.getGrade() != null && !cfg.getGrade().isBlank()) {
            w.eq(Question::getGrade, cfg.getGrade());
        }
        if (cfg.getMinDifficulty() != null) {
            w.ge(Question::getDifficulty, cfg.getMinDifficulty());
        }
        if (cfg.getMaxDifficulty() != null) {
            w.le(Question::getDifficulty, cfg.getMaxDifficulty());
        }
        if (cfg.getKnowledge() != null && !cfg.getKnowledge().isBlank()) {
            w.and(x -> {
                boolean first = true;
                for (String kp : cfg.getKnowledge().split(",")) {
                    if (kp.isBlank()) {
                        continue;
                    }
                    if (first) {
                        x.like(Question::getKnowledge, kp.trim());
                        first = false;
                    } else {
                        x.or().like(Question::getKnowledge, kp.trim());
                    }
                }
            });
        }
        List<Question> pool = questionMapper.selectList(w);
        pool.removeIf(q -> used.contains(q.getId()));

        // 难度轮转抽样：按难度分桶后交错取，保证梯度
        Map<Integer, List<Question>> buckets = pool.stream()
                .collect(Collectors.groupingBy(q -> q.getDifficulty() == null ? 3 : q.getDifficulty()));
        List<Integer> keys = new ArrayList<>(buckets.keySet());
        Collections.sort(keys);
        for (List<Question> l : buckets.values()) {
            Collections.shuffle(l);
        }
        List<Question> ordered = new ArrayList<>();
        int maxSize = buckets.values().stream().mapToInt(List::size).max().orElse(0);
        for (int i = 0; i < maxSize; i++) {
            for (Integer k : keys) {
                List<Question> l = buckets.get(k);
                if (i < l.size()) {
                    ordered.add(l.get(i));
                }
            }
        }
        return ordered;
    }

    private BigDecimal defaultScore(QuestionType t) {
        return switch (t) {
            case CHOICE -> BigDecimal.valueOf(3);
            case FILL -> BigDecimal.valueOf(2);
            case JUDGE -> BigDecimal.valueOf(2);
            case CALC -> BigDecimal.valueOf(6);
            case ANSWER -> BigDecimal.valueOf(8);
            case DRAW -> BigDecimal.valueOf(4);
            case EXPERIMENT -> BigDecimal.valueOf(6);
            default -> BigDecimal.valueOf(5);
        };
    }

    private static String strip(BigDecimal b) {
        return b.stripTrailingZeros().toPlainString();
    }

    /**
     * 自定义组卷：用户在题库中逐题勾选，按提交顺序组卷，每题可单独设置分值。
     * 同题型的相邻题目自动归入同一个大题。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> customBuild(CustomPaperRequest req) {
        if (req.getItems() == null || req.getItems().isEmpty()) {
            throw new BizException("请先从题库中选择题目");
        }

        Paper paper = new Paper();
        paper.setTitle(req.getTitle() == null || req.getTitle().isBlank() ? "自定义试卷" : req.getTitle());
        paper.setSubject(req.getSubject());
        paper.setGrade(req.getGrade());
        paper.setDuration(req.getDuration() == null ? 90 : req.getDuration());
        paper.setPaperType("MANUAL");
        paper.setStatus("DRAFT");
        paper.setRemark(req.getRemark());
        paper.setDeleted(0);
        paper.setQuestionCount(0);
        paper.setTotalScore(BigDecimal.ZERO);
        paperMapper.insert(paper);

        List<String> warnings = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        List<Picked> pickedList = new ArrayList<>();

        for (CustomPaperRequest.Item it : req.getItems()) {
            if (it.getQuestionId() == null) {
                continue;
            }
            Question q = questionMapper.selectById(it.getQuestionId());
            if (q == null) {
                warnings.add("题目 " + it.getQuestionId() + " 不存在，已跳过");
                continue;
            }
            if (!seen.add(q.getId())) {
                warnings.add("题目 " + q.getQNo() + "（" + brief(q.getStem()) + "）重复添加，已跳过");
                continue;
            }
            BigDecimal score = it.getScore() == null || it.getScore().compareTo(BigDecimal.ZERO) <= 0
                    ? q.getScore() == null ? BigDecimal.valueOf(5) : q.getScore() : it.getScore();
            pickedList.add(new Picked(q, score));
        }

        // 按题型集中：同一题型的题目归入同一个大题，不受勾选顺序影响；
        // 大题顺序遵循常规卷面习惯（选择 → 填空 → 判断 → 计算 → 解答 → 作图 → 实验 → 其他）
        Map<String, List<Picked>> byType = new LinkedHashMap<>();
        for (Picked p : pickedList) {
            String t = p.question().getQType() == null ? QuestionType.OTHER.name() : p.question().getQType();
            byType.computeIfAbsent(t, k -> new ArrayList<>()).add(p);
        }
        List<String> types = new ArrayList<>(byType.keySet());
        types.sort(Comparator.comparingInt(t -> QuestionType.of(t).getDisplayOrder()));

        BigDecimal total = BigDecimal.ZERO;
        int count = 0;
        int sectionNo = 0;
        for (String type : types) {
            sectionNo++;
            for (Picked p : byType.get(type)) {
                PaperItem item = new PaperItem();
                item.setPaperId(paper.getId());
                item.setQuestionId(p.question().getId());
                item.setSectionNo(sectionNo);
                item.setSectionName(QuestionType.of(type).getLabel());
                item.setQType(type);
                item.setSortNo(count + 1);
                item.setScore(p.score());
                itemMapper.insert(item);

                total = total.add(p.score());
                count++;
            }
        }

        if (count == 0) {
            throw new BizException("所选题目均无效，组卷失败");
        }
        paper.setTotalScore(total);
        paper.setQuestionCount(count);
        paperMapper.updateById(paper);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("paperId", paper.getId());
        out.put("totalScore", total);
        out.put("questionCount", count);
        out.put("sectionCount", sectionNo);
        out.put("warnings", warnings);
        return out;
    }

    private static String brief(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 20 ? s.substring(0, 20) + "…" : s;
    }

    /** 自定义组卷收集到的题目及其分值 */
    private record Picked(Question question, BigDecimal score) {
    }

    // ---------------- 查询 / 调整 ----------------

    public List<Paper> list() {
        return paperMapper.selectList(new LambdaQueryWrapper<Paper>().orderByDesc(Paper::getId));
    }

    /** 修改试卷名称；返回更新后的试卷，供前端直接刷新显示 */
    @Transactional(rollbackFor = Exception.class)
    public Paper renamePaper(Long paperId, String title) {
        String t = title == null ? "" : title.trim();
        if (t.isEmpty()) {
            throw new BizException("试卷名称不能为空");
        }
        if (t.length() > 100) {
            throw new BizException("试卷名称不能超过 100 字");
        }
        Paper paper = paperMapper.selectById(paperId);
        if (paper == null) {
            throw new BizException("试卷不存在");
        }
        paper.setTitle(t);
        paperMapper.updateById(paper);
        return paper;
    }

    /** 试卷详情：含题目明细与题目内容 */
    public Map<String, Object> detail(Long paperId) {
        Paper paper = paperMapper.selectById(paperId);
        if (paper == null) {
            throw new BizException("试卷不存在");
        }
        // 先按大题再按卷内顺序：保证同一题型的题目连续排列
        List<PaperItem> items = itemMapper.selectList(new LambdaQueryWrapper<PaperItem>()
                .eq(PaperItem::getPaperId, paperId)
                .orderByAsc(PaperItem::getSectionNo)
                .orderByAsc(PaperItem::getSortNo));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (PaperItem it : items) {
            Question q = questionMapper.selectById(it.getQuestionId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("item", it);
            m.put("question", q);
            rows.add(m);
        }
        // 按大题分组
        Map<Integer, List<Map<String, Object>>> sections = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            PaperItem it = (PaperItem) r.get("item");
            sections.computeIfAbsent(it.getSectionNo(), k -> new ArrayList<>()).add(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("paper", paper);
        out.put("items", rows);
        out.put("sections", sections);
        return out;
    }

    /** 调整某题分值 */
    @Transactional(rollbackFor = Exception.class)
    public void updateItemScore(Long itemId, BigDecimal score) {
        PaperItem it = itemMapper.selectById(itemId);
        if (it == null) {
            throw new BizException("题目明细不存在");
        }
        if (score == null || score.compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException("分值必须大于等于0");
        }
        it.setScore(score);
        itemMapper.updateById(it);
        recalcTotal(it.getPaperId());
    }

    /** 重新计算试卷总分 */
    public BigDecimal recalcTotal(Long paperId) {
        List<PaperItem> items = itemMapper.selectList(
                new LambdaQueryWrapper<PaperItem>().eq(PaperItem::getPaperId, paperId));
        BigDecimal total = items.stream()
                .map(PaperItem::getScore)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Paper p = paperMapper.selectById(paperId);
        if (p != null) {
            p.setTotalScore(total);
            p.setQuestionCount(items.size());
            paperMapper.updateById(p);
        }
        return total;
    }

    /** 从试卷移除一道题 */
    @Transactional(rollbackFor = Exception.class)
    public void removeItem(Long itemId) {
        PaperItem it = itemMapper.selectById(itemId);
        if (it == null) {
            return;
        }
        itemMapper.deleteById(itemId);
        recalcTotal(it.getPaperId());
    }

    /**
     * 题目上下换位：与同一大题内相邻的上一题/下一题交换 sortNo
     *
     * @param direction "up"=上移 / "down"=下移
     */
    @Transactional(rollbackFor = Exception.class)
    public void moveItem(Long itemId, String direction) {
        PaperItem cur = itemMapper.selectById(itemId);
        if (cur == null) {
            throw new BizException("题目明细不存在");
        }
        boolean up = "up".equalsIgnoreCase(direction);
        // 找同一大题内相邻的题目（按 sortNo 排序）
        List<PaperItem> sectionItems = itemMapper.selectList(
                new LambdaQueryWrapper<PaperItem>()
                        .eq(PaperItem::getPaperId, cur.getPaperId())
                        .eq(PaperItem::getSectionNo, cur.getSectionNo())
                        .orderByAsc(PaperItem::getSortNo));
        int idx = -1;
        for (int i = 0; i < sectionItems.size(); i++) {
            if (sectionItems.get(i).getId().equals(itemId)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            throw new BizException("题目明细数据异常");
        }
        int target = up ? idx - 1 : idx + 1;
        if (target < 0) {
            throw new BizException("已经是第一题，不能再上移");
        }
        if (target >= sectionItems.size()) {
            throw new BizException("已经是最后一题，不能再下移");
        }
        PaperItem neighbor = sectionItems.get(target);
        Integer tmp = cur.getSortNo();
        cur.setSortNo(neighbor.getSortNo());
        neighbor.setSortNo(tmp);
        itemMapper.updateById(cur);
        itemMapper.updateById(neighbor);
    }

    /**
     * 同一大题内小题随机打乱（选择、判断组内同样打乱），大题之间顺序不变。
     * 打乱后重新生成该试卷的答题卡，使卷面题号与新顺序一致。
     *
     * @return 打乱的大题数与重新生成的答题卡数
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> shuffleItems(Long paperId) {
        List<PaperItem> items = itemMapper.selectList(new LambdaQueryWrapper<PaperItem>()
                .eq(PaperItem::getPaperId, paperId)
                .orderByAsc(PaperItem::getSectionNo)
                .orderByAsc(PaperItem::getSortNo));
        if (items.isEmpty()) {
            throw new BizException("试卷还没有题目");
        }
        // 按大题分组，组内随机
        Map<Integer, List<PaperItem>> sections = new LinkedHashMap<>();
        for (PaperItem it : items) {
            sections.computeIfAbsent(it.getSectionNo(), k -> new ArrayList<>()).add(it);
        }
        int changedSections = 0;
        for (List<PaperItem> group : sections.values()) {
            if (group.size() < 2) {
                continue;
            }
            List<PaperItem> shuffled = new ArrayList<>(group);
            Collections.shuffle(shuffled);
            if (shuffled.equals(group)) {
                Collections.shuffle(shuffled);
            }
            for (int i = 0; i < shuffled.size(); i++) {
                PaperItem it = shuffled.get(i);
                if (it.getSortNo() == null || it.getSortNo() != i + 1) {
                    it.setSortNo(i + 1);
                    itemMapper.updateById(it);
                }
            }
            changedSections++;
        }
        // 已生成答题卡的重新排版，使答题卡题号顺序与打乱后一致
        int sheets = 0;
        Long cnt = answerSheetMapper.selectCount(new LambdaQueryWrapper<AnswerSheet>()
                .eq(AnswerSheet::getPaperId, paperId)
                .eq(AnswerSheet::getDeleted, 0));
        if (cnt != null && cnt > 0) {
            sheets = regenerateSheets(paperId);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sections", changedSections);
        out.put("sheetsRegenerated", sheets);
        return out;
    }

    /** 向试卷追加一道题（放到指定大题末尾） */
    @Transactional(rollbackFor = Exception.class)
    public void addItem(Long paperId, Long questionId, String qType, BigDecimal score) {
        Question q = questionMapper.selectById(questionId);
        if (q == null) {
            throw new BizException("题目不存在");
        }
        // paper_item 没有唯一约束，业务层拦住重复添加
        Long dup = itemMapper.selectCount(new LambdaQueryWrapper<PaperItem>()
                .eq(PaperItem::getPaperId, paperId)
                .eq(PaperItem::getQuestionId, questionId));
        if (dup != null && dup > 0) {
            throw new BizException("该题目已在试卷中");
        }
        List<PaperItem> existing = itemMapper.selectList(new LambdaQueryWrapper<PaperItem>()
                .eq(PaperItem::getPaperId, paperId)
                .orderByAsc(PaperItem::getSectionNo)
                .orderByAsc(PaperItem::getSortNo));
        QuestionType type = QuestionType.of(qType == null ? q.getQType() : qType);

        // 归入同题型的大题，保持「同一题型集中」；没有该题型的大题时新建
        int sectionNo = existing.stream()
                .filter(i -> type.name().equals(i.getQType()))
                .mapToInt(i -> i.getSectionNo() == null ? 0 : i.getSectionNo())
                .findFirst()
                .orElseGet(() -> existing.stream()
                        .mapToInt(i -> i.getSectionNo() == null ? 0 : i.getSectionNo())
                        .max().orElse(0) + 1);
        int maxSort = existing.stream()
                .mapToInt(i -> i.getSortNo() == null ? 0 : i.getSortNo())
                .max().orElse(0);

        PaperItem item = new PaperItem();
        item.setPaperId(paperId);
        item.setQuestionId(questionId);
        item.setSectionNo(sectionNo);
        item.setSectionName(type.getLabel());
        item.setQType(type.name());
        item.setSortNo(maxSort + 1);
        item.setScore(score == null ? defaultScore(type) : score);
        itemMapper.insert(item);
        recalcTotal(paperId);
        // 追加后按固定题型顺序归位，保证大题顺序始终符合标准卷面
        reorderPaper(paperId);
    }

    /**
     * 用题库里的另一道题替换试卷中已有的某题。
     * 保留原 paper_item 的 id、位置（sectionNo/sortNo）与分值，只把题目内容指向新题；
     * 题型变化时按标准大题顺序归位，避免同一大题里混入不同题型。
     */
    @Transactional(rollbackFor = Exception.class)
    public void replaceItem(Long itemId, Long questionId, BigDecimal score) {
        PaperItem it = itemMapper.selectById(itemId);
        if (it == null) {
            throw new BizException("题目明细不存在");
        }
        Question q = questionMapper.selectById(questionId);
        if (q == null) {
            throw new BizException("题目不存在");
        }
        Long dup = itemMapper.selectCount(new LambdaQueryWrapper<PaperItem>()
                .eq(PaperItem::getPaperId, it.getPaperId())
                .eq(PaperItem::getQuestionId, questionId)
                .ne(PaperItem::getId, itemId));
        if (dup != null && dup > 0) {
            throw new BizException("该题目已在试卷中");
        }
        QuestionType type = QuestionType.of(q.getQType());
        it.setQuestionId(questionId);
        it.setQType(type.name());
        it.setSectionName(type.getLabel());
        if (score != null) {
            it.setScore(score);
        }
        itemMapper.updateById(it);
        recalcTotal(it.getPaperId());
        reorderPaper(it.getPaperId());
    }

    /**
     * 把所有试卷的大题按固定顺序（填空→判断→选择→解答→实验探究→作图→计算→其他）重排，
     * 并重新生成对应的答题卡，使全局大题顺序统一。
     *
     * @return 处理过的试卷数与重新生成的答题卡数
     */
    public Map<String, Object> reorderAllSections() {
        List<Paper> papers = paperMapper.selectList(new LambdaQueryWrapper<Paper>()
                .eq(Paper::getDeleted, 0));
        int papersDone = 0;
        int sheetsRegen = 0;
        for (Paper paper : papers) {
            int n = reorderPaper(paper.getId());
            papersDone++;
            if (n > 0) {
                sheetsRegen += regenerateSheets(paper.getId());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("papers", papersDone);
        out.put("sheetsRegenerated", sheetsRegen);
        return out;
    }

    /**
     * 把单份试卷的大题按固定题型顺序重排 sectionNo（同题型归一组，组间按 displayOrder 递增）。
     *
     * @return 该试卷题目数（0 表示空卷，无需处理）
     */
    @Transactional(rollbackFor = Exception.class)
    public int reorderPaper(Long paperId) {
        List<PaperItem> items = itemMapper.selectList(new LambdaQueryWrapper<PaperItem>()
                .eq(PaperItem::getPaperId, paperId)
                .orderByAsc(PaperItem::getSectionNo)
                .orderByAsc(PaperItem::getSortNo));
        if (items.isEmpty()) {
            return 0;
        }
        // 按固定题型顺序分组：FILL→1, JUDGE→2, CHOICE→3 ...
        Map<Integer, List<PaperItem>> byOrder = new LinkedHashMap<>();
        for (PaperItem it : items) {
            int order = QuestionType.of(it.getQType()).getDisplayOrder();
            byOrder.computeIfAbsent(order, k -> new ArrayList<>()).add(it);
        }
        List<Integer> orders = new ArrayList<>(byOrder.keySet());
        Collections.sort(orders);
        int sectionNo = 0;
        for (int order : orders) {
            sectionNo++;
            for (PaperItem it : byOrder.get(order)) {
                it.setSectionNo(sectionNo);
                itemMapper.updateById(it);
            }
        }
        return items.size();
    }

    /** 删除该试卷的旧答题卡，并重新生成一张，使答题卡顺序与试卷保持一致 */
    private int regenerateSheets(Long paperId) {
        List<AnswerSheet> old = answerSheetMapper.selectList(new LambdaQueryWrapper<AnswerSheet>()
                .eq(AnswerSheet::getPaperId, paperId)
                .eq(AnswerSheet::getDeleted, 0));
        for (AnswerSheet s : old) {
            answerSheetMapper.deleteById(s.getId());
        }
        answerSheetService.generate(paperId, null);
        return 1;
    }

    public void deletePaper(Long paperId) {
        paperMapper.deleteById(paperId);
        itemMapper.delete(new LambdaQueryWrapper<PaperItem>().eq(PaperItem::getPaperId, paperId));
    }
}
