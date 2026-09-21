package gitlet;


import gitlet.repository.Repository;
import gitlet.utils.Utils;

import java.util.Objects;

/** Driver class for Gitlet, a subset of the Git version-control system.
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
        String firstArg = args[0];
        switch(firstArg) {
            case "init":
                // 一个文件夹下只能 init 一次 .gitlet 仓库
                validateNumArgs("init", args, 1);
                Repository.init();
                break;
            case "add":
                // add 添加的时候一次只能添加一个文件
                checkGitLet();
                validateNumArgs("add", args, 2);
                Repository.add(args[1]);
                break;
            case "commit":
                checkGitLet();

                if (commitCheckOk(args, 2)) {
                    // 如果提交信息为空就报错
                    if (Objects.equals(args[1], "")) {
                        Utils.message("Please enter a commit message.");
                        System.exit(0);
                    }
                    Repository.commit(args[1]);
                }
                break;
            case "rm":
                checkGitLet();
                validateNumArgs("rm", args, 2);
                Repository.rm(args[1]);
                break;
            case "log":
                checkGitLet();
                validateNumArgs("log", args, 1);
                Repository.log();
                break;
            case "global-log":
                checkGitLet();
                validateNumArgs("global-log", args, 1);
                Repository.globalLog();
                break;
            case "find":
                checkGitLet();
                validateNumArgs("find", args, 2);
                Repository.find(args[1]);
                break;
            case "status":
                checkGitLet();
                validateNumArgs("status", args, 1);
                Repository.status();
                break;
            case "checkout":
                checkGitLet();
                checkout(args);
                break;
            case "branch":
                checkGitLet();
                validateNumArgs("branch", args, 2);
                Repository.branch(args[1]);
                break;
            case "rm-branch":
                checkGitLet();
                validateNumArgs("rm-branch", args, 2);
                Repository.rmBranch(args[1]);
                break;
            case "reset":
                checkGitLet();
                validateNumArgs("reset", args, 2);
                Repository.reset(args[1]);
                break;
            case "merge":
                checkGitLet();
                validateNumArgs("merge", args, 2);
                Repository.merge(args[1]);
                break;
            default:
                // 不存在的指令
                System.out.println(Utils.error("No command with that name exists.").getMessage());
                break;
        }
    }

    private static void checkout(String[] args) {
        if (args.length == 3) {
            // 获取头指针存的文件。 checkout -- <文件名>
            // 格式检查
            if (!Objects.equals(args[1], "--")) {
                Utils.message("Incorrect operands.");
                System.exit(0);
            }

            Repository.checkoutHeadFile(args[2]);
        } else if (args.length == 4) {
            // 获取指定提交下的文件 checkout <commit id> -- <文件名>
            // 格式检查
            if (!Objects.equals(args[2], "--")) {
                Utils.message("Incorrect operands.");
                System.exit(0);
            }

            Repository.checkoutID(args[1], args[3]);
        } else if (args.length == 2) {
            // 切换到目标分支 checkout <branch name>
            Repository.checkoutBranch(args[1]);
        } else {
            throw new RuntimeException(
                    "Incorrect operands.");
        }
    }

    // .gitlet 目录是否存在，存在true，不存在false
    private static void checkGitLet() {
        if (!Repository.GITLETDIR.exists()) {
            Utils.message("Not in an initialized Gitlet directory.");
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
