package com.mineclone;

import com.mineclone.item.Combat;
import com.mineclone.item.Item;
import com.mineclone.item.Items;

/**
 * Боевые числа: раньше урон был константой и не зависел от того, что в руке.
 */
final class CombatTests {
    static void runAll(NetworkTests.Runner r) {
        r.run("a sword out-damages an axe over time, an axe per swing", CombatTests::swordVsAxe);
        r.run("swinging early costs damage", CombatTests::readiness);
        r.run("the bare hand hits exactly as it always did", CombatTests::hand);
        r.run("every weapon tier is an upgrade, and a pickaxe is not a weapon",
                CombatTests::tiers);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static Item item(String id) {
        return Items.get().require(id);
    }

    /**
     * Ровно то, ради чего скорость атаки задаётся отдельно от урона: топор
     * бьёт больнее за раз, меч — больше за секунду. Без этого топор был бы
     * просто хуже меча, а выбор между ними — фиктивным.
     */
    private static void swordVsAxe() {
        Item sword = item("iron_sword"), axe = item("iron_axe");
        check(Combat.baseDamage(axe) > Combat.baseDamage(sword),
                "an axe must hurt more per swing: " + Combat.baseDamage(axe)
                        + " vs " + Combat.baseDamage(sword));
        check(Combat.dps(sword) > Combat.dps(axe),
                "a sword must win over time: " + Combat.dps(sword) + " vs " + Combat.dps(axe));
        check(Combat.cooldown(axe) > Combat.cooldown(sword), "an axe must swing slower");
    }

    /** Удар до конца отката бьёт слабее — иначе закликивание выгодно. */
    private static void readiness() {
        Item sword = item("iron_sword");
        float full = Combat.damage(sword, 1f, false);
        float half = Combat.damage(sword, 0.5f, false);
        float none = Combat.damage(sword, 0f, false);
        check(full > half && half > none, "damage must rise with readiness");
        check(half < full * 0.5f, "half the cooldown gives less than half the damage");
        check(none > 0f, "a hasty swing still lands something");
        check(Math.abs(none - Combat.baseDamage(sword) * Combat.MIN_CHARGED) < 1e-4,
                "the floor is MIN_CHARGED of the base");
        check(Math.abs(Combat.damage(sword, 1f, true) - full * Combat.CRIT_MULTIPLIER) < 1e-4,
                "a crit multiplies the finished swing");

        float cd = Combat.cooldown(sword);
        check(Combat.readiness(cd, cd) == 0f, "a fresh cooldown is no readiness at all");
        check(Combat.readiness(0f, cd) == 1f, "an elapsed cooldown is full readiness");
    }

    /**
     * Мобы настроены под прежний урон руки, поэтому он обязан остаться тем
     * же: 2 за удар, два удара в секунду.
     */
    private static void hand() {
        check(Combat.baseDamage(null) == 2f, "bare hand still hits for one heart");
        check(Combat.cooldown(null) == 0.5f, "bare hand still swings twice a second");
        check(Combat.dps(null) == 4f, "bare-hand dps is unchanged");
        check(!Combat.isWeapon(null), "a fist is not a weapon item");
    }

    /** Материал должен что-то значить, а кирка — не быть оружием выбора. */
    private static void tiers() {
        String[] ladder = { "wooden_sword", "stone_sword", "iron_sword", "diamond_sword" };
        float previous = 0f;
        for (String id : ladder) {
            float d = Combat.baseDamage(item(id));
            check(d > previous, id + " must beat the tier below it");
            previous = d;
        }
        // Кирка бьёт, но слабее меча того же материала и реже его.
        check(Combat.isWeapon(item("iron_pickaxe")), "a pickaxe does have an attack");
        check(Combat.dps(item("iron_pickaxe")) < Combat.dps(item("iron_sword")),
                "but it is no match for a sword");
        // Лук в ближнем бою — не оружие: он стреляет, а не рубит.
        check(!Combat.isWeapon(item("bow")), "a bow has no melee attack");
        check(Combat.baseDamage(item("bow")) == Combat.HAND_DAMAGE,
                "so swinging it is swinging a fist");
    }
}
