package run.halo.propertyfee;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * 小区配置表：小区名称 + 楼栋列表。
 * 房屋录入时小区/楼栋改为从配置中选择，保证数据准确、口径统一。
 *
 * @author property-fee
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "propertyfee.halo.run", version = "v1alpha1", kind = "Community",
    plural = "communities", singular = "community")
public class Community extends AbstractExtension {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private CommunitySpec spec;

    @Data
    public static class CommunitySpec {

        /** 小区名称（唯一，房屋/标准/商户按此关联）。 */
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private String name;

        /** 楼栋列表，如 [1栋, 2栋, A2栋]。 */
        private List<String> buildings = new ArrayList<>();

        /** 是否启用（停用小区不再出现在下拉、不允许新增房屋）。 */
        private Boolean enabled = true;

        /** 排序（小在前）。 */
        private Integer sortOrder = 0;

        /** 备注。 */
        private String remark;
    }
}
