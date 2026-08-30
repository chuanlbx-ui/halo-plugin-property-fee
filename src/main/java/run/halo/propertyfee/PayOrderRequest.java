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
    /** 支付方式：native / jsapi / alipay / offline。 */
    String payType,
    /** 支付渠道名称（对应 PaymentConfig 的 channelName，多渠道时区分；空则用默认渠道）。 */
    String payChannel,
    /** 微信内 JSAPI 支付时的 openid（非微信环境 native 可空）。 */
    String openid,
    /** 线下收款备注（offline 渠道时填写，如：现金/转账/收据号）。 */
    String remark
) {
}
