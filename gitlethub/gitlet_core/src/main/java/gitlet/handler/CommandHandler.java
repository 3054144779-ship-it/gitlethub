package gitlet.handler;

import gitlet.commit.Commit;
import gitlet.repository.ObjectStore;
import gitlet.repository.Repository;
import gitlet.repository.RepositoryPaths;
import gitlet.stag.Staging;
import gitlet.utils.Utils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** 核心命令：init / add / commit / rm / log / global-log / find / checkout / reset。 */
public class CommandHandler {
    // 每个请求一个实例
    /** 本次操作针对的仓库 */
    private final Repository repo;
    /** 该仓库的路径集合 */
    private final RepositoryPaths paths;
    /** 该仓库的对象存储 */
    private final ObjectStore objects;

    public CommandHandler(Repository repo) {
        this.repo = repo;
        this.paths = repo.getPaths();
        this.objects = repo.getObjects();
    }

    /*
     * 创建一个无文件但有提交消息 "initial commit" 的提交
     * masker 指向初始提交,并且是当前分支
     * 存在初始提交时间戳 以及 创建仓库中文件对应的 SHA-1 映射
     * */
    public void init() {
        // 如果.gitlet 文件夹不存在就创建文件夹，存在就报错。
        if (paths.getGitletDir().exists()) {
            // 输出错误信息
            Utils.message("A Gitlet version-control system already "
                    + "exists in the current directory.");
            return;
        }

        // 初始化文件
        paths.getGitletDir().mkdirs();
        paths.getObject().mkdirs();
        paths.getBranchDir().mkdirs();
        paths.getCommits().mkdirs();

        // 创建一个初始提交
        Commit initCommit = new Commit("initial commit", new ArrayList<>(),
                null, null, new TreeMap<>());
        String initCommitSha1 = objects.saveCommit(initCommit);

        // 将这个提交的信息写入 master 分支,并且头指针指向 这个新提交.
        File masterFile = Utils.join(paths.getBranchDir(), "master");

        Utils.writeContents(masterFile, initCommitSha1);
        Utils.writeContents(paths.getHeadFile(), "master");

        repo.saveStaging();
    }

    /*
     * add 添加操作，支持单个文件、多个文件、目录和 "."。
     * 目录会递归展开，仓库内统一用相对路径（'/' 分隔）作为 key。
     * 如果添加的文件不存在直接返回 "File does not exist." 错误。
     * 如果添加的文件与最新提交相同，就撤销暂存区里的对应记录；
     * 与暂存区相同则什么都不做，否则写入对象库并暂存。
     * */
    public void add(String name) {
        Path cwd = paths.getCwd().toPath().toAbsolutePath().normalize();
        Path target = cwd.resolve(name).normalize();

        // .gitlet / .git 是版本库元数据，不能当普通文件/目录添加
        if (target.startsWith(paths.getGitletDir().toPath().toAbsolutePath().normalize())
                || target.startsWith(cwd.resolve(".git"))) {
            Utils.message("无法添加版本控制元数据（.gitlet / .git）。");
            return;
        }
        Set<String> trackedFiles = new BranchManager(repo).getHeadCommitTrackedFilesMap().keySet();

        // 只允许添加工作目录内的文件；不存在时若是已追踪文件，等价 git add 已删除文件：暂存删除
        if (!target.startsWith(cwd)) {
            Utils.message("文件不存在。");
            return;
        }
        if (!Files.exists(target)) {
            String rel = Utils.relativeName(cwd, target);
            if (trackedFiles.contains(rel)) {
                rmFile(rel, trackedFiles);
                repo.saveStaging();
                return;
            }
            Utils.message("文件不存在。");
            return;
        }

        if (Files.isDirectory(target)) {
            List<String> relFiles = Utils.recursiveFilenamesIn(target.toFile());
            String prefix = Utils.relativeName(cwd, target);
            if (relFiles != null) {
                for (String rel : relFiles) {
                    String repoPath = prefix.isEmpty() ? rel : prefix + "/" + rel;
                    stageFile(repoPath, target.resolve(rel).toFile());
                }
            }
            // 目录下已被 HEAD 追踪、但工作区已手动删除的文件同样要暂存删除（git add -A 语义）
            for (String file : new TreeSet<>(trackedFiles)) {
                if (isUnder(file, prefix) && !Files.exists(cwd.resolve(file))) {
                    rmFile(file, trackedFiles);
                }
            }
        } else {
            stageFile(Utils.relativeName(cwd, target), target.toFile());
        }

        repo.saveStaging();
    }

    /** 把单个文件按仓库内相对路径 name 加入暂存区 */
    private void stageFile(String name, File file) {
        // 获取当前文件的sha1值
        String nowSha1 = objects.getFileSha1(file);

        // 获取头指针的提交信息
        String headNowFile = new BranchManager(repo).getHeadCommitTrackedFilesMap().getOrDefault(name, null);

        Staging stage = repo.getStaging();
        // 获取暂存区的信息
        String stagingSha1 = stage.getStagingMap().getOrDefault(name, null);

        // 如果当前文件与最新一次提交的文件 SHA-1 值相同就删除暂存区的文件
        // 如果本来存在删除标记，现在 add 这个被删掉的文件之后就不存在删除标记了
        if (nowSha1.equals(headNowFile)) {
            stage.resetFile(name);
            return;
        }

        // 如果当前文件和暂存区中的文件是相同的就直接结束
        if (!nowSha1.equals(stagingSha1)) {
            // 把文件的实际内容作为 Blob 存入文件系统
            objects.saveFile(nowSha1, file);
            stage.addFile(name, nowSha1);
        }
    }

    /** 将暂存区的文件提交，常规情况下会继承父提交中未修改的文件，并更新新的暂存区的提交进去。
     * 如果缺少提交信息会返回 "Please enter a commit message."
     * 如果暂存区没有需要提交的文件会返回 "No changes added to the commit."
     * */
    public void commit(String message) {
        BranchManager branches = new BranchManager(repo);
        Staging stage = repo.getStaging();

        // 判断暂存区是否有东西，没有就报错
        if (stage.isEmpty()) {
            Utils.message("No changes added to the commit");
            return;
        }

        /* 继承父提交中未修改的文件，并更新新的暂存区的提交进去 */
        Map<String, String> newTrackedFiles =
                new TreeMap<>(branches.getHeadCommitTrackedFilesMap());
        newTrackedFiles.putAll(stage.getStagingMap());

        for (String removeFile : stage.getRemoveSet()) {
            newTrackedFiles.remove(removeFile);
        }

        // 若存在 MERGE_HEAD，说明这是「解决合并冲突后的收尾提交」，需要记录第二父，形成真正的合并提交
        File mergeHead = paths.getMergeHead();
        String secondParent = null;
        List<String> mergeFiles = new ArrayList<>();
        if (mergeHead.exists()) {
            if (hasConflictMarkers(stage)) {
                Utils.message("You still have unresolved merge conflicts. "
                        + "Fix them, add the files, then commit.");
                return;
            }
            secondParent = Utils.readContentsAsString(mergeHead);
            mergeFiles.add(branches.getHeadSha1().substring(0, 7)); // 第一父
            mergeFiles.add(secondParent.substring(0, 7));           // 第二父
        }

        // 保存提交，头指针指向该提交(第一个父提交)
        Commit newCommit = new Commit(message, mergeFiles, branches.getHeadSha1(),
                                        secondParent, newTrackedFiles);
        /** 更新当前分支的进度至最新提交 */
        Utils.writeContents(branches.getHeadBranch(), objects.saveCommit(newCommit));

        // 合并已收尾，清除 MERGE_HEAD 状态
        if (mergeHead.exists()) {
            mergeHead.delete();
        }

        // 清空暂存区
        stage.clear();
        // 暂存区持久化
        repo.saveStaging();
    }

    /**
     * 检查暂存区是否有冲突标记
     * @param stage 暂存区
     * @return 暂存区是否有冲突标记
     */
    private boolean hasConflictMarkers(Staging stage) {
        return stage.getStagingMap().values().stream()
                .anyMatch(s -> s.contains("<<<<<<< HEAD") || s.contains(">>>>>>>") || s.contains("======="));
    }

    /** 删除文件或目录：目录会递归处理其中所有已暂存 / 已追踪的文件。
     *  如果在暂存区就删除暂存区的记录；如果在提交中被追踪，就删除工作目录下的文件，并进行标记。 */
    public void rm(String name) {
        Path cwd = paths.getCwd().toPath().toAbsolutePath().normalize();
        Path target = cwd.resolve(name).normalize();
        if (!target.startsWith(cwd)) {
            Utils.message("No reason to remove the file.");
            return;
        }

        Staging stage = repo.getStaging();
        // 获取最新提交所记录的文件
        Set<String> trackedFiles = new BranchManager(repo).getHeadCommitTrackedFilesMap().keySet();

        if (Files.isDirectory(target)) {
            String prefix = Utils.relativeName(cwd, target);
            // 目录下现有的文件，加上可能已被手动删除但仍在暂存区 / HEAD 里的文件
            Set<String> candidates = new TreeSet<>();
            List<String> existing = Utils.recursiveFilenamesIn(target.toFile());
            if (existing != null) {
                for (String rel : existing) {
                    candidates.add(prefix.isEmpty() ? rel : prefix + "/" + rel);
                }
            }
            for (String file : stage.getStagingMap().keySet()) {
                if (isUnder(file, prefix)) {
                    candidates.add(file);
                }
            }
            for (String file : trackedFiles) {
                if (isUnder(file, prefix)) {
                    candidates.add(file);
                }
            }
            for (String file : candidates) {
                rmFile(file, trackedFiles);
            }
            repo.saveStaging();
            return;
        }

        if (!rmFile(Utils.relativeName(cwd, target), trackedFiles)) {
            Utils.message("No reason to remove the file.");
            return;
        }
        repo.saveStaging();
    }

    private boolean isUnder(String file, String dir) {
        return dir.isEmpty() || file.startsWith(dir + "/");
    }

    /** 返回是否确实处理了该文件（在暂存区或被 HEAD 追踪） */
    private boolean rmFile(String name, Set<String> trackedFiles) {
        Staging stage = repo.getStaging();
        Map<String, String> stagingFiles = stage.getStagingMap();
        boolean staged = stagingFiles.containsKey(name);
        boolean tracked = trackedFiles.contains(name);

        // 不存在暂存区也不被最新的提交追踪
        if (!staged && !tracked) {
            return false;
        }

        // 将暂存区的文件删除(如果没有会返回 null)
        stagingFiles.remove(name);

        // 如果在提交中被追踪，就删除工作目录下的文件，并进行标记。
        if (tracked) {
            stage.getRemoveSet().add(name);
            objects.deleteCwdFile(name);
        }

        return true;
    }

    public void log() {
        BranchManager branches = new BranchManager(repo);

        // 获取最新提交的 SHA-1 值
        String nowSha1 = branches.getHeadSha1();

        while (nowSha1 != null) {
            Commit nowCommit = objects.getCommit(nowSha1);
            Repository.outputCommitLog(nowCommit, nowSha1);
            nowSha1 = nowCommit.getParent();
        }
    }

    public void globalLog() {
        // 获取仓库中所有的 commit 文件的SHA-1值
        List<String> files = objects.getCommitsFiles();
        // 获取并打印所有commit的信息
        for (String commitFileName : files) {
            Commit nowCommit = objects.getCommit(commitFileName);
            Repository.outputCommitLog(nowCommit, commitFileName);
        }
    }

    public void find(String mes) {
        // 获取仓库文件SHA-1值
        List<String> files = objects.getCommitsFiles();
        boolean flag = false;

        for (String fileSha1 : files) {
            Commit nowCommit = objects.getCommit(fileSha1);

            if (nowCommit.getMessage().equals(mes)) {
                System.out.println(fileSha1);
                flag = true;
            }
        }

        if (!flag) {
            Utils.message("Found no commit with that message");
        }
    }

    /** 切换到分支 */
    public void checkoutBranch(String branchName) {
        BranchManager branches = new BranchManager(repo);

        // 如果分支不存在输出 No such branch exists
        if (!branches.branchCheck(branchName)) {
            Utils.message("No such branch exists");
            return;
        }

        // 如果自己切自己就输出 "No need to checkout the current branch"
        if (branchName.equals(branches.getHead())) {
            Utils.message("No need to checkout the current branch");
            return;
        }

        // 如果工作目录有未被追踪的文件就输出
        // There is an untracked file in the way; delete it, or add and commit it first.
        if (!new StatusHandler(repo).trackingCheck()) {
            Utils.message("There is an untracked file in the way"
                                  + " and commit it first.");
            return;
        }

        // 获取提交的id
        // 获取目标提交下追踪的所有文件映射
        Map<String, String> commitMap =
                objects.getCommit(branches.readBranchSha1(branchName)).getTrackedFiles();
        // 工作目录下的所有文件
        for (String file : objects.getCwdFiles()) {
            if (!commitMap.containsKey(file)) {
                objects.deleteCwdFile(file);
            }
        }

        // 覆写当前提交时的文件。
        commitMap.forEach(objects::overwriteFile);

        // 清空暂存区
        Staging stage = repo.getStaging();
        stage.clear();
        repo.saveStaging();
        // 移动头指针到目标提交。
        Utils.writeContents(paths.getHeadFile(), branchName);
    }

    /** 切换到指定提交 ID中该文件的版本 */
    public void checkoutID(String commitSha1, String fileName) {
        // 补全提交id
        commitSha1 = objects.completeSha1(commitSha1);
        // 如果当SHA-1值不存在或者不唯一的话返回 "No commit with that id exists"
        if (commitSha1 == null || !objects.sha1Check(commitSha1)) {
            Utils.message("No commit with that id exists");
            return;
        }

        // 校验文件是否存在于该提交中
        if (!objects.getCommit(commitSha1).getTrackedFiles().containsKey(fileName)) {
            Utils.message("File does not exist in that commit");
            return;
        }

        // 获取当前文件在Object文件夹下的SHA-1值
        String fileSha1 = objects.getCommit(commitSha1).getTrackedFiles().get(fileName);

        // 覆写该文件
        objects.overwriteFile(fileName, fileSha1);
    }

    /** 切换到当前头提交中该文件的版本 */
    public void checkoutHeadFile(String fileName) {
        checkoutID(new BranchManager(repo).getHeadSha1(), fileName);
    }

    /** 检出任意指定提交的所有文件，且同时修改当前分支的头指针指向。*/
    public void reset(String commitSha1) {
        BranchManager branches = new BranchManager(repo);

        // SHA-1值补全获取提交的id
        commitSha1 = objects.completeSha1(commitSha1);

        if (!objects.sha1Check(commitSha1)) {
            Utils.message("No commit with that id exists.");
            return;
        }

        // 如果当前分支的工作目录中存在文件没有被追踪的话就返回
        // "There is an untracked file in the way; delete it, or add and commit it first."
        if (!new StatusHandler(repo).trackingCheck()) {
            Utils.message("There is an untracked file in the way"
                                  + " and commit it first.");
            return;
        }

        // 获取目标提交下追踪的所有文件映射
        Map<String, String> commitMap = objects.getCommit(commitSha1).getTrackedFiles();

        // 删除工作目录下不被当时提交所追踪的文件。
        for (String file : objects.getCwdFiles()) {
            if (!commitMap.containsKey(file)) {
                objects.deleteCwdFile(file);
            }
        }

        // 覆写当前提交时的文件。
        commitMap.forEach(objects::overwriteFile);

        // 清空暂存区
        Staging stage = repo.getStaging();
        stage.clear();
        repo.saveStaging();
        // 更新头指针指向分支的提交SHA-1值
        Utils.writeContents(branches.getHeadBranch(), commitSha1);
    }
}
