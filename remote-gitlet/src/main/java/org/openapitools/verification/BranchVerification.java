package org.openapitools.verification;

import lombok.extern.slf4j.Slf4j;
import org.openapitools.model.*;
import org.openapitools.utils.Utils;

import java.io.File;

/**
 * 分支规则校验器：
 * 分支规则：
 * 1. 远程仓库是否存在当前分支，不存在则创建，存在则更新
 * 2. 保护分支：不允许删除和修改。比如main分支被保护，设置 禁止推送。即使是 Owner 也禁止推送。必须走 pull Request
 * 3. 强制推送检验：当前代码是否允许强制推送
 */

@Slf4j
public class BranchVerification {
    /**
     * 远程仓库是否存在分支，如果不存在则直接创建
     * @param repositoryPaths 远程仓库路径
     * @return 分支文件
     */
    public static File branchExists(RepositoryPaths repositoryPaths, String branch) {
        // 分支是否存在
        File currentBranch = Utils.join(repositoryPaths.getBranchDir(), branch);

        if (!currentBranch.exists()) {
            log.info("分支 {} 不存在，创建新分支", branch);
            currentBranch.getParentFile().mkdirs();
        }

        return currentBranch;
    }

    /**
     * TODO：是否是保护分支（如果说当前本地分支不是最新则不能推送）
     * @param repositoryPaths 远程仓库路径
     * @return 是否是保护分支
     */
    public static boolean isProtectedBranch(RepositoryPaths repositoryPaths) {
        return true;
    }

    /**
     * TODO：是否允许强制推送
     * @param repositoryPaths 远程仓库路径
     * @return 是否允许强制推送
     */
    public static boolean isForcePushAllowed(RepositoryPaths repositoryPaths) {
        return true;
    }
}
