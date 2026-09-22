package org.openapitools.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonValue;
import org.openapitools.model.ObjectUploadResult;
import org.springframework.lang.Nullable;
import org.openapitools.jackson.nullable.JsonNullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * ApiReposOwnerRepoObjectsPost200Response
 */

@JsonTypeName("_api_repos__owner___repo__objects_post_200_response")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2026-09-21T20:19:28.153831200+08:00[Asia/Shanghai]", comments = "Generator version: 7.25.0")
public class ApiReposOwnerRepoObjectsPost200Response {

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
    
    NUMBER_30001(30001), // 仓库不存在
    
    NUMBER_30002(30002), // 提交的文件不能为空

    NUMBER_30003(30003), // 仓库已存在

    NUMBER_30004(30004),

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

  private @Nullable ObjectUploadResult data;

  public ApiReposOwnerRepoObjectsPost200Response() {
    super();
  }

  /**
   * 仅含必填参数的构造函数
   */
  public ApiReposOwnerRepoObjectsPost200Response(CodeEnum code, String message) {
    this.code = code;
    this.message = message;
  }

  public ApiReposOwnerRepoObjectsPost200Response code(CodeEnum code) {
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

  public ApiReposOwnerRepoObjectsPost200Response message(String message) {
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

  public ApiReposOwnerRepoObjectsPost200Response data(@Nullable ObjectUploadResult data) {
    this.data = data;
    return this;
  }

  /**
   * Get data
   * @return data
   */
  @Valid 
  @Schema(name = "data", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("data")
  public @Nullable ObjectUploadResult getData() {
    return data;
  }

  @JsonProperty("data")
  public void setData(@Nullable ObjectUploadResult data) {
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
    ApiReposOwnerRepoObjectsPost200Response apiReposOwnerRepoObjectsPost200Response = (ApiReposOwnerRepoObjectsPost200Response) o;
    return Objects.equals(this.code, apiReposOwnerRepoObjectsPost200Response.code) &&
        Objects.equals(this.message, apiReposOwnerRepoObjectsPost200Response.message) &&
        Objects.equals(this.data, apiReposOwnerRepoObjectsPost200Response.data);
  }

  @Override
  public int hashCode() {
    return Objects.hash(code, message, data);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class ApiReposOwnerRepoObjectsPost200Response {\n");
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

