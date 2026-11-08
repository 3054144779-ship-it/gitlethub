package org.openapitools.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.lang.Nullable;
import org.openapitools.jackson.nullable.JsonNullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * ObjectUploadResult
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2026-09-21T20:19:28.153831200+08:00[Asia/Shanghai]", comments = "Generator version: 7.25.0")
public class ObjectUploadResult {

  private List<String> received = new ArrayList<>();

  private List<String> missing = new ArrayList<>();

  public ObjectUploadResult() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public ObjectUploadResult(List<String> received) {
    this.received = received;
  }

  public ObjectUploadResult received(List<String> received) {
    this.received = received;
    return this;
  }

  public ObjectUploadResult addReceivedItem(String receivedItem) {
    if (this.received == null) {
      this.received = new ArrayList<>();
    }
    this.received.add(receivedItem);
    return this;
  }

  /**
   * 服务端已确认接收的 sha1
   * @return received
   */
  @NotNull 
  @Schema(name = "received", description = "服务端已确认接收的 sha1", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("received")
  public List<String> getReceived() {
    return received;
  }

  @JsonProperty("received")
  public void setReceived(List<String> received) {
    this.received = received;
  }

  public ObjectUploadResult missing(List<String> missing) {
    this.missing = missing;
    return this;
  }

  public ObjectUploadResult addMissingItem(String missingItem) {
    if (this.missing == null) {
      this.missing = new ArrayList<>();
    }
    this.missing.add(missingItem);
    return this;
  }

  /**
   * 服务端仍缺失的 sha1，客户端据此重传
   * @return missing
   */
  
  @Schema(name = "missing", description = "服务端仍缺失的 sha1，客户端据此重传", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
  @JsonProperty("missing")
  public List<String> getMissing() {
    return missing;
  }

  @JsonProperty("missing")
  public void setMissing(List<String> missing) {
    this.missing = missing;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ObjectUploadResult objectUploadResult = (ObjectUploadResult) o;
    return Objects.equals(this.received, objectUploadResult.received) &&
        Objects.equals(this.missing, objectUploadResult.missing);
  }

  @Override
  public int hashCode() {
    return Objects.hash(received, missing);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class ObjectUploadResult {\n");
    sb.append("    received: ").append(toIndentedString(received)).append("\n");
    sb.append("    missing: ").append(toIndentedString(missing)).append("\n");
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

