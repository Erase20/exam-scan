package com.examscan.modules.grading.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.examscan.common.BizException;
import com.examscan.modules.grading.entity.GradeSheet;
import com.examscan.modules.grading.mapper.GradeSheetMapper;
import com.examscan.modules.paper.entity.Paper;
import com.examscan.modules.paper.mapper.PaperMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 阅卷成绩统计：按试卷界定「本场考试」，可选按班级、阅卷日期范围圈定范围。
 *
 * 输出四类内容：
 *  1. 汇总指标：卷面总分、成绩合计、平均分、最高分、最低分、中位数、及格率、优秀率
 *  2. 图表数据：分数段人数分布、各班平均分对比、按阅卷顺序的成绩走势
 *  3. 分组明细：班级成绩汇总、考生排名明细
 *  4. 筛选项：该场考试出现过的班级列表
 *
 * 成绩量为一个年级/班级级别（几十至几百条），统一在内存中聚合，
 * 避免自定义 SQL 在 H2 / MySQL 之间的语法差异，逻辑删除仍由 MyBatis-Plus 自动处理。
 */
@Service
@RequiredArgsConstructor
public class GradeStatsService {

    private final GradeSheetMapper gradeSheetMapper;
    private final PaperMapper paperMapper;

    private static final int BUCKETS = 10;
    private static final String UNKNOWN_CLASS = "未填班级";
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal PASS_LINE_RATE = new BigDecimal("0.60");
    private static final BigDecimal EXCELLENT_LINE_RATE = new BigDecimal("0.85");

    /** 本场考试成绩统计（paperId 为空时统计全部阅卷记录） */
    public Map<String, Object> stats(Long paperId, String className, String startDate, String endDate) {
        Paper paper = null;
        if (paperId != null) {
            paper = paperMapper.selectById(paperId);
            if (paper == null) {
                throw new BizException("试卷不存在");
            }
        }

        LocalDate from = parseDate(startDate);
        LocalDate to = parseDate(endDate);
        if (from != null && to != null && from.isAfter(to)) {
            throw new BizException("开始日期不能晚于结束日期");
        }

        // 只取统计所需字段，避开 detailJson / imagePath 等大字段
        LambdaQueryWrapper<GradeSheet> w = new LambdaQueryWrapper<>();
        w.select(GradeSheet::getId, GradeSheet::getPaperId, GradeSheet::getStudentNo,
                GradeSheet::getClassName, GradeSheet::getStudentName,
                GradeSheet::getObjectiveScore, GradeSheet::getSubjectiveScore,
                GradeSheet::getTotalScore, GradeSheet::getFullScore,
                GradeSheet::getStatus, GradeSheet::getCreateTime);
        if (paperId != null) {
            w.eq(GradeSheet::getPaperId, paperId);
        }
        w.orderByAsc(GradeSheet::getId);

        List<GradeSheet> scoped = gradeSheetMapper.selectList(w);
        // 班级下拉不受 className 筛选影响，所以用日期过滤后的全量数据生成
        List<GradeSheet> base = scoped.stream()
                .filter(g -> inDateRange(g.getCreateTime(), from, to))
                .collect(Collectors.toList());
        List<String> classNames = base.stream()
                .map(g -> displayClass(g.getClassName()))
                .distinct().sorted().collect(Collectors.toList());

        List<GradeSheet> rows = (className == null || className.isBlank())
                ? base
                : base.stream()
                    .filter(g -> className.equals(displayClass(g.getClassName())))
                    .collect(Collectors.toList());

        BigDecimal full = resolveFullScore(paper, base);
        BigDecimal passLine = full.multiply(PASS_LINE_RATE);
        BigDecimal excellentLine = full.multiply(EXCELLENT_LINE_RATE);

        int count = rows.size();
        BigDecimal sum = ZERO;
        BigDecimal objSum = ZERO;
        BigDecimal subjSum = ZERO;
        BigDecimal max = ZERO;
        BigDecimal min = null;
        int passCount = 0;
        int excellentCount = 0;
        for (GradeSheet g : rows) {
            BigDecimal s = score(g);
            sum = sum.add(s);
            objSum = objSum.add(nz(g.getObjectiveScore()));
            subjSum = subjSum.add(nz(g.getSubjectiveScore()));
            if (s.compareTo(max) > 0) {
                max = s;
            }
            if (min == null || s.compareTo(min) < 0) {
                min = s;
            }
            if (s.compareTo(passLine) >= 0) {
                passCount++;
            }
            if (s.compareTo(excellentLine) >= 0) {
                excellentCount++;
            }
        }
        if (min == null) {
            min = ZERO;
        }
        BigDecimal avg = count == 0 ? ZERO
                : sum.divide(BigDecimal.valueOf(count), 1, RoundingMode.HALF_UP);
        BigDecimal avgObj = count == 0 ? ZERO
                : objSum.divide(BigDecimal.valueOf(count), 1, RoundingMode.HALF_UP);
        BigDecimal avgSubj = count == 0 ? ZERO
                : subjSum.divide(BigDecimal.valueOf(count), 1, RoundingMode.HALF_UP);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("paper", paperInfo(paper));
        out.put("classNames", classNames);
        out.put("count", count);
        out.put("fullScore", scale1(full));
        out.put("sumScore", scale1(sum));
        out.put("avgScore", avg);
        out.put("maxScore", scale1(max));
        out.put("minScore", scale1(min));
        out.put("medianScore", median(rows));
        out.put("avgObjective", avgObj);
        out.put("avgSubjective", avgSubj);
        out.put("passCount", passCount);
        out.put("excellentCount", excellentCount);
        out.put("passRate", percent(passCount, count));
        out.put("excellentRate", percent(excellentCount, count));
        out.put("passLine", scale1(passLine));
        out.put("excellentLine", scale1(excellentLine));
        out.put("segmentWidth", scale1(full.divide(BigDecimal.valueOf(BUCKETS), 1, RoundingMode.HALF_UP)));
        out.put("distribution", distribution(rows, full));
        out.put("byClass", byClass(rows, full, passLine));
        out.put("ranking", ranking(rows, full));
        out.put("scoreLine", scoreLine(rows));
        return out;
    }

    /** 十个分数段的人数分布，最后一段包含满分 */
    private List<Map<String, Object>> distribution(List<GradeSheet> rows, BigDecimal full) {
        BigDecimal step = full.divide(BigDecimal.valueOf(BUCKETS), 4, RoundingMode.HALF_UP);
        int[] counts = new int[BUCKETS];
        if (step.signum() > 0) {
            for (GradeSheet g : rows) {
                int idx = score(g).divide(step, 0, RoundingMode.FLOOR).intValue();
                counts[Math.max(0, Math.min(BUCKETS - 1, idx))]++;
            }
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < BUCKETS; i++) {
            BigDecimal lo = step.multiply(BigDecimal.valueOf(i));
            BigDecimal hi = i == BUCKETS - 1 ? full : step.multiply(BigDecimal.valueOf(i + 1));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", plain(lo) + "-" + plain(hi));
            m.put("min", scale1(lo));
            m.put("max", scale1(hi));
            m.put("count", counts[i]);
            list.add(m);
        }
        return list;
    }

    /** 各班人数 / 平均分 / 最高 / 最低 / 及格率，按平均分降序 */
    private List<Map<String, Object>> byClass(List<GradeSheet> rows, BigDecimal full, BigDecimal passLine) {
        Map<String, List<GradeSheet>> groups = rows.stream()
                .collect(Collectors.groupingBy(g -> displayClass(g.getClassName()), LinkedHashMap::new, Collectors.toList()));
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, List<GradeSheet>> e : groups.entrySet()) {
            List<GradeSheet> gs = e.getValue();
            BigDecimal sum = ZERO, max = ZERO, min = null;
            int pass = 0;
            for (GradeSheet g : gs) {
                BigDecimal s = score(g);
                sum = sum.add(s);
                if (s.compareTo(max) > 0) max = s;
                if (min == null || s.compareTo(min) < 0) min = s;
                if (s.compareTo(passLine) >= 0) pass++;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("className", e.getKey());
            m.put("count", gs.size());
            m.put("avgScore", sum.divide(BigDecimal.valueOf(gs.size()), 1, RoundingMode.HALF_UP));
            m.put("maxScore", scale1(max));
            m.put("minScore", scale1(min == null ? ZERO : min));
            m.put("passRate", percent(pass, gs.size()));
            m.put("fullScore", full);
            list.add(m);
        }
        list.sort((a, b) -> ((BigDecimal) b.get("avgScore")).compareTo((BigDecimal) a.get("avgScore")));
        return list;
    }

    /**
     * 考生明细，按总分降序（同分并列名次）。
     * 同一考生（考号相同，其次姓名相同）的多条阅卷记录汇总为一条：客观/主观/总分求和，
     * 并保留记录份数——多页答题卡未合并或重复扫描时不再出现同一个人占多行。
     */
    private List<Map<String, Object>> ranking(List<GradeSheet> rows, BigDecimal full) {
        Map<String, List<GradeSheet>> groups = new LinkedHashMap<>();
        for (GradeSheet g : rows) {
            groups.computeIfAbsent(studentKey(g), k -> new ArrayList<>()).add(g);
        }
        List<List<GradeSheet>> sorted = new ArrayList<>(groups.values());
        Comparator<List<GradeSheet>> byScoreDesc = Comparator
                .comparing((List<GradeSheet> g) -> sumScore(g))
                .reversed()
                .thenComparing(g -> g.get(0).getId() == null ? 0L : g.get(0).getId());
        sorted.sort(byScoreDesc);
        List<Map<String, Object>> list = new ArrayList<>();
        BigDecimal prev = null;
        int rank = 0;
        for (int i = 0; i < sorted.size(); i++) {
            List<GradeSheet> group = sorted.get(i);
            GradeSheet first = group.get(0);
            BigDecimal obj = group.stream().map(g -> nz(g.getObjectiveScore()))
                    .reduce(ZERO, BigDecimal::add);
            BigDecimal subj = group.stream().map(g -> nz(g.getSubjectiveScore()))
                    .reduce(ZERO, BigDecimal::add);
            BigDecimal s = sumScore(group);
            if (prev == null || s.compareTo(prev) != 0) {
                rank = i + 1;
            }
            prev = s;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rank", rank);
            m.put("id", first.getId());
            m.put("className", displayClass(group.stream()
                    .map(GradeSheet::getClassName)
                    .filter(v -> v != null && !v.isBlank())
                    .findFirst().orElse(null)));
            m.put("studentName", group.stream()
                    .map(GradeSheet::getStudentName)
                    .filter(v -> v != null && !v.isBlank() && !"待确认".equals(v))
                    .findFirst().orElse(first.getStudentName()));
            m.put("studentNo", group.stream()
                    .map(GradeSheet::getStudentNo)
                    .filter(v -> v != null && !v.isBlank() && v.contains("_") == false)
                    .findFirst().orElse(first.getStudentNo()));
            m.put("recordCount", group.size());
            m.put("objectiveScore", scale1(obj));
            m.put("subjectiveScore", scale1(subj));
            m.put("totalScore", scale1(s));
            m.put("fullScore", full);
            m.put("scoreRate", full.signum() > 0
                    ? s.divide(full, 3, RoundingMode.HALF_UP).multiply(HUNDRED).setScale(1, RoundingMode.HALF_UP)
                    : ZERO);
            m.put("status", first.getStatus());
            m.put("createTime", group.stream()
                    .map(GradeSheet::getCreateTime)
                    .filter(Objects::nonNull)
                    .min(Comparator.naturalOrder())
                    .orElse(null));
            list.add(m);
        }
        return list;
    }

    /** 同一考生的分组键：考号优先，其次姓名+班级，都没有时按记录本身（不合并） */
    private static String studentKey(GradeSheet g) {
        String no = g.getStudentNo() == null ? "" : g.getStudentNo().trim();
        if (!no.isBlank() && !no.contains("_")) {
            return "N:" + no;
        }
        String name = g.getStudentName() == null ? "" : g.getStudentName().trim();
        if (!name.isBlank() && !"待确认".equals(name)) {
            String cls = g.getClassName() == null ? "" : g.getClassName().trim();
            return "M:" + name + "|" + cls;
        }
        return "R:" + g.getId();
    }

    private static BigDecimal sumScore(List<GradeSheet> group) {
        return group.stream().map(GradeStatsService::score).reduce(ZERO, BigDecimal::add);
    }

    /** 按阅卷先后顺序的得分序列，供折线图使用 */
    private List<Map<String, Object>> scoreLine(List<GradeSheet> rows) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            GradeSheet g = rows.get(i);
            String name = g.getStudentName();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", i + 1);
            m.put("label", (name == null || name.isBlank() || "待确认".equals(name))
                    ? "第" + (i + 1) + "份" : name);
            m.put("score", scale1(score(g)));
            list.add(m);
        }
        return list;
    }

    private BigDecimal median(List<GradeSheet> rows) {
        if (rows.isEmpty()) {
            return ZERO;
        }
        List<BigDecimal> ss = rows.stream().map(GradeStatsService::score).sorted().collect(Collectors.toList());
        int n = ss.size();
        BigDecimal mid = n % 2 == 1 ? ss.get(n / 2)
                : ss.get(n / 2 - 1).add(ss.get(n / 2)).divide(BigDecimal.valueOf(2), 1, RoundingMode.HALF_UP);
        return scale1(mid);
    }

    private BigDecimal resolveFullScore(Paper paper, List<GradeSheet> base) {
        if (paper != null && paper.getTotalScore() != null && paper.getTotalScore().signum() > 0) {
            return paper.getTotalScore();
        }
        BigDecimal fromRecords = base.stream()
                .map(g -> nz(g.getFullScore()))
                .filter(v -> v.signum() > 0)
                .max(BigDecimal::compareTo)
                .orElse(HUNDRED);
        return fromRecords.signum() > 0 ? fromRecords : HUNDRED;
    }

    private Map<String, Object> paperInfo(Paper paper) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", paper == null ? null : paper.getId());
        m.put("title", paper == null ? "全部阅卷记录" : paper.getTitle());
        m.put("subject", paper == null ? null : paper.getSubject());
        m.put("grade", paper == null ? null : paper.getGrade());
        m.put("questionCount", paper == null ? null : paper.getQuestionCount());
        return m;
    }

    private static BigDecimal score(GradeSheet g) {
        return nz(g.getTotalScore());
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? ZERO : v;
    }

    private static BigDecimal scale1(BigDecimal v) {
        return (v == null ? ZERO : v).setScale(1, RoundingMode.HALF_UP);
    }

    private static BigDecimal percent(int part, int total) {
        return total == 0 ? ZERO : BigDecimal.valueOf(part)
                .multiply(HUNDRED).divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP);
    }

    private static String displayClass(String name) {
        return name == null || name.isBlank() ? UNKNOWN_CLASS : name.trim();
    }

    private static String plain(BigDecimal v) {
        return v.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private LocalDate parseDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            String day = text.length() > 10 ? text.substring(0, 10) : text;
            return LocalDate.parse(day);
        } catch (Exception e) {
            throw new BizException("日期格式不正确，应为 yyyy-MM-dd");
        }
    }

    private boolean inDateRange(LocalDateTime time, LocalDate from, LocalDate to) {
        if (time == null || (from == null && to == null)) {
            return true;
        }
        LocalDate day = time.toLocalDate();
        return (from == null || !day.isBefore(from)) && (to == null || !day.isAfter(to));
    }
}
