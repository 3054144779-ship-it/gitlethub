package org.openapitools.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import po.Collaborator;

@Mapper
public interface RemoteCollaboratorMapper extends BaseMapper<Collaborator> {
    Collaborator getCollaborator(Long userId, Long repositoryId);

    /**
     * 重新激活/更新协作者等级。
     * 直接用 SQL 绕过 @TableLogic：逻辑删除过的行不能用 updateById 复活
     * （MP 会自动在 WHERE 上追加 is_deleted = 0）。
     * @param userId 用户ID
     * @param repositoryId 仓库ID
     * @param level 等级 code（0/1/2）
     * @return 受影响的行数
     */
    int reactivateCollaborator(@Param("userId") Long userId,
                               @Param("repositoryId") Long repositoryId,
                               @Param("level") Integer level);
}
