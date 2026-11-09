package com.examscan.modules.paper.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.examscan.modules.paper.entity.Paper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PaperMapper extends BaseMapper<Paper> {
}
