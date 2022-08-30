package org.touchhome.app.rest.widget;

import org.springframework.data.util.Pair;
import org.touchhome.app.model.entity.widget.impl.HasChartDataSource;
import org.touchhome.bundle.api.entity.widget.AggregationType;
import org.touchhome.bundle.api.ui.TimePeriod;
import org.touchhome.bundle.api.ui.field.selection.dynamic.HasDynamicParameterFields;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public final class EvaluateDatesAndValues {

    public static List<Float> aggregate(List<List<Float>> values, AggregationType aggregationType) {
        return values.stream().map(items -> {
            Stream<Float> stream = items.stream();
            if (aggregationType.isRequireSorting()) {
                stream = stream.sorted();
            }
            return aggregationType.evaluate(stream);
        }).collect(Collectors.toList());
    }

    public static <T extends HasDynamicParameterFields<?> & HasChartDataSource<?>> List<Date> calculateDates(
            TimePeriod.TimePeriodImpl timePeriod, List<TimeSeriesValues<T>> timeSeriesValues) {
        // get dates split by algorithm
        List<Date> dates = evaluateDates(timePeriod, timeSeriesValues);
        List<Date> initialDates = new ArrayList<>(dates);
        // minimum number not 0 values to fit requirements
        int minDateSize = initialDates.size() / 2;
        // fill values with remove 0 points. Attention: dates are modified by iterator
        fulfillValues(dates, timeSeriesValues);
        /* int index = 2;
        int prevDates = -1; // prevDates uses to avoid extra iterations if prevDates == datesWithMultiplier
        while (index != 5 && prevDates != dates.size()) {
            prevDates = dates.size();
            dates = new ArrayList<>(initialDates.size() * index);
            for (int i = 0; i < initialDates.size() - 1; i++) {
                dates.add(initialDates.get(i));
                dates.add(new Date(((initialDates.get(i + 1).getTime() + initialDates.get(i).getTime())) / 2));
            }
            dates.add(initialDates.get(initialDates.size() - 1));
            initialDates = new ArrayList<>(dates);
            fulfillValues(dates, timeSeriesValues);
            index++;
        }*/
        return dates;
    }

    private static <T extends HasChartDataSource<?>> List<Date>
    evaluateDates(TimePeriod.TimePeriodImpl timePeriod, List<TimeSeriesValues<T>> timeSeriesValues) {
        List<Date> dates = timePeriod.evaluateDateRange();
        if (dates == null) {
            long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
            for (TimeSeriesValues<T> timeSeriesValue : timeSeriesValues) {
                for (TimeSeriesContext<T> timeSeriesContext : timeSeriesValue.getItemSeries()) {
                    for (Object[] chartItem : timeSeriesContext.getValue()) {
                        min = Math.min(min, (long) chartItem[0]);
                        max = Math.max(max, (long) chartItem[0]);
                    }
                }
            }
            long delta = (max - min) / 30;
            long finalMin = min;
            dates = IntStream.range(0, 30).mapToObj(value -> new Date(finalMin + delta * value)).collect(Collectors.toList());
        }
        return dates;
    }

    private static <T extends HasDynamicParameterFields<?> & HasChartDataSource<?>>
    void fulfillValues(List<Date> dates, List<TimeSeriesValues<T>> timeSeriesValues) {
        boolean calcMissingValues = true;
        List<Iterator<List<Float>>> fullChartValueIterators = new ArrayList<>();

        for (TimeSeriesValues<T> timeSeriesValue : timeSeriesValues) {
            for (TimeSeriesContext<T> timeSeriesContext : timeSeriesValue.getItemSeries()) {
                if (timeSeriesContext.getValue().isEmpty()) {
                    continue;
                }
                List<List<Float>> values = new ArrayList<>(dates.size());
                IntStream.range(0, dates.size()).forEach(value -> values.add(new ArrayList<>()));
                // push values to date between buckets
                for (Object[] chartItem : timeSeriesContext.getValue()) {
                    long time = chartItem[0] instanceof Date ? ((Date) chartItem[0]).getTime() : (Long) chartItem[0];
                    int index = getDateIndex(dates, time);
                    if (index >= 0) {
                        values.get(index).add((Float) chartItem[1]);
                    }
                }
                timeSeriesContext.setValues(values);

                Boolean fillMissingValues = timeSeriesContext.getSeriesEntity().getFillMissingValues();

                calcMissingValues = calcMissingValues && !fillMissingValues;
                fullChartValueIterators.add(values.iterator());
            }
        }

        // need erase dates only through all datasets
        if (calcMissingValues) {
            for (Iterator<Date> dateIterator = dates.iterator(); dateIterator.hasNext(); ) {
                List<List<Float>> valuesList = fullChartValueIterators.stream().map(Iterator::next).collect(Collectors.toList());

                boolean notEmptyBlock = valuesList.stream().anyMatch(v -> !v.isEmpty());
                dateIterator.next();

                if (!notEmptyBlock) {
                    fullChartValueIterators.forEach(Iterator::remove);
                    dateIterator.remove();
                }
            }
        }
    }

    private static int getDateIndex(List<Date> dateList, long time) {
        for (int i = 0; i < dateList.size(); i++) {
            if (time < dateList.get(i).getTime()) {
                return i - 1;
            }
        }
        return dateList.size() - 1;
    }

    public static List<String> buildLabels(TimePeriod.TimePeriodImpl timePeriod, List<Date> dates) {
        DateFormat dateFormat = getDateFormat(timePeriod, dates);
        return dates.stream().map(dateFormat::format).collect(Collectors.toList());
    }

    public static DateFormat getDateFormat(TimePeriod.TimePeriodImpl timePeriod, List<Date> dates) {
        Pair<Date, Date> dateRange = timePeriod.getDateRange();
        long diff = dateRange.getSecond().getTime() - dateRange.getFirst().getTime();
        int minutes = (int) (diff / 60000);
        if (minutes < 60) {
            return new SimpleDateFormat("mm:ss");
        } else if (minutes < TimeUnit.HOURS.toMillis(24)) {
            return new SimpleDateFormat("HH:mm:ss");
        } else if (minutes < TimeUnit.DAYS.toMillis(7)) {
            return new SimpleDateFormat("dd:HH:mm");
        } else if (minutes < TimeUnit.DAYS.toMillis(30)) {
            return new SimpleDateFormat("MM-dd:HH:mm");
        } else {
            return new SimpleDateFormat("yy-MM-dd:HH");
        }
    }
}
