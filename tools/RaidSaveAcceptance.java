import com.mineclone.save.ChunkLoad;
import com.mineclone.save.SaveManager;
import com.mineclone.server.DedicatedServer;
import com.mineclone.server.ServerConfig;
import com.mineclone.sim.EntityStore;
import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.ChunkLoader;
import com.mineclone.world.World;
import com.mineclone.world.entity.Mob;
import com.mineclone.world.entity.MobType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import org.joml.Vector3f;

/** Temporary dedicated-server raid fixture and read-only verification of its real saved crop result. */
public final class RaidSaveAcceptance {
    private static final int FIELD_Z = -20;
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !(args[0].equals("prepare") || args[0].equals("verify")))
            throw new IllegalArgumentException("Usage: RaidSaveAcceptance.java prepare|verify <out-test/server/server.properties>");
        Path file=Path.of(args[1]).toAbsolutePath().normalize();
        Path testRoot=Path.of("out-test").toAbsolutePath().normalize();
        check(file.startsWith(testRoot), "Fixture configuration must be under this checkout's out-test directory");
        ServerConfig config=ServerConfig.load(file.toFile());
        Path saves=Path.of(config.savesDir).toAbsolutePath().normalize();
        check(saves.startsWith(file.getParent()) && config.worldId.equals("test"), "Refusing a non-test save path or world");
        if(args[0].equals("prepare"))prepare(config,saves);else verify(config,saves);
    }
    private static void prepare(ServerConfig config,Path saves) throws Exception {
        check(!Files.exists(saves.resolve(config.worldId)), "Raid fixture must start from a new temporary world");
        DedicatedServer server=new DedicatedServer(config);
        try {
            var open=DedicatedServer.class.getDeclaredMethod("openWorld");open.setAccessible(true);
            check((boolean)open.invoke(server), "Actual server world opening refused the fixture");
            World world=(World)get(server,"world");Vector3f spawn=(Vector3f)get(server,"spawn");
            int x=(int)Math.floor(spawn.x),z=(int)Math.floor(spawn.z),floor=Math.min(116,(int)Math.floor(spawn.y)+20);
            ChunkLoader loader=(ChunkLoader)get(server,"loader");
            for(int cx=Math.floorDiv(x,16);cx<=Math.floorDiv(x+15,16);cx++)
                for(int cz=Math.floorDiv(z+FIELD_Z-5,16);cz<=Math.floorDiv(z+FIELD_Z+4,16);cz++)loader.loadNow(cx,cz);
            // Same terrain as Autopilot.prepareRaidArena; the real seed/crop is still planted by a client.
            for(int dx=0;dx<=15;dx++)for(int dz=FIELD_Z-5;dz<=FIELD_Z+4;dz++){
                if(dx>6&&dx<11)continue;
                world.setBlock(x+dx,floor,z+dz,BlockType.GRASS);
                for(int y=floor+1;y<Chunk.SIZE_Y;y++)world.setBlock(x+dx,y,z+dz,BlockType.AIR);
            }
            for(int dz=FIELD_Z-5;dz<=FIELD_Z+4;dz++)for(int dx:new int[]{0,6,11,15})wall(world,x+dx,floor,z+dz);
            for(int dx=0;dx<=6;dx++){
                wall(world,x+dx,floor,z+FIELD_Z-5);wall(world,x+dx,floor,z+FIELD_Z+4);
                if(dx!=3)wall(world,x+dx,floor,z+FIELD_Z-2);
            }
            for(int dx=11;dx<=15;dx++){wall(world,x+dx,floor,z+FIELD_Z-5);wall(world,x+dx,floor,z+FIELD_Z+4);}
            world.setBlock(x+3,floor+1,z+FIELD_Z-2,BlockType.FENCE_GATE,(byte)0);
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)if(dx!=0||dz!=0)
                world.setBlock(x+13+dx,floor+1,z+FIELD_Z+dz,
                        dx==0&&dz==-1?BlockType.FENCE_GATE:BlockType.FENCE,(byte)0);
            world.setBlock(x+13,floor,z+FIELD_Z,BlockType.FARMLAND,(byte)7);
            world.setBlock(x+13,floor+1,z+FIELD_Z,BlockType.WHEAT,(byte)7);
            world.setBlock(x+15,floor,z+FIELD_Z,BlockType.WATER);
            world.setBlock(x+11,floor+1,z+FIELD_Z,BlockType.TORCH);
            // Saved ordinary rabbits have ordinary hunger, wandering, fear and collision.
            // No simulation runs during preparation; reopening adopts these finite reservations.
            server.ecology().setEnabled(true);
            EntityStore entities=(EntityStore)get(server,"entities");
            Mob openRabbit=rabbit(server,entities,x+3.5f,floor+1.001f,z+FIELD_Z-3.5f,world.seed);
            Mob protectedRabbit=rabbit(server,entities,x+13.5f,floor+1.001f,z+FIELD_Z-2.5f,world.seed+1);
            server.saveWorld();((SaveManager)get(server,"save")).flushAndAwait();
            Files.writeString(saves.getParent().resolve("raid-fixture.txt"),x+" "+floor+" "+z+"\n");
            System.out.println("RAID_SAVE_PREPARE PASS naturalRabbits="+openRabbit.id+","+protectedRabbit.id+" closedReleaseGate=true");
        } finally {
            ChunkLoader loader=(ChunkLoader)get(server,"loader");if(loader!=null)loader.shutdown();
            close((SaveManager)get(server,"save"));
        }
    }
    private static Mob rabbit(DedicatedServer server,EntityStore entities,float x,float y,float z,long seed){
        Mob mob=new Mob(MobType.RABBIT,x,y,z,new Random(seed));mob.onGround=true;mob.persistent=true;
        check(server.ecology().claim(mob), "No finite regional rabbit was available for fixture reservation");
        entities.mobs.add(mob);return mob;
    }
    private static void wall(World world,int x,int floor,int z){for(int y=floor+1;y<=floor+3;y++)world.setBlock(x,y,z,BlockType.STONE);}
    private static void verify(ServerConfig config,Path saves) throws Exception {
        String[] pose=Files.readString(saves.getParent().resolve("raid-fixture.txt")).trim().split("\\s+");
        int x=Integer.parseInt(pose[0]),floor=Integer.parseInt(pose[1]),z=Integer.parseInt(pose[2]);
        SaveManager save=new SaveManager(saves.toFile());
        try {
            check(save.loadLevel(config.worldId)!=null, "Server did not save its level");
            crop(save,config.worldId,x+3,floor+1,z+FIELD_Z,0);
            crop(save,config.worldId,x+13,floor+1,z+FIELD_Z,7);
            System.out.println("RAID_SAVE_VERIFY PASS authoritativeCropStage=0 protectedCropStage=7");
        } finally {close(save);}
    }
    private static void crop(SaveManager save,String world,int x,int y,int z,int expected){
        ChunkLoad result=save.readChunk(world,Math.floorDiv(x,16),Math.floorDiv(z,16));
        check(result instanceof ChunkLoad.Loaded, "Raid crop chunk did not reopen: "+result);
        var snapshot=((ChunkLoad.Loaded)result).snapshot();int cell=Chunk.idx(Math.floorMod(x,16),y,Math.floorMod(z,16));
        check(Byte.toUnsignedInt(snapshot.blocks[cell])==BlockType.WHEAT.ordinal(), "Server lost the crop block");
        check((snapshot.meta[cell]&7)==expected, "Saved raid crop stage was "+(snapshot.meta[cell]&7)+", expected "+expected);
    }
    private static Object get(Object object,String name) throws Exception {var field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    private static void close(SaveManager save) throws Exception {save.flushAndAwait();((ExecutorService)get(save,"chunkWriter")).shutdown();}
    private static void check(boolean okay,String why){if(!okay)throw new IllegalStateException(why);}
}
