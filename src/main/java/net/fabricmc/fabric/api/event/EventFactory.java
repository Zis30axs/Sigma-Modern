package net.fabricmc.fabric.api.event;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

// MODIFIED for porting: embedded stand-in for fabric-api
public final class EventFactory {
    private EventFactory() {
    }

    public static <T> Event<T> createArrayBacked(final Class<? super T> type, final Function<T[], T> invokerFactory) {
        final List<T> listeners = new ArrayList<>();
        return new Event<T>() {
            @Override
            public void register(final T listener) {
                synchronized (listeners) {
                    listeners.add(listener);
                }
            }

            @Override
            @SuppressWarnings("unchecked")
            public T invoker() {
                final T[] array = (T[]) listeners.toArray();
                return invokerFactory.apply(array);
            }
        };
    }
}
