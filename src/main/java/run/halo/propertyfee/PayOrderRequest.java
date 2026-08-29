package run.halo.propertyfee;

/**
 * 创建支付订单请求。
 *
 * @author property-fee
 */
public record PayOrderRequest(
    /** 小区名称。 */
    String community,
    /** 楼栋号。 */
    String building,
    /** 房号。 */
    String room,
    /** 缴费年份。 */
    Integer year,
    /** 支付方式：native / jsapi。 */
    String payType,
    /** 微信内 JSAPI 支付时的 openid（非微信环境 native 可空）。 */
    String openid
) {
}
