package org.openapitools.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import po.SubmissionRecord;

@Mapper
public interface RemoteSubmissionRecordMapper extends BaseMapper<SubmissionRecord> {
    /**
     * 根据 commitSha 获取提交记录
      * @param sha1 commitSha
     * @return 提交记录
     */
    boolean getSubmissionRecordByCommitSha(String sha1);
}
