package local.questrepeat.test;

import java.util.HashMap;
import java.util.Map;
import org.spongepowered.asm.service.IGlobalPropertyService;
import org.spongepowered.asm.service.IPropertyKey;

/** Test-only blackboard for Mixin bootstrap. */
public final class OfflineProperties implements IGlobalPropertyService {
    private final Map<String, IPropertyKey> keys = new HashMap<>();
    private final Map<IPropertyKey, Object> values = new HashMap<>();
    public IPropertyKey resolveKey(String name) { return keys.computeIfAbsent(name, ignored -> new IPropertyKey() {}); }
    @SuppressWarnings("unchecked")
    public <T> T getProperty(IPropertyKey key) { return (T) values.get(key); }
    public void setProperty(IPropertyKey key, Object value) { values.put(key, value); }
    public <T> T getProperty(IPropertyKey key, T fallback) { T value = getProperty(key); return value == null ? fallback : value; }
    public String getPropertyString(IPropertyKey key, String fallback) { Object value = values.get(key); return value == null ? fallback : value.toString(); }
}

