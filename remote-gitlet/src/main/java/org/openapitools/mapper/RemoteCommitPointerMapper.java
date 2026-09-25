package org.openapitools.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import po.CommitParent;

@Mapper
public interface RemoteCommitPointerMapper extends BaseMapper<CommitParent> {
}
