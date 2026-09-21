package gitlet.handler;

import gitlet.repository.ObjectStore;
import gitlet.repository.Repository;
import gitlet.repository.RepositoryPaths;
import gitlet.stag.Staging;
import gitlet.utils.Utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** 状态查询：工作目录 / 暂存区 / 分支状态的各种派生查询与打印。 */
public class StatusHandler {
    /** 本次操作针对的仓库 */
    private final Repository repo;
    /** 该仓库的路径集合 */
    private final RepositoryPaths paths;
    /** 该仓库的对象存储 */
    private final ObjectStore objects;

    public StatusHandler(Repository repo) {
        this.repo = repo;
        this.paths = repo.getPaths();
        this.objects = repo.getObjects();
    }

    public void status() {
        /* 打印状态 */
        Staging stage = repo.getStaging();
        // 分支状态
        printSection("Branches", getMarkingsBranches());
        // 暂存区的文件
        printSection("Staged Files", getStagingFiles());
        // 已经被删除的文件
        printSection("Removed Files", stage.getRemoveSet());
        // 未提交的修改
        printSection("Modifications Not Staged For Commit", getModifyNotStagingFiles(), getDeleteFiles());
        // 未被追踪的文件
        printSection("Untracked Files", getNotTrackingFiles());
    }

    /** 检查工作目录下是否所有文件都被追踪，都被追踪返回 true，有未被追踪的返回 false */
    public boolean trackingCheck() {
        return getNotTrackingFiles().isEmpty();
    }

    /** 打印已修改未暂存的文件列表(信息特殊) */
    public void printSection(String title, Collection<String> items1,
                                     Collection<String> items2) {

        System.out.println("=== " + title + " ===");
        Set<String> mergeItems = arrayMerge(items1, items2);
        for (final String fileName : mergeItems) {
            if (items2.contains(fileName)) {
                System.out.println(fileName + " (deleted)");
            } else {
                System.out.println(fileName + " (modified)");
            }
        }
        System.out.println();
    }

    /** 打印状态信息 */
    public void printSection(String title, Collection<String> items) {
        System.out.println("=== " + title + " ===");
        for (final String fileName : items) {
            System.out.println(fileName);
        }
        System.out.println();
    }

    /**  获取已修改未暂存的文件列表（排序后） */
    public List<String> getNotTrackingFiles() {
        Set<String> trackingFiles = getTrackingFiles();
        List<String> unTrackingFiles = new ArrayList<>();
        List<String> cwdFiles = objects.getCwdFiles();
        if (cwdFiles != null) {
            for (final String file : cwdFiles) {
                if (!trackingFiles.contains(file)) {
                    unTrackingFiles.add(file);
                }
            }
            unTrackingFiles.sort(String::compareTo);
        }

        return unTrackingFiles;
    }

    /** 获取已修改未暂存的文件列表（排序后） */
    public List<String> getModifyNotStagingFiles() {
        // 获取最新提交时所有的文件映射。
        Map<String, String> commitFilesMap = new BranchManager(repo).getHeadCommitTrackedFilesMap();
        Map<String, String> stagingMap = repo.getStaging().getStagingMap();

        List<String> modifiedFiles = new ArrayList<>();
        // 获取工作目录所有的文件名
        List<String> cwdFiles = objects.getCwdFiles();

        for (String fileName : cwdFiles) {
            // 如果在暂存区的所有提交中存在这个文件的话。
            if (stagingMap.containsKey(fileName)) {
                String serCwd = objects.getFileSha1(Utils.join(paths.getCwd(), fileName));
                String serStaging = stagingMap.get(fileName);
                // SHA-1值不同加入未加入暂存区
                if (!serStaging.equals(serCwd)) {
                    modifiedFiles.add(fileName);
                }
            } else if (commitFilesMap.containsKey(fileName)) {
                // 如果这个文件之前提交过的话(最新的一次为准)，SHA-1值不同说明修改过。
                String serCwd = objects.getFileSha1(Utils.join(paths.getCwd(), fileName));
                String serCommit = commitFilesMap.get(fileName);
                // SHA-1值不同加入未加入暂存区
                if (!serCommit.equals(serCwd)) {
                    modifiedFiles.add(fileName);
                }
            }

        }

        modifiedFiles.sort(String::compareTo);

        return modifiedFiles;
    }

    /** 获取暂存区的文件名（排序后） */
    public List<String> getStagingFiles() {
        Map<String, String> stagedFileMap = repo.getStaging().getStagingMap();
        List<String> stagedFiles = new ArrayList<>();
        stagedFileMap.forEach((fileName, sha1) -> {
            stagedFiles.add(fileName);
        });
        stagedFiles.sort(String::compareTo);

        return stagedFiles;
    }

    /** 获取暂存区的删除标记文件集合 */
    public Set<String> getDeleteFiles() {
        // 获取当前目录下的所有文件，如果是手动删除不是 rm 删除的话，那么不会在 removeSet 记录，
        // 同时应该在头指针提交下，因为被追踪过
        List<String> cwdFiles = objects.getCwdFiles();
        // 最新提交下被追踪的所有文件。
        Set<String> headCommitRepository = new BranchManager(repo).getHeadCommitTrackedFilesMap().keySet();
        // 被标记的删除文件。
        Set<String> removeSet = repo.getStaging().getRemoveSet();
        // 手动删除，未被记录的文件。
        Set<String> deleteFiles = new TreeSet<>();
        // 将被标记的文件全部去除掉，剩下的都是本应该存在的。
        headCommitRepository.removeAll(removeSet);

        // 获取当前文件夹下本应该存在的所有文件
        for (String fileName : headCommitRepository) {
            // 如果当前文件夹下没有这个文件说明是手动删除的
            if (!cwdFiles.contains(fileName)) {
                deleteFiles.add(fileName);
            }
        }

        return deleteFiles;
    }

    /** 获取所有分支（带标记） */
    public List<String> getMarkingsBranches() {
        // 当前所在的分支
        String nowBranch = new BranchManager(repo).getHead();
        List<String> branches = objects.getBranches();
        branches.sort(String::compareTo);
        // 将字典序排序完之后做所在分支的位置替换为带星号的分支。
        String markingBranch = "*" + nowBranch;
        branches.set(branches.indexOf(nowBranch), markingBranch);
        return branches;
    }

    /** 获取已追踪的文件 */
    public Set<String> getTrackingFiles() {
        Set<String> tracking = new HashSet<>();

        // 1. 获取 HEAD Commit 的所有文件
        Map<String, String> headFiles = new BranchManager(repo).getHeadCommitTrackedFilesMap();
        tracking.addAll(headFiles.keySet());

        // 2. 获取暂存区中准备 Add 的文件
        Staging stage = repo.getStaging();
        Map<String, String> stagedFiles = stage.getStagingMap();
        tracking.addAll(stagedFiles.keySet());

        // 3. 排除掉暂存区中标记为删除（rm）的文件
        Set<String> removedFiles = stage.getRemoveSet();
        tracking.removeAll(removedFiles);

        return tracking;
    }

    /** 用于集合或者列表的合并 */
    public Set<String> arrayMerge(Collection<String> items1,
                                          Collection<String> items2) {
        Set<String> st = new TreeSet<>();
        st.addAll(items1);
        st.addAll(items2);
        return st;
    }
}
