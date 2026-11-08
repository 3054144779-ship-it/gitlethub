package gitlet.remote.result;

import gitlet.bo.ObjectBundle;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * Pull 结果
 */
@Data
@AllArgsConstructor
public class PullResult {
    public final boolean success;
    public final String message;
    public final List<ObjectBundle> commits;
}
