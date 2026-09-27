package com.mineclone.save;

/**
 * A persistent mob as its chunk's {@code entities} section holds it (MOB-01).
 *
 * <p>{@code type} is the {@code MobType} name; a type this build does not know stays a
 * record and is written back as it came. {@code tail} is whatever a newer build appended
 * to the record: kept and written back unchanged.
 */
public record SavedMob(String type, long id, float x, float y, float z, float yaw, float health, byte[] tail) {
    public SavedMob {
        tail = tail == null ? new byte[0] : tail.clone();
    }

    @Override
    public byte[] tail() {
        return tail.clone();
    }
}
