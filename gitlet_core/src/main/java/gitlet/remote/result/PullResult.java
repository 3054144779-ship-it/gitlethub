package gitlet.remote.result;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.openapitools.model.ObjectBundle;

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
