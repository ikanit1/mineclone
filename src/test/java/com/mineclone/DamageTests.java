package com.mineclone;

import com.mineclone.game.Player;
import com.mineclone.world.GameMode;
import com.mineclone.world.damage.ArmorMath;
import com.mineclone.world.damage.ArmorView;
import com.mineclone.world.damage.DamageSource;
import com.mineclone.world.damage.DamageType;
import com.mineclone.world.entity.LimbDamage;
import com.mineclone.world.entity.Mob;
import com.mineclone.world.entity.MobType;
import com.mineclone.world.entity.Projectile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * SURV-01: one damage path. Every hit is a {@link DamageSource}; the player's
 * and the mob's rules live in their {@code damage} methods, and the old entry
 * points are adapters nothing in the game calls.
 */
final class DamageTests {
    static void runAll(TestMain.Runner r) {
        r.run("attacks share the player's window; the world's harm does not", DamageTests::playerWindow);
        r.run("creative survives everything but the void", DamageTests::creative);
        r.run("the last hit that landed is remembered until respawn", DamageTests::deathCause);
        r.run("broken amounts are refused and open no window", DamageTests::brokenAmounts);
        r.run("starvation stops exactly at its floor and says so", DamageTests::starvation);
        r.run("the old player entry points keep their meaning", DamageTests::playerAdapters);
        r.run("a mob's window stops attacks, not the world's harm", DamageTests::mobWindow);
        r.run("a hit inside a mob's window wounds no limb", DamageTests::limbInsideWindow);
        r.run("who killed a mob decides what it leaves", DamageTests::mobKillers);
        r.run("only a player angers a neutral mob; only a participant is avenged", DamageTests::angerAndRevenge);
        r.run("a hit from somewhere knocks away, a hit from nowhere does not", DamageTests::knockback);
        r.run("an arrow names its shooter", DamageTests::arrowSource);
        r.run("armor never touches drowning, starvation or poison", DamageTests::armorBypass);
        r.run("the game calls no old damage entry point", DamageTests::noOldEntryPoints);
    }

    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    private static final DamageSource ZOMBIE = DamageSource.byMob(DamageType.MELEE, MobType.ZOMBIE, 0, 64, 0, 1);

    private static void playerWindow() {
        Player p = new Player();
        check(p.damage(ZOMBIE, 3) && p.health == 17, "first blow");
        check(!p.damage(ZOMBIE, 3) && !p.damage(DamageSource.of(DamageType.PROJECTILE), 3)
                && !p.damage(DamageSource.of(DamageType.EXPLOSION), 3) && p.health == 17, "window ignored");
        for (DamageType world : new DamageType[] { DamageType.FALL, DamageType.FIRE, DamageType.POISON,
                DamageType.STARVE, DamageType.DROWN })
            check(p.damage(DamageSource.of(world), 1), world + " was swallowed by the window");
        check(p.health == 12, "world harm: " + p.health);
        // The world's harm neither opens the window nor delays regeneration.
        Player q = new Player();
        q.damage(DamageSource.of(DamageType.FALL), 2);
        check(q.hurtCooldown == 0f && q.canRegen(), "a fall opened the window");
        check(q.damage(ZOMBIE, 1) && !q.canRegen(), "a blow did not delay regeneration");
        q.hurtCooldown = 0f;
        check(q.damage(ZOMBIE, 1), "a blow after the window");
    }

    private static void creative() {
        Player p = new Player();
        p.setGameMode(GameMode.CREATIVE);
        for (DamageType type : DamageType.values())
            if (type != DamageType.VOID)
                check(!p.damage(DamageSource.of(type), 5), type + " hurt a creative player");
        check(p.health == Player.MAX_HEALTH, "creative lost health");
        check(p.damage(DamageSource.of(DamageType.VOID), 5) && p.health == Player.MAX_HEALTH - 5,
                "the void spared a creative player");
    }

    private static void deathCause() {
        Player p = new Player();
        check(p.lastDamageSource == null, "a new player has a cause");
        p.damage(ZOMBIE, 5);
        check(p.lastDamageSource == ZOMBIE, "blow not remembered");
        p.damage(DamageSource.of(DamageType.PROJECTILE), 5);
        check(p.lastDamageSource == ZOMBIE, "a refused hit replaced the cause");
        DamageSource lava = DamageSource.of(DamageType.LAVA);
        p.damage(lava, 100);
        check(p.isDead() && p.lastDamageSource == lava, "death cause");
        check(!p.damage(DamageSource.of(DamageType.FALL), 1) && p.lastDamageSource == lava, "the dead were hurt");
        p.respawn(0, 70, 0);
        check(p.lastDamageSource == null && p.health == Player.MAX_HEALTH, "respawn kept the cause");
    }

    private static void brokenAmounts() {
        Player p = new Player();
        for (float bad : new float[] { 0f, -3f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY })
            check(!p.damage(ZOMBIE, bad), "accepted " + bad);
        check(p.health == Player.MAX_HEALTH && p.hurtCooldown == 0f && p.lastDamageSource == null,
                "a refused amount changed the player");
        Mob cow = new Mob(MobType.COW, 0, 64, 0, new Random(1));
        for (float bad : new float[] { 0f, -3f, Float.NaN, Float.POSITIVE_INFINITY })
            check(!cow.damage(ZOMBIE, bad), "mob accepted " + bad);
        check(cow.health == MobType.COW.maxHealth && cow.damage(ZOMBIE, 1), "a refused amount opened the window");
    }

    private static void starvation() {
        Player p = new Player();
        p.hunger = 0f;
        p.health = 1.3f;
        for (int i = 0; i < 400; i++) p.tickHunger(0.05f, true);
        check(p.health == Player.STARVE_FLOOR, "starvation floor: " + p.health);
        check(p.lastDamageSource != null && p.lastDamageSource.type() == DamageType.STARVE, "cause of starvation");
    }

    private static void playerAdapters() {
        Player p = new Player();
        check(p.takeAttackDamage(3) && !p.takeAttackDamage(3) && p.health == 17, "attack adapter window");
        p.takeDamage(2);
        check(p.health == 15 && p.lastDamageSource.type() == DamageType.GENERIC, "environment adapter");
    }

    private static void mobWindow() {
        Mob cow = new Mob(MobType.COW, 0, 64, 0, new Random(1));
        float full = cow.health;
        check(cow.damage(ZOMBIE, 2) && !cow.damage(ZOMBIE, 2), "window");
        check(cow.damage(DamageSource.of(DamageType.FIRE), 1) && cow.health == full - 3, "fire held by the window");
        // The fire did not reopen the window: it has run out as for any hit.
        Mob pig = new Mob(MobType.PIG, 0, 64, 0, new Random(2));
        pig.damage(DamageSource.of(DamageType.FIRE), 1);
        check(pig.damage(ZOMBIE, 1), "fire opened the window");
    }

    private static void limbInsideWindow() {
        Mob zombie = new Mob(MobType.ZOMBIE, 0, 64, 0, new Random(3));
        DamageSource leg = DamageSource.byPlayer(DamageType.MELEE, 1, 2, 64, 0, 1).onLimb(LimbDamage.Limb.LEFT_LEG);
        check(zombie.damage(leg, 4), "first blow");
        float wounded = zombie.limbs.damage(LimbDamage.Limb.LEFT_LEG);
        check(wounded > 0f, "the leg took nothing");
        for (int i = 0; i < 10; i++)
            check(!zombie.damage(leg, 4), "a blow inside the window landed");
        check(zombie.limbs.damage(LimbDamage.Limb.LEFT_LEG) == wounded, "clicking inside the window broke the leg");
        // A head blow hurts by the head's own wound.
        Mob other = new Mob(MobType.ZOMBIE, 0, 64, 0, new Random(4));
        float full = other.health;
        other.damage(DamageSource.byPlayer(DamageType.MELEE, 1, 2, 64, 0, 1).onLimb(LimbDamage.Limb.HEAD), 4);
        check(other.health == full - 4 * other.limbs.headDamageMultiplier() && other.limbs.headDamageMultiplier() > 1f,
                "head blow");
    }

    private static void mobKillers() {
        Mob byPlayer = new Mob(MobType.COW, 0, 64, 0, new Random(5));
        byPlayer.damage(DamageSource.byPlayer(DamageType.MELEE, 7, 1, 64, 0, 1), 100);
        check(byPlayer.dead && byPlayer.killedByParticipant && byPlayer.killer == 7 && !byPlayer.eaten, "player kill");
        Mob byWolf = new Mob(MobType.CHICKEN, 0, 64, 0, new Random(6));
        byWolf.damage(DamageSource.byMob(DamageType.MELEE, MobType.WOLF, 1, 64, 0, 0.6f), 100);
        check(byWolf.dead && byWolf.eaten && !byWolf.killedByParticipant, "a wolf's prey is eaten");
        Mob byBlast = new Mob(MobType.PIG, 0, 64, 0, new Random(7));
        byBlast.damage(DamageSource.byMob(DamageType.EXPLOSION, MobType.CREEPER, 1, 64, 0, 1.8f), 100);
        check(byBlast.dead && byBlast.eaten, "a creeper's blast leaves nothing (the 1.0 rule)");
        Mob byFire = new Mob(MobType.ZOMBIE, 0, 64, 0, new Random(8));
        byFire.damage(DamageSource.of(DamageType.FIRE), 100);
        check(byFire.dead && !byFire.eaten && !byFire.killedByParticipant
                && byFire.killer == DamageSource.NO_ATTACKER, "the world's kill drops like a natural death");
        Mob lostArrow = new Mob(MobType.COW, 0, 64, 0, new Random(9));
        lostArrow.damage(DamageSource.byPlayer(DamageType.PROJECTILE, DamageSource.NO_ATTACKER, 1, 64, 0, 0.6f), 100);
        check(lostArrow.killedByParticipant && !lostArrow.eaten, "the arrow of a player who left still counts");
    }

    private static void angerAndRevenge() {
        Mob wolf = new Mob(MobType.WOLF, 0, 64, 0, new Random(10));
        wolf.damage(DamageSource.byMob(DamageType.MELEE, MobType.WOLF, 1, 64, 0, 1), 1);
        check(!wolf.isAngry() && wolf.revengeId == DamageSource.NO_ATTACKER, "a mob's bite angered the wolf");
        Mob other = new Mob(MobType.WOLF, 0, 64, 0, new Random(11));
        other.damage(DamageSource.byPlayer(DamageType.MELEE, 4, 1, 64, 0, 1), 1);
        check(other.isAngry() && other.revengeId == 4, "a player's hit");
        Mob third = new Mob(MobType.WOLF, 0, 64, 0, new Random(12));
        third.damage(DamageSource.byPlayer(DamageType.MELEE, DamageSource.NO_ATTACKER, 1, 64, 0, 1), 1);
        check(third.isAngry() && third.revengeId == DamageSource.NO_ATTACKER, "an unnumbered player hit");
    }

    private static void knockback() throws Exception {
        var knockX = Mob.class.getDeclaredField("knockX");
        knockX.setAccessible(true);
        Mob pushed = new Mob(MobType.COW, 0, 64, 0, new Random(13));
        pushed.damage(DamageSource.byPlayer(DamageType.MELEE, 1, -2, 64, 0, 2f), 1);
        Mob light = new Mob(MobType.COW, 0, 64, 0, new Random(13));
        light.damage(DamageSource.byPlayer(DamageType.MELEE, 1, -2, 64, 0, 1f), 1);
        float strong = (float) knockX.get(pushed), weak = (float) knockX.get(light);
        check(strong > 0f && Math.abs(strong - 2f * weak) < 1e-5f, "knockback away from the hit: " + strong + " " + weak);
        check(pushed.state == Mob.State.FLEE, "a struck cow does not run");
        Mob still = new Mob(MobType.COW, 0, 64, 0, new Random(14));
        still.damage(DamageSource.of(DamageType.MAGIC), 1);
        check((float) knockX.get(still) == 0f && still.state != Mob.State.FLEE, "a hit from nowhere moved the cow");
    }

    private static void arrowSource() {
        Mob skeleton = new Mob(MobType.SKELETON, 0, 64, 0, new Random(15));
        Projectile shot = new Projectile("arrow", skeleton, false, 4f);
        DamageSource bySkeleton = shot.source();
        check(bySkeleton.type() == DamageType.PROJECTILE && bySkeleton.attackerType() == MobType.SKELETON
                && !bySkeleton.byPlayer(), "a skeleton's arrow");
        com.mineclone.net.RemotePlayer guest = new com.mineclone.net.RemotePlayer(6, "guest");
        DamageSource byGuest = new Projectile("arrow", guest, true, 6f).source();
        check(byGuest.byPlayer() && byGuest.participant() == 6 && byGuest.attackerType() == null, "a guest's arrow");
        DamageSource orphan = new Projectile("arrow", null, true, 6f).source();
        check(orphan.byPlayer() && orphan.participant() == DamageSource.NO_ATTACKER, "an arrow whose shooter left");
        check(!new Projectile("arrow", null, false, 6f).source().byPlayer(), "an unowned stray arrow");
        // A guest's arrow on the host kills for that guest.
        Mob cow = new Mob(MobType.COW, 0, 64, 0, new Random(16));
        cow.damage(byGuest, 100);
        check(cow.killedByParticipant && cow.killer == 6, "the guest's kill");
    }

    private static void armorBypass() {
        ArmorView heavy = new ArmorView(20f, 8f);
        for (DamageType type : new DamageType[] { DamageType.DROWN, DamageType.STARVE, DamageType.POISON,
                DamageType.VOID })
            check(type.bypassesArmor() && ArmorMath.afterArmor(7f, type, heavy) == 7f, type + " was armored");
    }

    /** The acceptance of SURV-01: the old entry points are only adapters. */
    private static void noOldEntryPoints() throws Exception {
        // A call with arguments: sounds.hurt() and a snapshot's hurt() are other methods.
        Pattern old = Pattern.compile("\\.(takeDamage|takeAttackDamage|hurt|hurtBy|hurtLimb|takeProjectile)\\((?!\\))");
        List<String> calls = new ArrayList<>();
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++)
                    if (old.matcher(lines.get(i)).find())
                        calls.add(file.getFileName() + ":" + (i + 1) + " " + lines.get(i).trim());
            }
        }
        check(calls.isEmpty(), "old damage entry points called: " + calls);
    }
}
