package enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

import java.util.stream.Collector;

/**
 * 协作者等级枚举类
 */
public enum CollaboratorLevelEnum {
    OWNER(2, "所有者"),
    CONTRIBUTOR(1, "贡献者"),
    GUEST(0, "访客");

    @EnumValue
    private final int code;
    private final String message;

    CollaboratorLevelEnum(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    /**
     * 是否是所有者
     * @return 是否是所有者
     */
    public boolean isOwner() {
        return this.code == 2;
    }

    /**
     * 是否是贡献者
     * @return 是否是贡献者
     */
    public boolean isContributor() {
        return this.code == 1;
    }

    /**
     * 是否是访客
     * @return 是否是访客
     */
    public boolean isGuest() {
        return this.code == 0;
    }

}
