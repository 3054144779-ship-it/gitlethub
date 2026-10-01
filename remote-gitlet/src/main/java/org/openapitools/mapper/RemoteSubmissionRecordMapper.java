package org.openapitools.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import po.SubmissionRecord;

import java.util.List;

@Mapper
public interface RemoteSubmissionRecordMapper extends BaseMapper<SubmissionRecord> {
    /**
     * 根据 commitSha 获取提交记录
      * @param sha1 commitSha
     * @return 提交记录
     */
    boolean getSubmissionRecordByCommitSha(String sha1);

    /**
     * 批量插入提交记录：一次推送可能带进多个提交，逐条 insert 会产生 N 次往返
     * @param records 提交记录（调用方负责分块，别超过 max_allowed_packet）
     * @return 插入行数
     */
    int insertBatch(@Param("records") List<SubmissionRecord> records);
}
