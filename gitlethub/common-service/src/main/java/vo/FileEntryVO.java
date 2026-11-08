package vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 仓库文件树中的一个条目（gitlet 的 trackedFiles 是扁平的 path -> blobSha 映射，没有目录对象）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FileEntryVO {

    private String path;

    private String blobSha;
}
