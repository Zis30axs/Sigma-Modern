package net.fabricmc.fabric.api.event;

// MODIFIED for porting: embedded stand-in for fabric-api
public interface Event<T> {
    void register(T listener);

    T invoker();
}
