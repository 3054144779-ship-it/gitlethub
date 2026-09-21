package gitlet.repository;

import gitlet.utils.Utils;

import java.io.File;

import static gitlet.utils.Utils.join;

public class RepositoryPaths {
    /** 当前工作目录 */
    public static final File CWD = new File(System.getProperty("user.dir"));

    /** gilet 目录*/
    public static final File GITLETDIR = join(CWD, ".gitlet");
    /** 储存提交的文件备份*/
    public static final File OBJECT = join(GITLETDIR, "Objects");
    /** refs/heads 目录储存分支  */
    public static final File BRANCHDIR = join(GITLETDIR, "refs", "heads");
    /** 储存 HEAD 指针  */
    public static final File HEADFILE = join(GITLETDIR, "HEAD");
    /** 储存 commit 的文件  */
    public static final File COMMITS = join(OBJECT, "commits");
    /** 存储暂存区文件信息。 */
    public static final File STAGINGFILE = join(GITLETDIR, "staging");
}
