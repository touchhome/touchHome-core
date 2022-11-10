package org.touchhome.app.model.entity.widget.impl.chart;

import org.touchhome.bundle.api.entity.HasJsonData;
import org.touchhome.bundle.api.ui.field.UIField;
import org.touchhome.bundle.api.ui.field.UIFieldGroup;

public interface HasLegend extends HasJsonData {

  @UIField(order = 70)
  @UIFieldGroup(value = "Legend", order = 20, borderColor = "#77AD2F")
  default Boolean isShowLegend() {
    return getJsonData("ls", Boolean.FALSE);
  }

  default void setShowLegend(Boolean value) {
    setJsonData("ls", value);
  }

  @UIField(order = 71)
  @UIFieldGroup("Legend")
  default LegendPosition getLegendPosition() {
    return getJsonDataEnum("lp", LegendPosition.top);
  }

  default void setLegendPosition(LegendPosition value) {
    setJsonData("lp", value);
  }

  @UIField(order = 72)
  @UIFieldGroup("Legend")
  default LegendAlign getLegendAlign() {
    return getJsonDataEnum("la", LegendAlign.center);
  }

  default void setLegendAlign(LegendAlign value) {
    setJsonData("la", value);
  }

  enum LegendPosition {
    top,
    right,
    bottom,
    left
  }

  enum LegendAlign {
    start,
    center,
    end
  }
}
