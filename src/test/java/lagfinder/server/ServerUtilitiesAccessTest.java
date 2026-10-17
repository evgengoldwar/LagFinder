package lagfinder.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;

import org.junit.Test;

public class ServerUtilitiesAccessTest {

    public static class Rank {

        private final String id;
        private final List<Rank> parents = new ArrayList<>();

        public Rank(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }

        public List<Rank> getActualParents() {
            return parents;
        }
    }

    private static boolean allowed(Rank rank) throws Exception {
        return ServerUtilitiesAccess.matches(rank, Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>()));
    }

    @Test
    public void allowsDirectRanksAndDeniesSimilarNames() throws Exception {
        assertTrue(allowed(new Rank("lagfinder_admin")));
        assertFalse(allowed(new Rank("admin_lagfinder_admin")));
        assertFalse(allowed(new Rank("lagfinder")));
        assertFalse(allowed(new Rank("player")));
        assertFalse(allowed(null));
    }

    @Test
    public void allowsMultipleAndDeeplyInheritedRanks() throws Exception {
        Rank player = new Rank("player"), current = player;
        player.parents.add(new Rank("unrelated"));
        for (int i = 0; i < 32; i++) {
            Rank parent = new Rank("parent" + i);
            current.parents.add(parent);
            current = parent;
        }
        current.parents.add(new Rank("lagfinder_viewer"));
        assertTrue(allowed(player));
    }

    @Test
    public void handlesCyclesWithoutGrantingAccess() throws Exception {
        Rank first = new Rank("first"), second = new Rank("second");
        first.parents.add(second);
        second.parents.add(first);
        assertFalse(allowed(first));
        second.parents.add(new Rank("lagfinder_admin"));
        assertTrue(allowed(first));
    }
}
