package com.examscan.modules.question.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.examscan.modules.question.entity.Question;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

@Mapper
public interface QuestionMapper extends BaseMapper<Question> {

    /** 物理删除某文档已入库的题目（重新切分时避免题目重复累加） */
    @Delete("DELETE FROM question WHERE doc_id = #{docId}")
    int deleteByDocId(@Param("docId") Long docId);

    /** 清空题库：物理删除全部题目 */
    @Delete("DELETE FROM question")
    int deleteAll();

    /** 清空题库：仅删除指定来源（如 SCAN）的题目 */
    @Delete("DELETE FROM question WHERE source = #{source}")
    int deleteBySource(@Param("source") String source);

    /** 清理试卷中已无对应题目的条目（题目被清空后残留的引用） */
    @Delete("DELETE FROM paper_item WHERE question_id NOT IN (SELECT id FROM question)")
    int deleteOrphanPaperItems();

    /** 统计不同来源的题目数量（清空确认页用） */
    @Select("SELECT COALESCE(SUM(CASE WHEN source = 'SCAN' THEN 1 ELSE 0 END), 0) AS scanCount," +
            " COALESCE(SUM(CASE WHEN source <> 'SCAN' THEN 1 ELSE 0 END), 0) AS otherCount," +
            " COUNT(*) AS total FROM question")
    Map<String, Object> countBySource();

    /** 试卷中引用题目的条目总数 */
    @Select("SELECT COUNT(*) FROM paper_item")
    long countPaperItems();
}
