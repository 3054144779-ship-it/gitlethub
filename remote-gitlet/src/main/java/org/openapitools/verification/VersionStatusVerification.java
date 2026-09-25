package org.openapitools.verification;

import common.Result;
import common.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.openapitools.jackson.nullable.JsonNullable;
import org.openapitools.message.MsgConstant;
import org.openapitools.model.RefUpdateRequest;
import po.BranchPointer;

import java.util.Objects;

/**
 * 版本与状态校验器
 * 1.当前版本与远程版本是否一致
 * 2.当前仓库状态是否允许进行操作
 *
 */
@Slf4j
public class VersionStatusVerification {

    /**
     * 非快进校验
      * @param refUpdateRequest 推送请求
      * @param branchPointer 分支指针
      * @return 是否通过校验
     */
    public static Boolean nonFastForwardCheck(RefUpdateRequest refUpdateRequest, BranchPointer branchPointer) {
        JsonNullable<String> expectedOldSha = refUpdateRequest.getExpectedOldSha();
        if (expectedOldSha != null && expectedOldSha.isPresent()
                && !Objects.equals(expectedOldSha.get(), branchPointer.getCommitSha())) {
            log.warn("非快进推送被拒绝：期望旧指向 {}，服务端当前 {}",
                    expectedOldSha.get(), branchPointer.getCommitSha());
            return false;
        }
        return true;
    }
}
