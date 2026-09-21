package gitlet.commit;


import gitlet.repository.Repository;
import gitlet.utils.Utils;

import java.io.File;
import java.io.Serial;
import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 表示一个gitlet提交对象。
 * 在高水平上确实如此。
 */
public class Commit implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /** 这个承诺的信息。 */
    private String message;
    /** 提交时间。 */
    private String time;
    /** 父提交。 */
    private String parent;
    /** 父提交(分支) */
    private String secondParent;
    /** 合并的文件 */
    private List<String> mergeFiles;
    /** .gitlet仓库中的文件集合的映射。 */
    private Map<String, String> trackedFiles;

    public Commit(String message, List<String> mergeFiles, String parent,
                  String secondParent, Map<String, String> trackedFiles) {
        this.message = message;
        this.time = new SimpleDateFormat("EEE MMM d HH:mm:ss yyyy Z", Locale.US).format(new Date());
        this.mergeFiles = mergeFiles;
        this.parent = parent;
        this.secondParent = secondParent;
        this.trackedFiles = trackedFiles;
    }

    /** 保存提交到 Object/commits 文件夹，并返回 SHA-1 值 */
    public String saveCommit() {
        // 获取字节数组(序列化之后的结果)
        byte[] readfile = Utils.serialize(this);
        // 计算SHA-1 值
        String fileSha1 = Utils.sha1((Object) readfile);
        // 创建一个以sha-1命名的文件来记录提交
        File file = Utils.join(Repository.COMMITS, fileSha1);
        // 将 commit 写入文件
        Utils.writeObject(file, this);
        return fileSha1;
    }

    /** 给出当前提交.gitlet仓库中的文件集合的映射的引用。  */
    public Map<String, String> getTrackedFiles() {
        return trackedFiles;
    }

    /** 给出当前提交合并文件链表的引用。  */
    public List<String> getMergeFiles() {
        return mergeFiles;
    }

    /** 给出这个承诺的信息。  */
    public String getMessage() {
        return message;
    }

    /** 给出提交时间 */
    public String getTime() {
        return time;
    }

    /** 给出父提交 */
    public String getParent() {
        return parent;
    }

    /** 给出父分支 */
    public String getSecondParent() {
        return secondParent;
    }
}
