package org.openapitools.verification;

import po.Collaborator;

/**
 * 权限验证
 */
public class PermissionValidation {
    /**
     * 身份验证
     * @param collaborator 协作者
     * @return 是否验证通过
     */
    public static boolean identityVerification(Collaborator collaborator) {
        return collaborator != null
                && collaborator.getLevel() != null
                && (collaborator.getLevel().isOwner()        // 是所有者
                || collaborator.getLevel().isContributor()); // 是协作者
    }
}
