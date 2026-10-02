import com.mineclone.core.Input;
import com.mineclone.core.Window;
import com.mineclone.data.ResourceId;
import com.mineclone.game.Game;
import com.mineclone.game.Player;
import com.mineclone.save.LevelData;
import com.mineclone.save.SaveManager;
import com.mineclone.sim.WorldClock;
import com.mineclone.sim.WorldSession;
import com.mineclone.sim.ecology.Ecology;
import com.mineclone.sim.ecology.EcologyCodec;
import com.mineclone.sim.ecology.EcologyRegion;
import com.mineclone.ui.MenuAction;
import com.mineclone.ui.WorldSettings;
import com.mineclone.world.Chunk;
import com.mineclone.world.GameMode;
import com.mineclone.world.World;
import com.mineclone.world.damage.DamageSource;
import com.mineclone.world.damage.DamageType;
import com.mineclone.world.entity.Mob;
import com.mineclone.world.entity.MobType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

/** Real hidden Game loop: finite animal placement, owner commands, death, F3 and save/reopen. */
public class PopulationGameSmoke {
    private static Object get(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true); return field.get(target);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true); field.set(target, value);
    }
    private static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        var method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true); return method.invoke(target, args);
    }
    private static Object call(Object target, String name) throws Exception {
        return call(target, name, new Class<?>[0]);
    }
    private static void command(Game game, String value) throws Exception {
        call(game, "executeCommand", new Class<?>[] { String.class }, value);
    }
    private static void check(boolean okay, String message) {
        if (!okay) throw new AssertionError(message);
    }
    private static EcologyCodec.Snapshot snapshot(Ecology ecology) throws Exception {
        Map<String, byte[]> sections = new LinkedHashMap<>();
        ecology.writeSections(sections);
        return EcologyCodec.decode(sections.get(Ecology.SAVE_SECTION));
    }
    private static long population(EcologyCodec.Snapshot snapshot) {
        long result = 0;
        for (EcologyRegion region : snapshot.regions())
            for (int count : region.populations().values()) result += count;
        return result;
    }
    private static int population(EcologyCodec.Snapshot snapshot, long regionKey, ResourceId species) {
        for (EcologyRegion region : snapshot.regions())
            if (region.key() == regionKey) return region.population(species);
        throw new AssertionError("Missing reservation region " + regionKey);
    }

    private static final class ReviewWindow extends Window {
        final Path output;
        final long started = System.nanoTime();
        Game game;
        Throwable failure;
        int stage, stageFrame, frame;
        String worldId;
        long initialPopulation, victimRegion, survivorId, dayBeforeAdvance, dayAfterAdvance;
        Mob victim;
        ResourceId victimSpecies;
        int victimPopulation, itemsBefore;
        byte[] savedEcology, savedRules;
        int savedReservationCount;

        ReviewWindow(Path output) {
            super("Mineclone finite population gameplay check", 1280, 720, false);
            this.output = output;
        }

        private void next(int value) { stage = value; stageFrame = frame; }
        private Input input() throws Exception { return (Input) get(game, "input"); }
        private Player player() throws Exception { return (Player) get(game, "player"); }
        private WorldSession session() throws Exception { return (WorldSession) get(game, "session"); }

        @Override public void update() {
            try {
                frame++;
                check(System.nanoTime() - started < 300_000_000_000L,
                        "Population native smoke timeout at stage " + stage);
                int error = glGetError();
                check(error == GL_NO_ERROR, "OpenGL error 0x" + Integer.toHexString(error));
                tick();
            } catch (Throwable error) {
                failure = error;
                error.printStackTrace();
                glfwSetWindowShouldClose(getHandle(), true);
            }
            super.update();
        }

        private void tick() throws Exception {
            if (game == null) return;
            String state = get(game, "state").toString();
            SaveManager save = (SaveManager) get(game, "save");
            if (stage == 0 && frame > 4) {
                call(game, "createWorld", new Class<?>[] { WorldSettings.class },
                        new WorldSettings("Finite populations native check", 42, GameMode.SURVIVAL));
                worldId = (String) get(game, "worldId");
                next(1);
            } else if (stage == 1 && state.equals("PLAYING") && frame - stageFrame > 20) {
                WorldSession session = session();
                check(!session.ecology().enabled(), "New worlds must keep Living World opt-in");
                int loaded = 0;
                for (Chunk ignored : session.world().getLoadedChunks()) loaded++;
                check(loaded >= 49, "Native chunk streaming did not load the spawn ring: " + loaded);
                command(game, "/time set noon");
                command(game, "/weather clear");
                command(game, "/ecology on");
                check(session.ecology().enabled(), "Game owner command did not enable ecology");
                command(game, "/ecology info");
                String[] panel = (String[]) get(game, "commandPanel");
                check(panel.length >= 9 && panel[0].contains("Ecology ON"), "Real info command has no population rows");
                initialPopulation = population(snapshot(session.ecology()));
                System.out.println("POPULATION_GAME ownerCommands PASS loadedChunks=" + loaded
                        + " initialPopulation=" + initialPopulation);
                // Drive the actual Game -> WorldSession spawn interval every
                // frame, while terrain, entities and their rendering stay real.
                set(session, "spawnTimer", 0f);
                next(2);
            } else if (stage == 2 && state.equals("PLAYING")) {
                WorldSession session = session();
                set(session, "spawnTimer", 0f);
                int passive = 0;
                for (Mob body : session.entities().mobs) {
                    if (body.dead || body.type.temper != MobType.Temper.PASSIVE) continue;
                    passive++;
                    if (victim == null || body.type == MobType.COW || body.type == MobType.PIG)
                        victim = body;
                }
                if (passive < 3) {
                    check(frame - stageFrame < 500, "Generated terrain never materialized a passive herd");
                    victim = null;
                    return;
                }
                EcologyCodec.Snapshot current = snapshot(session.ecology());
                check(population(current) <= initialPopulation, "Materialization created population");
                check(current.reservations().containsKey(victim.id), "Rendered body has no finite reservation");
                set(session, "spawnTimer", 3600f);
                aimAt(victim, true);
                input().holdKey(GLFW_KEY_F3, true);
                input().holdKey(GLFW_KEY_G, true);
                set(game, "commandHelpTimer", 0f);
                System.out.println("POPULATION_GAME finiteBodies PASS passiveBodies=" + passive
                        + " reservations=" + current.reservations().size()
                        + " population=" + population(current));
                next(3);
            } else if (stage == 3) {
                input().holdKey(GLFW_KEY_F3, false);
                input().holdKey(GLFW_KEY_G, false);
                next(4);
            } else if (stage == 4 && state.equals("PLAYING") && frame - stageFrame > 15) {
                check((boolean) get(game, "showDebug"), "F3 input did not enable the ecology HUD");
                check((boolean) get(game, "debugEcology"), "F3+G did not select ecology diagnostics");
                // This screenshot is the previous rendered Game framebuffer,
                // not an offline scene or a generated mock image.
                shot("population-before-death");
                WorldSession session = session();
                EcologyCodec.Snapshot current = snapshot(session.ecology());
                EcologyCodec.Reservation reservation = current.reservations().get(victim.id);
                check(reservation != null, "Selected animal lost its reservation before the hit");
                victimRegion = reservation.regionKey(); victimSpecies = reservation.species();
                victimPopulation = population(current, victimRegion, victimSpecies);
                itemsBefore = session.entities().items.size();
                Player player = player();
                int actor = ((com.mineclone.net.Multiplayer) get(game, "net")).participants().get(0).id();
                check(victim.damage(DamageSource.byPlayer(DamageType.MELEE, actor,
                                player.position.x, player.position.y, player.position.z, 0f), victim.health + 100f),
                        "Real player attack was refused");
                check(victim.dead && victim.killedByParticipant, "Animal did not die from the player hit");
                next(5);
            } else if (stage == 5 && frame - stageFrame >= 1) {
                WorldSession session = session();
                check(victim.deathEffectsDone, "Game update did not process the animal death");
                EcologyCodec.Snapshot current = snapshot(session.ecology());
                check(!current.reservations().containsKey(victim.id), "Dead body retained its reservation");
                int after = population(current, victimRegion, victimSpecies);
                check(after == victimPopulation - 1,
                        "Death did not reduce its regional species by one: " + victimPopulation + " -> " + after);
                check(session.entities().items.size() > itemsBefore, "Real survival death did not drop loot");
                // A second real tick must not account for the same death again.
                session.tickMobs(.001f);
                check(population(snapshot(session.ecology()), victimRegion, victimSpecies) == after,
                        "The corpse was counted as a second death");
                WorldClock clock = (WorldClock) get(game, "worldClock");
                dayBeforeAdvance = Ecology.day(clock.gameTime());
                command(game, "/ecology advance 10");
                dayAfterAdvance = Ecology.day(clock.gameTime());
                check(dayAfterAdvance == dayBeforeAdvance + 10, "Owner command did not advance ten actual days");
                current = snapshot(session.ecology());
                for (EcologyRegion region : current.regions())
                    check(region.lastDay() == dayAfterAdvance, "A region missed the command catch-up");
                Mob survivor = null;
                for (Mob body : session.entities().mobs)
                    if (!body.dead && body.persistent() && (survivor == null
                            || body.position.distanceSquared(player().position) < survivor.position.distanceSquared(player().position)))
                        survivor = body;
                check(survivor != null, "Herd left no living body to reopen");
                survivorId = survivor.id;
                aimAt(survivor, true);
                command(game, "/ecology info");
                set(game, "commandHelpTimer", 0f);
                set(game, "commandToastTimer", 0f);
                System.out.println("POPULATION_GAME deathAndAdvance PASS species=" + victimSpecies
                        + " count=" + victimPopulation + "->" + after + " droppedLoot=true"
                        + " day=" + dayBeforeAdvance + "->" + dayAfterAdvance);
                next(6);
            } else if (stage == 6 && state.equals("PLAYING") && frame - stageFrame > 20) {
                shot("population-after-ten-days");
                call(game, "saveAll"); save.flushAndAwait();
                LevelData level = save.loadLevel(worldId);
                savedEcology = level.extraSections.get(Ecology.SAVE_SECTION);
                savedRules = level.extraSections.get("rules");
                check(savedEcology != null && savedRules != null, "Game save omitted ecology or rules");
                savedReservationCount = EcologyCodec.decode(savedEcology).reservations().size();
                check(savedReservationCount > 0, "Game save omitted body reservations");
                call(game, "handleMenuAction", new Class<?>[] { MenuAction.class },
                        MenuAction.of(MenuAction.Kind.MAIN_MENU));
                check(get(game, "world") == null, "Main menu did not unload native world");
                check((boolean) call(game, "startWorld", new Class<?>[] { String.class }, worldId),
                        "Saved population world was refused");
                next(7);
            } else if (stage == 7 && get(game, "world") != null) {
                WorldSession session = session();
                check(session.ecology().enabled(), "Reopen lost the Living World rule");
                Map<String, byte[]> restored = new LinkedHashMap<>();
                session.ecology().writeSections(restored);
                check(Arrays.equals(savedEcology, restored.get(Ecology.SAVE_SECTION)),
                        "Reopen changed saved regional populations or reservations");
                check(Arrays.equals(savedRules, restored.get("rules")), "Reopen changed rule bytes");
                set(session, "spawnTimer", 3600f);
                next(8);
            } else if (stage == 8 && state.equals("PLAYING") && frame - stageFrame > 25) {
                WorldSession session = session();
                var ids = new HashSet<Long>();
                Mob survivor = null;
                for (Mob body : session.entities().mobs) {
                    check(body.id > 0 && ids.add(body.id), "Reopen duplicated an animal identity");
                    if (body.id == survivorId) survivor = body;
                }
                check(survivor != null, "Reopen did not restore the nearby saved animal " + survivorId);
                check(!survivor.dead, "Saved survivor reopened dead");
                aimAt(survivor, false);
                command(game, "/ecology info");
                set(game, "commandHelpTimer", 0f);
                set(game, "commandToastTimer", 0f);
                check(snapshot(session.ecology()).reservations().size() == savedReservationCount,
                        "Reopening created or lost animal reservations");
                next(9);
            } else if (stage == 9 && state.equals("PLAYING") && frame - stageFrame > 12) {
                shot("population-after-reopen");
                System.out.println("POPULATION_GAME_SMOKE PASS frames=" + frame + " world=" + worldId
                        + " nativeRender=true ownerCommands=true finiteBodies=true deathExactlyOnce=true"
                        + " loot=true advanceDays=10 rulesRoundTrip=true populationsRoundTrip=true"
                        + " reservations=" + savedReservationCount + " restoredBody=" + survivorId
                        + " duplicateBodies=false F3=true screenshots=" + output);
                next(10);
                glfwSetWindowShouldClose(getHandle(), true);
            }
        }

        private void aimAt(Mob body, boolean move) throws Exception {
            Player player = player();
            if (move) {
                World world = session().world();
                int x = (int) Math.floor(body.position.x + 7), z = (int) Math.floor(body.position.z + 7);
                int y = Chunk.SIZE_Y - 1;
                while (y > 0 && !world.getBlock(x, y, z).solid) y--;
                command(game, String.format(Locale.ROOT, "/tp %.3f %.3f %.3f", x + .5f, y + 1.1f, z + .5f));
                player.velocity.zero();
            }
            float dx = body.position.x - player.position.x;
            float dy = body.position.y + body.type.height * .55f - player.position.y - Player.EYE_HEIGHT;
            float dz = body.position.z - player.position.z;
            player.camera.yaw = (float) Math.atan2(dx, -dz);
            player.camera.pitch = (float) -Math.atan2(dy, Math.hypot(dx, dz));
        }

        private void shot(String name) throws Exception {
            int width = getWidth(), height = getHeight();
            var pixels = org.lwjgl.system.MemoryUtil.memAlloc(width * height * 4);
            try {
                glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
                var image = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
                for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                    int at = (y * width + x) * 4;
                    image.setRGB(x, height - 1 - y, ((pixels.get(at) & 255) << 16)
                            | ((pixels.get(at + 1) & 255) << 8) | (pixels.get(at + 2) & 255));
                }
                Path file = output.resolve(name + ".png");
                javax.imageio.ImageIO.write(image, "png", file.toFile());
                System.out.println("POPULATION_GAME screenshot=" + file);
            } finally { org.lwjgl.system.MemoryUtil.memFree(pixels); }
        }
    }

    /** Run after current game compilation: java -cp 'out;libs/*' tools/PopulationGameSmoke.java. */
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length > 0 ? args[0] : "out-test/population-game").toAbsolutePath();
        Files.createDirectories(output);
        Path saves = Files.createTempDirectory(output, "saves-");
        System.setProperty("mineclone.savesDir", saves.toString());
        System.setProperty("mineclone.checkThreadOwnership", "true");
        ReviewWindow window = new ReviewWindow(output);
        window.init();
        boolean normalShutdown = false;
        try {
            window.game = new Game(window, false);
            ((Input) get(window.game, "input")).setGrabAllowed(false);
            set(window.game, "renderRadius", 4);
            set(window.game, "guiScale", 1);
            window.game.run();
            normalShutdown = true;
            if (window.failure != null) throw new AssertionError("Population native Game smoke failed", window.failure);
            check(window.stage == 10, "Population smoke did not reach completion");
        } finally {
            if (!normalShutdown && window.game != null) {
                try { call(window.game, "cleanup"); }
                catch (Exception cleanupFailure) { cleanupFailure.printStackTrace(); }
            }
            window.destroy();
        }
    }
}
