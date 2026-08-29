package run.halo.propertyfee;

import java.util.List;

/**
 * Excel 批量导入请求（由控制台 API 使用）。
 *
 * @author property-fee
 */
public record PropertyImportRequest(List<ImportRow> rows) {

    /**
     * 单行导入数据（对应 Excel 一行）。
     */
    public record ImportRow(
        String community,
        String building,
        String unit,
        String room,
        Double area,
        String ownerName,
        String ownerPhone
    ) {
    }
}
