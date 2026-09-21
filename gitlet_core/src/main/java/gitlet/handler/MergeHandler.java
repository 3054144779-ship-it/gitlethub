package gitlet.handler;

import gitlet.commit.Commit;
import gitlet.repository.ObjectStore;
import gitlet.repository.Repository;
import gitlet.repository.RepositoryPaths;
import gitlet.stag.Staging;
import gitlet.utils.Utils;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** 合并逻辑：三路合并、最近公共祖先（LCA）查找、冲突生成。 */
public class MergeHandler {

    /** 合并操作：
     * 情况 A：文件在给定分支中被修改，当前分支中未修改
     * （“被修改” 指：给定分支头部的文件内容与分割点的内容不同。）
     * 操作：将文件更新为给定分支的版本，并自动暂存。
     *
     * 情况 B：文件在当前分支中被修改，给定分支中未修改
     * 操作：保持现状（保留当前分支的修改）。
     *
     * 情况 C：文件在两个分支中以 “相同方式” 修改
     * 例如：两者内容完全一致，或两者都删除了该文件。
     * 操作：保持不变。
     * （若两个分支都删除了文件，但工作区中仍有同名文件，忽略它，不追踪也不暂存。）
     *
     * 情况 D：分割点不存在该文件，仅当前分支有
     * 操作：保持现状。
     *
     * 情况 E：分割点不存在该文件，仅给定分支有
     * 操作：检出给定分支的版本，并暂存。
     *
     * 情况 F：分割点有该文件，当前分支未修改，但给定分支删除了它
     * 操作：删除该文件（并取消追踪）。
     *
     * 情况 G：分割点有该文件，给定分支未修改，但当前分支删除了它
     * 操作：保持删除状态。
     *
     * 情况 H：文件在两个分支中 “以不同方式修改”（冲突）
     * “不同方式修改” 包括：
     * 两者内容都变了，且互不相同；
     * 一个修改了内容，另一个删除了文件；
     * 分割点没有该文件，但两个分支都新增了同名文件且内容不同。
     * */
    public static void merge(String branchName) {
        // 如果不存在当前分支
        if (!BranchManager.branchCheck(branchName)) {
            Utils.message("A branch with that name does not exist.");
            return;
        }

        // 尝试合并自身分支
        if (branchName.equals(BranchManager.getHead())) {
            Utils.message("Cannot merge a branch with itself.");
            return;
        }

        // 最近公共祖先的commit的SHA-1值
        String lcaSha1 = getLCA(branchName);
        // 给定分支的SHA—1值
        String givenBranchSha1 = BranchManager.readBranchSha1(branchName);
        // 当前所在的提交
        String nowHeadBranchSha1 = BranchManager.getHeadSha1();

        // 工作区中有未追踪文件
        if (!trackingCheck(ObjectStore.getCommit(givenBranchSha1).getTrackedFiles(),
                ObjectStore.getCommit(lcaSha1).getTrackedFiles())) {
            Utils.message("There is an untracked file in the way"
                                  + "delete it, or add and commit it first.");
            return;
        }

        Staging stage = Repository.getStaging();

        // 暂存区有未提交的添加或删除
        if (!stage.isEmpty()) {
            Utils.message("You have uncommitted changes.");
            return;
        }

        // 分割点就是给定分支：什么都不做，合并完成
        if (lcaSha1.equals(givenBranchSha1)) {
            Utils.message("Given branch is an ancestor of the current branch.");
            return;
        }

        // 分割点就是当前分支效果等同于 “检出”（checkout）给定分支
        if (lcaSha1.equals(BranchManager.getHeadSha1())) {
            CommandHandler.checkoutBranch(branchName);
            Utils.message("Current branch fast-forwarded.");
            return;
        }

        // 文件处理，返回结果查看是否存在合并冲突
        boolean hasConflict = fileProcessing(givenBranchSha1, nowHeadBranchSha1, lcaSha1);

        // 如果存在合并冲突
        if (hasConflict) {
            Utils.message("Encountered a merge conflict.");
        }

        // 无论冲突是否解决，创建新的提交对象
        // Merged [给定分支名] into [当前分支名]。
        String mes = "Merged " + branchName + " into " + BranchManager.getHead() + ".";
        List<String> mergeFiles = new ArrayList<>();
        mergeFiles.add(nowHeadBranchSha1.substring(0, 7)); // 第一父提交
        mergeFiles.add(givenBranchSha1.substring(0, 7)); // 第二父提交

        Map<String, String> newTrackedFiles =
                new TreeMap<>(BranchManager.getHeadCommitTrackedFilesMap());
        newTrackedFiles.putAll(stage.getStagingMap());
        for (String removeFile : stage.getRemoveSet()) {
            newTrackedFiles.remove(removeFile);
        }

        // 自动提交
        Commit newCommit = new Commit(mes, mergeFiles, nowHeadBranchSha1,
                                        givenBranchSha1, newTrackedFiles);

        /** 更新当前分支的进度至最新提交 */
        Utils.writeContents(BranchManager.getHeadBranch(), newCommit.saveCommit());
        // 清空暂存区文件
        stage.clear();
        // 持久化暂存区
        Repository.saveStaging();
    }

    /** 生成冲突文件内容 */
    public static StringBuilder getStringBuilder(String nowContent, String givenContent) {
        StringBuilder conflictFileContent = new StringBuilder();
        conflictFileContent.append("<<<<<<< HEAD\n");
        conflictFileContent.append(nowContent);
        // 如果原内容末尾没换行，手动补一个，防止 ======= 贴在内容屁股后面
        if (!nowContent.isEmpty() && !nowContent.endsWith("\n")) {
            conflictFileContent.append("\n");
        }

        conflictFileContent.append("=======\n");

        conflictFileContent.append(givenContent);
        if (!givenContent.isEmpty() && !givenContent.endsWith("\n")) {
            conflictFileContent.append("\n");
        }

        conflictFileContent.append(">>>>>>>\n");

        return conflictFileContent;
    }

    /** 生成冲突文件，并返回文件路径 */
    public static File generateConflict(String fileName,
                                         String givenBranchFileSha1, String nowBranchFileSha1) {
        File conflictFilePath = Utils.join(RepositoryPaths.CWD, fileName);

        StringBuilder conflictFileContent = getStringBuilder(
                ObjectStore.getFileContentInObject(nowBranchFileSha1),
                ObjectStore.getFileContentInObject(givenBranchFileSha1));

        Utils.writeContents(conflictFilePath, conflictFileContent.toString());

        return conflictFilePath;
    }

    public static boolean fileProcessing(String givenBranchSha1,
                                          String nowHeadBranchSha1, String lcaSha1) {

        // 获取三个提交的全部不重复文件
        Set<String> allFiles = new TreeSet<>();
        allFiles.addAll(ObjectStore.getCommit(givenBranchSha1).getTrackedFiles().keySet());
        allFiles.addAll(ObjectStore.getCommit(nowHeadBranchSha1).getTrackedFiles().keySet());
        allFiles.addAll(ObjectStore.getCommit(lcaSha1).getTrackedFiles().keySet());

        Staging stage = Repository.getStaging();
        boolean hasConflict = false;
        for (String fileName : allFiles) {
            String lcaSha = ObjectStore.getCommit(lcaSha1).getTrackedFiles().get(fileName);
            String givenSha = ObjectStore.getCommit(givenBranchSha1).getTrackedFiles().get(fileName);
            String nowSha = ObjectStore.getCommit(nowHeadBranchSha1).getTrackedFiles().get(fileName);

            // 情况 1: Given 和 Now 内容一致 (无论 LCA 是什么，或者是否存在)
            // 或者是 Given 的内容相对于 LCA 没变
            if (Objects.equals(givenSha, nowSha) || Objects.equals(givenSha, lcaSha)) {
                continue; // 保持现状，不做任何处理
            }

            // 情况 2: Now 的内容相对于 LCA 没变，但 Given 变了
            if (Objects.equals(nowSha, lcaSha)) {
                if (givenSha != null) {
                    // Given 修改了文件或新增了文件
                    ObjectStore.overwriteFile(fileName, givenSha);
                    stage.getStagingMap().put(fileName, givenSha);
                } else {
                    // Given 删除了文件
                    ObjectStore.deleteCwdFile(fileName);
                    stage.getRemoveSet().add(fileName);
                }
                continue;
            }

            // 情况 3: 冲突（LCA, Given, Now 三者各不相同）
            // 这里已经隐含了 givenSha != nowSha 且两者都与 lcaSha 不同
            File conflictFile = generateConflict(fileName, givenSha, nowSha);
            stage.getStagingMap().put(fileName, ObjectStore.getFileSha1(conflictFile));
            hasConflict = true;
        }

        return hasConflict;
    }

    /** 获取两个分支的最近公共祖先 (LCA) */
    public static String getLCA(String branchName) {

        // 找出当前分支的所有祖先（包含自己）
        Set<String> ancestorsOfCurrent = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        queue.add(BranchManager.getHeadSha1());

        while (!queue.isEmpty()) {
            String p = queue.poll();
            if (p == null || ancestorsOfCurrent.contains(p)) {
                continue;
            }
            ancestorsOfCurrent.add(p);

            if (ObjectStore.getCommit(p).getParent() != null) {
                queue.add(ObjectStore.getCommit(p).getParent());
            }
            // 如果是 Merge Commit，也要把第二个父节点加进去
            if (ObjectStore.getCommit(p).getSecondParent() != null) {
                // parentBranch 存了第二个父节点的 SHA1
                queue.add(ObjectStore.getCommit(p).getSecondParent());
            }
        }

        // 从 Given 分支开始 BFS，找到第一个在 ancestorsOfCurrent 中的节点
        queue.clear();
        queue.add(BranchManager.readBranchSha1(branchName));
        // 记录已访问，防止在图里绕圈
        Set<String> visited = new HashSet<>();

        while (!queue.isEmpty()) {
            String p = queue.poll();
            if (p == null || visited.contains(p)) {
                continue;
            }
            visited.add(p);

            if (ancestorsOfCurrent.contains(p)) {
                return p; // 找到了最近的
            }

            if (ObjectStore.getCommit(p).getParent() != null) {
                queue.add(ObjectStore.getCommit(p).getParent());
            }
            if (ObjectStore.getCommit(p).getSecondParent() != null) {
                queue.add(ObjectStore.getCommit(p).getSecondParent());
            }
        }

        return null; // 理论上不可能，除非两个分支没交集
    }

    /** 检查分支目录下是否所有文件都被追踪，都被追踪返回 true，有未被追踪的返回 false */
    public static boolean trackingCheck(Map<String, String> givenBranchFiles,
                                         Map<String, String> lcaFiles) {
        for (String fileName : StatusHandler.getNotTrackingFiles()) {
            // 只有当 "未追踪文件" 在 "给定分支" 中存在，且内容不同时，才算“没被追踪”
            // 情况 A：给定分支有这个文件，且内容和工作区不一样 (会被覆盖)
            if (givenBranchFiles.containsKey(fileName)) {
                String currentSha1 =
                        ObjectStore.getFileSha1(Utils.join(RepositoryPaths.CWD, fileName));
                if (!currentSha1.equals(givenBranchFiles.get(fileName))) {
                    return false;
                }
            }

            // 情况 B：给定分支没有，但 LCA 有 (会被删除)
            // 只有当该文件在当前 commit 也不存在时，它才是“未追踪”的
            if (lcaFiles.containsKey(fileName) && !givenBranchFiles.containsKey(fileName)) {
                return false;
            }
        }
        return true;
    }
}
