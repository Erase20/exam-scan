package com.examscan.common;

/**
 * 题型枚举 —— 全系统统一的题型定义与中文名称。
 *
 * displayOrder 是本系统「标准大题顺序」的唯一依据：
 *   一、填空  二、判断  三、选择  四、解答  五、实验探究  六、作图  七、计算  八、其他
 * 试卷、答题卡、Word 导出均按此顺序排布大题（不再跟随组卷配置书写顺序）。
 */
public enum QuestionType {
    FILL("填空题", 1),
    JUDGE("判断题", 2),
    CHOICE("选择题", 3),
    ANSWER("解答题", 4),
    EXPERIMENT("实验探究题", 5),
    DRAW("作图题", 6),
    CALC("计算题", 7),
    OTHER("其他", 8);

    private final String label;
    private final int displayOrder;

    QuestionType(String label, int displayOrder) {
        this.label = label;
        this.displayOrder = displayOrder;
    }

    public String getLabel() {
        return label;
    }

    /** 标准大题顺序序号（1 起）。越小越靠前 */
    public int getDisplayOrder() {
        return displayOrder;
    }

    public static String labelOf(String code) {
        for (QuestionType t : values()) {
            if (t.name().equals(code)) {
                return t.label;
            }
        }
        return OTHER.label;
    }

    public static QuestionType of(String code) {
        for (QuestionType t : values()) {
            if (t.name().equals(code)) {
                return t;
            }
        }
        return OTHER;
    }
}
