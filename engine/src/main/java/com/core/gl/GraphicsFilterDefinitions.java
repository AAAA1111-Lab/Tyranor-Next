package com.core.gl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Build-generated contributions: removing a descriptor removes all of its runtime registration. */
public final class GraphicsFilterDefinitions {
    private static final Map<String, GraphicsFilterDefinition> BY_ID;
    private static final List<GraphicsFilterDefinition> VISIBLE;
    static {
        Map<String, GraphicsFilterDefinition> ids = new LinkedHashMap<>();
        List<GraphicsFilterDefinition> visible = new ArrayList<>();
        for (GraphicsFilterDefinition definition : GeneratedGraphicsFilterCatalog.create()) {
            ids.put(definition.id, definition);
            if (definition.visible) visible.add(definition);
        }
        BY_ID = Collections.unmodifiableMap(ids);
        VISIBLE = Collections.unmodifiableList(visible);
    }
    private GraphicsFilterDefinitions() {}
    public static GraphicsFilterDefinition find(String id) { return BY_ID.get(id); }
    public static List<GraphicsFilterDefinition> visible() { return VISIBLE; }
}
