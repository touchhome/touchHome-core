package org.touchhome.app.model.workspace;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.json.JSONObject;
import org.touchhome.app.workspace.BroadcastLockManagerImpl;
import org.touchhome.bundle.api.EntityContext;
import org.touchhome.bundle.api.entity.BaseEntity;
import org.touchhome.bundle.api.entity.widget.AggregationType;
import org.touchhome.bundle.api.entity.widget.ChartRequest;
import org.touchhome.bundle.api.entity.widget.ability.HasAggregateValueFromSeries;
import org.touchhome.bundle.api.entity.widget.ability.HasSetStatusValue;
import org.touchhome.bundle.api.entity.widget.ability.HasTimeValueSeries;
import org.touchhome.bundle.api.inmemory.InMemoryDB;
import org.touchhome.bundle.api.inmemory.InMemoryDBService;
import org.touchhome.bundle.api.ui.field.UIFieldNumber;

import javax.persistence.Entity;
import java.util.List;
import java.util.function.Consumer;

@Getter
@Entity
public class WorkspaceBroadcastEntity extends BaseEntity<WorkspaceBroadcastEntity> implements HasTimeValueSeries,
        HasAggregateValueFromSeries, HasSetStatusValue {

    public static final String PREFIX = "brc_";

    @Override
    public String getTitle() {
        return "Broadcast event: " + this.getName();
    }

    @UIFieldNumber(min = 0, max = 10_000_000)
    private Integer quota;

    @Override
    public String getEntityPrefix() {
        return PREFIX;
    }

    public InMemoryDBService<BroadcastMessage> getStorage() {
        return InMemoryDB.getService(BroadcastMessage.class, (long) quota)
                .addSaveListener("", broadcastMessage -> {
                    WorkspaceBroadcastEntity.this.getEntityContext().getBean(BroadcastLockManagerImpl.class)
                            .signalAll(broadcastMessage.getName()); // TODO: Without prefix?? WorkspaceBroadcastEntity.PREFIX +
                    // broadcastRefEntityID
                });
    }

    public void fireBroadcastEvent(String name, Object value) {
        getStorage().save(new BroadcastMessage(name, value));
    }

    @Override
    public void setStatusValue(SetStatusValueRequest request) {
        fireBroadcastEvent(getName(), request.getValue());
    }

    @Override
    public Object getAggregateValueFromSeries(ChartRequest request, AggregationType aggregationType, boolean filterOnlyNumbers) {
        return getStorage()
                .aggregate(request.getFrom().getTime(), request.getTo().getTime(), "entityID", getEntityID(),
                        aggregationType, filterOnlyNumbers);
    }

    @Override
    public AggregationType[] getAvailableAggregateTypes() {
        return new AggregationType[]{AggregationType.Count};
    }

    @Override
    public void addUpdateValueListener(EntityContext entityContext, String key, JSONObject dynamicParameters,
                                       Consumer<Object> listener) {
        entityContext.event().addEventListener(getEntityID(), listener);
    }

    @Override
    public @NotNull List<Object[]> getTimeValueSeries(@NotNull ChartRequest request) {
        return getStorage()
                .getTimeSeries(request.getFrom().getTime(), request.getTo().getTime(), "entityID", getEntityID());
    }

    @Override
    public String getAggregateValueDescription() {
        return "Broadcast event count";
    }

    @Override
    public String getTimeValueSeriesDescription() {
        return "Broadcast time-value series";
    }

    @Override
    public String getSetStatusDescription() {
        return "Fire broadcast event";
    }
}
