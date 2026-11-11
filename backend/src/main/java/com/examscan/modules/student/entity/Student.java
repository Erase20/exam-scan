package com.examscan.modules.student.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 考生信息表：与阅卷记录（grade_sheet）通过考号关联。
 * 来源：Excel 名单导入 + 阅卷识别后自动补录。
 */
@Data
@TableName("student")
public class Student {
    @TableId(type = IdType.AUTO)
    private Long id;
    @JsonProperty("name")
    private String name;
    @JsonProperty("studentNo")
    private String studentNo;
    @JsonProperty("className")
    private String className;
    @JsonProperty("grade")
    private String grade;
    private LocalDateTime createTime;
}
