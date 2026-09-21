package gitlet.repository;

import gitlet.commit.Commit;
import gitlet.utils.Utils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 对象存储类
 */
public class ObjectStore {

    /**
     * 检测当前提交是否存在，存在就返回true，否则就false
     * @param sha1
     * @return
     */
    public static boolean sha1Check(String sha1) {
        List<String> commitFiles = getCommitsFiles();
        return commitFiles.contains(sha1);
    }
    
    /**
     * 补全简写的SHA-1值
     * @param sha1
     * @return
     */
    public static String completeSha1(String sha1) {
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

    /**
     * 获取所有分支的名称
     * @return
     */
    public static List<String> getBranches() {
        return Utils.plainFilenamesIn(RepositoryPaths.BRANCHDIR);
    }
    
    /**
     * 获取全部的提交文件作为名称的SHA-1值
     * @return
     */
    public static List<String> getCommitsFiles() {
        return Utils.plainFilenamesIn(RepositoryPaths.COMMITS);
    }
    
    /**
     * 获取工作目录下的所有文件
     * @return
     */
    public static List<String> getCwdFiles() {
        return Utils.plainFilenamesIn(RepositoryPaths.CWD);
    }
    
    /**
     * 删除工作目录下的文件
     * @param name
     */
    public static void deleteCwdFile(String name) {
        File file = Utils.join(RepositoryPaths.CWD, name);
        Utils.restrictedDelete(file);
    }

    /**
     * 覆写文件
     * @param fileName 被覆写的文件的文件名
     * @param sha1 用来覆写的文件的SHA1值
     */
    public static void overwriteFile(String fileName, String sha1) {
        // 被覆写的文件路径。
        File filePath = Utils.join(RepositoryPaths.CWD, fileName);
        // 获取用来覆写文件的文件路径。
        File copyFilePath = Utils.join(RepositoryPaths.OBJECT, sha1);
        // 获取用来覆写的文件序列化后的文件内容。
        byte[] file = Utils.readContents(copyFilePath);
        // 将Object内的文件覆写到同名文件。
        Utils.writeContents(filePath, (Object) file);
    }

    /**
     * 根据sha1值获取文件内容
     * @param sha1
     * @return
     */
    public static String getFileContentInObject(String sha1) {
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

    /**
     * 获取文件的sha1值
     * @param file
     * @return
     */
    public static String getFileSha1(File file) {
        byte[] fileSer = Utils.readContents(file); // 文件序列化
        return Utils.sha1((Object) fileSer);
    }

    /**
     * 将文件保存到Object文件夹
     * @param sha1
     * @param file
     */
    public static void saveFile(String sha1, File file) {
        File filePath = Utils.join(RepositoryPaths.OBJECT, sha1); // 保存路径
        byte[] fileSer = Utils.readContents(file); // 序列化文件到byt文件
        Utils.writeContents(filePath, (Object) fileSer);
    }

    /**
     * 根据sha1值获取commit对象
     * @param sha1
     * @return
     */
    public static Commit getCommit(String sha1) {
        File commitFile = Utils.join(RepositoryPaths.COMMITS, sha1);
        return Utils.readObject(commitFile, Commit.class);
    }
}
