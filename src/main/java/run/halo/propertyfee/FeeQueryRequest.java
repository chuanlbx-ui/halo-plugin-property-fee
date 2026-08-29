package run.halo.propertyfee;

/**
 * 查费请求：小区 + 楼栋 + 房号 + 年份。
 *
 * @author property-fee
 */
public record FeeQueryRequest(String community, String building, String room, Integer year) {
}
