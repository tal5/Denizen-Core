package com.denizenscript.denizencore.utilities;

import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.objects.core.ElementTag;
import com.denizenscript.denizencore.objects.core.MapTag;
import com.denizenscript.denizencore.tags.TagContext;

public class SimpleDefinitionProvider implements DefinitionProvider {

    private final MapTag definitions = new MapTag();

    @Override
    public void addDefinition(String definition, ObjectTag value) {
        definitions.putDeepObject(definition, value);
    }

    @Override
    public void addDefinition(String definition, String value) {
        addDefinition(definition, new ElementTag(value));
    }

    @Override
    public MapTag getAllDefinitions() {
        return this.definitions;
    }

    @Override
    public ObjectTag getDefinitionObjectWithWarn(String definition, TagContext context) {
        if (definition == null) {
            return null;
        }
        return definitions.getDeepObjectWithWarn(definition, context);
    }
}
