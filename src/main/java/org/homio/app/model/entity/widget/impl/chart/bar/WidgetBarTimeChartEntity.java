package org.homio.app.model.entity.widget.impl.chart.bar;

import jakarta.persistence.Entity;
import org.homio.app.model.entity.widget.UIFieldJSONLine;
import org.homio.app.model.entity.widget.attributes.HasChartTimePeriod;
import org.homio.app.model.entity.widget.impl.chart.ChartBaseEntity;
import org.homio.app.model.entity.widget.impl.chart.HasAxis;
import org.homio.app.model.entity.widget.impl.chart.HasHorizontalLine;
import org.homio.app.model.entity.widget.impl.chart.HasMinMaxChartValue;
import org.homio.bundle.api.EntityContextWidget.BarChartType;
import org.homio.bundle.api.ui.field.UIField;
import org.homio.bundle.api.ui.field.UIFieldGroup;

@Entity
public class WidgetBarTimeChartEntity
    extends ChartBaseEntity<WidgetBarTimeChartEntity, WidgetBarTimeChartSeriesEntity>
    implements HasChartTimePeriod, HasMinMaxChartValue, HasHorizontalLine, HasAxis {

    public static final String PREFIX = "wgtbtc_";

    @UIField(order = 40)
    @UIFieldGroup("CHART_AXIS")
    public String getAxisLabel() {
        return getJsonData("al");
    }

    public WidgetBarTimeChartEntity setAxisLabel(String value) {
        setJsonData("al", value);
        return this;
    }

    @UIField(order = 10)
    @UIFieldGroup(value = "CHART_UI", order = 5, borderColor = "#673AB7")
    public BarChartType getDisplayType() {
        return getJsonDataEnum("displayType", BarChartType.Vertical);
    }

    public WidgetBarTimeChartEntity setDisplayType(BarChartType value) {
        setJsonData("displayType", value);
        return this;
    }

    @UIField(order = 12)
    @UIFieldGroup("CHART_UI")
    @UIFieldJSONLine(
        template = "{\"top\": number}, \"left\": number, \"bottom\": number, \"right\": number")
    public String getBarBorderWidth() {
        return getJsonData("bbw", "{\"top\": 1, \"left\": 1, \"bottom\": 1, \"right\": 1}");
    }

    public void setBarBorderWidth(String value) {
        setJsonData("bbw", value);
    }

    @Override
    public String getImage() {
        return "fas fa-chart-bar";
    }

    @Override
    public String getEntityPrefix() {
        return PREFIX;
    }

    @Override
    public String getDefaultName() {
        return null;
    }
}
