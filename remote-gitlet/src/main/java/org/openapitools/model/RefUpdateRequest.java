package org.openapitools.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
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
 * RefUpdateRequest
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2026-09-21T20:19:28.153831200+08:00[Asia/Shanghai]", comments = "Generator version: 7.25.0")
public class RefUpdateRequest {

  private String sha;

  private JsonNullable<@Pattern(regexp = "^[0-9a-f]{40}$") String> expectedOldSha = JsonNullable.<String>undefined();

  public RefUpdateRequest() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public RefUpdateRequest(String sha) {
    this.sha = sha;
  }

  public RefUpdateRequest sha(String sha) {
    this.sha = sha;
    return this;
  }

  /**
   * 目标 commit，服务端必须已有该对象
   * @return sha
   */
  @NotNull @Pattern(regexp = "^[0-9a-f]{40}$") 
  @Schema(name = "sha", description = "目标 commit，服务端必须已有该对象", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("sha")
  public String getSha() {
    return sha;
  }

  @JsonProperty("sha")
  public void setSha(String sha) {
    this.sha = sha;
  }

  public RefUpdateRequest expectedOldSha(String expectedOldSha) {
    this.expectedOldSha = JsonNullable.of(expectedOldSha);
    return this;
  }

  /**
   * *临时* 乐观锁。服务端当前指向不等于它时返回 409。 新建分支时传空。 
   * @return expectedOldSha
   */
  @Pattern(regexp = "^[0-9a-f]{40}$") 
  @Schema(name = "expectedOldSha", description = "*临时* 乐观锁。服务端当前指向不等于它时返回 409。 新建分支时传空。 ", requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true)
  @JsonProperty("expectedOldSha")
  public JsonNullable<@Pattern(regexp = "^[0-9a-f]{40}$") String> getExpectedOldSha() {
    return expectedOldSha;
  }

  public void setExpectedOldSha(JsonNullable<String> expectedOldSha) {
    this.expectedOldSha = expectedOldSha;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    RefUpdateRequest refUpdateRequest = (RefUpdateRequest) o;
    return Objects.equals(this.sha, refUpdateRequest.sha) &&
        equalsNullable(this.expectedOldSha, refUpdateRequest.expectedOldSha);
  }

  private static <T> boolean equalsNullable(JsonNullable<T> a, JsonNullable<T> b) {
    return a == b || (a != null && b != null && a.isPresent() && b.isPresent() && Objects.deepEquals(a.get(), b.get()));
  }

  @Override
  public int hashCode() {
    return Objects.hash(sha, hashCodeNullable(expectedOldSha));
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
    sb.append("class RefUpdateRequest {\n");
    sb.append("    sha: ").append(toIndentedString(sha)).append("\n");
    sb.append("    expectedOldSha: ").append(toIndentedString(expectedOldSha)).append("\n");
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

