package enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * AI 审查记录状态
 */
public enum AiReviewStatusEnum {
    RUNNING(0, "进行中"),
    SUCCESS(1, "成功"),
    FAILED(2, "失败"),
    CANCELLED(3, "已取消");

    @EnumValue
    private final int value;
    private final String desc;

    AiReviewStatusEnum(int value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    public int getValue() {
        return value;
    }

    public String getDesc() {
        return desc;
    }
}
