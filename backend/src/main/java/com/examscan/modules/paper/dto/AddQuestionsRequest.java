package com.examscan.modules.paper.dto;

import lombok.Data;

import java.util.List;

/** 题库批量加入试卷请求：paperId 与 newTitle 二选一（newTitle 优先，直接新建试卷） */
@Data
public class AddQuestionsRequest {
    /** 目标试卷 id（加入已有试卷） */
    private Long paperId;
    /** 新建试卷标题（填写则新建试卷） */
    private String newTitle;
    /** 新建试卷学科（可选） */
    private String newSubject;
    /** 新建试卷年级（可选） */
    private String newGrade;
    /** 新建试卷考试时长（分钟，默认 90） */
    private Integer newDuration;
    /** 待加入的题目 id 列表 */
    private List<Long> questionIds;
}
