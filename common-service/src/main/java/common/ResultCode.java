package common;

/**
 * 业务码段，与 HTTP 状态码解耦；客户端判断分支只看 code。
 * 码段划分：1xxxx 请求与内容，2xxxx 认证授权，3xxxx 仓库与分支，5xxxx 并发冲突，9xxxx 系统。
 */
public final class ResultCode {

    /** 成功 */
    public static final int OK = 0;

    /** 请求格式非法（JSON 解析失败、缺必填字段、路径参数不合法） */
    public static final int BAD_REQUEST = 10001;

    /** sha1 与内容不符 */
    public static final int SHA1_MISMATCH = 10002;

    /** refs 指向的 commit 服务端没有 */
    public static final int MISSING_OBJECT = 10003;

    /** 超过体积限制 */
    public static final int PAYLOAD_TOO_LARGE = 10004;

    /** 触发限流 */
    public static final int TOO_MANY_REQUESTS = 10005;

    /** 未认证或 token 失效 */
    public static final int UNAUTHORIZED = 20001;

    /** 已认证但不是仓库的 owner */
    public static final int FORBIDDEN = 20002;

    /** 仓库不存在 */
    public static final int REPOSITORY_NOT_FOUND = 30001;

    /** 分支不存在 */
    public static final int BRANCH_NOT_FOUND = 30002;

    /** 仓库已存在 */
    public static final int REPOSITORY_ALREADY_EXISTS = 30003;

    /** 分支不存在：refs 接口实际返回的是 30004 而非 30002，保持原码值不变 */
    public static final int BRANCH_NOT_FOUND_REFS = 30004;

    /** 提交为空，拉取失败 */
    public static final int EMPTY_REPOSITORY = 30005;

    /** 分支已被别人推进（乐观锁失败） */
    public static final int VERSION_CONFLICT = 50001;

    /** 服务端错误 */
    public static final int INTERNAL_ERROR = 90001;

    private ResultCode() {
    }
}
