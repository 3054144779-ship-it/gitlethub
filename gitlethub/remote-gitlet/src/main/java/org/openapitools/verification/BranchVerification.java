package org.openapitools.verification;

import lombok.extern.slf4j.Slf4j;
import org.openapitools.model.*;
import po.BranchPointer;
import po.Collaborator;

/**
 * 分支规则校验器：
 * 分支规则：
 * 1. 保护分支：不允许删除和修改。比如main分支被保护，设置 禁止推送。即使是 Owner 也禁止推送。必须走 pull Request
 * 2. 强制推送检验：当前代码是否允许强制推送
 */

@Slf4j
public class BranchVerification {
    /**
     * 是否是保护分支（如果说当前本地分支不是最新则不能推送）
     * @param branchPointer 分支指针
     * @return 是否是保护分支
     */
    public static boolean isProtectedBranch(BranchPointer branchPointer) {
        // 等于1代表是被保护的指针
        return branchPointer.getIsProtected() == 1;
    }

    /**
     * 是否允许强制推送
      * @param branchPointer 分支指针
     * @return 是否允许强制推送
     */
    public static boolean isForcePushAllowed(BranchPointer branchPointer) {
        // 等于1代表允许强制推送
        return branchPointer.getIsForcePush() == 1;
    }
}
