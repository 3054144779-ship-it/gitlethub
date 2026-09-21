package org.openapitools.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import org.openapitools.jackson.nullable.JsonNullable;
import org.springframework.lang.Nullable;
import java.util.NoSuchElementException;
import org.openapitools.jackson.nullable.JsonNullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * 统一响应外层。所有接口（含错误）都用这个结构。  &#x60;code&#x60; 是**业务码段**，与 HTTP 状态码解耦。HTTP 状态码仍保留语义， 但客户端判断分支应该只看 &#x60;code&#x60;。  | code | HTTP | 含义 | 客户端动作 | | --- | --- | --- | --- | | 0 | 200 | 成功 | — | | 10001 | 400 | 请求格式非法 | 修 bug | | 10002 | 422 | sha1 与内容不符 | 修 bug | | 10003 | 422 | refs 指向的 commit 服务端没有 | 先补传对象再重试 | | 10004 | 413 | 超过体积限制 | 拆分后重传 | | 10005 | 429 | 触发限流 | 退避重试 | | 20001 | 401 | 未认证或 token 失效 | 重新认证 | | 20002 | 403 | 非 owner，无权限 | 放弃 | | 30001 | 404 | 仓库不存在 | — | | 30002 | 404 | 分支不存在 | — | | 50001 | 409 | 分支已被推进（乐观锁失败） | pull 后重试 | | 90001 | 500 | 服务端错误 | 重试 / 上报 |  码段划分：&#x60;1xxxx&#x60; 请求与内容，&#x60;2xxxx&#x60; 认证授权，&#x60;3xxxx&#x60; 仓库与分支， &#x60;5xxxx&#x60; 并发冲突，&#x60;9xxxx&#x60; 系统。&#x60;4xxxx&#x60;、&#x60;6xxxx&#x60;–&#x60;8xxxx&#x60; 预留。 
 */

@Schema(name = "ApiResponse", description = "统一响应外层。所有接口（含错误）都用这个结构。  `code` 是**业务码段**，与 HTTP 状态码解耦。HTTP 状态码仍保留语义， 但客户端判断分支应该只看 `code`。  | code | HTTP | 含义 | 客户端动作 | | --- | --- | --- | --- | | 0 | 200 | 成功 | — | | 10001 | 400 | 请求格式非法 | 修 bug | | 10002 | 422 | sha1 与内容不符 | 修 bug | | 10003 | 422 | refs 指向的 commit 服务端没有 | 先补传对象再重试 | | 10004 | 413 | 超过体积限制 | 拆分后重传 | | 10005 | 429 | 触发限流 | 退避重试 | | 20001 | 401 | 未认证或 token 失效 | 重新认证 | | 20002 | 403 | 非 owner，无权限 | 放弃 | | 30001 | 404 | 仓库不存在 | — | | 30002 | 404 | 分支不存在 | — | | 50001 | 409 | 分支已被推进（乐观锁失败） | pull 后重试 | | 90001 | 500 | 服务端错误 | 重试 / 上报 |  码段划分：`1xxxx` 请求与内容，`2xxxx` 认证授权，`3xxxx` 仓库与分支， `5xxxx` 并发冲突，`9xxxx` 系统。`4xxxx`、`6xxxx`–`8xxxx` 预留。 ")
@JsonTypeName("ApiResponse")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2026-09-21T20:19:28.153831200+08:00[Asia/Shanghai]", comments = "Generator version: 7.25.0")
public class ModelApiResponse {

  /**
   * 业务码段，见上表
   */
  public enum CodeEnum {
    NUMBER_0(0),
    
    NUMBER_10001(10001),
    
    NUMBER_10002(10002),
    
    NUMBER_10003(10003),
    
    NUMBER_10004(10004),
    
    NUMBER_10005(10005),
    
    NUMBER_20001(20001),
    
    NUMBER_20002(20002),
    
    NUMBER_30001(30001),
    
    NUMBER_30002(30002),
    
    NUMBER_50001(50001),
    
    NUMBER_90001(90001);

    private final Integer value;

    CodeEnum(Integer value) {
      this.value = value;
    }

    @JsonValue
    public Integer getValue() {
      return value;
    }

    @Override
    public String toString() {
      return String.valueOf(value);
    }

    @JsonCreator
    public static CodeEnum fromValue(Integer value) {
      for (CodeEnum b : CodeEnum.values()) {
        if (b.value.equals(value)) {
          return b;
        }
      }
      throw new IllegalArgumentException("Unexpected value '" + value + "'");
    }
  }

  private CodeEnum code;

  private String message;

  private JsonNullable<Object> data = JsonNullable.<Object>undefined();

  public ModelApiResponse() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public ModelApiResponse(CodeEnum code, String message) {
    this.code = code;
    this.message = message;
  }

  public ModelApiResponse code(CodeEnum code) {
    this.code = code;
    return this;
  }

  /**
   * 业务码段，见上表
   * @return code
   */
  @NotNull 
  @Schema(name = "code", example = "0", description = "业务码段，见上表", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("code")
  public CodeEnum getCode() {
    return code;
  }

  @JsonProperty("code")
  public void setCode(CodeEnum code) {
    this.code = code;
  }

  public ModelApiResponse message(String message) {
    this.message = message;
    return this;
  }

  /**
   * 人类可读的描述，成功时为 ok
   * @return message
   */
  @NotNull 
  @Schema(name = "message", example = "ok", description = "人类可读的描述，成功时为 ok", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("message")
  public String getMessage() {
    return message;
  }

  @JsonProperty("message")
  public void setMessage(String message) {
    this.message = message;
  }

  public ModelApiResponse data(Object data) {
    this.data = JsonNullable.of(data);
    return this;
  }

  /**
   * 业务数据；失败时为 null 或错误详情
   * @return data
   */
  
  @Schema(name = "data", description = "业务数据；失败时为 null 或错误详情", requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true)
  @JsonProperty("data")
  public JsonNullable<Object> getData() {
    return data;
  }

  public void setData(JsonNullable<Object> data) {
    this.data = data;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ModelApiResponse _apiResponse = (ModelApiResponse) o;
    return Objects.equals(this.code, _apiResponse.code) &&
        Objects.equals(this.message, _apiResponse.message) &&
        equalsNullable(this.data, _apiResponse.data);
  }

  private static <T> boolean equalsNullable(JsonNullable<T> a, JsonNullable<T> b) {
    return a == b || (a != null && b != null && a.isPresent() && b.isPresent() && Objects.deepEquals(a.get(), b.get()));
  }

  @Override
  public int hashCode() {
    return Objects.hash(code, message, hashCodeNullable(data));
  }

  private static <T> int hashCodeNullable(JsonNullable<T> a) {
    if (a == null) {
      return 1;
    }
    return a.isPresent() ? Arrays.deepHashCode(new Object[]{a.get()}) : 31;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class ModelApiResponse {\n");
    sb.append("    code: ").append(toIndentedString(code)).append("\n");
    sb.append("    message: ").append(toIndentedString(message)).append("\n");
    sb.append("    data: ").append(toIndentedString(data)).append("\n");
    sb.append("}");
    return sb.toString();
  }

  /**
   * Convert the given object to string with each line indented by 4 spaces
   * (except the first line).
   */
  private String toIndentedString(@Nullable Object o) {
    return o == null ? "null" : o.toString().replace("\n", "\n    ");
  }
}

