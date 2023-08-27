package org.homio.app.model.entity.widget.impl.chart.pie;

import jakarta.persistence.Entity;
import org.homio.api.ui.UI;
import org.homio.api.ui.field.*;
import org.homio.app.model.entity.widget.WidgetSeriesEntity;
import org.homio.app.model.entity.widget.attributes.HasSingleValueDataSource;
import org.homio.app.model.entity.widget.attributes.HasValueConverter;

@Entity
public class WidgetPieChartSeriesEntity extends WidgetSeriesEntity<WidgetPieChartEntity>
        implements HasSingleValueDataSource, HasValueConverter {

    @UIField(order = 20, isRevert = true)
    @UIFieldGroup(order = 54, value = "CHART_UI", borderColor = "#673AB7")
    @UIFieldColorPicker
    @UIFieldReadDefaultValue
    public String getChartColor() {
        return getJsonData("chartC", UI.Color.WHITE);
    }

    public WidgetPieChartSeriesEntity setChartColor(String value) {
        setJsonData("chartC", value);
        return this;
    }

    @UIField(order = 21)
    @UIFieldSlider(min = 0, max = 100, step = 5)
    @UIFieldGroup("CHART_UI")
    public int getChartColorOpacity() {
        return getJsonData("chartCO", 50);
    }

    public void setChartColorOpacity(int value) {
        setJsonData("chartCO", value);
    }

    @Override
    protected String getSeriesPrefix() {
        return "pie";
    }

    @Override
    public String getDefaultName() {
        return null;
    }

    @Override
    public void beforePersist() {
        if (!getJsonData().has("chartC")) {
            setChartColor(UI.Color.random());
        }
    }
}
