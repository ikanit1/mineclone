import com.mineclone.save.*;
import com.mineclone.server.DedicatedServer;
import com.mineclone.server.ServerConfig;
import com.mineclone.sim.WorldClock;
import com.mineclone.sim.WorldSession;
import com.mineclone.sim.ecology.*;
import com.mineclone.world.*;
import com.mineclone.world.entity.Mob;
import com.mineclone.world.entity.MobType;
import com.mineclone.world.gen.TreeShapes;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutorService;

/** Actual burned disposable server save and read-only verification after live recovery and /stop. */
public final class BurnScarsSaveAcceptance {
    public static final int RADIUS = 24;
    private static final int[][] TREES={{-16,-12},{-8,8},{0,-8},{8,12},{16,-12},{16,8}};
    public static void main(String[] a)throws Exception{
        check(a.length>=2&&(a[0].equals("prepare")||a[0].equals("verify")),"Usage: BurnScarsSaveAcceptance.java prepare <test config> | verify <test config> <observed animal IDs>");
        Path file=Path.of(a[1]).toAbsolutePath().normalize(),root=Path.of("out-test").toAbsolutePath().normalize();check(file.startsWith(root),"Burn fixture must be below this checkout's out-test");
        ServerConfig config=ServerConfig.load(file.toFile());Path saves=Path.of(config.savesDir).toAbsolutePath().normalize();check(saves.startsWith(file.getParent())&&config.worldId.equals("test"),"Refusing a non-test world/save path");
        if(a[0].equals("prepare")){check(a.length==2,"Unexpected prepare arguments");prepare(config,saves);}else{check(a.length==3,"Verification needs actual peer-observed finite animal IDs");verify(config,saves,a[2]);}
    }
    /** Ground coordinate contract shared by network probes: x, groundY, z. */
    public static int[] anchor(World world,float spawnX,float spawnY,float spawnZ){
        return new int[]{Math.floorDiv((int)Math.floor(spawnX),256)*256+70,Math.min(86,(int)Math.floor(spawnY)+20),Math.floorDiv((int)Math.floor(spawnZ),256)*256+70};
    }
    /** Authored living grove only, always called before fire. */
    public static void build(World w,int x,int y,int z){
        w.batchBlocks(()->{
            for(int dx=-RADIUS-16;dx<=RADIUS+16;dx++)for(int dz=-RADIUS-16;dz<=RADIUS+16;dz++){
                w.setBlock(x+dx,y-1,z+dz,BlockType.DIRT);w.setBlock(x+dx,y,z+dz,BlockType.GRASS);
                for(int by=y+1;by<128;by++)w.setBlock(x+dx,by,z+dz,BlockType.AIR);
                if(Math.abs(dx)<=RADIUS&&Math.abs(dz)<=RADIUS&&Math.floorMod(dx,4)!=0&&Math.floorMod(dz,4)!=0)w.setBlock(x+dx,y+1,z+dz,BlockType.TALL_GRASS);
            }
            for(int[] t:TREES)for(var c:TreeShapes.cells(0,x+t[0],y,z+t[1],w.seed^cell(x+t[0],z+t[1])))w.setBlock(c.x(),c.y(),c.z(),c.block());
        });
        for(Chunk c:w.getLoadedChunks())if(c.cx>=Math.floorDiv(x-RADIUS-16,16)&&c.cx<=Math.floorDiv(x+RADIUS+16,16)&&c.cz>=Math.floorDiv(z-RADIUS-16,16)&&c.cz<=Math.floorDiv(z+RADIUS+16,16))c.computeSkyLight();
    }
    /** Accelerated production random callbacks; no DIRT, GRASS, SAPLING or recovered tree writes. */
    public static int pump(World w,BlockTicker ticker,int[] p,boolean raining,int passes){
        int callbacks=0;for(int pass=0;pass<passes;pass++)for(int y=p[1]+1;y<=p[1]+12;y++)for(int x=p[0]-RADIUS-1;x<=p[0]+RADIUS+1;x++)for(int z=p[2]-RADIUS-1;z<=p[2]+RADIUS+1;z++){
            BlockType b=w.getBlock(x,y,z);if(b==BlockType.FIRE||raining&&b==BlockType.ASH){ticker.apply(w,x,y,z,raining);callbacks++;}
        }return callbacks;
    }
    public static int count(World w,int[] p,BlockType kind,int low,int high){int n=0;for(int x=p[0]-RADIUS;x<=p[0]+RADIUS;x++)for(int z=p[2]-RADIUS;z<=p[2]+RADIUS;z++)for(int y=low;y<=high;y++)if(w.getBlock(x,y,z)==kind)n++;return n;}
    /** The fixture may seed FIRE; the native harness independently proves ordinary RMB ignition. */
    public static int[] ignitionSite(World w,int[] p){
        for(int[] t:TREES){int x=p[0]+t[0],z=p[2]+t[1];if(w.getBlock(x,p[1]+1,z)!=BlockType.WOOD)continue;
            for(int[] o:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})if(w.getBlock(x+o[0],p[1]+1,z+o[1])==BlockType.AIR)return new int[]{x+o[0],p[1]+1,z+o[1]};
        }
        for(int r=0;r<=RADIUS;r++)for(int dx=-r;dx<=r;dx++)for(int dz=-r;dz<=r;dz++){
            if(Math.max(Math.abs(dx),Math.abs(dz))!=r)continue;int x=p[0]+dx,z=p[2]+dz;
            if(w.getBlock(x,p[1]+1,z)!=BlockType.AIR||w.getBlock(x,p[1],z)!=BlockType.GRASS)continue;
            for(int[] o:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})if(w.getBlock(x+o[0],p[1]+1,z+o[1]).isFlammable())return new int[]{x,p[1]+1,z};
        }return null;
    }
    private static int capacity(EcologyRegion r){int n=0;for(var d:SpeciesTable.get().definitions())n+=EcologyModel.effectiveCapacity(r,d.id());return n;}
    public static int oldRoots(World w,int[] p){int n=0;for(int[] t:TREES)if(w.getBlock(p[0]+t[0],p[1]+1,p[2]+t[1])==BlockType.WOOD)n++;return n;}
    private static void prepare(ServerConfig config,Path saves)throws Exception{
        check(!Files.exists(saves.resolve(config.worldId)),"Burn fixture requires a new disposable world");DedicatedServer server=new DedicatedServer(config);
        try{
            check((boolean)call(server,"openWorld"),"Actual server refused fresh fixture");World w=(World)get(server,"world");ChunkLoader loader=(ChunkLoader)get(server,"loader");WorldClock clock=(WorldClock)get(server,"worldClock");WorldSession session=(WorldSession)get(server,"session");
            var spawn=server.spawn();int[] p=anchor(w,spawn.x,spawn.y,spawn.z);check(Weather.precipitates(w.biomes.biomeAt(p[0],p[2]))&&!Weather.snowsAt(w.biomes.biomeAt(p[0],p[2]),p[1]+2),"Burn fixture needs a warm rainy biome");
            int margin=config.viewDistance;
            for(int cx=Math.min(Math.floorDiv((int)spawn.x,16),Math.floorDiv(p[0],16))-margin;cx<=Math.max(Math.floorDiv((int)spawn.x,16),Math.floorDiv(p[0]+29,16))+margin;cx++)
                for(int cz=Math.min(Math.floorDiv((int)spawn.z,16),Math.floorDiv(p[2],16))-margin;cz<=Math.max(Math.floorDiv((int)spawn.z,16),Math.floorDiv(p[2]+44,16))+margin;cz++)loader.loadNow(cx,cz);
            check(!session.ecology().enabled(),"Fresh fixture must precede finite Ecology adoption");session.entities().mobs.clear();session.entities().items.clear();
            for(Chunk c:w.getLoadedChunks()){c.setPendingMobs(List.of());c.modified=true;}
            build(w,p[0],p[1],p[2]);clock.setHours(6);Ecology ecology=server.ecology();ecology.regionAt(p[0],p[2]);ecology.setEnabled(true);call(ecology,"refreshHabitat");EcologyRegion region=ecology.regionAt(p[0],p[2]);int before=capacity(region);
            Mob cow=new Mob(MobType.COW,p[0]-14.5f,p[1]+1.001f,p[2]-10.5f,new Random(w.seed));cow.onGround=true;cow.persistent=true;check(ecology.claim(cow),"Fixture cow could not reserve an existing finite animal");session.entities().mobs.add(cow);
            BlockTicker ticker=new BlockTicker(w.seed^0x4255524eL);int callbacks=0,ignitions=0;
            for(int round=0;round<3000;round++){
                if(count(w,p,BlockType.FIRE,p[1]+1,p[1]+12)==0){
                    if(count(w,p,BlockType.DIRT,p[1],p[1])>=192&&oldRoots(w,p)==0)break;
                    int[] fire=ignitionSite(w,p);check(fire!=null,"Remaining grove had no legal fuel-side ignition");w.setBlock(fire[0],fire[1],fire[2],BlockType.FIRE,(byte)0);ignitions++;
                }
                callbacks+=pump(w,ticker,p,false,3);
                if(round%500==499)System.out.println("BURN_SAVE fire progress rounds="+(round+1)+" dirt="+count(w,p,BlockType.DIRT,p[1],p[1])+" oldRoots="+oldRoots(w,p)+" fire="+count(w,p,BlockType.FIRE,p[1]+1,p[1]+12)+" callbacks="+callbacks+" ignitions="+ignitions);
            }
            check(count(w,p,BlockType.DIRT,p[1],p[1])>=192,"Production fire callbacks did not scorch the grove: anchor="+Arrays.toString(p)+" dirt="+count(w,p,BlockType.DIRT,p[1],p[1])+" oldRoots="+oldRoots(w,p)+" fire="+count(w,p,BlockType.FIRE,p[1]+1,p[1]+12)+" callbacks="+callbacks+" ignitions="+ignitions);
            check(oldRoots(w,p)==0,"Production fire did not consume the six original mature roots");
            for(int n=0;n<300&&count(w,p,BlockType.FIRE,p[1]+1,p[1]+12)>0;n++)callbacks+=pump(w,ticker,p,false,3);
            check(count(w,p,BlockType.FIRE,p[1]+1,p[1]+12)==0,"Prepared grove still has live flames");
            call(ecology,"refreshHabitat");int after=capacity(ecology.regionAt(p[0],p[2]));check(after<before,"Actual fire did not decrease regional capacity");
            // A real seed/clock rain front inside the first day cleans exposed ASH using the same host input.
            boolean rain=false;for(int front=1;front<4;front++){
                double time=(front*Weather.FRONT_LENGTH+Weather.TRANSITION+1)*WorldClock.TIME_SCALE;
                if(Ecology.day(time)!=0)continue;Weather.State weather=Weather.sample(w.seed,(float)(time/WorldClock.TIME_SCALE));
                if(weather.precipitation()>=BlockTicker.RAIN_EXTINGUISH){clock.setGameTime(time);rain=true;break;}
            }
            check(rain,"Fixture seed has no natural rain front inside day zero");
            for(int n=0;n<4;n++)callbacks+=pump(w,ticker,p,true,1);
            clock.setHours(6);Map<String,byte[]> sections=new LinkedHashMap<>();ecology.writeSections(sections);
            var snapshot=EcologyCodec.decode(sections.get(Ecology.SAVE_SECTION));int sites=0,records=0;
            for(EcologyRegion r:snapshot.regions())if(r.key()==region.key())for(byte[] bytes:r.scars()){
                BurnScars.Scar scar=BurnScars.Scar.decode(bytes);if(scar!=null){records++;sites+=scar.sites().size();for(var site:scar.sites())check(site.burnDay()==0&&!site.plantedOnce(),"Preparation fabricated recovery or advanced scar age");}
            }
            check(records>0&&records<=16&&sites>=192,"Prepared actual burn did not retain its bounded sites");
            server.saveWorld();((SaveManager)get(server,"save")).flushAndAwait();
            Files.writeString(saves.getParent().resolve("burn-fixture.txt"),p[0]+" "+p[1]+" "+p[2]+" 0 "+cow.id+" "+before+" "+after+" "+sites+"\n");
            System.out.println("BURN_SAVE_PREPARE PASS anchor="+p[0]+","+p[1]+","+p[2]+" burnDay=0 scars="+records+" sites="+sites+" capacity="+before+"->"+after+" initialCowID="+cow.id+" productionFireCallbacks="+callbacks+" fixtureIgnitions="+ignitions+" naturalRain=true");
        }finally{ChunkLoader loader=(ChunkLoader)get(server,"loader");if(loader!=null)loader.shutdown();close((SaveManager)get(server,"save"));}
    }
    private static void verify(ServerConfig config,Path saves,String observed)throws Exception{
        long[] ids=Arrays.stream(observed.split(",")).mapToLong(Long::parseLong).sorted().toArray();check(ids.length>0&&ids[0]>0&&Arrays.stream(ids).distinct().count()==ids.length,"Invalid actual animal IDs");
        String[] fixture=Files.readString(saves.getParent().resolve("burn-fixture.txt")).trim().split("\\s+");check(fixture.length==8,"Invalid initial burn manifest");
        int x=Integer.parseInt(fixture[0]),ground=Integer.parseInt(fixture[1]),z=Integer.parseInt(fixture[2]),after=Integer.parseInt(fixture[6]),initialSites=Integer.parseInt(fixture[7]);long burnDay=Long.parseLong(fixture[3]),cowId=Long.parseLong(fixture[4]);
        SaveManager save=new SaveManager(saves.toFile());
        try{
            LevelData level=save.loadLevel(config.worldId);check(level!=null,"Server did not save level");WorldClock clock=WorldClock.fromSaved(level.timeOfDay,level.extraSections.get(WorldClock.SAVE_SECTION));check(Ecology.day(clock.gameTime())>=burnDay+7,"Actual seven-day command was not saved");
            EcologyCodec.Snapshot ecology=EcologyCodec.decode(level.extraSections.get(Ecology.SAVE_SECTION));EcologyRegion r=ecology.regions().stream().filter(region->region.x()==Math.floorDiv(x,256)&&region.z()==Math.floorDiv(z,256)).findFirst().orElseThrow();
            int sites=0,records=0,grass=0,youngTrees=0;Set<Long> cells=new HashSet<>();Map<Long,ChunkSnapshot> chunks=new HashMap<>();Map<Long,SavedMob> mobs=new HashMap<>();
            try(var files=Files.list(saves.resolve(config.worldId).resolve("chunks"))){for(Path file:files.toList()){
                String name=file.getFileName().toString();if(!name.matches("c\\.-?\\d+\\.-?\\d+\\.dat"))continue;String[] coord=name.split("\\.");int cx=Integer.parseInt(coord[1]),cz=Integer.parseInt(coord[2]);ChunkLoad loaded=save.readChunk(config.worldId,cx,cz);check(loaded instanceof ChunkLoad.Loaded,"Saved burn chunk cannot reopen");ChunkSnapshot c=((ChunkLoad.Loaded)loaded).snapshot();chunks.put(cell(cx,cz),c);for(SavedMob m:c.mobs)check(mobs.putIfAbsent(m.id(),m)==null,"Duplicate saved body ID "+m.id());
            }}
            for(byte[] bytes:r.scars()){
                BurnScars.Scar scar=BurnScars.Scar.decode(bytes);if(scar==null)continue;records++;check(scar.lastProcessedDay()>=burnDay+7,"Saved scar never caught up to day seven");
                for(var site:scar.sites()){
                    sites++;check(site.burnDay()==burnDay&&site.groundY()==ground,"Scar lost actual location/burn day");int sx=scar.cx()*16+site.localX(),sz=scar.cz()*16+site.localZ();check(cells.add(cell(sx,sz)),"Duplicate saved scar site");
                    if(block(chunks,sx,ground,sz)==BlockType.GRASS.ordinal())grass++;
                    if(site.plantedOnce()&&block(chunks,sx,ground+1,sz)==BlockType.WOOD.ordinal())youngTrees++;
                }
            }
            check(records>0&&records<=16&&sites==initialSites,"Saved typed burn history disappeared or exceeded its bound");check(grass>=sites/2&&youngTrees>0,"Saved day seven grove lacks real grass and grown natural trees");check(capacity(r)>after,"Saved recovered greenery did not restore capacity");
            check(mobs.containsKey(cowId),"Original finite cow disappeared from server save");Set<Long> proven=new TreeSet<>();
            for(long id:ids){SavedMob mob=mobs.get(id);check(mob!=null&&mob.persistent()&&mob.health()>0,"Observed animal "+id+" has no intact saved body");MobType type=MobType.valueOf(mob.type());check(type.temper==MobType.Temper.PASSIVE&&!type.hostile,"Observed return is not a passive animal");check(ecology.reservations().containsKey(id),"Observed body is not finite reserved wildlife");check(clock.entityCounter()>=id,"Clock allocator is behind returned animal");proven.add(id);}
            System.out.println("BURN_SAVE_VERIFY PASS day="+Ecology.day(clock.gameTime())+" scars="+records+" sites="+sites+" grass="+grass+" grownNaturalTrees="+youngTrees+" capacity="+capacity(r)+" originalCowID="+cowId+" observedFiniteIDs="+proven+" uniqueSavedIDs=true");
        }finally{close(save);}
    }
    private static int block(Map<Long,ChunkSnapshot> chunks,int x,int y,int z){ChunkSnapshot c=chunks.get(cell(Math.floorDiv(x,16),Math.floorDiv(z,16)));check(c!=null,"Missing saved recovered grove chunk");return Byte.toUnsignedInt(c.blocks[Chunk.idx(Math.floorMod(x,16),y,Math.floorMod(z,16))]);}
    private static long cell(int x,int z){return ((long)x<<32)|(z&0xffff_ffffL);}
    private static Object get(Object o,String n)throws Exception{var f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
    private static Object call(Object o,String n)throws Exception{var m=o.getClass().getDeclaredMethod(n);m.setAccessible(true);return m.invoke(o);}
    private static void close(SaveManager save)throws Exception{if(save!=null){save.flushAndAwait();((ExecutorService)get(save,"chunkWriter")).shutdown();}}
    private static void check(boolean b,String m){if(!b)throw new IllegalStateException(m);}
}
