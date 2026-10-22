package lagfinder.server;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import net.neoforged.bus.GeneratedEventListener;
import net.neoforged.bus.SubscribeEventListener;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventListener;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;

import lagfinder.LagFinder;
import lagfinder.core.Category;
import lagfinder.core.ProfileSession;

/**
 * Times every listener of the game's event bus while a scan runs: the bus's own code can't be changed (it is loaded
 * before any mod), so each list of listeners the bus keeps for an event is handed timing wrappers instead, and
 * rebuilt as it was when the scan ends. A list the bus rebuilds meanwhile (a listener added) is wrapped again.
 */
public final class EventTiming {

    private static Field listenerLists, listeners, priorities;
    private static Method readMap, forceRebuild;
    private static boolean broken;

    /** The lists wrapped and the array each was handed. */
    private static final Map<Object, EventListener[]> wrapped = new IdentityHashMap<>();

    private EventTiming() {}

    /** A listener timed as itself: what it calls, without what is measured inside it. */
    private static final class Timed extends GeneratedEventListener {

        private final EventListener inner;
        private final Object key;

        private Timed(EventListener inner, Object key) {
            this.inner = inner;
            this.key = key;
        }

        @Override
        public void invoke(Event event) {
            ProfileSession session = ProfileSession.current();
            if (session == null) {
                inner.invoke(event);
                return;
            }
            long outer = session.enter();
            long start = System.nanoTime();
            try {
                inner.invoke(event);
            } finally {
                session.record(Category.EVENTS, key, start, outer);
            }
        }

        @Override
        public String toString() {
            return inner.toString();
        }
    }

    /** One key per listener for the whole scan, whatever list it is in: the line it is counted on. */
    private static final Map<EventListener, Key> keys = new IdentityHashMap<>();

    /** What a listener is counted as: the listener registered, and the event it was first seen for. */
    public static final class Key {

        final Object listener;
        final String event;

        Key(Object listener, String event) {
            this.listener = listener;
            this.event = event;
        }
    }

    /** What the listener behind a key calls, as the bus tells it; null when not known. */
    public static String describe(Object key) {
        Object listener = key instanceof Key ? ((Key) key).listener : key;
        try {
            return listener == null ? null : listener.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** The simple name of the event a key was first seen for, or null. */
    public static String eventOf(Object key) {
        return key instanceof Key ? ((Key) key).event : null;
    }

    private static boolean look() {
        if (broken) {
            return false;
        }
        if (listenerLists != null) {
            return true;
        }
        try {
            Class<?> bus = Class.forName("net.neoforged.bus.EventBus");
            Class<?> list = Class.forName("net.neoforged.bus.ListenerList");
            Class<?> lock = Class.forName("net.neoforged.bus.LockHelper");
            listenerLists = bus.getDeclaredField("listenerLists");
            listenerLists.setAccessible(true);
            listeners = list.getDeclaredField("listeners");
            listeners.setAccessible(true);
            priorities = list.getDeclaredField("priorities");
            priorities.setAccessible(true);
            forceRebuild = list.getDeclaredMethod("forceRebuild");
            forceRebuild.setAccessible(true);
            readMap = lock.getDeclaredMethod("getReadMap");
            readMap.setAccessible(true);
            return true;
        } catch (Throwable t) {
            broken = true;
            LagFinder.LOG.warn("LagFinder can't time event listeners on this version of the event bus", t);
            return false;
        }
    }

    /** Wraps the listeners of every event of the game's bus (and again those the bus rebuilt since). */
    @SuppressWarnings("unchecked")
    public static void install() {
        if (!look()) {
            return;
        }
        try {
            IEventBus bus = NeoForge.EVENT_BUS;
            Object lock = listenerLists.get(bus);
            Map<Class<?>, Object> lists = new IdentityHashMap<>((Map<Class<?>, Object>) readMap.invoke(lock));
            for (Map.Entry<Class<?>, Object> entry : lists.entrySet()) {
                Object list = entry.getValue();
                AtomicReference<EventListener[]> ref = (AtomicReference<EventListener[]>) listeners.get(list);
                EventListener[] current = ref.get();
                if (current == null || current.length == 0 || current == wrapped.get(list)) {
                    continue;
                }
                Map<EventListener, Object> registered = registered(list);
                String event = eventName(entry.getKey());
                EventListener[] timed = new EventListener[current.length];
                for (int i = 0; i < current.length; i++) {
                    EventListener listener = current[i];
                    if (listener instanceof Timed) {
                        timed[i] = listener;
                        continue;
                    }
                    Object original = registered.getOrDefault(listener, listener);
                    Key key = keys.computeIfAbsent(listener, l -> new Key(original, event));
                    timed[i] = new Timed(listener, key);
                }
                if (ref.compareAndSet(current, timed)) {
                    wrapped.put(list, timed);
                }
            }
        } catch (Throwable t) {
            broken = true;
            LagFinder.LOG.warn("LagFinder could not time the event listeners", t);
            uninstall();
        }
    }

    /** The event's name without its package: "ServerTickEvent.Post". */
    private static String eventName(Class<?> type) {
        String name = type.getName();
        return name.substring(name.lastIndexOf('.') + 1)
            .replace('$', '.');
    }

    /** The listeners as registered (with what they call) by the listener the bus calls in their place. */
    @SuppressWarnings("unchecked")
    private static Map<EventListener, Object> registered(Object list) throws IllegalAccessException {
        Map<EventListener, Object> map = new IdentityHashMap<>();
        List<List<EventListener>> byPriority = new ArrayList<>((List<List<EventListener>>) priorities.get(list));
        for (List<EventListener> phase : byPriority) {
            for (EventListener listener : new ArrayList<>(phase)) {
                map.put(listener, listener);
                if (listener instanceof SubscribeEventListener subscribed) {
                    map.put(subscribed.getWithoutCheck(), subscribed);
                } else if (listener instanceof net.neoforged.bus.IWrapperListener wrapper) {
                    map.put(wrapper.getWithoutCheck(), listener);
                }
            }
        }
        return map;
    }

    /** Gives every list wrapped back its own listeners: the bus builds them again as they were. */
    public static void uninstall() {
        try {
            for (Object list : wrapped.keySet()) {
                forceRebuild.invoke(list);
            }
        } catch (Throwable t) {
            LagFinder.LOG.warn("LagFinder could not give the event listeners back", t);
        }
        wrapped.clear();
        keys.clear();
    }
}
