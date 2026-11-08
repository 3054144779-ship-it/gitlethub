package enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * PR 评论类型：1=整体评论，2=行级评论
 */
public enum PrCommentTypeEnum {
    GENERAL(1, "整体评论"),
    INLINE(2, "行级评论");

    @EnumValue
    private final int value;
    private final String desc;

    PrCommentTypeEnum(int value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    /** HTTP 参数（数字）转枚举，null 表示不筛选 */
    public static PrCommentTypeEnum fromValue(Integer value) {
        if (value == null) {
            return null;
        }
        for (PrCommentTypeEnum type : values()) {
            if (type.value == value) {
                return type;
            }
        }
        return null;   // 或抛业务异常，见下
    }

    public int getValue() {
        return value;
    }

    public String getDesc() {
        return desc;
    }
}