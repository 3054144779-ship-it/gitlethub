package gitlet.repository;

import gitlet.commit.Commit;
import gitlet.stag.Staging;
import gitlet.utils.Utils;

import java.io.File;
import java.io.Serializable;
import java.util.*;

import static gitlet.utils.Utils.join;

/** 代表一个 gitlet 仓库。
 *  这里介绍一下这个职业还有哪些内容是个好主意
 *  在高水平上。
 *  @author TODO
 */
public class Repository implements Serializable {



    private static Staging stage = getStaging();

    /*
    * 创建一个无文件但有提交消息 "initial commit" 的提交
    * masker 指向初始提交,并且是当前分支
    * 存在初始提交时间戳 以及 创建仓库中文件对应的 SHA-1 映射
    * */
    public static void init() {
        // 如果.gitlet 文件夹不存在就创建文件夹，存在就报错。
        if (RepositoryPaths.GITLETDIR.exists()) {
            // 输出错误信息
            Utils.message("A Gitlet version-control system already "
                    + "exists in the current directory.");
            return;
        }

        // 初始化文件
        RepositoryPaths.GITLETDIR.mkdirs();
        RepositoryPaths.OBJECT.mkdirs();
        RepositoryPaths.BRANCHDIR.mkdirs();
        RepositoryPaths.COMMITS.mkdirs();

        // 创建一个初始提交
        Commit initCommit = new Commit("initial commit", new ArrayList<>(),
                                    null, null, new TreeMap<>());
        String initCommitSha1 = initCommit.saveCommit();

        // 将这个提交的信息写入 master 分支,并且头指针指向 这个新提交.
        File masterFile = Utils.join(RepositoryPaths.BRANCHDIR, "master");

        Utils.writeContents(masterFile, initCommitSha1);
        Utils.writeContents(RepositoryPaths.HEADFILE, "master");

        saveStaging();
    }
    /*
     * add 添加操作，如果添加的文件不存在直接返回 "File does not exist." 错误
     * 如果 .gitlet 仓库中已经存在 add 的 file 文件，并且与当前 add 的 file 文件相同
     * 就直接清空缓存区的 file 文件，不相同就检查是否和暂存区的文件相同，相同不用做任何操作
     * 如果不相同就直接添加即可。
     * */
    // 添加一个文件

    public static void add(String name) {
        /* 合法性判断 */
        // 如果不存在这文件直接报错
        File file = Utils.join(RepositoryPaths.CWD, name);
        if (!file.exists()) {
            Utils.message("File does not exist.");
            return;
        }

        // 获取当前文件的sha1值
        String nowSha1 = getFileSha1(file);

        // 获取头指针的提交信息
        String headNowFile = getHeadCommitTrackedFilesMap().getOrDefault(name, null);

        // 获取暂存区的信息
        String stagingSha1 = stage.getStagingMap().getOrDefault(name, null);

        // 如果当前提交的文件与最新一次提交的文件(因为只能单个提交)SHA-1值相同就删除暂存区的的文件
        // 如果本来存在删除标记，现在add这个被删掉的文件之后就不存在删除标记了
        if (nowSha1.equals(headNowFile)) {
            // 删除文件标记
            stage.resetFile(name);
            saveStaging();

            return;
        }

        // 如果当前文件和暂存区中的文件是相同的就直接结束
        // 如果最新提交不相同并且与暂存区的文件也不相同
        if (!nowSha1.equals(stagingSha1)) {
            // 把文件的实际内容作为 Blob 存入文件系统
            saveFile(nowSha1, file);
            // 将暂存区信息持久化储存起来
            stage.addFile(name, nowSha1);
        }

        saveStaging();
    }

    /** 将暂存区的文件提交，常规情况下会继承父提交中未修改的文件，并更新新的暂存区的提交进去。
     * 如果缺少提交信息会返回 "Please enter a commit message."
     * 如果暂存区没有需要提交的文件会返回 "No changes added to the commit."
     * */
    public static void commit(String message) {
        // 判断暂存区是否有东西，没有就报错
        if (stage.isEmpty()) {
            Utils.message("No changes added to the commit");
            return;
        }

        /* 继承父提交中未修改的文件，并更新新的暂存区的提交进去 */
        // 复制一份老提交的快照作为底稿
        Map<String, String> newTrackedFiles = new TreeMap<>(getHeadCommitTrackedFilesMap());
        // 然后在这个新底稿上做修改
        newTrackedFiles.putAll(stage.getStagingMap());

        for (String removeFile : stage.getRemoveSet()) {
            newTrackedFiles.remove(removeFile);
        }

        // 保存提交，将该提交之前的最新提交赋值给父提交，并将头指针指向该提交(第一个父提交)
        Commit newCommit = new Commit(message, new ArrayList<>(), getHeadSha1(),
                                        null, newTrackedFiles);
        /** 更新当前分支的进度至最新提交 */
        Utils.writeContents(getHeadBranch(), newCommit.saveCommit());
        // 清空暂存区文件
        stage.clear();
        // 暂存区持久化
        saveStaging();
    }

    /** 删除文件，如果在暂存区就删除暂存区的，如果在提交中被追踪，就删除工作目录下的文件，并进行标记。 */
    public static void rm(String name) {
        // 获取暂存文件的信息
        Map<String, String> stagingFiles = stage.getStagingMap();
        boolean flag1 = stagingFiles.containsKey(name);

        // 获取最新提交所记录的文件
        boolean flag2 = getHeadCommitTrackedFilesMap().containsKey(name);

        // 不存在暂存区也不被最新的提交追踪
        if (!flag1 && !flag2) {
            Utils.message("No reason to remove the file.");
            return;
        }

        // 将暂存区的文件删除(如果没有会返回 null)
        stagingFiles.remove(name);

        // 如果在提交中被追踪，就删除工作目录下的文件，并进行标记。
        if (flag2) {
            // 将最新提交的文件标记
            stage.getRemoveSet().add(name);
            // 删除工作目录下的文件
            deleteCwdFile(name);

        }

        saveStaging();
    }

    public static void log() {
        // 获取最新提交的 SHA-1 值
        String nowSha1 = getHeadSha1();

        while (nowSha1 != null) {
            Commit nowCommit = getCommit(nowSha1);
            outputCommitLog(nowCommit, nowSha1);
            nowSha1 = nowCommit.getParent();
        }
    }

    public static void globalLog() {
        // 获取仓库中所有的 commit 文件的SHA-1值
        List<String> files = getCommitsFiles();
        // 获取并打印所有commit的信息
        for (String commitFileName : files) {
            Commit nowCommit = getCommit(commitFileName);
            outputCommitLog(nowCommit, commitFileName);
        }
    }

    public static void find(String mes) {
        // 获取仓库文件SHA-1值
        List<String> files = getCommitsFiles();
        boolean flag = false;

        for (String fileSha1 : files) {
            Commit nowCommit = getCommit(fileSha1);

            if (nowCommit.getMessage().equals(mes)) {
                System.out.println(fileSha1);
                flag = true;
            }
        }

        if (!flag) {
            Utils.message("Found no commit with that message");
        }
    }

    public static void status() {
        /* 打印状态 */
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

    /** 切换到分支 */
    public static void checkoutBranch(String branchName) {
        // 如果分支不存在输出 No such branch exists
        if (!branchCheck(branchName)) {
            Utils.message("No such branch exists");
            return;
        }

        // 如果自己切自己就输出 "No need to checkout the current branch"
        if (branchName.equals(getHead())) {
            Utils.message("No need to checkout the current branch");
            return;
        }

        // 如果工作目录有未被追踪的文件就输出
        // There is an untracked file in the way; delete it, or add and commit it first.
        if (!trackingCheck()) {
            Utils.message("There is an untracked file in the way"
                                  + " and commit it first.");
            return;
        }

        // 获取提交的id
        // 获取目标提交下追踪的所有文件映射
        Map<String, String> commitMap = getCommit(readBranchSha1(branchName)).getTrackedFiles();
        // 工作目录下的所有文件
        for (String file : getCwdFiles()) {
            if (!commitMap.containsKey(file)) {
                deleteCwdFile(file);
            }
        }

        // 覆写当前提交时的文件。
        commitMap.forEach(Repository::overwriteFile);

        // 清空暂存区
        stage.clear();
        saveStaging();
        // 移动头指针到目标提交。
        Utils.writeContents(RepositoryPaths.HEADFILE, branchName);
    }

    /** 切换到指定提交 ID中该文件的版本 */
    public static void checkoutID(String commitSha1, String fileName) {
        // 补全提交id
        commitSha1 = completeSha1(commitSha1);
        // 如果当SHA-1值不存在或者不唯一的话返回 "No commit with that id exists"
        if (commitSha1 == null || !sha1Check(commitSha1)) {
            Utils.message("No commit with that id exists");
            return;
        }

        // 校验文件是否存在于该提交中
        if (!getCommit(commitSha1).getTrackedFiles().containsKey(fileName)) {
            Utils.message("File does not exist in that commit");
            return;
        }

        // 获取当前文件在Object文件夹下的SHA-1值
        String fileSha1 = getCommit(commitSha1).getTrackedFiles().get(fileName);

        // 覆写该文件
        overwriteFile(fileName, fileSha1);
    }

    /** 切换到当前头提交中该文件的版本 */
    public static void checkoutHeadFile(String fileName) {
        checkoutID(getHeadSha1(), fileName);
    }

    /** 创建分支 */
    public static void branch(String branchName) {
        // 如果已经存在一个名字一样的分支了，就返回“A branch with that name already exists.”，不创建新的分支
        if (getBranches().contains(branchName)) {
            Utils.message("A branch with that name already exists.");
            return;
        }
        // 创建分支辅助函数，将分支名和当前所在分支的SHA-1值传入
        createBranch(branchName, getHeadSha1());
    }

    /** 删除分支 */
    public static void rmBranch(String branchName) {
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

        File branchPath = Utils.join(RepositoryPaths.BRANCHDIR, branchName);
        notCwdFileDelete(branchPath);
    }

    /** 检出任意指定提交的所有文件，且同时修改当前分支的头指针指向。*/
    public static void reset(String commitSha1) {
        // SHA-1值补全获取提交的id
        commitSha1 = completeSha1(commitSha1);

        if (!sha1Check(commitSha1)) {
            Utils.message("No commit with that id exists.");
            return;
        }

        // 如果当前分支的工作目录中存在文件没有被追踪的话就返回
        // "There is an untracked file in the way; delete it, or add and commit it first."
        if (!trackingCheck()) {
            Utils.message("There is an untracked file in the way"
                                  + " and commit it first.");
            return;
        }

        // 获取目标提交下追踪的所有文件映射
        Map<String, String> commitMap = getCommit(commitSha1).getTrackedFiles();

        // 删除工作目录下不被当时提交所追踪的文件。
        for (String file : getCwdFiles()) {
            if (!commitMap.containsKey(file)) {
                deleteCwdFile(file);
            }
        }

        // 覆写当前提交时的文件。
        commitMap.forEach(Repository::overwriteFile);

        // 清空暂存区
        stage.clear();
        saveStaging();
        // 更新头指针指向分支的提交SHA-1值
        Utils.writeContents(getHeadBranch(), commitSha1);
    }

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
        if (!branchCheck(branchName)) {
            Utils.message("A branch with that name does not exist.");
            return;
        }

        // 尝试合并自身分支
        if (branchName.equals(getHead())) {
            Utils.message("Cannot merge a branch with itself.");
            return;
        }

        // 最近公共祖先的commit的SHA-1值
        String lcaSha1 = getLCA(branchName);
        // 给定分支的SHA—1值
        String givenBranchSha1 = readBranchSha1(branchName);
        // 当前所在的提交
        String nowHeadBranchSha1 = getHeadSha1();

        // 工作区中有未追踪文件
        if (!trackingCheck(getCommit(givenBranchSha1).getTrackedFiles(),
                getCommit(lcaSha1).getTrackedFiles())) {
            Utils.message("There is an untracked file in the way"
                                  + "delete it, or add and commit it first.");
            return;
        }

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
        if (lcaSha1.equals(getHeadSha1())) {
            checkoutBranch(branchName);
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
        String mes = "Merged " + branchName + " into " + getHead() + ".";
        List<String> mergeFiles = new ArrayList<>();
        mergeFiles.add(nowHeadBranchSha1.substring(0, 7)); // 第一父提交
        mergeFiles.add(givenBranchSha1.substring(0, 7)); // 第二父提交

        Map<String, String> newTrackedFiles = new TreeMap<>(getHeadCommitTrackedFilesMap());
        newTrackedFiles.putAll(stage.getStagingMap());
        for (String removeFile : stage.getRemoveSet()) {
            newTrackedFiles.remove(removeFile);
        }

        // 自动提交
        Commit newCommit = new Commit(mes, mergeFiles, nowHeadBranchSha1,
                                        givenBranchSha1, newTrackedFiles);

        /** 更新当前分支的进度至最新提交 */
        Utils.writeContents(getHeadBranch(), newCommit.saveCommit());
        // 清空暂存区文件
        stage.clear();
        // 持久化暂存区
        saveStaging();
    }

    /*
     *
     * 辅助函数区
     *
     * */

    /** 生成冲突文件内容 */
    private static StringBuilder getStringBuilder(String nowContent, String givenContent) {
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

    /** 获取文件在Object中的内容 */
    private static String getFileContentInObject(String sha1) {
        // 如果 sha1 为空或 null，直接返回空字符串，不要去读文件
        if (sha1 == null || sha1.isEmpty()) {
            return "";
        }
        File blobFile = Utils.join(RepositoryPaths.OBJECT, sha1);
        // 如果文件不存在或者已经被删除就返回空字符串
        if (!blobFile.exists() || blobFile.isDirectory()) {
            return "";
        }
        return Utils.readContentsAsString(blobFile);
    }

    /** 生成冲突文件，并返回文件路径 */
    private static File generateConflict(String fileName,
                                         String givenBranchFileSha1, String nowBranchFileSha1) {
        File conflictFilePath = Utils.join(RepositoryPaths.CWD, fileName);

        StringBuilder conflictFileContent = getStringBuilder(
                getFileContentInObject(nowBranchFileSha1),
                getFileContentInObject(givenBranchFileSha1));

        Utils.writeContents(conflictFilePath, conflictFileContent.toString());

        return conflictFilePath;
    }

    private static boolean fileProcessing(String givenBranchSha1,
                                          String nowHeadBranchSha1, String lcaSha1) {

        // 获取三个提交的全部不重复文件
        Set<String> allFiles = new TreeSet<>();
        allFiles.addAll(getCommit(givenBranchSha1).getTrackedFiles().keySet());
        allFiles.addAll(getCommit(nowHeadBranchSha1).getTrackedFiles().keySet());
        allFiles.addAll(getCommit(lcaSha1).getTrackedFiles().keySet());

        boolean hasConflict = false;
        for (String fileName : allFiles) {
            String lcaSha = getCommit(lcaSha1).getTrackedFiles().get(fileName);
            String givenSha = getCommit(givenBranchSha1).getTrackedFiles().get(fileName);
            String nowSha = getCommit(nowHeadBranchSha1).getTrackedFiles().get(fileName);

            // 情况 1: Given 和 Now 内容一致 (无论 LCA 是什么，或者是否存在)
            // 或者是 Given 的内容相对于 LCA 没变
            if (Objects.equals(givenSha, nowSha) || Objects.equals(givenSha, lcaSha)) {
                continue; // 保持现状，不做任何处理
            }

            // 情况 2: Now 的内容相对于 LCA 没变，但 Given 变了
            if (Objects.equals(nowSha, lcaSha)) {
                if (givenSha != null) {
                    // Given 修改了文件或新增了文件
                    overwriteFile(fileName, givenSha);
                    stage.getStagingMap().put(fileName, givenSha);
                } else {
                    // Given 删除了文件
                    deleteCwdFile(fileName);
                    stage.getRemoveSet().add(fileName);
                }
                continue;
            }

            // 情况 3: 冲突（LCA, Given, Now 三者各不相同）
            // 这里已经隐含了 givenSha != nowSha 且两者都与 lcaSha 不同
            File conflictFile = generateConflict(fileName, givenSha, nowSha);
            stage.getStagingMap().put(fileName, getFileSha1(conflictFile));
            hasConflict = true;
        }

        return hasConflict;
    }

    /** 获取两个分支的最近公共祖先 (LCA) */
    private static String getLCA(String branchName) {

        // 找出当前分支的所有祖先（包含自己）
        Set<String> ancestorsOfCurrent = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        queue.add(getHeadSha1());

        while (!queue.isEmpty()) {
            String p = queue.poll();
            if (p == null || ancestorsOfCurrent.contains(p)) {
                continue;
            }
            ancestorsOfCurrent.add(p);

            if (getCommit(p).getParent() != null) {
                queue.add(getCommit(p).getParent());
            }
            // 如果是 Merge Commit，也要把第二个父节点加进去
            if (getCommit(p).getSecondParent() != null) {
                // parentBranch 存了第二个父节点的 SHA1
                queue.add(getCommit(p).getSecondParent());
            }
        }

        // 从 Given 分支开始 BFS，找到第一个在 ancestorsOfCurrent 中的节点
        queue.clear();
        queue.add(readBranchSha1(branchName));
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

            if (getCommit(p).getParent() != null) {
                queue.add(getCommit(p).getParent());
            }
            if (getCommit(p).getSecondParent() != null) {
                queue.add(getCommit(p).getSecondParent());
            }
        }

        return null; // 理论上不可能，除非两个分支没交集
    }


    /** 删除不在当前工作目录下的文件(比如分支，Object下的文件等) */
    private static void notCwdFileDelete(File file) {
        if (file.exists()) {
            boolean success = file.delete();
            if (!success) {
                System.out.println("删除失败的文件路径是:" + file);
            }
        }
    }

    /** 创建一个新的分支，并且存入最新提交的SHA-1值代表这个分支是在这个提交创建的
     *  branchName 创建的分支名称 nowSha1 当前所在分支的最新提交的SHA-1值。
     * */
    private static void createBranch(String branchName, String nowSha1) {
        File branchPath = Utils.join(RepositoryPaths.BRANCHDIR, branchName);
        // 将当前分支的SHA-1值写入文件。
        Utils.writeContents(branchPath, nowSha1);
    }

    /** 检测当前提交是否存在，存在就返回true，否则就false */
    private static boolean sha1Check(String sha1) {
        List<String> commitFiles = getCommitsFiles();
        return commitFiles.contains(sha1);
    }

    /** 补全简写的SHA-1值 */
    private static String completeSha1(String sha1) {
        if (sha1 == null) {
            return null;
        }
        int length = sha1.length();
        if (length > 40) {
            return null;
        }

        // 性能优化: 如果已经是 40 位，直接验证文件是否存在，没必要遍历
        if (length == 40) {
            // 假设你有一个方法判断这个 ID 是否对应一个存在的 Commit
            return sha1Check(sha1) ? sha1 : null;
        }

        // 所有commit文件
        List<String> commitFiles = getCommitsFiles();
        // 符合的文件
        List<String> conformFiles = new ArrayList<>();
        for (String fileSha1 : commitFiles) {
            // 只比较所有文件commit的length长度的前缀
            if (fileSha1.startsWith(sha1)) {
                conformFiles.add(fileSha1);
            }
        }
        return conformFiles.size() == 1 ? conformFiles.get(0) : null;
    }

//    /** 更新头指针的指向分支 */
//    private static void pushHead(String branchName) {
//
//    }

    /** 获取分支中存储提交的SHA-1值 */
    private static String readBranchSha1(String branchName) {
        return Utils.readContentsAsString(Utils.join(RepositoryPaths.BRANCHDIR, branchName));
    }

    /** 将Object文件夹中的覆写到工作目录
     * fileName:目标文件名字 sha1:用来覆写文件的SHA-1值
     * */
    private static void overwriteFile(String fileName, String sha1) {
        // 被覆写的文件路径。
        File filePath = Utils.join(RepositoryPaths.CWD, fileName);
        // 获取用来覆写文件的文件路径。
        File copyFilePath = Utils.join(RepositoryPaths.OBJECT, sha1);
        // 获取用来覆写的文件序列化后的文件内容。
        byte[] file = Utils.readContents(copyFilePath);
        // 将Object内的文件覆写到同名文件。
        Utils.writeContents(filePath, (Object) file);
    }

    /** 检查工作目录下是否所有文件都被追踪，都被追踪返回 true，有未被追踪的返回 false */
    private static boolean trackingCheck() {
        return getNotTrackingFiles().isEmpty();
    }

    /** 检查分支目录下是否所有文件都被追踪，都被追踪返回 true，有未被追踪的返回 false */
    private static boolean trackingCheck(Map<String, String> givenBranchFiles,
                                         Map<String, String> lcaFiles) {
        for (String fileName : getNotTrackingFiles()) {
            // 只有当 "未追踪文件" 在 "给定分支" 中存在，且内容不同时，才算“没被追踪”
            // 情况 A：给定分支有这个文件，且内容和工作区不一样 (会被覆盖)
            if (givenBranchFiles.containsKey(fileName)) {
                String currentSha1 = getFileSha1(Utils.join(RepositoryPaths.CWD, fileName));
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

    /** 检查是否存在这个分支，存在返回 true，不存在返回 false */
    private static boolean branchCheck(String branch) {
        return getBranches().contains(branch);
    }

    /** 打印已修改未暂存的文件列表(信息特殊) */
    private static void printSection(String title, Collection<String> items1,
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
    private static void printSection(String title, Collection<String> items) {
        System.out.println("=== " + title + " ===");
        for (final String fileName : items) {
            System.out.println(fileName);
        }
        System.out.println();
    }

    /**  获取已修改未暂存的文件列表（排序后） */
    private static List<String> getNotTrackingFiles() {
        Set<String> trackingFiles = getTrackingFiles();
        List<String> unTrackingFiles = new ArrayList<>();
        List<String> cwdFiles = getCwdFiles();
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
    private static List<String> getModifyNotStagingFiles() {
        // 获取最新提交时所有的文件映射。
        Map<String, String> commitFilesMap = getHeadCommitTrackedFilesMap();
        Map<String, String> stagingMap = stage.getStagingMap();

        List<String> modifiedFiles = new ArrayList<>();
        // 获取工作目录所有的文件名
        List<String> cwdFiles = getCwdFiles();

        for (String fileName : cwdFiles) {
            // 如果在暂存区的所有提交中存在这个文件的话。
            if (stagingMap.containsKey(fileName)) {
                String serCwd = getFileSha1(Utils.join(RepositoryPaths.CWD, fileName));
                String serStaging = stagingMap.get(fileName);
                // SHA-1值不同加入未加入暂存区
                if (!serStaging.equals(serCwd)) {
                    modifiedFiles.add(fileName);
                }
            } else if (commitFilesMap.containsKey(fileName)) {
                // 如果这个文件之前提交过的话(最新的一次为准)，SHA-1值不同说明修改过。
                String serCwd = getFileSha1(Utils.join(RepositoryPaths.CWD, fileName));
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
    private static List<String> getStagingFiles() {
        Map<String, String> stagedFileMap = stage.getStagingMap();
        List<String> stagedFiles = new ArrayList<>();
        stagedFileMap.forEach((fileName, sha1) -> {
            stagedFiles.add(fileName);
        });
        stagedFiles.sort(String::compareTo);

        return stagedFiles;
    }

    /** 获取暂存区的删除标记文件集合 */
    private static Set<String> getDeleteFiles() {
        // 获取当前目录下的所有文件，如果是手动删除不是 rm 删除的话，那么不会在 removeSet 记录，
        // 同时应该在头指针提交下，因为被追踪过
        List<String> cwdFiles = getCwdFiles();
        // 最新提交下被追踪的所有文件。
        Set<String> headCommitRepository = getHeadCommitTrackedFilesMap().keySet();
        // 被标记的删除文件。
        Set<String> removeSet = stage.getRemoveSet();
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

    /** 获取当前工作目录下的全部文件名 */
    private static List<String> getCwdFiles() {
        return Utils.plainFilenamesIn(RepositoryPaths.CWD);
    }

    /** 获取所有分支 */
    private static List<String> getBranches() {
        return Utils.plainFilenamesIn(RepositoryPaths.BRANCHDIR);
    }

    /** 获取所有分支（带标记） */
    private static List<String> getMarkingsBranches() {
        // 当前所在的分支
        String nowBranch = getHead();
        List<String> branches = getBranches();
        branches.sort(String::compareTo);
        // 将字典序排序完之后做所在分支的位置替换为带星号的分支。
        String markingBranch = "*" + nowBranch;
        branches.set(branches.indexOf(nowBranch), markingBranch);
        return branches;
    }

    /** 获取已追踪的文件 */
    private static Set<String> getTrackingFiles() {
        Set<String> tracking = new HashSet<>();

        // 1. 获取 HEAD Commit 的所有文件
        Map<String, String> headFiles = getHeadCommitTrackedFilesMap();
        tracking.addAll(headFiles.keySet());

        // 2. 获取暂存区中准备 Add 的文件
        Map<String, String> stagedFiles = stage.getStagingMap();
        tracking.addAll(stagedFiles.keySet());

        // 3. 排除掉暂存区中标记为删除（rm）的文件
        Set<String> removedFiles = stage.getRemoveSet();
        tracking.removeAll(removedFiles);

        return tracking;
    }

    /** 用于集合或者列表的合并 */
    private static Set<String> arrayMerge(Collection<String> items1,
                                          Collection<String> items2) {
        Set<String> st = new TreeSet<>();
        st.addAll(items1);
        st.addAll(items2);
        return st;
    }

    /** 获取目标文件的SHA-1值 */
    private static String getFileSha1(File file) {
        byte[] fileSer = Utils.readContents(file); // 文件序列化
        return Utils.sha1((Object) fileSer);
    }

    /** 获取 head-commit 中的 <\name:SHA-1> 映射表*/
    private static Map<String, String> getHeadCommitTrackedFilesMap() {
        // 获取头指针指向的分支
        String branch = Utils.readContentsAsString(RepositoryPaths.HEADFILE);
        // 获取头指针指向的分支中存储的 commit 的SHA-1值
        File file = Utils.join(RepositoryPaths.BRANCHDIR, branch);
        String sha1 = Utils.readContentsAsString(file);

        return getCommit(sha1).getTrackedFiles();
    }

    /** 获取 head-commit 中的 merge 链表*/
    private static List<String> getHeadCommitMargeFilesList() {
        // 获取头指针指向的分支
        String branch = Utils.readContentsAsString(RepositoryPaths.HEADFILE);
        // 获取头指针指向的分支中存储的 commit 的SHA-1值
        File file = Utils.join(RepositoryPaths.BRANCHDIR, branch);
        String sha1 = Utils.readContentsAsString(file);

        return getCommit(sha1).getMergeFiles();
    }

    private static Commit getCommit(String sha1) {
        File commitFile = Utils.join(RepositoryPaths.COMMITS, sha1);
        return Utils.readObject(commitFile, Commit.class);
    }

    /** 获取头指针指向的分支的SHA-1值 */
    private static String getHeadSha1() {
        return Utils.readContentsAsString(getHeadBranch());
    }

    /** 获取头指针指向的分支路径 */
    private static File getHeadBranch() {
        return Utils.join(RepositoryPaths.BRANCHDIR, getHead());
    }

    /** 获取头指针指向的分支名称 */
    private static String getHead() {
        return Utils.readContentsAsString(RepositoryPaths.HEADFILE);
    }

    /** 获取暂存区对象 */
    private static Staging getStaging() {
        // 如果 .gitlet 目录都不存在，说明是刚开始 init，直接返回空对象
        if (!RepositoryPaths.GITLETDIR.exists()) {
            return new Staging();
        }

        /* 如果当前文件不存在就创建这个文件 */
        if (!RepositoryPaths.STAGINGFILE.exists()) {
            Utils.writeObject(RepositoryPaths.STAGINGFILE, new Staging());
        }

        return Utils.readObject(RepositoryPaths.STAGINGFILE, Staging.class);
    }

    /** 将文件保存到Object文件夹 */
    private static void saveFile(String sha1, File file) {
        File filePath = Utils.join(RepositoryPaths.OBJECT, sha1); // 保存路径
        byte[] fileSer = Utils.readContents(file); // 序列化文件到byt文件
        Utils.writeContents(filePath, (Object) fileSer);
    }

    /** 删除工作目录下的文件 */
    private static void deleteCwdFile(String name) {
        File file = Utils.join(RepositoryPaths.CWD, name);
        Utils.restrictedDelete(file);
    }

    private static void outputCommitLog(Commit commitFile, String commitName) {
        System.out.println("===");
        System.out.println("commit" + " " + commitName);
        // 如果存在合并文件，就输出合并文件
        List<String> mergeFiles = commitFile.getMergeFiles();
        if (!mergeFiles.isEmpty()) {
            System.out.print("Merge:" + " ");
            for (int i = 0; i < mergeFiles.size(); ++i) {
                String mergeFileName = mergeFiles.get(i);
                System.out.print(mergeFileName);
                if (i != mergeFiles.size() - 1) {
                    System.out.print(" ");
                }
            }
            System.out.println();
        }
        System.out.println("Date:" + " " + commitFile.getTime());
        System.out.println(commitFile.getMessage());
        System.out.println();
    }

    /** 获取全部的提交文件作为名称的SHA-1值 */
    private static List<String> getCommitsFiles() {
        return Utils.plainFilenamesIn(RepositoryPaths.COMMITS);
    }

    // 暂存区持久化
    private static void saveStaging() {
        Utils.writeObject(RepositoryPaths.STAGINGFILE, stage);
    }

}
