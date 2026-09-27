package gitlet.repository;

import gitlet.utils.Utils;
import lombok.Getter;

import java.io.File;
import java.util.Map;

import static gitlet.utils.Utils.join;

/** 一个仓库涉及的所有磁盘路径。
 *  路径只在构造时算出，之后不再变化；构造函数不碰磁盘，
 *  目录的创建交给 CommandHandler.init()。
 */
@Getter
public class RepositoryPaths {
    /** 仓库的标识，只用于日志 / 诊断，不参与路径计算 */
    private final String repoId;
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
    /** 储存 commit 的文件  */
    private final File commits;
    /** 存储暂存区文件信息。 */
    private final File stagingFile;
    /** 存储远程仓库路径信息。 */
    private final File remoteFile;
    /** 存储远程仓库推送分支信息。(上一次提交时候的 commit SHA) */
    private final File remotePushBranchDir;
    /** 合并进行中时记录「第二父提交」(被合并进来的 SHA)；文件存在即代表有一次未完成的合并。 */
    private final File mergeHead;

    public RepositoryPaths(String repoId, File cwd) {
        this.repoId = repoId;
        this.cwd = cwd;
        this.gitletDir = join(cwd, ".gitlet");
        this.object = join(gitletDir, "Objects");
        this.branchDir = join(gitletDir, "refs", "heads");
        this.headFile = join(gitletDir, "HEAD");
        this.commits = join(object, "commits");
        this.stagingFile = join(gitletDir, "staging");
        this.remoteFile = join(gitletDir, "remotes");
        this.remotePushBranchDir = join(gitletDir, "refs", "remotes");
        this.mergeHead = join(gitletDir, "MERGE_HEAD");
    }

    /**
     * 解析远程仓库的配置
     * @param remoteFile 远程仓库文件
     * @return 仓库路径信息
     */
    public static Map<String, String> parseRemoteFile(File remoteFile) {
        /*
         * 构建内容字符串 "url=%s\nowner=%s\nrepo=%s\ndefaultBranch=master\n"
         * url = {baseUrl}
         * owner = {ownerName}
         * repo = {repoName}
         * defaultBranch = master
         * */
        String content = Utils.readContentsAsString(remoteFile);
        String[] lines = content.split("\n");
        String baseUrl = lines[0].split("=")[1];
        String ownerName = lines[1].split("=")[1];
        String repoName = lines[2].split("=")[1];
        String defaultBranch = lines[3].split("=")[1];
        return Map.of(
                "baseUrl", baseUrl,
                "owner", ownerName,
                "repo", repoName,
                "defaultBranch", defaultBranch
        );
    }
}
