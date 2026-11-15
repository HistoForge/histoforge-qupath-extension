package qupath.ext.histoforge.platform;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class IsolatedPreferencesFactoryTest {
    @Test
    void settingsRoundTripOnlyWithinTheirOwnFactoryAndRoot() throws Exception {
        var factory = new IsolatedPreferencesFactory();
        var settings = factory.userRoot().node("qupath/settings");
        settings.put("theme", "light");
        settings.flush();
        settings.sync();
        assertEquals("light", factory.userRoot().node("qupath/settings").get("theme", null));
        assertNull(factory.systemRoot().node("qupath/settings").get("theme", null));
        assertNull(
                new IsolatedPreferencesFactory()
                        .userRoot()
                        .node("qupath/settings")
                        .get("theme", null));
    }

    @Test
    void removedKeysAndNodesDoNotReappear() throws Exception {
        var root = new IsolatedPreferencesFactory().userRoot();
        var settings = root.node("qupath/settings");
        settings.put("theme", "light");
        assertArrayEquals(new String[] {"theme"}, settings.keys());
        settings.remove("theme");
        assertEquals(0, settings.keys().length);
        settings.put("theme", "dark");
        root.node("qupath").removeNode();
        assertFalse(root.nodeExists("qupath"));
        assertEquals(0, root.childrenNames().length);
        assertNull(root.node("qupath/settings").get("theme", null));
    }
}
