package com.mineclone;

import com.mineclone.game.LocalParticipant;
import com.mineclone.game.Player;
import com.mineclone.net.RemoteParticipant;
import com.mineclone.net.RemotePlayer;
import com.mineclone.sim.Participant;
import com.mineclone.sim.Participants;
import com.mineclone.sim.ecology.Materializer;
import com.mineclone.world.Biome;
import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.GameMode;
import com.mineclone.world.World;
import com.mineclone.world.damage.ArmorView;
import com.mineclone.world.damage.DamageSource;
import com.mineclone.world.entity.Mob;
import com.mineclone.world.entity.MobSpawner;
import com.mineclone.world.entity.MobType;
import com.mineclone.world.shape.BodyCollision;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** LW-03 placement checks use real terrain queries, camera axes and finite reservation accounting. */
public final class MaterializerTests {
    public static void runAll(TestMain.Runner runner) {
        runner.run("materialization uses actual local and accepted remote camera direction", MaterializerTests::cameraAxes);
        runner.run("finite materialization is deterministic and never creates population or duplicate bodies", MaterializerTests::finite);
        runner.run("materialization avoids the distance and view of every living participant", MaterializerTests::allParticipants);
        runner.run("a second participant's view prevents bodies even behind the first", MaterializerTests::secondView);
        runner.run("materialization cannot generate chunks or put animals in water", MaterializerTests::loadedAndDry);
        runner.run("materialized groups respect local and global caps without overshooting", MaterializerTests::groupCap);
        runner.run("hostile-only spawning never adds infinite animals in living mode", MaterializerTests::hostilesOnly);
    }

    private static void cameraAxes() {
        Player player = new Player();
        LocalParticipant local = new LocalParticipant(player, () -> 1);
        Vector3f actual = new Vector3f();
        for (float yaw : new float[] { 0f, (float) Math.PI / 2, (float) Math.PI, -(float) Math.PI / 2 })
            for (float pitch : new float[] { -.7f, 0f, .7f }) {
                player.camera.yaw = yaw; player.camera.pitch = pitch;
                check(local.viewDirection(actual) == actual && actual.distance(player.camera.forward()) < 1e-6f,
                        "local axes disagree with camera");
            }
        RemotePlayer remote = new RemotePlayer(2, "view");
        remote.accept(0, 41, 0, 0, 0, 0); remote.update(1f);
        remote.accept(1, 41, 0, (float) Math.PI / 2, .7f, 0);
        new RemoteParticipant(remote).viewDirection(actual);
        check(remote.yaw == 0f && actual.x > .7f && actual.y < -.6f && Math.abs(actual.z) < 1e-5f,
                "remote view followed eased model instead of accepted camera");
    }

    private static void finite() {
        Fixture a = new Fixture(0, 0), b = new Fixture(0, 0);
        Stock stockA = new Stock(MobType.COW, 5), stockB = new Stock(MobType.COW, 5);
        Materializer ma = new Materializer(93), mb = new Materializer(93);
        for (int n = 0; n < 150; n++) {
            ma.tryMaterialize(a.world, a.mobs, a.players, 1f, stockA);
            mb.tryMaterialize(b.world, b.mobs, b.players, 1f, stockB);
        }
        check(a.mobs.size() == 5 && b.mobs.size() == 5, "finite population not exhausted exactly");
        check(stockA.total == 5 && stockA.claimed.size() == 5 && stockA.available(MobType.COW, 0, 0) == 0,
                "materializing altered total population");
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < a.mobs.size(); i++) {
            Mob one = a.mobs.get(i), two = b.mobs.get(i);
            check(one.id != 0 && ids.add(one.id), "identity reused");
            check(one.position.equals(two.position) && one.type == two.type && one.id == two.id,
                    "seed replay changed placement");
            check(!BodyCollision.overlaps(a.world, one.position.x, one.position.y, one.position.z,
                    one.type.width, one.type.height), "body overlaps terrain");
        }
        Stock refusal = new Stock(MobType.COW, 5) {
            @Override public boolean claim(Mob body) { return false; }
        };
        Fixture rejected = new Fixture(0, 0);
        for (int n = 0; n < 50; n++)
            new Materializer(n).tryMaterialize(rejected.world, rejected.mobs, rejected.players, 1f, refusal);
        check(rejected.mobs.isEmpty(), "failed reservation still added a body");
    }

    private static void allParticipants() {
        Fixture fixture = new Fixture(0, 0);
        View guest = new View(2, 50, 0, -1, 0, 0);
        fixture.players.put(guest);
        Stock stock = new Stock(MobType.COW, 100);
        Materializer materializer = new Materializer(1);
        for (int n = 0; n < 200; n++)
            materializer.tryMaterialize(fixture.world, fixture.mobs, fixture.players, 1f, stock);
        check(!fixture.mobs.isEmpty(), "multi-player placement vacuously passed");
        check(fixture.mobs.size() <= MobSpawner.PEACEFUL_CAP, "overlapping participants exceeded shared local cap");
        for (Mob body : fixture.mobs)
            for (int i = 0; i < fixture.players.size(); i++) {
                View player = (View) fixture.players.get(i);
                check(player.position.distanceSquared(body.position) >= Materializer.MIN_DISTANCE * Materializer.MIN_DISTANCE,
                        "too close to participant " + player.id);
                check(!insideView(player, body.position.x, body.position.y + body.type.height * .5f, body.position.z),
                        "appeared in participant " + player.id + " view");
            }
    }

    private static void secondView() {
        Fixture fixture = new Fixture(0, 0);
        View guest = new View(2, 0, 0, 0, 0, 1);
        fixture.players.put(guest);
        Stock stock = new Stock(MobType.COW, 100) {
            @Override public int available(MobType type, float x, float z) {
                // Eligible sites are behind the host, inside the guest's view.
                return z > 0 && insideView(guest, x, 41.7f, z) ? super.available(type, x, z) : 0;
            }
        };
        Materializer materializer = new Materializer(33);
        for (int n = 0; n < 150; n++)
            materializer.tryMaterialize(fixture.world, fixture.mobs, fixture.players, 1f, stock);
        check(fixture.mobs.isEmpty() && stock.claimed.isEmpty(), "only the host view was checked");
        guest.alive = false;
        for (int n = 0; n < 100; n++)
            materializer.tryMaterialize(fixture.world, fixture.mobs, fixture.players, 1f, stock);
        check(!fixture.mobs.isEmpty(), "dead participant still blocked placement");
    }

    private static void loadedAndDry() {
        World absent = new World(73) {
            @Override public Chunk generateDetached(int cx, int cz) { throw new AssertionError("spawn generated terrain"); }
        };
        Participants players = new Participants(); players.put(new View(1, 0, 0, 0, 0, -1));
        List<Mob> mobs = new ArrayList<>();
        Stock stock = new Stock(MobType.COW, 100);
        Materializer materializer = new Materializer(13);
        for (int n = 0; n < 100; n++) materializer.tryMaterialize(absent, mobs, players, 1f, stock);
        check(mobs.isEmpty(), "unloaded terrain accepted");
        Fixture flooded = new Fixture(0, 0);
        for (Chunk chunk : flooded.world.getLoadedChunks())
            for (int x = 0; x < Chunk.SIZE_X; x++)
                for (int z = 0; z < Chunk.SIZE_Z; z++)
                    chunk.set(x, 41, z, BlockType.WATER);
        for (int n = 0; n < 100; n++)
            materializer.tryMaterialize(flooded.world, flooded.mobs, flooded.players, 1f, stock);
        check(flooded.mobs.isEmpty() && stock.claimed.isEmpty(), "animal materialized in water");
    }

    private static void groupCap() {
        Fixture local = new Fixture(0, 0);
        Stock cows = new Stock(MobType.COW, 100) {
            @Override public int groupMin(MobType type) { return 8; }
            @Override public int groupMax(MobType type) { return 8; }
        };
        for (int n = 0; n < MobSpawner.PEACEFUL_CAP - 1; n++)
            local.mobs.add(new Mob(MobType.COW, n, 41, 10, new Random(n)));
        Materializer materializer = new Materializer(88);
        for (int n = 0; n < 150; n++)
            materializer.tryMaterialize(local.world, local.mobs, local.players, 1f, cows);
        check(local.mobs.size() == MobSpawner.PEACEFUL_CAP && cows.claimed.size() == 1,
                "group overshot local cap");

        // Populate a forest with only wolves eligible; the group also has one
        // remaining slot in the smaller wildlife cap.
        World biomes = new World(73);
        int forestX = 0, forestZ = 0;
        boolean forest = false;
        for (int x = -2000; x <= 2000 && !forest; x += 32)
            for (int z = -2000; z <= 2000 && !forest; z += 32)
                if (biomes.biomes.biomeAt(x, z) == Biome.FOREST) {
                    forestX = x; forestZ = z; forest = true;
                }
        check(forest, "forest fixture not found");
        Fixture wild = new Fixture(forestX, forestZ);
        Stock wolves = new Stock(MobType.WOLF, 100);
        for (int n = 0; n < MobSpawner.WILDLIFE_CAP - 1; n++)
            wild.mobs.add(new Mob(MobType.WOLF, forestX + n, 41, forestZ + 10, new Random(n)));
        for (int n = 0; n < 300; n++)
            materializer.tryMaterialize(wild.world, wild.mobs, wild.players, 1f, wolves);
        check(wild.mobs.size() == MobSpawner.WILDLIFE_CAP && wolves.claimed.size() == 1,
                "wolf pack overshot wildlife cap");

        Fixture global = new Fixture(0, 0);
        for (int n = 0; n < MobSpawner.PEACEFUL_CAP; n++)
            global.mobs.add(new Mob(MobType.COW, 1000 + n, 41, 1000, new Random(n)));
        Stock blocked = new Stock(MobType.COW, 100);
        for (int n = 0; n < 100; n++)
            materializer.tryMaterialize(global.world, global.mobs, global.players, 1f, blocked);
        check(global.mobs.size() == MobSpawner.PEACEFUL_CAP && blocked.claimed.isEmpty(),
                "far bodies were ignored by global cap");
    }

    private static void hostilesOnly() {
        Fixture fixture = new Fixture(0, 0);
        MobSpawner spawner = new MobSpawner(3);
        List<Vector3fc> centres = List.of(fixture.players.get(0).position());
        for (int n = 0; n < 150; n++) spawner.trySpawnHostiles(fixture.world, fixture.mobs, centres, 0f);
        check(!fixture.mobs.isEmpty() && fixture.mobs.size() <= MobSpawner.HOSTILE_CAP,
                "hostile spawn/cap path did not run");
        for (Mob body : fixture.mobs) check(body.type.hostile, "hostile path created an animal");
    }

    private static boolean insideView(View player, float x, float y, float z) {
        Vector3fc eye = player.eye();
        float dx = x - eye.x(), dy = y - eye.y(), dz = z - eye.z();
        float dot = dx * player.direction.x + dy * player.direction.y + dz * player.direction.z;
        return dot >= Math.cos(Materializer.VIEW_HALF_ANGLE) * Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static class Stock implements Materializer.Populations {
        final MobType type;
        final int total;
        final List<Mob> claimed = new ArrayList<>();
        Stock(MobType type, int total) { this.type = type; this.total = total; }
        @Override public int available(MobType kind, float x, float z) {
            return kind == type ? total - claimed.size() : 0;
        }
        @Override public boolean claim(Mob body) {
            if (available(body.type, body.position.x, body.position.z) <= 0) return false;
            body.id = claimed.size() + 1;
            check(!claimed.contains(body), "body reserved twice");
            claimed.add(body);
            return true;
        }
    }

    private static final class Fixture {
        final World world = new World(73);
        final List<Mob> mobs = new ArrayList<>();
        final Participants players = new Participants();
        Fixture(int x, int z) {
            players.put(new View(1, x, z, 0, 0, -1));
            int cx = Math.floorDiv(x, Chunk.SIZE_X), cz = Math.floorDiv(z, Chunk.SIZE_Z);
            for (int dx = -4; dx <= 4; dx++)
                for (int dz = -4; dz <= 4; dz++) {
                    Chunk chunk = new Chunk(cx + dx, cz + dz);
                    for (int lx = 0; lx < Chunk.SIZE_X; lx++)
                        for (int lz = 0; lz < Chunk.SIZE_Z; lz++)
                            chunk.set(lx, 40, lz, BlockType.GRASS);
                    world.publish(chunk);
                }
        }
    }

    private static final class View implements Participant {
        final int id;
        final Vector3f position = new Vector3f(), direction = new Vector3f(), eye = new Vector3f();
        boolean alive = true;
        View(int id, float x, float z, float dx, float dy, float dz) {
            this.id = id; position.set(x, 41, z); direction.set(dx, dy, dz).normalize();
        }
        @Override public int id() { return id; }
        @Override public Vector3fc position() { return position; }
        @Override public Vector3fc eye() { return eye.set(position.x, position.y + EYE_HEIGHT, position.z); }
        @Override public Vector3f viewDirection(Vector3f target) { return target.set(direction); }
        @Override public GameMode mode() { return GameMode.SURVIVAL; }
        @Override public boolean alive() { return alive; }
        @Override public ArmorView armor() { return ArmorView.NONE; }
        @Override public boolean local() { return false; }
        @Override public boolean damage(DamageSource source, float amount) { return false; }
    }

    private static void check(boolean okay, String message) { if (!okay) throw new AssertionError(message); }
}
