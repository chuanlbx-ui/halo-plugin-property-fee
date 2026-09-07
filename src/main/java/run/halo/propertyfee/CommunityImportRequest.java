package run.halo.propertyfee;

import java.util.List;

/**
 * 小区/楼栋 Excel 批量导入请求（控制台 API）。
 * 每行 = 小区 + 楼栋；同小区多行自动合并楼栋列表（去重）。
 *
 * @author property-fee
 */
public record CommunityImportRequest(List<ImportRow> rows) {

    /**
     * 单行导入数据（对应 Excel 一行：小区名、楼栋）。
     */
    public record ImportRow(
        String community,
        String building
    ) {
    }
}
