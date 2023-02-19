package org.touchhome.app.builder.widget;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.touchhome.app.builder.widget.hasBuilder.HasIconColorThresholdBuilder;
import org.touchhome.app.builder.widget.hasBuilder.HasNameBuilder;
import org.touchhome.app.builder.widget.hasBuilder.HasPaddingBuilder;
import org.touchhome.app.builder.widget.hasBuilder.HasToggleBuilder;
import org.touchhome.app.manager.common.EntityContextImpl;
import org.touchhome.app.model.entity.widget.impl.toggle.WidgetToggleEntity;
import org.touchhome.app.model.entity.widget.impl.toggle.WidgetToggleSeriesEntity;
import org.touchhome.bundle.api.EntityContextWidget.ToggleType;
import org.touchhome.bundle.api.EntityContextWidget.ToggleWidgetBuilder;
import org.touchhome.bundle.api.EntityContextWidget.ToggleWidgetSeriesBuilder;

public class ToggleBuilderImpl extends WidgetBaseBuilderImpl<ToggleWidgetBuilder, WidgetToggleEntity>
    implements ToggleWidgetBuilder,
    HasPaddingBuilder<WidgetToggleEntity, ToggleWidgetBuilder>,
    HasNameBuilder<WidgetToggleEntity, ToggleWidgetBuilder> {

    @Getter
    private final List<WidgetToggleSeriesEntity> series = new ArrayList<>();

    ToggleBuilderImpl(WidgetToggleEntity widget, EntityContextImpl entityContext) {
        super(widget, entityContext);
    }

    @Override
    public ToggleWidgetBuilder setShowAllButton(Boolean value) {
        widget.setShowAllButton(value);
        return this;
    }

    @Override
    public ToggleWidgetBuilder setDisplayType(ToggleType value) {
        widget.setDisplayType(value);
        return this;
    }

    @Override
    public ToggleWidgetBuilder addSeries(@Nullable String name, @NotNull Consumer<ToggleWidgetSeriesBuilder> builder) {
        WidgetToggleSeriesEntity entity = new WidgetToggleSeriesEntity();
        entity.setName(name);
        series.add(entity);
        ToggleSeriesBuilderImpl seriesBuilder = new ToggleSeriesBuilderImpl(entity);
        builder.accept(seriesBuilder);
        return this;
    }

    @Override
    public ToggleWidgetBuilder setLayout(String value) {
        widget.setLayout(value);
        return this;
    }

    @Override
    public ToggleWidgetBuilder setListenSourceUpdates(@Nullable Boolean value) {
        widget.setListenSourceUpdates(value);
        return this;
    }

    @Override
    public ToggleWidgetBuilder setShowLastUpdateTimer(@Nullable Boolean value) {
        widget.setShowLastUpdateTimer(value);
        return this;
    }
}

@RequiredArgsConstructor
class ToggleSeriesBuilderImpl implements ToggleWidgetSeriesBuilder,
    HasToggleBuilder<WidgetToggleSeriesEntity, ToggleWidgetSeriesBuilder>,
    HasIconColorThresholdBuilder<WidgetToggleSeriesEntity, ToggleWidgetSeriesBuilder>,
    HasNameBuilder<WidgetToggleSeriesEntity, ToggleWidgetSeriesBuilder> {

    private final WidgetToggleSeriesEntity series;

    @Override
    public WidgetToggleSeriesEntity getWidget() {
        return series;
    }

    @Override
    public ToggleWidgetSeriesBuilder setValueDataSource(String value) {
        series.setValueDataSource(value);
        return this;
    }

    @Override
    public ToggleWidgetSeriesBuilder setSetValueDataSource(String value) {
        series.setSetValueDataSource(value);
        return this;
    }
}
