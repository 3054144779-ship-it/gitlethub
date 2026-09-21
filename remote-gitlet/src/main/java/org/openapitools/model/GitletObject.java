package org.openapitools.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import org.springframework.lang.Nullable;
import org.openapitools.jackson.nullable.JsonNullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * GitletObject
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2026-09-21T20:19:28.153831200+08:00[Asia/Shanghai]", comments = "Generator version: 7.25.0")
public class GitletObject {

  private String sha1;

  /**
   * Gets or Sets type
   */
  public enum TypeEnum {
    BLOB("blob"),
    
    COMMIT("commit");

    private final String value;

    TypeEnum(String value) {
      this.value = value;
    }

    @JsonValue
    public String getValue() {
      return value;
    }

    @Override
    public String toString() {
      return String.valueOf(value);
    }

    @JsonCreator
    public static TypeEnum fromValue(String value) {
      for (TypeEnum b : TypeEnum.values()) {
        if (b.value.equals(value)) {
          return b;
        }
      }
      throw new IllegalArgumentException("Unexpected value '" + value + "'");
    }
  }

  private TypeEnum type;

  private byte[] content;

  public GitletObject() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public GitletObject(String sha1, TypeEnum type, byte[] content) {
    this.sha1 = sha1;
    this.type = type;
    this.content = content;
  }

  public GitletObject sha1(String sha1) {
    this.sha1 = sha1;
    return this;
  }

  /**
   * 40 位十六进制
   * @return sha1
   */
  @NotNull @Pattern(regexp = "^[0-9a-f]{40}$") 
  @Schema(name = "sha1", description = "40 位十六进制", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("sha1")
  public String getSha1() {
    return sha1;
  }

  @JsonProperty("sha1")
  public void setSha1(String sha1) {
    this.sha1 = sha1;
  }

  public GitletObject type(TypeEnum type) {
    this.type = type;
    return this;
  }

  /**
   * Get type
   * @return type
   */
  @NotNull 
  @Schema(name = "type", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("type")
  public TypeEnum getType() {
    return type;
  }

  @JsonProperty("type")
  public void setType(TypeEnum type) {
    this.type = type;
  }

  public GitletObject content(byte[] content) {
    this.content = content;
    return this;
  }

  /**
   * *临时* base64 编码的原始字节。 注意：commit 是 Java 序列化对象（serialVersionUID 1L）， 不是文本，服务端必须原样落盘不能重新序列化，否则 sha1 会变。 
   * @return content
   */
  @NotNull 
  @Schema(name = "content", description = "*临时* base64 编码的原始字节。 注意：commit 是 Java 序列化对象（serialVersionUID 1L）， 不是文本，服务端必须原样落盘不能重新序列化，否则 sha1 会变。 ", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("content")
  public byte[] getContent() {
    return content;
  }

  @JsonProperty("content")
  public void setContent(byte[] content) {
    this.content = content;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    GitletObject gitletObject = (GitletObject) o;
    return Objects.equals(this.sha1, gitletObject.sha1) &&
        Objects.equals(this.type, gitletObject.type) &&
        Arrays.equals(this.content, gitletObject.content);
  }

  @Override
  public int hashCode() {
    return Objects.hash(sha1, type, Arrays.hashCode(content));
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class GitletObject {\n");
    sb.append("    sha1: ").append(toIndentedString(sha1)).append("\n");
    sb.append("    type: ").append(toIndentedString(type)).append("\n");
    sb.append("    content: ").append(toIndentedString(content)).append("\n");
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

