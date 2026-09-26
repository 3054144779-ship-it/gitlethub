package gitlet;


import cn.hutool.core.collection.CollUtil;
import gitlet.bo.GitletObject;
import gitlet.bo.ObjectBundle;
import gitlet.commit.Commit;
import gitlet.handler.BranchManager;
import gitlet.handler.CommandHandler;
import gitlet.handler.MergeHandler;
import gitlet.handler.StatusHandler;
import gitlet.remote.RemoteApiClient;
import gitlet.remote.result.PushResult;
import gitlet.repository.Repository;
import gitlet.repository.RepositoryPaths;
import gitlet.utils.Utils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.sql.Blob;
import java.util.*;

/** Gitlet（Git版本控制系统的一个子集）的驱动程序类。
 */
public class Main {
    private static final String baseUrl = "http://192.168.12.168:8080";

    /** 使用方式：Java gitlet。主ARGS，其中ARGS包含
     *  <COMMAND> <OPERAND1> <OPERAND2> ...
     */
    public static void main(String[] args) {
        // 如果是空返回Please enter a command.
        if (args.length == 0) {
            System.out.println(Utils.error("Please enter a command.").getMessage());
            return ;
        }

        // CLI 只操作「当前工作目录」这一个仓库；网关将来会在这里换成别的目录。
        // 构造 Repository 本身不碰磁盘，所以放在 checkGitLet 之前是安全的。
        Repository repo = new Repository("local", new File(System.getProperty("user.dir")));
        CommandHandler commands = new CommandHandler(repo);
        BranchManager branches = new BranchManager(repo);
        MergeHandler merger = new MergeHandler(repo);
        StatusHandler status = new StatusHandler(repo);

        String firstArg = args[0];
        switch(firstArg) {
            case "init":
                // 一个文件夹下只能 init 一次 .gitlet 仓库
                validateNumArgs("init", args, 1);
                commands.init();
                break;
            case "add":
                // add 添加的时候一次只能添加一个文件
                checkGitLet(repo.getPaths());
                validateNumArgs("add", args, 2);
                commands.add(args[1]);
                break;
            case "commit":
                checkGitLet(repo.getPaths());

                if (commitCheckOk(args, 2)) {
                    // 如果提交信息为空就报错
                    if (Objects.equals(args[1], "")) {
                        Utils.message("Please enter a commit message.");
                        System.exit(0);
                    }
                    commands.commit(args[1]);
                }
                break;
            case "rm":
                checkGitLet(repo.getPaths());
                validateNumArgs("rm", args, 2);
                commands.rm(args[1]);
                break;
            case "log":
                checkGitLet(repo.getPaths());
                validateNumArgs("log", args, 1);
                commands.log();
                break;
            case "global-log":
                checkGitLet(repo.getPaths());
                validateNumArgs("global-log", args, 1);
                commands.globalLog();
                break;
            case "find":
                checkGitLet(repo.getPaths());
                validateNumArgs("find", args, 2);
                commands.find(args[1]);
                break;
            case "status":
                checkGitLet(repo.getPaths());
                validateNumArgs("status", args, 1);
                status.status();
                break;
            case "checkout":
                checkGitLet(repo.getPaths());
                checkout(commands, args);
                break;
            case "branch":
                checkGitLet(repo.getPaths());
                validateNumArgs("branch", args, 2);
                branches.branch(args[1]);
                break;
            case "rm-branch":
                checkGitLet(repo.getPaths());
                validateNumArgs("rm-branch", args, 2);
                branches.rmBranch(args[1]);
                break;
            case "reset":
                checkGitLet(repo.getPaths());
                validateNumArgs("reset", args, 2);
                commands.reset(args[1]);
                break;
            case "merge":
                checkGitLet(repo.getPaths());
                validateNumArgs("merge", args, 2);
                merger.merge(args[1]);
                break;
            case "push":
                // 检查仓库是否初始化
                checkGitLet(repo.getPaths());

                // push 推送的时候需要网关地址
                RemoteApiClient remoteApiClient = new RemoteApiClient(baseUrl);
                // 获取远程仓库仓库路径
                // gitlet push origin main => origin = 远程仓库别名

                File remoteFile = repo.getPaths().getRemoteFile();
                // 解析remoteFile
                Map<String, String> remoteConfig = RepositoryPaths.parseRemoteFile(remoteFile);
                String currentOwnerName = remoteConfig.get("owner");
                String currentRepoName = remoteConfig.get("repo");
                String branch = Utils.readContentsAsString(repo.getPaths().getHeadFile());
                // 获取当前用户在当前仓库的当前分支的 SHA-1 值
                String currentBranchSha = Utils.readContentsAsString(
                        Utils.join(repo.getPaths().getBranchDir(), branch));

                // 远程仓库的跟踪分支的 SHA-1 值(本地以为远程指向哪个SHA)
                String remoteTackingSha = readRemoteTackingSha(repo, currentOwnerName, branch);
                // 收集本地未推送的 commit（从 HEAD 回溯，直到遇到远程已有的）
                List<Commit> commitsToPush = collectCommitsToPush(repo, remoteTackingSha);
                // 收集所有需要推送的 blob SHA-1 值
                Set<String> blobShas = new HashSet<>();
                for (Commit c : commitsToPush) {
                    blobShas.addAll(c.getTrackedFiles().values());
                }

                // 创建 commit GitletObject 列表
                List<GitletObject> commitGitletObjects = commitsToPush.stream()
                        .map(commit -> new GitletObject().sha1(Utils.sha1(commit))
                                .type(GitletObject.TypeEnum.COMMIT).content(Utils.serialize(commit))).toList();

                // 创建 blob GitletObject 列表
                List<GitletObject> blobGitletObjects = blobShas.stream()
                        .map(blob -> new GitletObject().sha1(blob).type(GitletObject.TypeEnum.BLOB)
                                .content(Utils.readContents(Utils.join(repo.getPaths().getObject(), blob)))).toList();

                // 创建文件列表
                List<GitletObject> fileList = new ArrayList<>();
                fileList.addAll(commitGitletObjects);
                fileList.addAll(blobGitletObjects);

                // 创建 ObjectBundle 对象
                ObjectBundle objectBundle = new ObjectBundle().objects(fileList);

                // 发起请求，申请文件存储
                PushResult pushResult1 = remoteApiClient.pushObjects(currentOwnerName, currentRepoName, objectBundle);
                // 发起请求，申请推送分支更新
                PushResult pushResult2 = remoteApiClient.updateBranch(
                        currentOwnerName, currentRepoName, branch, currentBranchSha, remoteTackingSha);

                // 获取网关返回结果，判断推送成功或失败
                if (pushResult1.isSuccess() && pushResult2.isSuccess()) {
                    // 更新 remote tracing ref:记录本次推送的SHA
                    File tracking = Utils.join(repo.getPaths().getRemotePushBranchDir(), currentOwnerName, branch);
                    Utils.writeContents(tracking, currentBranchSha);
                    Utils.message("Push successful.");
                } else {
                    Utils.message("Push failed.");
                }
                break;
            case "remote":
                // gitlet remote add [远程仓库地址]（http://192.168.12.168:8080/api/repos/{owner}/{repo}）
                // TODO:后续要加远程仓库地址的判断，比如发请求是否返回请求之类的
                if (args.length == 4) {
                    // gitlet remote add [远程仓库地址]（http://192.168.12.168:8080/api/repos/{owner}/{repo}）
                    // 默认别名是origin
                    try {
                        RepositoryPaths repoPaths = repo.getPaths();
                        URI uri = new URI(args[3]);
                        String path = uri.getPath();
                        String[] parts = path.split("/");

                        String repoName = parts[parts.length - 1];
                        String ownerName = parts[parts.length - 2];

                        // 示例：http://192.168.12.168:8080 or http://www.gitlethub.com
                        // 构建基础 URL（不含 owner/repo）
                        String baseUrl = uri.getScheme() + "://" + uri.getHost();
                        if (uri.getPort() != -1) {
                            baseUrl += ":" + uri.getPort();
                        }

                        /*
                        * 构建内容字符串
                        * url = {baseUrl}
                        * owner = {ownerName}
                        * repo = {repoName}
                        * defaultBranch = master
                        * */
                        String content = String.format("url=%s\nowner=%s\nrepo=%s\ndefaultBranch=master\n",
                                baseUrl, ownerName, repoName);
                        // 写入远程仓库配置文件
                        Utils.writeContents(repoPaths.getRemoteFile(), "origin", content);

                    } catch (URISyntaxException e) {
                        Utils.message("Invalid URL.");
                        throw new RuntimeException(e);
                    }
                } else if (args.length == 5) {
                    // gitlet remote add [远程仓库别名] [远程仓库地址]（http://192.168.12.168:8080/api/repos/{owner}/{repo}）
                    try {
                        RepositoryPaths repoPaths = repo.getPaths();
                        URI uri = new URI(args[4]);
                        String remoteName = args[3];

                        // 检查远程仓库名称是否已存在
                        if (new File(repoPaths.getRemoteFile(), remoteName).exists()) {
                            Utils.message("Remote name already exists.");
                            System.exit(0);
                        }

                        String path = uri.getPath();
                        String[] parts = path.split("/");

                        String repoName = parts[parts.length - 1];
                        String ownerName = parts[parts.length - 2];

                        // 示例：http://192.168.12.168:8080 or http://www.gitlethub.com
                        // 构建基础 URL（不含 owner/repo）
                        String baseUrl = uri.getScheme() + "://" + uri.getHost();
                        if (uri.getPort() != -1) {
                            baseUrl += ":" + uri.getPort();
                        }

                        /*
                         * 构建内容字符串
                         * url = {baseUrl}
                         * owner = {ownerName}
                         * repo = {repoName}
                         * defaultBranch = master
                         * */
                        String content = String.format("url=%s\nowner=%s\nrepo=%s\ndefaultBranch=master\n",
                                baseUrl, ownerName, repoName);
                        // 写入远程仓库配置文件
                        Utils.writeContents(repoPaths.getRemoteFile(), remoteName, content);

                    } catch (URISyntaxException e) {
                        Utils.message("Invalid URL.");
                        throw new RuntimeException(e);
                    }
                } else {
                    validateNumArgs("remote", args, 0);
                }
                break;
            default:
                // 不存在的指令
                System.out.println(Utils.error("No command with that name exists.").getMessage());
                break;
        }
    }

    /**
     * 读取远程仓库的跟踪分支的 SHA-1 值
     * @return 远程跟踪分支的 SHA-1 值
     */
    private static String readRemoteTackingSha(Repository repo, String remoteName, String branch) {
        // refs/remote/{remoteName}/{branch}
        File remotePushBranchDir = Utils.join(repo.getPaths().getRemotePushBranchDir(), remoteName, branch);
        // 如果当前文件不存在就返回null
        if (!remotePushBranchDir.exists()) {
            return null;
        }
        // 返回文件内容（远程仓库跟踪分支的SHA-1值）
        return Utils.readContentsAsString(remotePushBranchDir);
    }

    /**
     * 收集本地未推送的 commit（从 HEAD 回溯，直到遇到远程已有的）
     * @param repo 仓库
     * @param remoteTackingSha 远程跟踪分支的 SHA-1 值
     * @return 未推送的 commit 列表
     */
    private static List<Commit> collectCommitsToPush(Repository repo, String remoteTackingSha) {
        File commits = repo.getPaths().getCommits();
        File headBranchFile = Utils.join(repo.getPaths().getBranchDir(),
                Utils.readContentsAsString(repo.getPaths().getHeadFile()));
        String currentCommitSha = Utils.readContentsAsString(headBranchFile);
        // 获取当前 commit
        Commit commit = Utils.readObject(Utils.join(commits, currentCommitSha), Commit.class);
        // 创建一个列表来存储 commit
        List<Commit> commitObjects = new ArrayList<>();

        // 遍历 commit，直到遇到远程已有的 commit
        while (commit != null
                && (remoteTackingSha == null || !commit.getParent().equals(remoteTackingSha))) {
            commitObjects.add(commit);
            if (commit.getParent() == null) {
                break;
            }
            commit = Utils.readObject(Utils.join(commits, commit.getParent()), Commit.class);
        }

        return commitObjects;
    }

    private static void checkout(CommandHandler commands, String[] args) {
        if (args.length == 3) {
            // 获取头指针存的文件。 checkout -- <文件名>
            // 格式检查
            if (!Objects.equals(args[1], "--")) {
                Utils.message("Incorrect operands.");
                System.exit(0);
            }

            commands.checkoutHeadFile(args[2]);
        } else if (args.length == 4) {
            // 获取指定提交下的文件 checkout <commit id> -- <文件名>
            // 格式检查
            if (!Objects.equals(args[2], "--")) {
                Utils.message("Incorrect operands.");
                System.exit(0);
            }

            commands.checkoutID(args[1], args[3]);
        } else if (args.length == 2) {
            // 切换到目标分支 checkout <branch name>
            commands.checkoutBranch(args[1]);
        } else {
            throw new RuntimeException(
                    "Incorrect operands.");
        }
    }

    // .gitlet 目录是否存在，存在true，不存在false
    private static void checkGitLet(RepositoryPaths paths) {
        if (!paths.getGitletDir().exists()) {
            Utils.message("Not in an initialized Gitlet directory.");
            // TODO: 这些 System.exit 和直接写 stdout 是把核心接进网关时的真正阻塞点，
            //       会直接杀掉服务进程。改造 API 层之前先不要删。
            System.exit(0);
        }
    }

    // 对输入的判断
    private static void validateNumArgs(String cmd, String[] args, int n) {
        if (args.length != n) {
            throw new RuntimeException(
                    "Incorrect operands.");
        }
    }

    // 对 commit 输入的判断
    private static boolean commitCheckOk(String[] args, int n) {
        if (args.length != n) {
            System.out.println(Utils.error("Incorrect operands.").getMessage());
            return false;
        }
        return true;
    }
}
