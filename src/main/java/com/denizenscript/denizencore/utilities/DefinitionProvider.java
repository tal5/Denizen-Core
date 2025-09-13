package com.denizenscript.denizencore.utilities;

import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.objects.core.MapTag;
import com.denizenscript.denizencore.tags.TagContext;

public interface DefinitionProvider {

    void addDefinition(String definition, String value);

    void addDefinition(String definition, ObjectTag value);

    MapTag getAllDefinitions();

    ObjectTag getDefinitionObjectWithWarn(String definition, TagContext context);

    default ObjectTag getDefinitionObject(String definition) {
        if (definition == null) {
            return null;
        }
        return getDefinitionObjectWithWarn(definition, null);
    }

    default String getDefinitionWithWarn(String definition, TagContext context) {
        return CoreUtilities.stringifyNullPass(getDefinitionObjectWithWarn(definition, context));
    };

    default String getDefinition(String definition) {
        return CoreUtilities.stringifyNullPass(getDefinitionObject(definition));
    }

    default boolean hasDefinition(String definition) {
        return getDefinitionObject(definition) != null;
    }

    default void removeDefinition(String definition) {
        addDefinition(definition, (ObjectTag) null);
    }
}
