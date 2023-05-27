package org.homio.app.setting.system.auth;

import org.homio.api.setting.SettingPluginSlider;
import org.homio.app.setting.CoreSettingPlugin;

public class SystemJWTTokenValidSetting implements CoreSettingPlugin<Integer>, SettingPluginSlider {

    @Override
    public GroupKey getGroupKey() {
        return GroupKey.system;
    }

    @Override
    public String getSubGroupKey() {
        return "AUTH";
    }

    @Override
    public int defaultValue() {
        return 30;
    }

    @Override
    public Integer getMin() {
        return 30;
    }

    @Override
    public Integer getMax() {
        return 1440;
    }

    @Override
    public String getHeader() {
        return "min";
    }

    @Override
    public int order() {
        return 400;
    }
}
