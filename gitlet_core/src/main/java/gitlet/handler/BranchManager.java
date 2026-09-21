package gitlet.handler;

import gitlet.repository.ObjectStore;
import gitlet.repository.Repository;
import gitlet.repository.RepositoryPaths;
import gitlet.utils.Utils;

import java.io.File;
import java.util.List;
import java.util.Map;

/** 分支管理：分支的创建、删除、查询，以及 HEAD 指针相关操作。 */
public class BranchManager {
    /** 本次操作针对的仓库 */
    private final Repository repo;
    /** 该仓库的路径集合 */
    private final RepositoryPaths paths;
    /** 该仓库的对象存储 */
    private final ObjectStore objects;

    public BranchManager(Repository repo) {
        this.repo = repo;
        this.paths = repo.getPaths();
        this.objects = repo.getObjects();
    }

    /** 创建分支 */
    public void branch(String branchName) {
        // 如果已经存在一个名字一样的分支了，就返回“A branch with that name already exists.”，不创建新的分支
        if (objects.getBranches().contains(branchName)) {
            Utils.message("A branch with that name already exists.");
            return;
        }
        // 创建分支辅助函数，将分支名和当前所在分支的SHA-1值传入
        createBranch(branchName, getHeadSha1());
    }

    /** 删除分支 */
    public void rmBranch(String branchName) {
        // 如果不存在当前分支就返回 "A branch with that name does not exist"
        if (!branchCheck(branchName)) {
            Utils.message("A branch with that name does not exist");
            return;
        }

        // 如果删除的是当前的分支就返回 "Cannot remove the current branch"
        if (branchName.equals(getHead())) {
            Utils.message("Cannot remove the current branch");
            return;
        }

        File branchPath = Utils.join(paths.getBranchDir(), branchName);
        Repository.notCwdFileDelete(branchPath);
    }

    /**
     * 创建一个新的分支，并且存入最新提交的SHA-1值代表这个分支是在这个提交创建的
     * branchName 创建的分支名称 nowSha1 当前所在分支的最新提交的SHA-1值。
     * @param branchName
     * @param nowSha1
     */
    public void createBranch(String branchName, String nowSha1) {
        File branchPath = Utils.join(paths.getBranchDir(), branchName);
        // 将当前分支的SHA-1值写入文件。
        Utils.writeContents(branchPath, nowSha1);
    }

    /** 检查是否存在这个分支，存在返回 true，不存在返回 false */
    public boolean branchCheck(String branch) {
        return objects.getBranches().contains(branch);
    }

    /** 获取分支中存储提交的SHA-1值 */
    public String readBranchSha1(String branchName) {
        return Utils.readContentsAsString(Utils.join(paths.getBranchDir(), branchName));
    }

    /** 获取头指针指向的分支名称 */
    public String getHead() {
        return Utils.readContentsAsString(paths.getHeadFile());
    }

    /** 获取头指针指向的分支路径 */
    public File getHeadBranch() {
        return Utils.join(paths.getBranchDir(), getHead());
    }

    /** 获取头指针指向的分支的SHA-1值 */
    public String getHeadSha1() {
        return Utils.readContentsAsString(getHeadBranch());
    }

    /** 获取 head-commit 中的 <\name:SHA-1> 映射表*/
    public Map<String, String> getHeadCommitTrackedFilesMap() {
        // 获取头指针指向的分支
        String branch = Utils.readContentsAsString(paths.getHeadFile());
        // 获取头指针指向的分支中存储的 commit 的SHA-1值
        File file = Utils.join(paths.getBranchDir(), branch);
        String sha1 = Utils.readContentsAsString(file);

        return objects.getCommit(sha1).getTrackedFiles();
    }

    /** 获取 head-commit 中的 merge 链表*/
    public List<String> getHeadCommitMargeFilesList() {
        // 获取头指针指向的分支
        String branch = Utils.readContentsAsString(paths.getHeadFile());
        // 获取头指针指向的分支中存储的 commit 的SHA-1值
        File file = Utils.join(paths.getBranchDir(), branch);
        String sha1 = Utils.readContentsAsString(file);

        return objects.getCommit(sha1).getMergeFiles();
    }
}
