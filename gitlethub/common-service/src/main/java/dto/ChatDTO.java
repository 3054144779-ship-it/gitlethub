package dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
public class ChatDTO {
    @JsonProperty("id")
    private Long pr_id;
    private String session_id;
    private String query;
}
