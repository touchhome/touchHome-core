package org.touchhome.app.model.entity.widget.impl;

import org.touchhome.bundle.api.entity.HasJsonData;
import org.touchhome.bundle.api.ui.field.UIField;
import org.touchhome.bundle.api.ui.field.UIFieldGroup;

public interface HasSourceServerUpdates extends HasJsonData {

    @UIField(order = 2)
    @UIFieldGroup("Update")
    default Boolean getListenSourceUpdates() {
        return getJsonData("lsu", Boolean.TRUE);
    }

    default void setListenSourceUpdates(Boolean value) {
        setJsonData("lsu", value);
    }

    @UIField(order = 10)
    @UIFieldGroup("Update")
    default Boolean getShowLastUpdateTimer() {
        return getJsonData("slut", Boolean.FALSE);
    }

    default void setShowLastUpdateTimer(Boolean value) {
        setJsonData("slut", value);
    }
}
