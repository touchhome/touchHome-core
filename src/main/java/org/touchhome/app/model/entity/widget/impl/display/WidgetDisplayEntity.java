package org.touchhome.app.model.entity.widget.impl.display;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.touchhome.app.model.entity.widget.UIFieldJSONLine;
import org.touchhome.app.model.entity.widget.UIFieldLayout;
import org.touchhome.app.model.entity.widget.WidgetBaseEntityAndSeries;
import org.touchhome.app.model.entity.widget.impl.HasLineChartDataSource;
import org.touchhome.bundle.api.ui.TimePeriod;
import org.touchhome.bundle.api.ui.field.*;
import org.touchhome.bundle.api.ui.field.selection.dynamic.HasDynamicParameterFields;

import javax.persistence.Entity;

@Entity
public class WidgetDisplayEntity extends WidgetBaseEntityAndSeries<WidgetDisplayEntity, WidgetDisplaySeriesEntity>
        implements HasLineChartDataSource<WidgetDisplayEntity>, HasDynamicParameterFields<WidgetDisplayEntity> {

    public static final String PREFIX = "wgtdp_";

    @Override
    public String getImage() {
        return "fas fa-tv";
    }

    @Override
    public String getEntityPrefix() {
        return PREFIX;
    }

    @UIField(order = 50)
    @UIFieldLayout(options = {"name", "value", "icon"})
    public String getLayout() {
        return getJsonData("layout");
    }

    @Override
    @UIFieldColorPicker
    public String getBackground() {
        return super.getBackground();
    }

    @JsonIgnore
    @UIFieldIgnore
    public Boolean getFillMissingValues() {
        return true;
    }

    @JsonIgnore
    @UIFieldIgnore
    public String getChartLabel() {
        return "";
    }

    @Override
    @JsonIgnore
    @UIFieldIgnore
    public Boolean getShowTimeButtons() {
        throw new IllegalStateException("MNC");
    }

    @UIField(order = 2)
    @UIFieldSlider(min = 1, max = 72)
    @UIFieldGroup("Chart")
    public int getHoursToShow() {
        return getJsonData("hts", 1);
    }

    @UIField(order = 3)
    @UIFieldSlider(min = 1, max = 60)
    @UIFieldGroup("Chart")
    public int getPointsPerHour() {
        return getJsonData("pph", 30);
    }

    @UIField(order = 4)
    @UIFieldSlider(min = 20, max = 100)
    @UIFieldGroup("Chart ui")
    public int getChartHeight() {
        return getJsonData("ch", 30);
    }

    @UIField(order = 20)
    @UIFieldJSONLine(template = "{\"top\": number}, \"left\": number, \"bottom\": number, \"right\": number")
    @UIFieldGroup("Chart ui")
    @UIFieldShowOnCondition("eval(\"this.get('chartType') == 'bar'\")")
    public String getBarBorderWidth() {
        return getJsonData("bbw", "{\"top\": 0, \"left\": 0, \"bottom\": 0, \"right\": 0}");
    }

    @UIField(order = 7)
    @UIFieldGroup("Chart")
    public ChartType getChartType() {
        return getJsonDataEnum("ct", ChartType.line);
    }

    @Override
    @JsonIgnore
    @UIFieldIgnore
    public TimePeriod getTimePeriod() {
        throw new IllegalStateException("MNC");
    }

    @Override
    protected String getDefaultLayout() {
        return UIFieldLayout.LayoutBuilder.builder(50, 50) //
                .addRow(rb -> rb //
                        .addCol("name", UIFieldLayout.HorizontalAlign.left) //
                        .addCol("icon", UIFieldLayout.HorizontalAlign.right))
                .addRow(rb -> rb //
                        .addCol("value", UIFieldLayout.HorizontalAlign.left)
                        .addCol("none", UIFieldLayout.HorizontalAlign.center))
                .addRow(rb -> rb //
                        .addCol("none", UIFieldLayout.HorizontalAlign.center)
                        .addCol("none", UIFieldLayout.HorizontalAlign.center))
                .build();
    }

    public void setChartHeight(int value) {
        setJsonData("ch", value);
    }

    public void setHoursToShow(int value) {
        setJsonData("hts", value);
    }

    public void setPointsPerHour(int value) {
        setJsonData("pph", value);
    }

    public void setChartType(ChartType value) {
        setJsonData("ct", value);
    }

    public void setBarBorderWidth(String value) {
        setJsonData("bbw", value);
    }
}
