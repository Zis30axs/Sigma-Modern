package com.mentalfrostbyte.jello.event;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an instance method as an event listener. The method must take exactly one parameter, and
 * that parameter's type is the event class it subscribes to.
 *
 * <p>{@code @EventTarget(EventPriority.HIGHEST) void onTick(EventTick event) { ... }}</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface EventTarget {

    EventPriority value() default EventPriority.NORMAL;
}
