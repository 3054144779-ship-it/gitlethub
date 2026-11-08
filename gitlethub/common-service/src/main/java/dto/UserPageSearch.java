package dto;

import lombok.Data;

@Data
public class UserPageSearch {
    private String username;
    private Long pageNum = 1L;
    private Long pageSize = 10L;
}
