package com.mentalfrostbyte.jello.util.collection;

/**
 * An immutable two-value tuple, for the places where a method has to return two things and neither
 * deserves its own type.
 *
 * <p>Replaces the old {@code Pair} / {@code SimpleEntryPair} split, which was the same tuple written
 * twice - once mutable, once not - with lower-case type parameters named {@code key} and
 * {@code value}.</p>
 */
public record Pair<K, V>(K key, V value) {

    public static <K, V> Pair<K, V> of(final K key, final V value) {
        return new Pair<>(key, value);
    }
}
