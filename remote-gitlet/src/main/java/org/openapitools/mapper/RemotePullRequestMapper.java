package org.openapitools.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import po.PrReview;
import po.PullRequest;

import java.util.List;

@Mapper
public interface RemotePullRequestMapper extends BaseMapper<PullRequest> {
    List<PrReview> getAllPrs(String owner, List<String> repos);
}
