package gitlet;


import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import gitlet.bo.GitletObject;
import gitlet.bo.ObjectBundle;
import gitlet.commit.Commit;
import gitlet.handler.BranchManager;
import gitlet.handler.CommandHandler;
import gitlet.handler.MergeHandler;
import gitlet.handler.StatusHandler;
import gitlet.remote.RemoteApiClient;
import gitlet.remote.result.PullResult;
import gitlet.remote.result.PushResult;
import gitlet.repository.Repository;
import gitlet.repository.RepositoryPaths;
import gitlet.utils.Utils;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

import java.io.Console;
import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

/** Gitlet（Git版本控制系统的一个子集）的驱动程序类。
 *
 * TODO:仅实现基础功能，高级功能完成后将添加更多功能。
 *
 */
@Slf4j
public class Main {
//    private static final String baseUrl = "http://127.0.0.1:8081";
//    private static final String baseUrl = "http://192.168.12.168:8081";

    /** 使用方式：Java gitlet。主ARGS，其中ARGS包含
     *  <COMMAND> <OPERAND1> <OPERAND2> ...
     */
    public static void main(String[] args) {
        // 如果是空返回Please enter a command.
        if (args.length == 0) {
            System.out.println(Utils.error("请输入一条命令。").getMessage());
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

                // TODO：当前为全量推送，后续优化为增量推送

                // 获取远程仓库仓库路径
                // gitlet push origin main => origin = 远程仓库别名

                File remoteFile = Utils.join(repo.getPaths().getRemoteFile(), args[1]);
                // 解析remoteFile
                Map<String, String> remoteConfig = RepositoryPaths.parseRemoteFile(remoteFile);
                String currentOwnerName = remoteConfig.get("owner");
                String currentRepoName = remoteConfig.get("repo");
//                String branch = Utils.readContentsAsString(repo.getPaths().getHeadFile());
                String branch = args.length > 2 ? args[2] : Utils.readContentsAsString(repo.getPaths().getHeadFile());
                String remotePushName = args[1];
                String baseUrl = remoteConfig.get("baseUrl");
                // push 推送的时候需要网关地址
                RemoteApiClient remoteApiClient = new RemoteApiClient(baseUrl);
                // 获取当前用户在当前仓库的当前分支的 SHA-1 值
                String currentBranchSha = Utils.readContentsAsString(
                        Utils.join(repo.getPaths().getBranchDir(), branch));

                // 远程仓库的跟踪分支的 SHA-1 值(本地以为远程指向哪个SHA)
                String remoteTackingSha = readRemoteTackingSha(repo, remotePushName, branch);
                // 收集本地未推送的 commit（从 HEAD 回溯，直到遇到远程已有的）
                List<Commit> commitsToPush = collectCommitsToPush(repo, remoteTackingSha);
                // 收集所有需要推送的 blob SHA-1 值
                Set<String> blobShas = new HashSet<>();
                for (Commit c : commitsToPush) {
                    blobShas.addAll(c.getTrackedFiles().values());
                }

                // 创建 commit GitletObject 列表
                List<GitletObject> commitGitletObjects = commitsToPush.stream()
                        .map(commit -> new GitletObject().sha1(Utils.sha1((Object) Utils.serialize(commit)))
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
                log.info("objectBundle: {}", objectBundle);

                // 发起请求，申请文件存储
                PushResult pushResult1 = remoteApiClient.pushObjects(
                        currentOwnerName, currentRepoName, objectBundle, getToken(repo));
                // 发起请求，申请推送分支更新
                PushResult pushResult2 = remoteApiClient.updateBranch(
                        currentOwnerName, currentRepoName, branch, currentBranchSha, remoteTackingSha, getToken(repo));

                // 获取网关返回结果，判断推送成功或失败。
                if (pushResult1.isSuccess() && pushResult2.isSuccess()) {
                    // 更新 remote tracing ref:记录本次推送的SHA
                    File tracking = Utils.join(repo.getPaths().getRemotePushBranchDir(), remotePushName, branch);
                    Utils.writeContents(tracking, currentBranchSha);
                    Utils.message("推送成功。");
                } else {
                    Utils.message("推送失败。");
                }
                break;
            case "remote":
                // 检测仓库是否初始化
                checkGitLet(repo.getPaths());
                // gitlet remote add [远程仓库地址]（http://192.168.12.168:8080/api/repos/{owner}/{repo}）
                // TODO:后续要加远程仓库地址的判断，比如发请求是否返回请求之类的
                if (args.length == 3) {
                    // gitlet remote add [远程仓库地址]（http://192.168.12.168:8080/api/repos/{owner}/{repo}）
                    // 默认别名是origin
                    try {
                        RepositoryPaths repoPaths = repo.getPaths();
                        URI uri = new URI(args[2]);
                        String path = uri.getPath();
                        String[] parts = path.split("/");

                        String repoName = parts[parts.length - 1];
                        String ownerName = parts[parts.length - 2];

                        // 示例：http://192.168.12.168:8080 or http://www.gitlethub.com
                        // 构建基础 URL（不含 owner/repo）
                        String remoteBaseUrl = uri.getScheme() + "://" + uri.getHost();
                        if (uri.getPort() != -1) {
                            remoteBaseUrl += ":" + uri.getPort();
                        }

                        /*
                        * 构建内容字符串
                        * url = {baseUrl}
                        * owner = {ownerName}
                        * repo = {repoName}
                        * defaultBranch = master
                        * */
                        String content = String.format("url=%s\nowner=%s\nrepo=%s\ndefaultBranch=master\n",
                                remoteBaseUrl, ownerName, repoName);
                        // 写入远程仓库配置文件
                        Utils.writeContents(Utils.join(repoPaths.getRemoteFile(), "origin"), content);

                    } catch (URISyntaxException e) {
                        Utils.message("Invalid URL.");
                        throw new RuntimeException(e);
                    }
                } else if (args.length == 4) {
                    // gitlet remote add [远程仓库别名] [远程仓库地址]（http://192.168.12.168:8080/api/repos/{owner}/{repo}）
                    try {
                        RepositoryPaths repoPaths = repo.getPaths();
                        URI uri = new URI(args[3]);
                        String remoteName = args[2];

                        // 检查远程仓库名称是否已存在
                        if (new File(repoPaths.getRemoteFile(), remoteName).exists()) {
                            Utils.message("远程名称已存在。");
                            System.exit(0);
                        }

                        String path = uri.getPath();
                        String[] parts = path.split("/");

                        String repoName = parts[parts.length - 1];
                        String ownerName = parts[parts.length - 2];

                        // 示例：http://192.168.12.168:8080 or http://www.gitlethub.com
                        // 构建基础 URL（不含 owner/repo）
                        String remoteBaseUrl = uri.getScheme() + "://" + uri.getHost();
                        if (uri.getPort() != -1) {
                            remoteBaseUrl += ":" + uri.getPort();
                        }

                        /*
                         * 构建内容字符串
                         * url = {baseUrl}
                         * owner = {ownerName}
                         * repo = {repoName}
                         * defaultBranch = master
                         * */
                        String content = String.format("url=%s\nowner=%s\nrepo=%s\ndefaultBranch=master\n",
                                remoteBaseUrl, ownerName, repoName);
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
            case "pull":
                // 检测仓库是否初始化
                checkGitLet(repo.getPaths());

                // gitlet pull
                // gitlet pull [远程仓库别名] [远程分支名称]
                String pullRemote = args.length >= 2 ? args[1] : "origin";
                String pullBranch = args.length >= 3 ? args[2]
                        : Utils.readContentsAsString(repo.getPaths().getHeadFile());
                Map<String, String> config = RepositoryPaths.parseRemoteFile(
                        Utils.join(repo.getPaths().getRemoteFile(), pullRemote));

                String pullOwner = config.get("owner");
                String pullRepo = config.get("repo");
                String pullBase = config.get("baseUrl");
                RemoteApiClient remoteClient = new RemoteApiClient(pullBase);

                // 2. 获取远程分支当前 SHA
                String remoteBranchSha = remoteClient.getBranchSha(pullOwner, pullRepo, pullBranch, getToken(repo));
                log.info("远程分支 SHA: {}", remoteBranchSha);
                if (remoteBranchSha == null) {
                    Utils.message("未找到远程分支，或者仓库为空。");
                    throw new RuntimeException("未找到远程分支，或者仓库为空。");
                }

                // 3. 对比本地，决定是否拉取
                File branchFileToCommit = Utils.join(repo.getPaths().getBranchDir(),
                        Utils.readContentsAsString(repo.getPaths().getHeadFile()));
                String currentBranchSha1 = Utils.readContentsAsString(branchFileToCommit);
                if (remoteBranchSha.equals(currentBranchSha1)) {
                    // 已经是最新的
                    Utils.message("已经更新完毕。");
                    break;
                }

                // 4. 拉对象并写入本地
                PullResult pullResult = remoteClient.pullObjects(pullOwner, pullRepo, currentBranchSha1, getToken(repo));
                if (!pullResult.isSuccess()) {
                    // 拉取失败
                    Utils.message("拉取失败。");
                    throw new RuntimeException("拉取失败。");
                }

                // 拉取成功
                List<ObjectBundle> commits = pullResult.getCommits();
                commits.forEach(object -> {
                    List<@Valid GitletObject> objects = object.getObjects();
                    objects.forEach(gitletObject -> {
                        if (gitletObject.getType().equals(GitletObject.TypeEnum.COMMIT)) {
                            // 如果是提交的话则写入
                            File commit = Utils.join(repo.getPaths().getCommits(), gitletObject.getSha1());
                            // 只有文件不存在的时候才写入
                            if (!commit.exists()) {
                                Utils.writeContents(commit, (Object) gitletObject.getContent());
                            }
                        } else {
                            // 如果是Blob的话则写入
                            File blob = Utils.join(repo.getPaths().getObject(), gitletObject.getSha1());
                            // 只有文件不存在的时候才写入
                            if (!blob.exists()) {
                                Utils.writeContents(blob, (Object) gitletObject.getContent());
                            }
                        }
                    });
                });

                // 5. 只更新远程跟踪分支指针（fetch 语义：记录“远程在哪”，绝不动本地分支指针）
                //    不要在这里 writeContents(branchFileToCommit, remoteBranchSha)：
                //    那会让本地指针先于 merge 被硬覆盖，导致 mergeBySha 里 nowHead==remote、
                //    LCA==remote，在 L200 提前 return 变成空操作，同时丢失本地未推送提交。
                File tracking = Utils.join(repo.getPaths().getRemotePushBranchDir(), pullRemote, pullBranch);
                log.info("path:{}", tracking.getPath());
                Utils.writeContents(tracking, remoteBranchSha);

                // 6. 如果拉取的是当前分支，交给 mergeBySha 合并：
                //    它自己负责移动本地分支指针 + 更新工作目录（快进走 reset，分叉生成合并提交）
                String currentBranch = Utils.readContentsAsString(repo.getPaths().getHeadFile());
                if (currentBranch.equals(pullBranch)) {
                    merger.mergeBySha(remoteBranchSha);
                }

                break;
            case "login":
                checkGitLet(repo.getPaths());
                doLogin(repo);
                break;
            case "logout":
                checkGitLet(repo.getPaths());
                doLogout(repo);
                break;

            default:
                // 不存在的指令
                System.out.println(Utils.error("不存在该名称的命令。").getMessage());
                break;
        }
    }

    private static void doLogout(Repository repo) {
        File originFile = Utils.join(repo.getPaths().getRemoteFile(), "origin");
        String baseUrl;
        if (originFile.exists()) {
            // parseRemoteFile 存的 "baseUrl" 
            baseUrl = RepositoryPaths.parseRemoteFile(originFile).get("baseUrl");
        } else {
            baseUrl = "http://192.168.12.168:8080";   // 默认网关
        }
        boolean ok = new RemoteApiClient(baseUrl).logout(getToken(repo));
        // 无论服务端是否成功，本地登录态都要清掉
        File credentials = repo.getPaths().getCredentials();
        if (credentials.exists()) {
            credentials.delete();
        }
        Utils.message(ok ? "已登出。" : "登出请求未成功，但已清除本地登录状态。");
    }

    /**
     * 本地登录
     * @param repo 仓库
     */
    private static void doLogin(Repository repo) {
        String username;
        char[] password;
        // 获取控制台对象
        Console console = System.console();
        if (console == null) {
//            System.out.println("控制台不可用。若控制台在 IDE 中运行，请切换到真实终端。");
            // IDE 里 System.console() == null，降级读取（注意：密码会回显，建议到真实终端跑）
            Scanner sc = new Scanner(System.in);
            System.out.print("username: ");
            username = sc.nextLine();
            System.out.print("password: ");
            password = sc.nextLine().toCharArray();
        } else {
            username = console.readLine("请输入用户名：");
            password = console.readPassword("请输入密码：");
        }

        if (StrUtil.isEmpty(username) || password == null || password.length == 0) {
            Utils.message("用户名或密码不能为空。");
            return;
        }

        // 登录走网关地址：优先用已配置的 origin，否则用默认网关
        File originFile = Utils.join(repo.getPaths().getRemoteFile(), "origin");
        String baseUrl;
        if (originFile.exists()) {
            baseUrl = RepositoryPaths.parseRemoteFile(originFile).get("baseUrl");   // 不是 "url"
        } else {
            baseUrl = "http://192.168.12.168:8080";   // 默认网关
        }

        // 获取 token
        String token = new RemoteApiClient(baseUrl).login(username, new String(password));
        // 立即清除内存中的密码
        Arrays.fill(password, '\0');

        if (StrUtil.isEmpty(token)) {
            Utils.message("登录失败：用户名/密码错误，或服务不可达。");
            return;
        }

        File credentials = repo.getPaths().getCredentials();
        Utils.writeContents(credentials, token);
        Utils.message("登录成功。");
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
        // 创建一个列表来存储 commit
        List<Commit> commitObjects = new ArrayList<>();

        // 遍历 commit，直到遇到远程已有的 commit
        while (currentCommitSha != null && !currentCommitSha.equals(remoteTackingSha)) {
            Commit commit = Utils.readObject(Utils.join(commits, currentCommitSha), Commit.class);

            if (commit== null) {
                break;
            }

            commitObjects.add(commit);
            currentCommitSha = commit.getParent();
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
            Utils.message("已初始化的 Gitlet 目录不存在。");
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

    private static String getToken(Repository repo) {
        File credentials = repo.getPaths().getCredentials();
        if (!credentials.exists()) {
            Utils.message("尚未登录，请先执行 gitlet login。");
            System.exit(0);
        }
        return Utils.readContentsAsString(credentials).trim();
    }
}
