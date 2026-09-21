package gitlet;


import gitlet.handler.BranchManager;
import gitlet.handler.CommandHandler;
import gitlet.handler.MergeHandler;
import gitlet.handler.StatusHandler;
import gitlet.repository.Repository;
import gitlet.repository.RepositoryPaths;
import gitlet.utils.Utils;

import java.io.File;
import java.util.Objects;

/** Gitlet（Git版本控制系统的一个子集）的驱动程序类。
 *  @author TODO
 */
public class Main {

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
            default:
                // 不存在的指令
                System.out.println(Utils.error("No command with that name exists.").getMessage());
                break;
        }
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
