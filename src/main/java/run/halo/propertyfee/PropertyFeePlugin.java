package run.halo.propertyfee;

import org.springframework.stereotype.Component;
import run.halo.app.extension.SchemeManager;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

/**
 * 物业费收缴管理插件入口。启动时注册 4 个自定义模型 Scheme。
 *
 * @author property-fee
 */
@Component
public class PropertyFeePlugin extends BasePlugin {

    private final SchemeManager schemeManager;

    public PropertyFeePlugin(PluginContext pluginContext, SchemeManager schemeManager) {
        super(pluginContext);
        this.schemeManager = schemeManager;
    }

    @Override
    public void start() {
        schemeManager.register(Property.class);
        schemeManager.register(FeeStandard.class);
        schemeManager.register(FeeRecord.class);
        schemeManager.register(PaymentConfig.class);
        System.out.println("[property-fee-plugin] 物业费插件启动成功，已注册 Property/FeeStandard/FeeRecord/PaymentConfig Scheme");
    }

    @Override
    public void stop() {
        schemeManager.unregister(schemeManager.get(Property.class));
        schemeManager.unregister(schemeManager.get(FeeStandard.class));
        schemeManager.unregister(schemeManager.get(FeeRecord.class));
        schemeManager.unregister(schemeManager.get(PaymentConfig.class));
        System.out.println("[property-fee-plugin] 物业费插件已停止");
    }
}
