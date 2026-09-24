package org.openapitools.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import po.Collaborator;

@Mapper
public interface RemoteCollaboratorMapper extends BaseMapper<Collaborator> {
    Collaborator getCollaborator(Long userId, Long repositoryId);
}
