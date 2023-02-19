package org.touchhome.app.builder.widget.hasBuilder;

import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;
import org.touchhome.app.builder.widget.ThresholdBuilderImpl;
import org.touchhome.bundle.api.EntityContextWidget.HasIcon;
import org.touchhome.bundle.api.EntityContextWidget.ThresholdBuilder;
import org.touchhome.bundle.api.entity.BaseEntity;

public interface HasIconColorThresholdBuilder<T extends BaseEntity & org.touchhome.app.model.entity.widget.attributes.HasIcon, R>
    extends HasIcon<R> {

    T getWidget();

    @Override
    default R setIcon(@Nullable String icon, @Nullable Consumer<ThresholdBuilder> iconBuilder) {
        if (iconBuilder == null) {
            getWidget().setIcon(icon);
        } else {
            ThresholdBuilderImpl builder = new ThresholdBuilderImpl(icon);
            iconBuilder.accept(builder);
            getWidget().setIcon(builder.build());
        }
        return (R) this;
    }

    @Override
    default R setIconColor(@Nullable String color, @Nullable Consumer<ThresholdBuilder> colorBuilder) {
        if (colorBuilder == null) {
            getWidget().setIconColor(color);
        } else {
            ThresholdBuilderImpl builder = new ThresholdBuilderImpl(color);
            colorBuilder.accept(builder);
            getWidget().setIconColor(builder.build());
        }
        return (R) this;
    }
}
