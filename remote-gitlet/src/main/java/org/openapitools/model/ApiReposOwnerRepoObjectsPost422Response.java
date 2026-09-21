package org.openapitools.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonValue;
import org.openapitools.model.ErrorDetail;
import org.springframework.lang.Nullable;
import org.openapitools.jackson.nullable.JsonNullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * ApiReposOwnerRepoObjectsPost422Response
 */

@JsonTypeName("_api_repos__owner___repo__objects_post_422_response")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2026-09-21T20:19:28.153831200+08:00[Asia/Shanghai]", comments = "Generator version: 7.25.0")
public class ApiReposOwnerRepoObjectsPost422Response {

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

  private @Nullable ErrorDetail data;

  public ApiReposOwnerRepoObjectsPost422Response() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public ApiReposOwnerRepoObjectsPost422Response(CodeEnum code, String message) {
    this.code = code;
    this.message = message;
  }

  public ApiReposOwnerRepoObjectsPost422Response code(CodeEnum code) {
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

  public ApiReposOwnerRepoObjectsPost422Response message(String message) {
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

  public ApiReposOwnerRepoObjectsPost422Response data(@Nullable ErrorDetail data) {
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
  public @Nullable ErrorDetail getData() {
    return data;
  }

  @JsonProperty("data")
  public void setData(@Nullable ErrorDetail data) {
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
    ApiReposOwnerRepoObjectsPost422Response apiReposOwnerRepoObjectsPost422Response = (ApiReposOwnerRepoObjectsPost422Response) o;
    return Objects.equals(this.code, apiReposOwnerRepoObjectsPost422Response.code) &&
        Objects.equals(this.message, apiReposOwnerRepoObjectsPost422Response.message) &&
        Objects.equals(this.data, apiReposOwnerRepoObjectsPost422Response.data);
  }

  @Override
  public int hashCode() {
    return Objects.hash(code, message, data);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class ApiReposOwnerRepoObjectsPost422Response {\n");
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

