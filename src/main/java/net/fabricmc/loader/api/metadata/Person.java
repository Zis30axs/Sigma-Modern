package net.fabricmc.loader.api.metadata;

import java.util.Map;

// MODIFIED for porting: embedded stand-in for fabric-loader
public final class Person {
    private final String name;

    public Person(final String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public Contact getContact() {
        return Map::of;
    }
}
