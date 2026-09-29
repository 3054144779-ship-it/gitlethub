package enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.Getter;

/**
 * PR状态枚举
 */
public enum PRStatusEnum {
    OPEN(0, "open"),
    CLOSED(1, "closed"),
    MERGED(2, "merged");

    @EnumValue
    private Integer value;
    private String desc;

    PRStatusEnum(Integer value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    public Integer getValue() {
        return value;
    }

    public String getDesc() {
        return desc;
    }
}
