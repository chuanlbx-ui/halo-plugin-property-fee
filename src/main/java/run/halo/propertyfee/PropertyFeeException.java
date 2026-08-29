package run.halo.propertyfee;

/**
 * 物业费业务异常。统一转 400 返回。
 *
 * @author property-fee
 */
public class PropertyFeeException extends RuntimeException {

    public PropertyFeeException(String message) {
        super(message);
    }
}
