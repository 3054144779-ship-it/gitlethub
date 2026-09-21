package gitlet.repository;

import gitlet.commit.Commit;
import gitlet.stag.Staging;
import gitlet.utils.Utils;
import lombok.Getter;

import java.io.File;
import java.util.List;

/** 代表一个 gitlet 仓库。
 *  一个仓库一个实例：目录由构造时传入，所以同一个进程可以同时持有多个互不干扰的仓库。
 *  这里只保留跨命令共享的状态（暂存区）以及几个通用的文件 / 日志工具，
 *  具体的命令逻辑见 gitlet.handler 包下的各个 Handler。
 */
@Getter
public class Repository {
    /** 仓库标识，只用于日志 / 诊断 */
    private final String repoId;
    /** 该仓库涉及的所有磁盘路径 */
    private final RepositoryPaths paths;
    /** 该仓库的对象存储 */
    private final ObjectStore objects;
    /** 该仓库自己的暂存区，首次调用 getStaging() 时才从磁盘加载 */
    private Staging stage;

    public Repository(String repoId, File cwd) {
        this.repoId = repoId;
        this.paths = new RepositoryPaths(repoId, cwd);
        this.objects = new ObjectStore(paths);
    }

    /** 获取共享的暂存区对象，首次调用时才从磁盘加载 */
    public Staging getStaging() {
        if (stage == null) {
            stage = loadStaging(); // 懒加载每次用的时候读
        }
        return stage;
    }

    /** 从磁盘加载暂存区，.gitlet 还没创建时返回一个空的暂存区 */
    private Staging loadStaging() {
        // 如果 .gitlet 目录都不存在，说明是刚开始 init，直接返回空对象
        if (!paths.getGitletDir().exists()) {
            return new Staging();
        }

        /* 如果暂存区文件不存在就创建这个文件 */
        if (!paths.getStagingFile().exists()) {
            Utils.writeObject(paths.getStagingFile(), new Staging());
        }

        return Utils.readObject(paths.getStagingFile(), Staging.class);
    }

    /** 暂存区持久化 */
    public void saveStaging() {
        Utils.writeObject(paths.getStagingFile(), getStaging());
    }

    /** 删除不在当前工作目录下的文件(比如分支，Object下的文件等) */
    public static void notCwdFileDelete(File file) {
        if (file.exists()) {
            boolean success = file.delete();
            if (!success) {
                System.out.println("删除失败的文件路径是:" + file);
            }
        }
    }

    /** 打印一条提交日志 */
    public static void outputCommitLog(Commit commitFile, String commitName) {
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
}
