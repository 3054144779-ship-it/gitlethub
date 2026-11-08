package common;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 统一响应外层。所有接口（含错误）都返回 {@code {"code":..,"message":..,"data":..}}，
 * code 为业务码（见 {@link ResultCode}），data 为业务数据或错误详情，失败时可为 null。
 *
 * @param <T> 业务数据类型
 */
@Data
@Accessors(chain = true)
public class Result<T> {

    /** 成功时的固定描述，与 openapi.yaml 中 ApiResponse 的定义一致 */
    private static final String SUCCESS_MESSAGE = "ok";

    private Integer code;

    private String message;

    private T data;

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> ok(T data) {
        return new Result<T>().setCode(ResultCode.OK).setMessage(SUCCESS_MESSAGE).setData(data);
    }

    public static <T> Result<T> fail(Integer code, String message) {
        return new Result<T>().setCode(code).setMessage(message);
    }
}
