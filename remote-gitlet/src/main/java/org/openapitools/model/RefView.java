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
 * RefView
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2026-09-21T20:19:28.153831200+08:00[Asia/Shanghai]", comments = "Generator version: 7.25.0")
public class RefView {

  private String branch;

  private String sha;

  public RefView() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public RefView(String branch, String sha) {
    this.branch = branch;
    this.sha = sha;
  }

  public RefView branch(String branch) {
    this.branch = branch;
    return this;
  }

  /**
   * Get branch
   * @return branch
   */
  @NotNull 
  @Schema(name = "branch", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("branch")
  public String getBranch() {
    return branch;
  }

  @JsonProperty("branch")
  public void setBranch(String branch) {
    this.branch = branch;
  }

  public RefView sha(String sha) {
    this.sha = sha;
    return this;
  }

  /**
   * Get sha
   * @return sha
   */
  @NotNull @Pattern(regexp = "^[0-9a-f]{40}$") 
  @Schema(name = "sha", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("sha")
  public String getSha() {
    return sha;
  }

  @JsonProperty("sha")
  public void setSha(String sha) {
    this.sha = sha;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    RefView refView = (RefView) o;
    return Objects.equals(this.branch, refView.branch) &&
        Objects.equals(this.sha, refView.sha);
  }

  @Override
  public int hashCode() {
    return Objects.hash(branch, sha);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class RefView {\n");
    sb.append("    branch: ").append(toIndentedString(branch)).append("\n");
    sb.append("    sha: ").append(toIndentedString(sha)).append("\n");
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

