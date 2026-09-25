package org.openapitools.verification;

import org.openapitools.model.RepositoryPaths;
import org.openapitools.utils.Utils;

import java.io.File;
import java.util.ArrayList;
import java.util.TreeMap;

/**
 * 仓库校验器
 * 1.当前仓库是否存在，如果存在则不允许被创建
 *
 */
public class RepositoryVerification {

    /**
     * 仓库是否存在
     * @param repositoryPaths 仓库路径
     * @return 仓库是否存在
     */
    public static boolean repositoryExists(RepositoryPaths repositoryPaths) {
        return repositoryPaths.getRepositoryId().exists();
    }

    /**
     * 创建仓库，创建仓库时，HEAD 会指向 master 分支，此时 master 分支还不存在
     * @param repositoryPaths 仓库路径
     */
    public static void createRepository(RepositoryPaths repositoryPaths) {
        repositoryPaths.getRepositoryId().mkdirs();
        repositoryPaths.getObject().mkdirs();
        repositoryPaths.getCommits().mkdirs();
        repositoryPaths.getBranchDir().mkdirs();
//        Utils.writeContents(repositoryPaths.getHeadFile(), "master");
    }
}
