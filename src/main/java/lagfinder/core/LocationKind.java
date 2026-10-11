package lagfinder.core;

/** What an instance's position stands for, which decides how it is highlighted. */
public enum LocationKind {

    /** No place in the world. */
    NONE,
    /** One block (a tile entity). */
    BLOCK,
    /** An entity, at its feet. */
    ENTITY,
    /** A chunk, with the block that ticked last in it. */
    CHUNK,
    /** A 16x16x16 section of a chunk, from its lowest corner. */
    SECTION
}
