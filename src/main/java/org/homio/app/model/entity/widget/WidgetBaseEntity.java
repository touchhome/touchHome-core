package org.homio.app.model.entity.widget;

import static org.apache.commons.lang3.StringUtils.isEmpty;
import static org.apache.commons.lang3.StringUtils.isNotEmpty;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.ManyToOne;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.apache.commons.lang3.tuple.Pair;
import org.homio.api.converter.JSONConverter;
import org.homio.api.entity.BaseEntity;
import org.homio.api.entity.HasJsonData;
import org.homio.api.model.JSON;
import org.homio.api.ui.UISidebarMenu;
import org.homio.api.ui.field.UIField;
import org.homio.api.ui.field.UIFieldColorPicker;
import org.homio.api.ui.field.UIFieldGroup;
import org.homio.api.ui.field.UIFieldIgnore;
import org.homio.api.ui.field.UIFieldReadDefaultValue;
import org.homio.api.ui.field.UIFieldSlider;
import org.homio.app.manager.common.ContextImpl;
import org.homio.app.model.entity.widget.attributes.HasPosition;
import org.homio.app.model.entity.widget.attributes.HasStyle;
import org.jetbrains.annotations.NotNull;

@Getter
@Setter
@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@UISidebarMenu(icon = "fas fa-tachometer-alt", bg = "#107d6b", overridePath = "widgets")
@Accessors(chain = true)
@NoArgsConstructor
public abstract class WidgetBaseEntity<T extends WidgetBaseEntity> extends BaseEntity
        implements HasPosition<WidgetBaseEntity>, HasStyle, HasJsonData {

    private static final String PREFIX = "widget_";

    @Override
    public final @NotNull String getEntityPrefix() {
        return PREFIX + getWidgetPrefix() + "_";
    }

    protected abstract @NotNull String getWidgetPrefix();

    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    private WidgetTabEntity widgetTabEntity;

    @Column(length = 65535)
    @Convert(converter = JSONConverter.class)
    private JSON jsonData = new JSON();

    /**
     * Uses for grouping widget by type on UI
     */
    public WidgetGroup getGroup() {
        return null;
    }

    public String getFieldFetchType() {
        return getJsonData("fieldFetchType", (String) null);
    }

    public T setFieldFetchType(String value) {
        jsonData.put("fieldFetchType", value);
        return (T) this;
    }

    @Override
    @UIFieldIgnore
    public String getName() {
        return super.getName();
    }

    public abstract @NotNull String getImage();

    public boolean isVisible() {
        return true;
    }

    @Override
    public void beforePersist() {
        super.beforePersist();
        this.findSuitablePosition();
    }

    @Override
    public @NotNull String getDynamicUpdateType() {
        return "widget";
    }

    @Override
    public void afterUpdate() {
        super.afterUpdate();
        ((ContextImpl) context()).event().removeEvents(getEntityID());
    }

    /**
     * Check if matrix has free slot for specific width/height and return first available position
     */
    private static Pair<Integer, Integer> findMatrixFreePosition(boolean[][] matrix, int bw, int bh, int hBlockCount, int vBlockCount) {
        for (int j = 0; j < hBlockCount; j++) {
            for (int i = 0; i < vBlockCount; i++) {
                if (isSatisfyPosition(matrix, i, j, bw, bh, hBlockCount, vBlockCount)) {
                    return Pair.of(i, j);
                }
            }
        }
        return null;
    }

    private static boolean isSatisfyPosition(boolean[][] matrix, int xPos, int yPos, int width, int height, int hBlockCount, int vBlockCount) {
        for (int j = xPos; j < xPos + width; j++) {
            for (int i = yPos; i < yPos + height; i++) {
                if (i >= vBlockCount || j >= hBlockCount || matrix[i][j]) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void initMatrix(List<WidgetBaseEntity> widgets, boolean[][] matrix) {
        for (WidgetBaseEntity model : widgets) {
            if (isEmpty(model.getParent())) {
                for (int j = model.getXb(); j < model.getXb() + model.getBw(); j++) {
                    for (int i = model.getYb(); i < model.getYb() + model.getBh(); i++) {
                        matrix[i][j] = true;
                    }
                }
            }
        }
    }

    @Override
    protected long getChildEntityHashCode() {
        return 0;
    }

    /**
     * Find free space in matrix for new item
     */
    private void findSuitablePosition() {
        List<WidgetBaseEntity> widgets = context().db().findAll(WidgetBaseEntity.class);
        if (isNotEmpty(getParent())) {
            WidgetBaseEntity layout = widgets.stream().filter(w -> w.getEntityID().equals(getParent())).findAny().orElse(null);
            if (layout == null) {
                throw new IllegalArgumentException("Widget: " + getTitle() + " has xbl/tbl and have to be belong to layout widget but it's not found");
            }
            // do not change position for widget which belong to layout
            return;
        }


        var hBlockCount = this.widgetTabEntity.getHorizontalBlocks();
        var vBlockCount = this.widgetTabEntity.getVerticalBlocks();
        boolean[][] matrix = new boolean[vBlockCount][hBlockCount];
        for (int j = 0; j < vBlockCount; j++) {
            matrix[j] = new boolean[hBlockCount];
        }
        initMatrix(widgets, matrix);
        if (!isSatisfyPosition(matrix, getXb(), getYb(), getBw(), getBh(), hBlockCount, vBlockCount)) {
            Pair<Integer, Integer> freePosition = findMatrixFreePosition(matrix, getBw(), getBh(), hBlockCount, vBlockCount);
            if (freePosition == null) {
                throw new IllegalStateException("W.ERROR.NO_WIDGET_FREE_POSITION");
            }
            setXb(freePosition.getKey());
            setYb(freePosition.getValue());
        }
    }
}
