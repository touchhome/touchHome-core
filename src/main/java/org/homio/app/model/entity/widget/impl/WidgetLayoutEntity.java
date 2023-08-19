package org.homio.app.model.entity.widget.impl;

import jakarta.persistence.Entity;
import org.homio.api.EntityContext;
import org.homio.api.ui.field.*;
import org.homio.api.ui.field.condition.UIFieldDisableEditOnCondition;
import org.homio.app.model.entity.widget.WidgetBaseEntity;
import org.homio.app.model.entity.widget.attributes.HasLayout;
import org.homio.app.setting.dashboard.WidgetBorderColorMenuSetting;
import org.jetbrains.annotations.NotNull;

@Entity
public class WidgetLayoutEntity extends WidgetBaseEntity<WidgetLayoutEntity>
        implements HasLayout {

    @Override
    public @NotNull String getImage() {
        return "fas fa-layer-group";
    }

    @Override
    protected @NotNull String getWidgetPrefix() {
        return "layout";
    }

    @UIField(order = 35, showInContextMenu = true, icon = "fas fa-table")
    @UIFieldTableLayout(maxRows = 30, maxColumns = 15)
    @UIFieldDisableEditOnCondition("return context.get('hasChildren')")
    public String getLayout() {
        return getJsonData("layout", "2x2");
    }

    @Override
    public String getDefaultName() {
        return null;
    }

    @UIField(order = 24, isRevert = true)
    @UIFieldGroup("UI")
    @UIFieldColorPicker
    @UIFieldReadDefaultValue
    public String getBorderColor() {
        return getJsonData("bc", getEntityContext().setting().getValue(WidgetBorderColorMenuSetting.class));
    }

    public void setBorderColor(String value) {
        setJsonData("bc", value);
    }

    @Override
    public void afterDelete(@NotNull EntityContext entityContext) {
        for (WidgetBaseEntity entity : getEntityContext().findAll(WidgetBaseEntity.class)) {
            if (getEntityID().equals(entity.getParent())) {
                entityContext.delete(entity);
            }
        }
    }

    @Override
    protected void beforePersist() {
        if (!getJsonData().has("bw")) {
            setBw(2);
        }
        if (!getJsonData().has("zi")) {
            setIndex(15);
        }
        super.beforePersist();
    }
}
