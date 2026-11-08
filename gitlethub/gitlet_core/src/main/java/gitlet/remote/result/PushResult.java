package gitlet.remote.result;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Push 结果
 */
@Data
@AllArgsConstructor
public class PushResult {
    public final boolean success;
    public final String message;
}
