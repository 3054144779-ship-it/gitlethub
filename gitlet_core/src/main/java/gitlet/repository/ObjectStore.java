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
    /** 该对象存储所属仓库的路径集合 */
    private final RepositoryPaths paths;

    public ObjectStore(RepositoryPaths paths) {
        this.paths = paths;
    }

    /**
     * 检测当前提交是否存在，存在就返回true，否则就false
     * @param sha1
     * @return
     */
    public boolean sha1Check(String sha1) {
        List<String> commitFiles = getCommitsFiles();
        return commitFiles.contains(sha1);
    }

    /**
     * 补全简写的SHA-1值
     * @param sha1
     * @return
     */
    public String completeSha1(String sha1) {
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
    public List<String> getBranches() {
        return Utils.plainFilenamesIn(paths.getBranchDir());
    }

    /**
     * 获取全部的提交文件作为名称的SHA-1值
     * @return
     */
    public List<String> getCommitsFiles() {
        return Utils.plainFilenamesIn(paths.getCommits());
    }

    /**
     * 获取工作目录下的所有文件
     * @return
     */
    public List<String> getCwdFiles() {
        return Utils.plainFilenamesIn(paths.getCwd());
    }

    /**
     * 删除工作目录下的文件
     * @param name
     */
    public void deleteCwdFile(String name) {
        File file = Utils.join(paths.getCwd(), name);
        Utils.restrictedDelete(file);
    }

    /**
     * 覆写文件
     * @param fileName 被覆写的文件的文件名
     * @param sha1 用来覆写的文件的SHA1值
     */
    public void overwriteFile(String fileName, String sha1) {
        // 被覆写的文件路径。
        File filePath = Utils.join(paths.getCwd(), fileName);
        // 获取用来覆写文件的文件路径。
        File copyFilePath = Utils.join(paths.getObject(), sha1);
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
    public String getFileContentInObject(String sha1) {
        // 如果 sha1 为空或 null，直接返回空字符串，不要去读文件
        if (sha1 == null || sha1.isEmpty()) {
            return "";
        }
        File blobFile = Utils.join(paths.getObject(), sha1);
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
    public String getFileSha1(File file) {
        byte[] fileSer = Utils.readContents(file); // 文件序列化
        return Utils.sha1((Object) fileSer);
    }

    /**
     * 将文件保存到Object文件夹
     * @param sha1
     * @param file
     */
    public void saveFile(String sha1, File file) {
        File filePath = Utils.join(paths.getObject(), sha1); // 保存路径
        byte[] fileSer = Utils.readContents(file); // 序列化文件到byt文件
        Utils.writeContents(filePath, (Object) fileSer);
    }

    /**
     * 保存提交到 Object/commits 文件夹，并返回 SHA-1 值
     * @param commit
     * @return
     */
    public String saveCommit(Commit commit) {
        // 获取字节数组(序列化之后的结果)
        byte[] readfile = Utils.serialize(commit);
        // 计算SHA-1 值
        String fileSha1 = Utils.sha1((Object) readfile);
        // 创建一个以sha-1命名的文件来记录提交
        File file = Utils.join(paths.getCommits(), fileSha1);
        // 将 commit 写入文件
        Utils.writeObject(file, commit);
        return fileSha1;
    }

    /**
     * 根据sha1值获取commit对象
     * @param sha1
     * @return
     */
    public Commit getCommit(String sha1) {
        File commitFile = Utils.join(paths.getCommits(), sha1);
        return Utils.readObject(commitFile, Commit.class);
    }
}
