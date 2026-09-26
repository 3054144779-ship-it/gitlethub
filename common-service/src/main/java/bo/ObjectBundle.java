package bo;

import java.util.Objects;
import com.fasterxml.jackson.annotation.JsonProperty;

import bo.GitletObject;
import java.util.ArrayList;
import java.util.List;
import org.springframework.lang.Nullable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import io.swagger.v3.oas.annotations.media.Schema;


import jakarta.annotation.Generated;

/**
 * ObjectBundle
 */

@Generated(value = "org.openapitools.codegen.languages.SpringCodegen", date = "2026-09-21T20:19:28.153831200+08:00[Asia/Shanghai]", comments = "Generator version: 7.25.0")
public class ObjectBundle {

  private List<@Valid GitletObject> objects = new ArrayList<>();

  public ObjectBundle() {
    super();
  }

  /**
   * 仅含必填参数的构造函数
   */
  public ObjectBundle(List<@Valid GitletObject> objects) {
    this.objects = objects;
  }

  public ObjectBundle objects(List<@Valid GitletObject> objects) {
    this.objects = objects;
    return this;
  }

  public ObjectBundle addObjectsItem(GitletObject objectsItem) {
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
  @NotNull @Valid 
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
    ObjectBundle objectBundle = (ObjectBundle) o;
    return Objects.equals(this.objects, objectBundle.objects);
  }

  @Override
  public int hashCode() {
    return Objects.hash(objects);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class ObjectBundle {\n");
    sb.append("    objects: ").append(toIndentedString(objects)).append("\n");
    sb.append("}");
    return sb.toString();
  }

  /**
* 将给定的对象转换为字符串，每行缩进4个空格
   * （第一行除外）。
   */
  private String toIndentedString(@Nullable Object o) {
    return o == null ? "null" : o.toString().replace("\n", "\n    ");
  }
}

