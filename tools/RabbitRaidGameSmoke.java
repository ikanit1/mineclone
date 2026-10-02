import com.mineclone.core.Input;
import com.mineclone.core.Window;
import com.mineclone.data.ResourceId;
import com.mineclone.game.Game;
import com.mineclone.game.Player;
import com.mineclone.save.SaveManager;
import com.mineclone.sim.WorldClock;
import com.mineclone.sim.WorldSession;
import com.mineclone.sim.ecology.Ecology;
import com.mineclone.sim.ecology.EcologyCodec;
import com.mineclone.sim.ecology.EcologyRegion;
import com.mineclone.ui.MenuAction;
import com.mineclone.ui.WorldSettings;
import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.GameMode;
import com.mineclone.world.World;
import com.mineclone.world.Weather;
import com.mineclone.world.behavior.Behaviors;
import com.mineclone.world.damage.DamageSource;
import com.mineclone.world.damage.DamageType;
import com.mineclone.world.entity.Mob;
import com.mineclone.world.entity.MobType;
import com.mineclone.world.shape.BodyCollision;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

/** Actual hidden Game loop: story A's wolf deaths, seven days, two plots, gate and save/reopen. */
public final class RabbitRaidGameSmoke {
    private static final long SEED = 20261002L;
    private static final int FLOOR = 100, CROP = FLOOR + 1, OPEN_X = 8, CLOSED_X = 32, PLOT_Z = 14;
    private static final ResourceId RABBIT = ResourceId.of("rabbit"), WOLF = ResourceId.of("wolf");

    private static Object get(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        var method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(target, args);
    }
    private static Object call(Object target, String name) throws Exception { return call(target, name, new Class<?>[0]); }
    private static void command(Game game, String value) throws Exception { call(game, "executeCommand", new Class<?>[] {String.class}, value); }
    private static void check(boolean okay, String why) { if (!okay) throw new AssertionError(why); }
    private static EcologyCodec.Snapshot snapshot(Ecology ecology) throws Exception {
        Map<String, byte[]> sections = new LinkedHashMap<>(); ecology.writeSections(sections);
        return EcologyCodec.decode(sections.get(Ecology.SAVE_SECTION));
    }

    private static final class ReviewWindow extends Window {
        final Path output;
        final long started = System.nanoTime();
        Game game;
        Throwable failure;
        int frame, stage, stageFrame;
        float stageSeconds;
        String worldId;
        boolean reviewPositionRequested;
        Mob[] wolves = new Mob[5];
        Mob openRabbit, closedRabbit;
        long openId, closedId, survivorWolfId, initialDay;
        int beforeRabbits, afterRabbits, openDamage, finalOpenDamage, finalClosedDamage, savedReservations;
        byte[] savedEcology;
        byte[] savedStages;
        Mob noiseSpider;
        int noiseX, noiseZ;
        static final int NOISE_FLOOR = 70, NOISE_STONE_Y = NOISE_FLOOR + 2;

        ReviewWindow(Path output) { super("Mineclone rabbit raid story A", 1280, 720, false); this.output = output; }
        private void next(int value) { stage = value; stageFrame = frame; stageSeconds = 0f; }
        private WorldSession session() throws Exception { return (WorldSession) get(game, "session"); }
        private Player player() throws Exception { return (Player) get(game, "player"); }
        private Input input() throws Exception { return (Input) get(game, "input"); }

        @Override public void update() {
            try {
                frame++;
                check(System.nanoTime() - started < 300_000_000_000L, "Rabbit native Game smoke timed out at stage " + stage);
                int error = glGetError(); check(error == GL_NO_ERROR, "OpenGL error 0x" + Integer.toHexString(error));
                if (game != null) stageSeconds += (float) get(game, "lastDt");
                tick();
            } catch (Throwable error) {
                failure = error; error.printStackTrace(); glfwSetWindowShouldClose(getHandle(), true);
            }
            super.update();
        }

        private void tick() throws Exception {
            if (game == null) return;
            String state = get(game, "state").toString();
            SaveManager save = (SaveManager) get(game, "save");
            if (get(game, "session") != null) set(session(), "spawnTimer", 3600f);
            if (stage >= 3 && stage <= 10 && stage != 6 && state.equals("PLAYING")) reviewPose();
            if (stage == 0 && frame > 4) {
                call(game, "createWorld", new Class<?>[] {WorldSettings.class},
                        new WorldSettings("Rabbit raid story A native check", SEED, GameMode.CREATIVE));
                worldId = (String) get(game, "worldId"); next(1);
            } else if (stage == 1 && state.equals("PLAYING") && frame - stageFrame > 20) {
                WorldSession session = session(); World world = session.world();
                check(!session.ecology().enabled(), "new world did not leave Living World opt-in");
                command(game, "/time set noon"); command(game, "/weather clear");
                if (!reviewPositionRequested) {
                    command(game, "/tp 22.5 115 23"); command(game, "/fly"); player().velocity.zero();
                    check(player().flying, "real creative flight command did not enable the observer");
                    reviewPositionRequested = true; stageFrame = frame; stageSeconds = 0f; return;
                }
                if (stageSeconds < 3f) return;
                boolean loaded = true;
                for (int cx = -3; cx <= 5; cx++) for (int cz = -3; cz <= 5; cz++) loaded &= world.getChunkIfExists(cx, cz) != null;
                if (!loaded) { check(frame - stageFrame < 600, "Game did not stream the fixture's native chunks"); return; }
                session.entities().mobs.clear(); session.entities().items.clear();
                buildFixture(world);
                check(!BodyCollision.overlaps(world, player().position.x, player().position.y,
                        player().position.z, .6f, 1.8f), "review observer intersects generated terrain");
                for (int i = 0; i < wolves.length; i++) {
                    wolves[i] = new Mob(MobType.WOLF, 55.5f + i, CROP + .001f, 27.5f, new Random(SEED + i));
                    check(!BodyCollision.overlaps(world, wolves[i].position.x, wolves[i].position.y,
                            wolves[i].position.z, wolves[i].type.width, wolves[i].type.height), "wolf fixture intersects generated terrain");
                    wolves[i].onGround = true; session.entities().mobs.add(wolves[i]);
                }
                command(game, "/ecology on");
                EcologyRegion region = session.ecology().regionAt(8, 14);
                region.population(RABBIT, 20); region.population(WOLF, 5);
                beforeRabbits = region.population(RABBIT); survivorWolfId = wolves[4].id;
                check(snapshot(session.ecology()).reservations().size() == 5, "fixture wolves were not finite reserved bodies");
                freezeCropGrowth(world); aimAtFields();
                ((com.mineclone.world.Inventory) get(game, "inventory")).set(0, null);
                input().holdKey(GLFW_KEY_F3, true); input().holdKey(GLFW_KEY_G, true);
                next(2);
            } else if (stage == 2) {
                check(player().flying, "real F3+G ecology shortcut changed the observer's creative flight");
                input().holdKey(GLFW_KEY_F3, false); input().holdKey(GLFW_KEY_G, false);
                next(3);
            } else if (stage == 3 && state.equals("PLAYING") && stageSeconds > 1f) {
                check((boolean) get(game, "showDebug") && (boolean) get(game, "debugEcology"), "native F3+G did not show ecology diagnostics");
                check(damaged(OPEN_X) == 0 && damaged(CLOSED_X) == 0, "initial fields were not equivalent mature crops");
                clearToasts(); shot("raid-day-0-five-wolves");
                Player player = player();
                int actor = ((com.mineclone.net.Multiplayer) get(game, "net")).participants().get(0).id();
                for (int i = 0; i < 4; i++) check(wolves[i].damage(DamageSource.byPlayer(DamageType.MELEE, actor,
                        player.position.x, player.position.y, player.position.z, 0f), 100f), "player wolf removal was refused");
                next(4);
            } else if (stage == 4 && state.equals("PLAYING") && stageSeconds > 1f) {
                WorldSession session = session(); EcologyRegion region = session.ecology().regionAt(8, 14);
                check(region.population(WOLF) == 1, "normal Game death retirement did not remove exactly four wolves");
                check(region.lossCount(EcologyRegion.LossCause.PLAYER) == 4, "Game did not record four player wolf losses once each");
                for (int i = 0; i < 4; i++) check(wolves[i].deathEffectsDone, "Game did not retire a dead wolf");
                WorldClock clock = (WorldClock) get(game, "worldClock"); initialDay = Ecology.day(clock.gameTime());
                command(game, "/ecology advance 7");
                check(Ecology.day(clock.gameTime()) == initialDay + 7, "real owner command did not advance exactly seven days");
                afterRabbits = session.ecology().regionAt(8, 14).population(RABBIT);
                check(afterRabbits > beforeRabbits, "seven days did not recover the regional rabbits: " + beforeRabbits + " -> " + afterRabbits);
                openRabbit = rabbit(9.5f, 11.5f, 20); closedRabbit = rabbit(33.5f, 10.5f, 21);
                openId = openRabbit.id; closedId = closedRabbit.id;
                clearToasts(); aimAtFields();
                System.out.println("RABBIT_RAID_GAME wolvesAndDays PASS wolves=5->1 playerDeaths=4 day=" + initialDay + "->"
                        + (initialDay + 7) + " rabbits=" + beforeRabbits + "->" + afterRabbits + " controlledFiniteRabbitBodies=2");
                next(5);
            } else if (stage == 5 && state.equals("PLAYING")) {
                openDamage = damaged(OPEN_X);
                check(damaged(CLOSED_X) == 0, "a rabbit damaged the fully fenced field through its closed gate");
                check(!openRabbit.dead && !closedRabbit.dead, "story rabbit died before comparison");
                check(stageSeconds < 35f, "normal Game rabbit brain never ate the open field");
                if (stageSeconds < 12f || openDamage == 0) return;
                check(closedRabbit.position.z < 12.5f, "rabbit crossed a closed gate");
                shot("raid-day-7-open-damaged-closed-intact");
                System.out.println("RABBIT_RAID_GAME fields PASS openDamaged=" + openDamage + "/16 closedDamaged=0/16"
                        + " nativeSimulationSeconds=" + stageSeconds + " cropGrowthFrozen=true supportsIntact=true");
                savedStages = stages(); call(game, "saveAll"); save.flushAndAwait();
                savedEcology = save.loadLevel(worldId).extraSections.get(Ecology.SAVE_SECTION);
                check(savedEcology != null, "Game save omitted ecology");
                savedReservations = EcologyCodec.decode(savedEcology).reservations().size();
                call(game, "handleMenuAction", new Class<?>[] {MenuAction.class}, MenuAction.of(MenuAction.Kind.MAIN_MENU));
                check(get(game, "world") == null, "main menu did not unload the real world");
                check((boolean) call(game, "startWorld", new Class<?>[] {String.class}, worldId), "native save reopen was refused");
                next(6);
            } else if (stage == 6 && get(game, "world") != null) {
                check(session().ecology().enabled(), "reopen lost Living World rule");
                Map<String, byte[]> restored = new LinkedHashMap<>(); session().ecology().writeSections(restored);
                check(Arrays.equals(savedEcology, restored.get(Ecology.SAVE_SECTION)), "reopen changed saved finite populations or reservations");
                freezeCropGrowth(session().world());
                // Flight is transient rather than saved; resume this review pose through the owner command.
                if (!player().flying) command(game, "/fly");
                next(7);
            } else if (stage == 7 && state.equals("PLAYING") && stageSeconds > 1f) {
                check(Arrays.equals(savedStages, stages()), "save/reopen changed either plot's exact stages");
                var ids = new HashSet<Long>(); openRabbit = null; closedRabbit = null; boolean wolf = false;
                for (Mob body : session().entities().mobs) {
                    check(body.id > 0 && ids.add(body.id), "reopen duplicated a finite animal identity");
                    if (body.id == openId) openRabbit = body;
                    if (body.id == closedId) closedRabbit = body;
                    if (body.id == survivorWolfId) wolf = true;
                }
                check(openRabbit != null && closedRabbit != null && wolf, "reopen lost a saved story body");
                check(snapshot(session().ecology()).reservations().size() == savedReservations, "reopen changed reservation ownership");
                aimAtFields(); clearToasts(); next(8);
            } else if (stage == 8 && state.equals("PLAYING") && stageSeconds > .5f) {
                shot("raid-after-save-reopen-closed-intact");
                check(damaged(CLOSED_X) == 0, "the closed field changed after reopen");
                // Open the actual block; the following Game frames decide whether the rabbit can enter.
                session().world().setBlock(33, CROP, 12, BlockType.FENCE_GATE, (byte) 4);
                set(closedRabbit, "hungerTimer", 0f); next(9);
            } else if (stage == 9 && state.equals("PLAYING")) {
                check(stageSeconds < 35f, "opening the gate did not grant the actual rabbit access");
                if (damaged(CLOSED_X) == 0 || stageSeconds < 1f) return;
                aimAtFields(); clearToasts(); next(10);
            } else if (stage == 10 && state.equals("PLAYING") && stageSeconds > .75f) {
                shot("raid-open-gate-access");
                finalOpenDamage = damaged(OPEN_X); finalClosedDamage = damaged(CLOSED_X);
                prepareHearingFixture(); command(game, "/weather clear"); next(11);
            } else if (stage == 11 && state.equals("PLAYING") && frame - stageFrame > 2) {
                Weather.State weather = (Weather.State) get(get(game, "atmosphere"), "global");
                check(weather.precipitation() == 0f, "clear owner command did not reach authoritative Game weather");
                noiseSpider = hearingSpider(); placeNoiseStone(); input().injectMouseButton(GLFW_MOUSE_BUTTON_LEFT, true); next(12);
            } else if (stage == 12 && state.equals("PLAYING") && frame - stageFrame >= 1) {
                input().injectMouseButton(GLFW_MOUSE_BUTTON_LEFT, false);
                check(session().world().getBlock(noiseX, NOISE_STONE_Y, noiseZ) == BlockType.AIR,
                        "normal Game left-click did not break the stone source");
                check(noiseSpider.isInvestigating(), "spider did not investigate the actual clear-weather stone break at 12 blocks");
                shot("hearing-clear-stone-break"); session().entities().mobs.remove(noiseSpider);
                command(game, "/weather storm"); next(13);
            } else if (stage == 13 && state.equals("PLAYING") && frame - stageFrame > 2) {
                Weather.State weather = (Weather.State) get(get(game, "atmosphere"), "global");
                check(weather.precipitation() > .9f && weather.storm() > .9f, "storm command did not reach authoritative Game weather");
                noiseSpider = hearingSpider(); placeNoiseStone(); input().injectMouseButton(GLFW_MOUSE_BUTTON_LEFT, true); next(14);
            } else if (stage == 14 && state.equals("PLAYING") && frame - stageFrame >= 1) {
                input().injectMouseButton(GLFW_MOUSE_BUTTON_LEFT, false);
                check(session().world().getBlock(noiseX, NOISE_STONE_Y, noiseZ) == BlockType.AIR,
                        "normal Game left-click did not break the storm stone source");
                check(!noiseSpider.isInvestigating(), "spider heard the same 12-block stone break through full storm rain");
                shot("hearing-storm-stone-break");
                System.out.println("WEATHER_HEARING_GAME PASS actualGameInput=true actualStoneBreak=true distanceHorizontal=12"
                        + " clearInvestigates=true stormInvestigates=false sourceRoof=false sourceSnow=false commands=true");
                System.out.println("RABBIT_RAID_GAME_SMOKE PASS frames=" + frame + " nativeGameLoop=true nativeRender=true"
                        + " playerWolfDeaths=4 advanceDays=7 rabbitPopulation=" + beforeRabbits + "->" + afterRabbits
                        + " openDamaged=" + finalOpenDamage + " closedBeforeGate=0 closedAfterGate=" + finalClosedDamage
                        + " stageRoundTrip=true finiteIdsRoundTrip=true reservations=" + savedReservations + " F3=true flightShortcutPreserved=true"
                        + " directCropRaidCalls=0 weatherStoneInput=true screenshots=" + output);
                next(15); glfwSetWindowShouldClose(getHandle(), true);
            }
        }

        private void buildFixture(World world) throws Exception {
            // Loaded generated chunks remain the real Game world; only this lit, level fixture is edited.
            for (int x = 0; x < 64; x++) for (int z = 0; z < 40; z++) {
                world.setBlock(x, FLOOR - 1, z, BlockType.DIRT); world.setBlock(x, FLOOR, z, BlockType.GRASS);
                for (int y = CROP; y < Chunk.SIZE_Y; y++) world.setBlock(x, y, z, BlockType.AIR);
            }
            for (int left : new int[] {OPEN_X, CLOSED_X})
                for (int x = left; x < left + 4; x++) for (int z = PLOT_Z; z < PLOT_Z + 4; z++) {
                    world.setBlock(x, FLOOR, z, BlockType.FARMLAND, (byte) 7);
                    world.setBlock(x, CROP, z, BlockType.CARROTS, (byte) 7);
                    Behaviors.of(BlockType.CARROTS).onPlaced(world, x, CROP, z, (byte) 7);
                }
            for (int z = 6; z <= 19; z++) { world.setBlock(30, CROP, z, BlockType.FENCE); world.setBlock(37, CROP, z, BlockType.FENCE); }
            for (int x = 30; x <= 37; x++) for (int z : new int[] {6, 12, 19}) world.setBlock(x, CROP, z, BlockType.FENCE);
            world.setBlock(33, CROP, 12, BlockType.FENCE_GATE, (byte) 0);
            for (int x = 53; x <= 62; x++) for (int z = 24; z <= 31; z++)
                if (x == 53 || x == 62 || z == 24 || z == 31)
                    for (int y = CROP; y <= CROP + 2; y++) world.setBlock(x, y, z, BlockType.STONE);
            for (int cx = 0; cx < 4; cx++) for (int cz = 0; cz < 3; cz++) world.getChunkIfExists(cx, cz).computeSkyLight();
        }
        private void freezeCropGrowth(World world) {
            world.setScheduledTickHandler((w, x, y, z, block, meta, late) -> {
                if (block != BlockType.WHEAT && block != BlockType.CARROTS && block != BlockType.POTATOES)
                    World.ScheduledTickHandler.BEHAVIOURS.tick(w, x, y, z, block, meta, late);
            });
        }
        private void prepareHearingFixture() throws Exception {
            World world = session().world(); boolean found = false;
            for (int x = 2; x <= 45 && !found; x++) for (int z = 25; z <= 32 && !found; z++) {
                var biome = world.biomes.biomeAt(x, z);
                if (Weather.precipitates(biome) && !Weather.snowsAt(biome, NOISE_STONE_Y + .5f)) {
                    noiseX = x; noiseZ = z; found = true;
                }
            }
            check(found, "generated loaded fixture has no temperate rain source below the snow line");
            for (int x = noiseX - 2; x <= noiseX + 15; x++) for (int z = noiseZ - 2; z <= noiseZ + 7; z++) {
                world.setBlock(x, NOISE_FLOOR, z, BlockType.GRASS);
                for (int y = NOISE_FLOOR + 1; y < Chunk.SIZE_Y; y++) world.setBlock(x, y, z, BlockType.AIR);
            }
            command(game, "/tp " + (noiseX + .5f) + " " + (NOISE_FLOOR + 1.001f) + " " + (noiseZ + 4.5f));
            // Hostile routines sleep at noon: this hearing comparison requires an awake spider.
            command(game, "/time set midnight");
            world.setBlock(noiseX - 1, NOISE_FLOOR + 1, noiseZ + 2, BlockType.TORCH);
            Player player = player(); check(player.flying, "hearing observer lost actual creative flight"); player.velocity.zero();
            float dy = NOISE_STONE_Y + .5f - player.position.y - Player.EYE_HEIGHT;
            player.camera.yaw = 0f; player.camera.pitch = (float) -Math.atan2(dy, 4f);
            clearToasts();
        }
        private void placeNoiseStone() throws Exception {
            session().world().setBlock(noiseX, NOISE_STONE_Y, noiseZ, BlockType.STONE);
            check(!BodyCollision.overlaps(session().world(), player().position.x, player().position.y,
                    player().position.z, .6f, 1.8f), "stone hearing fixture intersects the player's body");
        }
        private Mob hearingSpider() throws Exception {
            Mob body = new Mob(MobType.SPIDER, noiseX + 12.5f, NOISE_FLOOR + 1.001f, noiseZ + .5f, new Random(SEED + 30));
            body.onGround = true; body.yaw = (float) Math.PI / 2f;
            check(!BodyCollision.overlaps(session().world(), body.position.x, body.position.y,
                    body.position.z, body.type.width, body.type.height), "spider hearing fixture intersects terrain");
            session().entities().mobs.add(body); return body;
        }
        private Mob rabbit(float x, float z, int salt) throws Exception {
            Mob body = new Mob(MobType.RABBIT, x, CROP + .001f, z, new Random(SEED + salt));
            check(!BodyCollision.overlaps(session().world(), body.position.x, body.position.y,
                    body.position.z, body.type.width, body.type.height), "rabbit fixture intersects generated terrain");
            body.onGround = true; set(body, "hungerTimer", 0f);
            check(session().ecology().claim(body), "rabbit did not reserve one existing finite population animal");
            session().entities().mobs.add(body); return body;
        }
        private int damaged(int left) throws Exception {
            World world = session().world(); int count = 0;
            for (int x = left; x < left + 4; x++) for (int z = PLOT_Z; z < PLOT_Z + 4; z++) {
                check(world.getBlock(x, FLOOR, z) == BlockType.FARMLAND, "ordinary trampling destroyed fixture farmland");
                check(world.getBlock(x, CROP, z) == BlockType.CARROTS, "fixture crop vanished instead of losing growth stages");
                check(world.plantLight(x, CROP, z) >= 9, "controlled crop has insufficient light");
                if ((world.getBlockMeta(x, CROP, z) & 7) < 7) count++;
            }
            return count;
        }
        private byte[] stages() throws Exception {
            byte[] bytes = new byte[32]; int index = 0; World world = session().world();
            for (int left : new int[] {OPEN_X, CLOSED_X})
                for (int x = left; x < left + 4; x++) for (int z = PLOT_Z; z < PLOT_Z + 4; z++) bytes[index++] = world.getBlockMeta(x, CROP, z);
            return bytes;
        }
        private void aimAtFields() throws Exception {
            Player player = player(); float dx = 22.5f - player.position.x, dz = 16f - player.position.z;
            float dy = CROP + .15f - player.position.y - Player.EYE_HEIGHT;
            player.camera.yaw = (float) Math.atan2(dx, -dz); player.camera.pitch = (float) -Math.atan2(dy, Math.hypot(dx, dz));
            player.velocity.zero();
        }
        private void reviewPose() throws Exception {
            Player player = player(); check(player.flying, "review observer lost actual creative flight");
            player.position.set(22.5f, 115f, 23f);
            player.camera.position.set(player.position.x, player.position.y + Player.EYE_HEIGHT, player.position.z);
            aimAtFields();
        }
        private void clearToasts() throws Exception {
            set(game, "commandHelpTimer", 0f); set(game, "commandToastTimer", 0f); set(game, "commandPanel", new String[0]);
        }
        private void shot(String name) throws Exception {
            int width = getWidth(), height = getHeight(); var pixels = org.lwjgl.system.MemoryUtil.memAlloc(width * height * 4);
            try {
                glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
                var image = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
                for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                    int at = (y * width + x) * 4;
                    image.setRGB(x, height - 1 - y, ((pixels.get(at) & 255) << 16) | ((pixels.get(at + 1) & 255) << 8) | (pixels.get(at + 2) & 255));
                }
                Path file = output.resolve(name + ".png"); javax.imageio.ImageIO.write(image, "png", file.toFile());
                System.out.println("RABBIT_RAID_GAME screenshot=" + file + " observer=" + player().position
                        + " camera=" + player().camera.position + " forward=" + player().camera.forward());
            } finally { org.lwjgl.system.MemoryUtil.memFree(pixels); }
        }
    }

    /** Run against current compiled sources: java -cp 'out;libs/*' tools/RabbitRaidGameSmoke.java. */
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length > 0 ? args[0] : "out-test/rabbit-raid-game").toAbsolutePath();
        Files.createDirectories(output); Path saves = Files.createTempDirectory(output, "saves-");
        System.setProperty("mineclone.savesDir", saves.toString()); System.setProperty("mineclone.checkThreadOwnership", "true");
        ReviewWindow window = new ReviewWindow(output); window.init(); boolean normalShutdown = false;
        try {
            window.game = new Game(window, false); ((Input) get(window.game, "input")).setGrabAllowed(false);
            set(window.game, "renderRadius", 4); set(window.game, "guiScale", 1); set(window.game, "mouseSensitivity", 0f);
            window.game.run(); normalShutdown = true;
            if (window.failure != null) throw new AssertionError("Rabbit raid native Game smoke failed", window.failure);
            check(window.stage == 15, "rabbit native smoke did not reach completion");
        } finally {
            if (!normalShutdown && window.game != null) try { call(window.game, "cleanup"); } catch (Exception failure) { failure.printStackTrace(); }
            window.destroy();
        }
    }
}
