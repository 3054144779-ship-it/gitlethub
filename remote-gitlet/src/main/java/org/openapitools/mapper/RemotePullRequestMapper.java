package org.openapitools.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import po.PullRequest;

@Mapper
public interface RemotePullRequestMapper extends BaseMapper<PullRequest> {
}
