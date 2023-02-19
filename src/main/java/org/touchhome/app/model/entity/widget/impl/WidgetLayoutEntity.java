package org.touchhome.app.model.entity.widget.impl;

import java.util.Set;
import javax.persistence.Entity;
import org.touchhome.app.model.entity.widget.WidgetBaseEntity;
import org.touchhome.app.model.entity.widget.attributes.HasLayout;
import org.touchhome.app.setting.dashboard.WidgetBorderColorMenuSetting;
import org.touchhome.bundle.api.EntityContext;
import org.touchhome.bundle.api.entity.BaseEntity;
import org.touchhome.bundle.api.ui.field.UIField;
import org.touchhome.bundle.api.ui.field.UIFieldColorPicker;
import org.touchhome.bundle.api.ui.field.UIFieldGroup;
import org.touchhome.bundle.api.ui.field.UIFieldReadDefaultValue;
import org.touchhome.bundle.api.ui.field.UIFieldTableLayout;
import org.touchhome.bundle.api.ui.field.condition.UIFieldDisableEditOnCondition;

@Entity
public class WidgetLayoutEntity extends WidgetBaseEntity<WidgetLayoutEntity>
    implements HasLayout {

    public static final String PREFIX = "wgtcmp_";

    @Override
    public String getImage() {
        return "fas fa-layer-group";
    }

    @Override
    public String getEntityPrefix() {
        return PREFIX;
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

    @UIField(order = 31, showInContextMenu = true, icon = "fas fa-border-top-left")
    public Boolean isShowWidgetBorders() {
        return getJsonData("swb", Boolean.FALSE);
    }

    public void setShowWidgetBorders(Boolean value) {
        setJsonData("swb", value);
    }

    @Override
    protected void beforePersist() {
        if (!getJsonData().has("bw")) {
            setBw(2);
        }
        super.beforePersist();
    }

    @Override
    public void afterDelete(EntityContext entityContext) {
        for (WidgetBaseEntity entity : getEntityContext().findAll(WidgetBaseEntity.class)) {
            if (getEntityID().equals(entity.getParent())) {
                entityContext.delete(entity);
            }
        }
    }

    @Override
    public void getAllRelatedEntities(Set<BaseEntity> set) {
        for (WidgetBaseEntity entity : getEntityContext().findAll(WidgetBaseEntity.class)) {
            if (getEntityID().equals(entity.getParent())) {
                set.add(entity);
            }
        }
    }
}
