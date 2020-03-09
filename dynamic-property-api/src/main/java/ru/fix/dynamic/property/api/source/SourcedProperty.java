package ru.fix.dynamic.property.api.source;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.fix.dynamic.property.api.DynamicProperty;
import ru.fix.dynamic.property.api.DynamicPropertyWeakListener;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Contains property initial value.
 * Registers listener within {@link DynamicPropertySource}.
 * Listen for events from  {@link DynamicPropertySource}.
 * Update local value and propagates update events to it's subscribes.
 * See {@link DynamicProperty#addAndCallListener(DynamicPropertyWeakListener)}
 *
 * If instance of this class became weakly reachable it will stop receiving events from {@link DynamicPropertySource}
 * Same effect will be archived through {@link DynamicProperty#close()}
 */
public class SourcedProperty<T> implements DynamicProperty<T> {

    private static final Logger log = LoggerFactory.getLogger(SourcedProperty.class);

    private final String name;
    private final Class<T> type;
    private final AtomicReference<T> currentValue = new AtomicReference<>();
    private final List<DynamicPropertyWeakListener<T>> listeners = new CopyOnWriteArrayList<>();

    private final DynamicPropertySource propertySource;
    private final DynamicPropertySource.Subscription subscription;

    public SourcedProperty(DynamicPropertySource propertySource,
                           String name,
                           Class<T> type,
                           OptionalDefaultValue<T> defaultValue) {
        this.name = name;
        this.type = type;
        this.propertySource = propertySource;

        subscription = propertySource.subscribeAndCallListener(
                this.name,
                this.type,
                defaultValue,
                newValue -> {
                    synchronized (SourcedProperty.this) {
                        T oldValue = currentValue.getAndSet(newValue);
                        if(log.isTraceEnabled()){
                            log.trace("Sourced property update: name: {}, oldValue: {}, newValue: {}",
                                    name,
                                    oldValue,
                                    newValue);
                        }
                        listeners.forEach(listener -> {
                            try {
                                listener.onPropertyChanged(oldValue, newValue);
                            } catch (Exception e) {
                                log.error("Failed to update property {} from oldValue {} to newValue {}",
                                        this.name,
                                        oldValue,
                                        newValue,
                                        e);
                            }
                        });
                    }
                }
        );
    }

    @Override
    public T get() {
        return currentValue.get();
    }

    /**
     * Listener callback runs in the {@link DynamicPropertySource} thread.
     *
     * @param listener Listener runs whenever property value changes.
     */
    @Override
    public DynamicProperty<T> addListener(DynamicPropertyWeakListener<T> listener) {
        listeners.add(listener);
        return this;
    }

    @Override
    public synchronized DynamicProperty<T> addAndCallListener(DynamicPropertyWeakListener<T> listener) {
        listeners.add(listener);
        listener.onPropertyChanged(null, currentValue.get());
        return this;
    }

    @Override
    public synchronized T addListenerAndGet(DynamicPropertyWeakListener<T> listener) {
        listeners.add(listener);
        return currentValue.get();
    }

    @Override
    public DynamicProperty<T> removeListener(DynamicPropertyWeakListener<T> listener) {
        listeners.remove(listener);
        return this;
    }

    @Override
    public void close() {
        this.subscription.close();
        listeners.clear();
    }

    @Override
    public String toString() {
        return "SourcedProperty{" +
                "type=" + type +
                ", name='" + name + '\'' +
                ", currentValue=" + currentValue +
                '}';
    }
}
