package com.mineclone.save;

/**
 * A persistent mob as its chunk's {@code entities} section holds it (MOB-01).
 *
 * <p>{@code type} is the {@code MobType} name; a type this build does not know stays a
 * record and is written back as it came. {@code tail} is whatever a newer build appended
 * to the record: kept and written back unchanged.
 */
public record SavedMob(String type, long id, float x, float y, float z, float yaw, float health,
                       float age, boolean persistent, float loveCooldown, float love, byte[] tail) {
    /** The original MOB-01 records represented adult persistent animals. */
    public SavedMob(String type, long id, float x, float y, float z, float yaw, float health, byte[] tail) {
        this(type, id, x, y, z, yaw, health, 0f, true, 0f, 0f, tail);
    }

    public SavedMob {
        tail = tail == null ? new byte[0] : tail.clone();
    }

    public static SavedMob from(com.mineclone.world.entity.Mob mob) {
        return new SavedMob(mob.type.name(), mob.id, mob.position.x, mob.position.y, mob.position.z,
                mob.yaw, mob.health, mob.age, mob.persistent(), mob.loveCooldown, mob.love, mob.savedTail);
    }

    /** Unknown types remain records; the caller keeps them with their chunk. */
    public com.mineclone.world.entity.Mob toMob(java.util.Random random) {
        var known = com.mineclone.world.entity.MobType.byName(type);
        if (known == null) return null;
        var mob = new com.mineclone.world.entity.Mob(known, x, y, z, random);
        mob.id = id;
        mob.yaw = yaw;
        mob.health = Math.min(health, known.maxHealth);
        mob.age = age;
        mob.persistent = persistent;
        mob.loveCooldown = loveCooldown;
        mob.love = love;
        mob.savedTail = tail.length == 0 ? null : tail.clone();
        return mob;
    }

    @Override
    public byte[] tail() {
        return tail.clone();
    }
}
