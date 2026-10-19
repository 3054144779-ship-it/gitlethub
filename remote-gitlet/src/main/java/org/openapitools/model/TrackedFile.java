package org.openapitools.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 *  "tracked_files": [
 *      {
 *          "file_path": "src/OrderService.java",
 *          "status": "MODIFIED"
 *      }
 *  ]
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrackedFile {
    private String filePath;
    private String status;
}
