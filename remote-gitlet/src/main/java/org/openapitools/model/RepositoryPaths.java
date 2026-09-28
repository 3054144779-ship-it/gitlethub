package org.openapitools.model;

import lombok.Getter;

import java.io.File;

import static org.openapitools.utils.Utils.join;

/** 一个仓库涉及的所有磁盘路径。
 *  路径只在构造时算出，之后不再变化；构造函数不碰磁盘，
 *  目录的创建交给 CommandHandler.init()。
 */
@Getter
public class RepositoryPaths {
    /** 仓库所在的工作目录，由调用方传入 */
    private final File cwd;

    /** gilet 目录*/
    private final File gitletDir;
    /** 储存提交的文件备份*/
    private final File object;
    /** refs/heads 目录储存分支  */
    private final File branchDir;
    /** 储存 HEAD 指针  */
    private final File headFile;
    /** 储存 commit 的文件夹  */
    private final File commits;
    /** 储存仓库的标识。 */
    private final File repositoryId;
    /** 储存暂存区的文件。 */
    private final File PRStaging;

    public RepositoryPaths(String repoId, File cwd) {
        this.cwd = cwd;
        this.gitletDir = join(cwd, ".gitlet");
        this.repositoryId = join(gitletDir, repoId);
        this.object = join(repositoryId, "Objects");
        this.branchDir = join(repositoryId, "refs", "heads");
        this.headFile = join(repositoryId, "HEAD");
        this.commits = join(object, "commits");
        this.PRStaging = join(repositoryId, "storage");
    }
}
