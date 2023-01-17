package org.touchhome.app.model.entity;

import javax.persistence.DiscriminatorValue;
import javax.persistence.Entity;
import org.hibernate.mapping.PersistentClass;
import org.touchhome.bundle.api.entity.DeviceBaseEntity;
import org.touchhome.bundle.api.ui.UISidebarChildren;

@Entity
@DiscriminatorValue(PersistentClass.NOT_NULL_DISCRIMINATOR_MAPPING)
@UISidebarChildren(icon = "", color = "", allowCreateItem = false)
public class DeviceFallbackEntity extends DeviceBaseEntity<DeviceFallbackEntity> {

    @Override
    public String getEntityPrefix() {
        return "misc_fallback";
    }

    @Override
    public String getDefaultName() {
        return null;
    }
}
