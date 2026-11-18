package qupath.ext.histoforge.platform;

import java.util.HashMap;
import java.util.Map;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.Preferences;
import java.util.prefs.PreferencesFactory;

/**
 * Process-local preferences for disposable native UI qualification.
 *
 * <p>Select with {@code -Djava.util.prefs.PreferencesFactory} before starting JavaFX/QuPath. Unlike
 * changing userRoot alone on macOS, this cannot read or write the user's native preference store.
 * It intentionally provides no persistence across fixture processes.
 */
public final class IsolatedPreferencesFactory implements PreferencesFactory {
    private final Preferences user = new Node(null, "");
    private final Preferences system = new Node(null, "");

    @Override
    public Preferences userRoot() {
        return user;
    }

    @Override
    public Preferences systemRoot() {
        return system;
    }

    private static final class Node extends AbstractPreferences {
        private final Map<String, String> values = new HashMap<>();
        private final Map<String, Node> children = new HashMap<>();

        private Node(Node parent, String name) {
            super(parent, name);
        }

        @Override
        protected void putSpi(String key, String value) {
            values.put(key, value);
        }

        @Override
        protected String getSpi(String key) {
            return values.get(key);
        }

        @Override
        protected void removeSpi(String key) {
            values.remove(key);
        }

        @Override
        protected void removeNodeSpi() {
            ((Node) parent()).children.remove(name());
        }

        @Override
        protected String[] keysSpi() {
            return values.keySet().toArray(String[]::new);
        }

        @Override
        protected String[] childrenNamesSpi() {
            return children.keySet().toArray(String[]::new);
        }

        @Override
        protected AbstractPreferences childSpi(String name) {
            return children.computeIfAbsent(name, child -> new Node(this, child));
        }

        @Override
        protected void syncSpi() {}

        @Override
        protected void flushSpi() {}
    }
}
