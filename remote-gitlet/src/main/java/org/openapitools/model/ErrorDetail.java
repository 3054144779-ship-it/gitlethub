package org.openapitools.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import org.springframework.lang.Nullable;
import org.openapitools.jackson.nullable.JsonNullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * 通用错误详情，放在 &#x60;data&#x60; 里
 */

@Schema(name = "ErrorDetail", description = "通用错误详情，放在 `data` 里")
@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2026-09-21T20:19:28.153831200+08:00[Asia/Shanghai]", comments = "Generator version: 7.25.0")
public class ErrorDetail {

  private @Nullable String currentSha;

  private @Nullable String missingSha;

  public ErrorDetail currentSha(@Nullable String currentSha) {
    this.currentSha = currentSha;
    return this;
  }

  /**
   * 仅 code 50001（REF_CONFLICT）时出现，服务端当前指向
   * @return currentSha
   */
  
  @Schema(name = "currentSha", description = "仅 code 50001（REF_CONFLICT）时出现，服务端当前指向", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("currentSha")
  public @Nullable String getCurrentSha() {
    return currentSha;
  }

  @JsonProperty("currentSha")
  public void setCurrentSha(@Nullable String currentSha) {
    this.currentSha = currentSha;
  }

  public ErrorDetail missingSha(@Nullable String missingSha) {
    this.missingSha = missingSha;
    return this;
  }

  /**
   * 仅 code 10003（OBJECT_MISSING）时出现，缺失的对象
   * @return missingSha
   */
  
  @Schema(name = "missingSha", description = "仅 code 10003（OBJECT_MISSING）时出现，缺失的对象", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("missingSha")
  public @Nullable String getMissingSha() {
    return missingSha;
  }

  @JsonProperty("missingSha")
  public void setMissingSha(@Nullable String missingSha) {
    this.missingSha = missingSha;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ErrorDetail errorDetail = (ErrorDetail) o;
    return Objects.equals(this.currentSha, errorDetail.currentSha) &&
        Objects.equals(this.missingSha, errorDetail.missingSha);
  }

  @Override
  public int hashCode() {
    return Objects.hash(currentSha, missingSha);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class ErrorDetail {\n");
    sb.append("    currentSha: ").append(toIndentedString(currentSha)).append("\n");
    sb.append("    missingSha: ").append(toIndentedString(missingSha)).append("\n");
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

