package gitlet.stag;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** 提交类 */
public class Staging implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private Map<String, String> stagingMap;
    private Set<String> removeSet;

    public Staging() {
        this.stagingMap = new TreeMap<>();
        this.removeSet = new TreeSet<>();
    }

    // 清空暂存区
    public void clear() {
        stagingMap.clear();
        removeSet.clear();
    }

    public void addFile(String fileName, String nowSha1) {
        stagingMap.put(fileName, nowSha1);
    }

    public void resetFile(String name) {
        stagingMap.remove(name);
        removeSet.remove(name);
    }

    // 提供一些只读的 getter 供 Repository 检查状态
    public Map<String, String> getStagingMap() {
        return stagingMap;
    }

    public Set<String> getRemoveSet() {
        return removeSet;
    }

    /** 如果暂存区有未提交添加或删除返回false, 没有返回true */
    public boolean isEmpty() {
        return stagingMap.isEmpty() && removeSet.isEmpty();
    }

}
