package gitlet;


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
                // add 支持一个或多个操作数：文件、目录（递归）或 "."
                checkGitLet(repo.getPaths());
                if (args.length < 2) {
                    throw new RuntimeException("Incorrect operands.");
                }
                for (int i = 1; i < args.length; i++) {
                    commands.add(args[i]);
                }
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
                        // 写入远程仓库配置文件：按远程名落到 remotes/<name>，不能直接写 remotes 文件
                        Utils.writeContents(Utils.join(repoPaths.getRemoteFile(), remoteName), content);

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

                // 3. 确定“本地已有到哪”：必须取被拉取分支的本地位置，而不是当前 HEAD 分支。
                //    否则在 newBranch 上 pull master 会把 newBranch 的 tip 当成 since，
                //    服务端按该提交反查到的分支是 newBranch，回溯会立刻停在 since 上，返回空仓库。
                String currentBranch = Utils.readContentsAsString(repo.getPaths().getHeadFile());
                File localBranchFile = Utils.join(repo.getPaths().getBranchDir(), pullBranch);
                File tracking = Utils.join(repo.getPaths().getRemotePushBranchDir(), pullRemote, pullBranch);
                String localBranchSha = localBranchFile.exists()
                        ? Utils.readContentsAsString(localBranchFile).trim() : null;
                String trackingSha = tracking.exists()
                        ? Utils.readContentsAsString(tracking).trim() : null;

                if (remoteBranchSha.equals(localBranchSha)) {
                    // 本地分支已经指向远程 tip
                    Utils.message("已经更新完毕。");
                    break;
                }

                // 4. 拉对象并写入本地。远程 tip 已在对象库里（之前 fetch 过）时不必再请求。
                File remoteHeadCommit = Utils.join(repo.getPaths().getCommits(), remoteBranchSha);
                if (!remoteHeadCommit.exists()) {
                    // since 优先用远程跟踪指针：它是服务端认识的提交，且不会把本地未推送提交发给服务端
                    String sinceSha = trackingSha != null ? trackingSha : localBranchSha;
                    PullResult pullResult = remoteClient.pullObjects(pullOwner, pullRepo, sinceSha, getToken(repo));
                    if (pullResult.isSuccess()) {
                        storeObjects(repo, pullResult);
                    }
                    // 本地提交不在服务端历史里（如 init 产生的空提交）时服务端会拒绝增量，
                    // 退回全量拉取，一次性把仓库对象拿齐
                    if (!remoteHeadCommit.exists() && sinceSha != null) {
                        log.info("增量拉取未拿到远程提交 {}（{}），改为全量拉取",
                                remoteBranchSha, pullResult.getMessage());
                        pullResult = remoteClient.pullObjects(pullOwner, pullRepo, null, getToken(repo));
                        if (pullResult.isSuccess()) {
                            storeObjects(repo, pullResult);
                        }
                    }
                    if (!pullResult.isSuccess()) {
                        // 拉取失败
                        Utils.message("拉取失败。" + (pullResult.getMessage() == null ? "" : pullResult.getMessage()));
                        throw new RuntimeException("拉取失败。");
                    }
                }

                // 拉取后校验：远程最新提交的对象必须已落盘，否则不能进入 merge
                if (!remoteHeadCommit.exists()) {
                    Utils.message("未拉取到远程最新提交 " + remoteBranchSha
                            + " 的对象（服务端返回 0 个对象？），合并已中止。");
                    break;
                }

                // 5. 只更新远程跟踪分支指针（fetch 语义：记录“远程在哪”，绝不动本地分支指针）
                //    不要在这里 writeContents(localBranchFile, remoteBranchSha)：
                //    那会让本地指针先于 merge 被硬覆盖，导致 mergeBySha 里 nowHead==remote、
                //    LCA==remote，在 L200 提前 return 变成空操作，同时丢失本地未推送提交。
                log.info("path:{}", tracking.getPath());
                Utils.writeContents(tracking, remoteBranchSha);

                // 6. 如果拉取的是当前分支，交给 mergeBySha 合并：
                //    它自己负责移动本地分支指针 + 更新工作目录（快进走 reset，分叉生成合并提交）
                if (currentBranch.equals(pullBranch)) {
                    if (localBranchSha == null || isFreshInitCommit(repo, localBranchSha)) {
                        // 本地只有一个 init 产生的空提交，和远程历史无关：按 clone 语义直接采用远程分支
                        log.info("本地分支 {} 是初始空提交，直接重置到远程 {}", pullBranch, remoteBranchSha);
                        commands.reset(remoteBranchSha);
                    } else {
                        merger.mergeBySha(remoteBranchSha);
                    }
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

    /** 把拉取到的对象写入本地对象库，已存在的不重复写 */
    private static void storeObjects(Repository repo, PullResult pullResult) {
        List<ObjectBundle> bundles = pullResult.getCommits();
        if (bundles == null) {
            return;
        }
        int pulledObjects = bundles.stream()
                .mapToInt(b -> b.getObjects() == null ? 0 : b.getObjects().size()).sum();
        log.info("拉取到 {} 个对象", pulledObjects);
        bundles.forEach(bundle -> {
            List<@Valid GitletObject> objects = bundle.getObjects();
            if (objects == null) {
                return;
            }
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
    }

    /**
     * 本地分支 tip 是否只是 init 生成的空提交（无父提交、无追踪文件）。
     * 这类提交与远程历史无关，pull 时应按 clone 语义直接采用远程分支。
     */
    private static boolean isFreshInitCommit(Repository repo, String sha1) {
        if (StrUtil.isEmpty(sha1) || !repo.getObjects().sha1Check(sha1)) {
            return false;
        }
        Commit commit = repo.getObjects().getCommit(sha1);
        return commit != null
                && commit.getParent() == null
                && (commit.getTrackedFiles() == null || commit.getTrackedFiles().isEmpty());
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
