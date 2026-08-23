package me.flashyreese.mods.sodiumextra.common.util;

import com.google.gson.*;
import net.minecraft.resources.Identifier;
import net.minecraft.util.GsonHelper;

import java.lang.reflect.Type;

public class IdentifierSerializer implements JsonDeserializer<Identifier>, JsonSerializer<Identifier> {
    public IdentifierSerializer() {
    }

    public Identifier deserialize(JsonElement jsonElement, Type type, JsonDeserializationContext jsonDeserializationContext) throws JsonParseException {
        return Identifier.parse(GsonHelper.convertToString(jsonElement, "location"));
    }

    public JsonElement serialize(Identifier resourceLocation, Type type, JsonSerializationContext jsonSerializationContext) {
        return new JsonPrimitive(resourceLocation.toString());
    }
}