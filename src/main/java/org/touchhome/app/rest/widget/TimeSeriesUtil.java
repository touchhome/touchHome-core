package org.touchhome.app.rest.widget;

import static org.apache.commons.lang3.StringUtils.defaultString;
import static org.apache.commons.lang3.StringUtils.isEmpty;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.JSONObject;
import org.touchhome.app.manager.common.EntityContextImpl;
import org.touchhome.app.model.entity.widget.WidgetBaseEntity;
import org.touchhome.app.model.entity.widget.impl.DataSourceUtil;
import org.touchhome.app.model.entity.widget.impl.HasChartTimePeriod;
import org.touchhome.app.model.entity.widget.impl.HasSingleValueAggregatedDataSource;
import org.touchhome.app.model.entity.widget.impl.HasSingleValueDataSource;
import org.touchhome.app.model.entity.widget.impl.HasSourceServerUpdates;
import org.touchhome.app.model.entity.widget.impl.chart.HasChartDataSource;
import org.touchhome.bundle.api.entity.widget.AggregationType;
import org.touchhome.bundle.api.entity.widget.PeriodRequest;
import org.touchhome.bundle.api.entity.widget.ability.HasAggregateValueFromSeries;
import org.touchhome.bundle.api.entity.widget.ability.HasGetStatusValue;
import org.touchhome.bundle.api.entity.widget.ability.HasTimeValueSeries;
import org.touchhome.bundle.api.entity.widget.ability.HasUpdateValueListener;
import org.touchhome.bundle.api.model.HasEntityIdentifier;
import org.touchhome.bundle.api.ui.field.selection.dynamic.HasDynamicParameterFields;

@RequiredArgsConstructor
public class TimeSeriesUtil {

    private final EntityContextImpl entityContext;

    public <T extends HasDynamicParameterFields & HasChartDataSource> WidgetChartsController.TimeSeriesChartData<ChartDataset>
    buildTimeSeriesFullData(String entityID, HasChartTimePeriod.TimePeriod timePeriod,
        boolean addUpdateListener, Set<T> series) {

        List<TimeSeriesValues<T>> timeSeriesValuesList = new ArrayList<>(series.size());

        for (T item : series) {
            DataSourceUtil.DataSourceContext sourceDS = DataSourceUtil.getSource(entityContext, item.getChartDataSource());
            Object source = sourceDS.getSource();
            if (source instanceof HasTimeValueSeries) {
                Set<TimeSeriesContext<T>> timeSeries = buildTimeSeriesFromDataSource(timePeriod, item, (HasTimeValueSeries) source);
                timeSeriesValuesList.add(new TimeSeriesValues<>(timeSeries, sourceDS.getSource()));
            }
        }

        WidgetChartsController.TimeSeriesChartData<ChartDataset>
            timeSeriesChartData = new WidgetChartsController.TimeSeriesChartData<>();

        if (!timeSeriesValuesList.isEmpty()) {
            List<Date> dates = EvaluateDatesAndValues.calculateDates(timePeriod, timeSeriesValuesList);
            timeSeriesChartData.setTimestamp(dates.stream().map(Date::getTime).collect(Collectors.toList()));
        }

        for (TimeSeriesValues<T> timeSeriesValues : timeSeriesValuesList) {
            for (TimeSeriesContext<T> item : timeSeriesValues.getItemSeries()) {
                T seriesEntity = item.getSeriesEntity();
                timeSeriesChartData.getDatasets().add(seriesEntity.buildTargetDataset(item));

                // add update listeners
                if (addUpdateListener) {
                    addChangeListenerForTimeSeriesEntity(item, timePeriod, entityID, series, timeSeriesValues.getSource());
                }
            }
        }

        return timeSeriesChartData;
    }

    public <T extends HasDynamicParameterFields & HasChartDataSource> void addChangeListenerForTimeSeriesEntity(
        TimeSeriesContext<T> timeSeriesContext,
        HasChartTimePeriod.TimePeriod timePeriod, String entityID, Set<T> series, Object source) {

        T item = timeSeriesContext.getSeriesEntity();
        ((HasTimeValueSeries) source).addUpdateValueListener(entityContext, entityID + "_timeSeries",
            item.getChartDynamicParameterFields(),
            o -> {
                Set<TimeSeriesContext<T>> cts =
                    buildTimeSeriesFromDataSource(timePeriod, item, timeSeriesContext.getSeries());
                TimeSeriesValues<T> values = timeSeriesContext.getOwner();

                // if context was updated - we need update rest of values also !!!
                if (!values.isEqualSeries(cts)) {
                    WidgetChartsController.TimeSeriesChartData<ChartDataset> fullUpdatedData =
                        this.buildTimeSeriesFullData(entityID, timePeriod, false, series);

                    entityContext.ui().sendDynamicUpdate(item.getChartDataSource(), entityID, fullUpdatedData);
                }
            });
    }

    /**
     * Evaluate single value from specific data source and attach listener on it for dynamic updates
     */
    public <T extends WidgetBaseEntity<T> & HasSourceServerUpdates, R> R getSingleValue(
        @NotNull T entity,
        @Nullable String valueDataSource,
        @Nullable JSONObject dynamicParameters,
        @NotNull Function<Object, R> resultConverter) {
        if (isEmpty(valueDataSource)) {
            return null;
        }
        String seriesEntityId = entity.getEntityID();
        DataSourceUtil.DataSourceContext dsContext = DataSourceUtil.getSource(entityContext, valueDataSource);
        Object source = dsContext.getSource();
        if (source == null) {
            return null;
        }

        Object value;
        if (source instanceof HasGetStatusValue) {
            value = getValueFromGetStatusValue(entity, resultConverter, seriesEntityId, dynamicParameters, source, valueDataSource);
        } else {
            value = getValueFromHasAggregationValue(entity, resultConverter, seriesEntityId, dynamicParameters, source, valueDataSource,
                AggregationType.Last, null);
        }
        return resultConverter.apply(value);
    }

    public <T extends WidgetBaseEntity<T> & HasSourceServerUpdates, DS extends HasSingleValueDataSource & HasEntityIdentifier, R> R
    getSingleValue(@NotNull T entity, @NotNull DS dataSource, @NotNull Function<Object, R> resultConverter) {
        String seriesEntityId = dataSource.getEntityID();
        JSONObject dynamicParameters = dataSource.getValueDynamicParameterFields();
        DataSourceUtil.DataSourceContext dsContext = DataSourceUtil.getSource(entityContext, dataSource.getValueDataSource());
        Object source = dsContext.getSource();
        if (source == null) {
            return (R) "BAD_SOURCE";
        }
        String dataSourceEntityID = dataSource.getValueDataSource();

        Object value;
        AggregationType aggregationType = getAggregationType(dataSource);

        if (aggregationType == AggregationType.None) {
            if (source instanceof HasGetStatusValue) {
                value = getValueFromGetStatusValue(entity, resultConverter, seriesEntityId, dynamicParameters, source, dataSourceEntityID);
            } else {
                value = getValueFromHasAggregationValue(entity, resultConverter, seriesEntityId, dynamicParameters, source, dataSourceEntityID,
                    AggregationType.Last, null);
            }
        } else {
            if (!(source instanceof HasAggregateValueFromSeries)) {
                throw new IllegalStateException("Unable to calculate value for: " + entity.getEntityID() +
                    "Entity has to implement HasAggregateValueFromSeries");
            }
            value = getValueFromHasAggregationValue(entity, resultConverter, seriesEntityId, dynamicParameters, source,
                dataSourceEntityID, aggregationType, TimeUnit.MINUTES.toMillis(((HasSingleValueAggregatedDataSource) dataSource)
                    .getValueAggregationPeriod()));
        }

        return resultConverter.apply(value);
    }

    public <R> void addListenValueIfRequire(boolean listenSourceUpdates, @NotNull String entityID, @NotNull Object source,
        @Nullable JSONObject dynamicParameters, @Nullable String seriesEntityId, @NotNull String dataSourceEntityID,
        @NotNull Function<Object, R> valueSupplier) {
        if (listenSourceUpdates) {
            AtomicReference<R> valueRef = new AtomicReference<>(null);
            String key = entityID + defaultString(seriesEntityId, "");
            ((HasUpdateValueListener) source).addUpdateValueListener(entityContext, key, dynamicParameters,
                o -> {
                    R updatedValue = valueSupplier.apply(o);
                    if (valueRef.get() != updatedValue) {
                        valueRef.set(updatedValue);
                        entityContext.ui().sendDynamicUpdate(dataSourceEntityID, entityID,
                            new WidgetChartsController.SingleValueData(updatedValue, seriesEntityId));
                    }
                });
        }
    }

    public <T extends HasDynamicParameterFields & HasChartDataSource> Set<TimeSeriesContext<T>>
    buildTimeSeriesFromDataSource(HasChartTimePeriod.TimePeriod timePeriod, T item, HasTimeValueSeries source) {
        Set<TimeSeriesContext<T>> result = new HashSet<>();
        PeriodRequest periodRequest = new PeriodRequest(entityContext, timePeriod.getFrom(), timePeriod.getTo())
            .setParameters(item.getChartDynamicParameterFields());

        Map<HasTimeValueSeries.TimeValueDatasetDescription, List<Object[]>> timeSeries =
            source.getMultipleTimeValueSeries(periodRequest);

        for (Map.Entry<HasTimeValueSeries.TimeValueDatasetDescription, List<Object[]>> entry : timeSeries.entrySet()) {
            // convert chartItem[0] to long if it's a Date type
            if (!entry.getValue().isEmpty() && entry.getValue().get(0)[0] instanceof Date) {
                for (Object[] chartItem : entry.getValue()) {
                    chartItem[0] = ((Date) chartItem[0]).getTime();
                }
            }
            HasTimeValueSeries.TimeValueDatasetDescription tvd = entry.getKey();
            result.add(new TimeSeriesContext<>(tvd.getId(), item, source).setValue(entry.getValue()));
        }
        return result;
    }

    private <DS extends HasSingleValueDataSource & HasEntityIdentifier> AggregationType getAggregationType(@NotNull DS dataSource) {
        return dataSource instanceof HasSingleValueAggregatedDataSource ? ((HasSingleValueAggregatedDataSource) dataSource).getValueAggregationType() :
            AggregationType.None;
    }

    @Nullable
    private <T extends WidgetBaseEntity<T> & HasSourceServerUpdates, R> Object getValueFromHasAggregationValue(@NotNull T entity,
        @NotNull Function<Object, R> resultConverter,
        String seriesEntityId, JSONObject dynamicParameters, Object source, String dataSourceEntityID,
        AggregationType aggregationType, @Nullable Long diffMilliseconds) {
        Object value;
        PeriodRequest periodRequest = new PeriodRequest(entityContext, diffMilliseconds).setParameters(dynamicParameters);
        value = ((HasAggregateValueFromSeries) source).getAggregateValueFromSeries(periodRequest, aggregationType, false);

        addListenValueIfRequire(entity.getListenSourceUpdates(), entity.getEntityID(), source, dynamicParameters, seriesEntityId, dataSourceEntityID,
            object -> {
                PeriodRequest request = new PeriodRequest(entityContext, diffMilliseconds).setParameters(dynamicParameters);
                return resultConverter.apply(((HasAggregateValueFromSeries) source).getAggregateValueFromSeries(request, aggregationType, false));
            });
        return value;
    }

    private <T extends WidgetBaseEntity<T> & HasSourceServerUpdates, R> Object getValueFromGetStatusValue(@NotNull T entity,
        @NotNull Function<Object, R> resultConverter,
        String seriesEntityId, JSONObject dynamicParameters, Object source, String dataSourceEntityID) {

        Object value;
        HasGetStatusValue.GetStatusValueRequest valueRequest = new HasGetStatusValue.GetStatusValueRequest(entityContext, dynamicParameters);
        value = ((HasGetStatusValue) source).getStatusValue(valueRequest);

        addListenValueIfRequire(entity.getListenSourceUpdates(), entity.getEntityID(), source, dynamicParameters, seriesEntityId, dataSourceEntityID,
            object -> {
                return resultConverter.apply(((HasGetStatusValue) source).getStatusValue(valueRequest));
            });
        return value;
    }
}
