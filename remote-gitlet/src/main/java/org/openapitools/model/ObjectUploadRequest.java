package org.openapitools.model;

import java.net.URI;
import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.openapitools.model.GitletObject;
import org.springframework.lang.Nullable;
import org.openapitools.jackson.nullable.JsonNullable;
import java.time.OffsetDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;


import java.util.*;
import jakarta.annotation.Generated;

/**
 * ObjectUploadRequest
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2026-09-21T20:19:28.153831200+08:00[Asia/Shanghai]", comments = "Generator version: 7.25.0")
public class ObjectUploadRequest {

  private List<@Valid GitletObject> objects = new ArrayList<>();

  public ObjectUploadRequest() {
    super();
  }

  /**
   * Constructor with only required parameters
   */
  public ObjectUploadRequest(List<@Valid GitletObject> objects) {
    this.objects = objects;
  }

  public ObjectUploadRequest objects(List<@Valid GitletObject> objects) {
    this.objects = objects;
    return this;
  }

  public ObjectUploadRequest addObjectsItem(GitletObject objectsItem) {
    if (this.objects == null) {
      this.objects = new ArrayList<>();
    }
    this.objects.add(objectsItem);
    return this;
  }

  /**
   * Get objects
   * @return objects
   */
  @NotNull @Valid @Size(max = 500) 
  @Schema(name = "objects", requiredMode = Schema.RequiredMode.REQUIRED)
  @JsonProperty("objects")
  public List<@Valid GitletObject> getObjects() {
    return objects;
  }

  @JsonProperty("objects")
  public void setObjects(List<@Valid GitletObject> objects) {
    this.objects = objects;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ObjectUploadRequest objectUploadRequest = (ObjectUploadRequest) o;
    return Objects.equals(this.objects, objectUploadRequest.objects);
  }

  @Override
  public int hashCode() {
    return Objects.hash(objects);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class ObjectUploadRequest {\n");
    sb.append("    objects: ").append(toIndentedString(objects)).append("\n");
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

