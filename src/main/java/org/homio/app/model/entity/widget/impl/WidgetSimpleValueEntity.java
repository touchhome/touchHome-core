package org.homio.app.model.entity.widget.impl;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Entity;
import org.homio.api.exception.ProhibitedExecution;
import org.homio.api.ui.field.UIFieldIgnore;
import org.homio.app.model.entity.widget.WidgetBaseEntity;
import org.homio.app.model.entity.widget.attributes.*;
import org.jetbrains.annotations.NotNull;

@Entity
public class WidgetSimpleValueEntity extends WidgetBaseEntity<WidgetSimpleValueEntity>
        implements
        HasIcon,
        HasActionOnClick,
        HasSingleValueAggregatedDataSource,
        HasValueTemplate,
        HasPadding,
        HasAlign,
        HasValueConverter,
        HasSourceServerUpdates {

    @Override
    public @NotNull String getImage() {
        return "";
    }

    @Override
    public boolean isVisible() {
        return false;
    }

    @Override
    protected @NotNull String getWidgetPrefix() {
        return "sim-value";
    }

    @Override
    public String getDefaultName() {
        return null;
    }

    @Override
    @UIFieldIgnore
    @JsonIgnore
    public Boolean getShowLastUpdateTimer() {
        throw new ProhibitedExecution();
    }
}
