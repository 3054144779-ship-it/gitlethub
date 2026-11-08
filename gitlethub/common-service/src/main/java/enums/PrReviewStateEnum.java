package enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * PR 评审状态
 */
public enum PrReviewStateEnum {
    COMMENTED(0, "仅评论"),
    APPROVED(1, "通过"),
    CHANGES_REQUESTED(2, "请求修改");

    @EnumValue
    private final int value;
    private final String desc;

    PrReviewStateEnum(int value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    /** HTTP 参数（数字）转枚举，null 或未知码返回 null */
    public static PrReviewStateEnum fromValue(Integer value) {
        if (value == null) {
            return null;
        }
        for (PrReviewStateEnum state : values()) {
            if (state.value == value) {
                return state;
            }
        }
        return null;
    }

    public int getValue() {
        return value;
    }

    public String getDesc() {
        return desc;
    }
}
