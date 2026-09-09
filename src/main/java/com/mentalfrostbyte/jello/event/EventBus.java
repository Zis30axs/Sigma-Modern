package com.mentalfrostbyte.jello.event;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reflection based event bus: objects expose {@link EventTarget} annotated methods, vanilla hooks
 * publish events through {@link #call(Event)}.
 *
 * <p>Dispatch is by <em>exact</em> event class - subscribing to a base type does not deliver its
 * subclasses. That keeps a call to a hot hook (movement, packets, render) a single map lookup plus
 * an array walk.</p>
 *
 * <p>Thread safety matters here because packet events are fired from Netty's event loop while the
 * game thread registers and unregisters modules. Listener arrays are therefore immutable: mutating
 * operations build a fresh array and swap it in, so {@link #call(Event)} can iterate without
 * locking and without risking a {@code ConcurrentModificationException}.</p>
 */
public final class EventBus {

    private static final Logger LOGGER = LoggerFactory.getLogger("Sigma/EventBus");

    private static final Subscription[] NO_SUBSCRIBERS = new Subscription[0];

    /** Highest priority first; {@link List#sort} is stable, so ties keep registration order. */
    private static final Comparator<Subscription> BY_PRIORITY =
            Comparator.<Subscription>comparingInt(subscription -> subscription.priority().ordinal()).reversed();

    private static final Map<Class<? extends Event>, Subscription[]> SUBSCRIPTIONS = new ConcurrentHashMap<>();

    private EventBus() {
    }

    /**
     * Subscribes every {@link EventTarget} method declared by {@code subscriber}'s class or any of
     * its superclasses.
     *
     * <p>Listeners are tracked per instance, so registering two objects of the same class gives two
     * independent subscriptions and unregistering one leaves the other in place.</p>
     */
    public static void register(final Object subscriber) {
        collect(subscriber).forEach((eventType, added) -> SUBSCRIPTIONS.compute(eventType, (type, current) -> {
            List<Subscription> merged = new ArrayList<>(added.size() + (current == null ? 0 : current.length));
            if (current != null) {
                merged.addAll(Arrays.asList(current));
            }

            merged.addAll(added);
            merged.sort(BY_PRIORITY);
            return merged.toArray(NO_SUBSCRIBERS);
        }));
    }

    /**
     * Removes every listener belonging to {@code subscriber}. Unknown objects are ignored.
     *
     * <p>Each event type is updated with a single {@link ConcurrentHashMap#computeIfPresent}, the same
     * primitive {@link #register(Object)} builds on, so a concurrent {@code register}/{@code unregister}
     * pair on the same event type can never lose an update the way a plain read-then-{@code setValue}
     * (or a racing {@code remove(key, value)}) could: whichever of the two remappings runs second always
     * sees the result of the first.</p>
     */
    public static void unregister(final Object subscriber) {
        for (Class<? extends Event> eventType : SUBSCRIPTIONS.keySet()) {
            SUBSCRIPTIONS.computeIfPresent(eventType, (type, current) -> {
                Subscription[] remaining = Arrays.stream(current)
                        .filter(subscription -> subscription.subscriber() != subscriber)
                        .toArray(Subscription[]::new);
                return remaining.length == 0 ? null : remaining;
            });
        }
    }

    /**
     * Notifies every listener subscribed to {@code event}'s exact class and returns the event, so a
     * hook site can dispatch and inspect the result in one expression:
     * {@code if (EventBus.call(new EventUpdate()).isCancelled()) return;}
     *
     * <p>A listener that throws is logged and skipped; the remaining listeners still run. That is
     * deliberate: a broken module must not take the game down or silently drop the vanilla body of
     * whichever method fired the event.</p>
     *
     * <p>Dispatch iterates a snapshot array, so a subscriber that was disabled after the snapshot was
     * taken - e.g. a module toggled off on the game thread while this call started from a Netty thread
     * with an older array already in hand - could otherwise still receive the event. Each subscription
     * carries an {@link EnableAware#isEnabled()} check (true for an ordinary subscriber) taken at
     * invocation time, so a subscriber that has since disabled itself is skipped even from a stale
     * snapshot.</p>
     */
    public static <T extends Event> T call(final T event) {
        Subscription[] subscriptions = SUBSCRIPTIONS.get(event.getClass());
        if (subscriptions == null) {
            return event;
        }

        for (Subscription subscription : subscriptions) {
            if (!subscription.active().getAsBoolean()) {
                continue;
            }

            try {
                subscription.method().invoke(subscription.subscriber(), event);
            } catch (InvocationTargetException failure) {
                LOGGER.error("Listener {}#{} threw",
                        subscription.subscriber().getClass().getName(), subscription.method().getName(), failure.getCause());
            } catch (ReflectiveOperationException | RuntimeException failure) {
                LOGGER.error("Could not invoke listener {}#{}",
                        subscription.subscriber().getClass().getName(), subscription.method().getName(), failure);
            }
        }

        return event;
    }

    private static Map<Class<? extends Event>, List<Subscription>> collect(final Object subscriber) {
        Map<Class<? extends Event>, List<Subscription>> found = new HashMap<>();
        Set<String> alreadyBound = new HashSet<>();
        BooleanSupplier active = subscriber instanceof EnableAware aware ? aware::isEnabled : () -> true;

        for (Class<?> type = subscriber.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                EventTarget target = method.getAnnotation(EventTarget.class);
                if (target == null) {
                    continue;
                }

                if (Modifier.isStatic(method.getModifiers())) {
                    LOGGER.warn("Ignoring static @EventTarget {}#{}", type.getName(), method.getName());
                    continue;
                }

                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length != 1 || !Event.class.isAssignableFrom(parameters[0])) {
                    LOGGER.warn("Ignoring @EventTarget {}#{}: expected exactly one Event parameter", type.getName(), method.getName());
                    continue;
                }

                // An override and the method it overrides both carry the annotation. We walk from
                // the concrete class upwards, so the first signature we see is the one that runs.
                if (!alreadyBound.add(method.getName() + '(' + parameters[0].getName() + ')')) {
                    continue;
                }

                method.setAccessible(true);
                @SuppressWarnings("unchecked")
                Class<? extends Event> eventType = (Class<? extends Event>) parameters[0];
                found.computeIfAbsent(eventType, ignored -> new ArrayList<>())
                        .add(new Subscription(subscriber, method, target.value(), active));
            }
        }

        return found;
    }

    private record Subscription(Object subscriber, Method method, EventPriority priority, BooleanSupplier active) {
    }
}
