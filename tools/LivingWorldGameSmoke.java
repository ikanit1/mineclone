import com.mineclone.core.Input;
import com.mineclone.core.Window;
import com.mineclone.game.Game;
import com.mineclone.game.Player;
import com.mineclone.game.Raycaster;
import com.mineclone.save.SaveManager;
import com.mineclone.sim.WorldClock;
import com.mineclone.sim.WorldSession;
import com.mineclone.sim.ecology.*;
import com.mineclone.ui.MenuAction;
import com.mineclone.ui.WorldSettings;
import com.mineclone.world.*;
import com.mineclone.world.entity.Mob;
import com.mineclone.world.entity.MobType;
import com.mineclone.world.gen.TreeShapes;
import com.mineclone.world.shape.BodyCollision;
import java.nio.file.*;
import java.util.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

/** One chronological native Game journey through A/B/C, real inputs, combined save and 30 days away. */
public final class LivingWorldGameSmoke {
    static final long SEED = 20261002L;
    static final int X = 70, Y = 86, Z = 70, RADIUS = 24;
    static final int[][] TREES = {{-16,-12},{-8,8},{0,-8},{8,12},{16,-12},{16,8}};
    private static Object get(Object o,String n)throws Exception{var f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
    private static void set(Object o,String n,Object v)throws Exception{var f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}
    private static Object call(Object o,String n,Class<?>[] t,Object...a)throws Exception{var m=o.getClass().getDeclaredMethod(n,t);m.setAccessible(true);return m.invoke(o,a);}
    private static Object call(Object o,String n)throws Exception{return call(o,n,new Class<?>[0]);}
    private static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
    private static long cell(int x,int z){return ((long)x<<32)|(z&0xffff_ffffL);}

    /** Authored initial terrain, before ignition; recovery never calls this again. */
    static void build(World w,int x,int y,int z){
        w.batchBlocks(()->{
            for(int dx=-RADIUS-16;dx<=RADIUS+16;dx++)for(int dz=-RADIUS-16;dz<=RADIUS+16;dz++){
                w.setBlock(x+dx,y-1,z+dz,BlockType.DIRT);w.setBlock(x+dx,y,z+dz,BlockType.GRASS);
                for(int by=y+1;by<128;by++)w.setBlock(x+dx,by,z+dz,BlockType.AIR);
                if(Math.abs(dx)<=RADIUS&&Math.abs(dz)<=RADIUS&&Math.floorMod(dx,4)!=0&&Math.floorMod(dz,4)!=0)
                    w.setBlock(x+dx,y+1,z+dz,BlockType.TALL_GRASS);
            }
            for(int[] tree:TREES)for(var c:TreeShapes.cells(0,x+tree[0],y,z+tree[1],w.seed^cell(x+tree[0],z+tree[1])))
                w.setBlock(c.x(),c.y(),c.z(),c.block());
        });
        for(Chunk c:w.getLoadedChunks())if(c.cx>=Math.floorDiv(x-RADIUS-16,16)&&c.cx<=Math.floorDiv(x+RADIUS+16,16)
                &&c.cz>=Math.floorDiv(z-RADIUS-16,16)&&c.cz<=Math.floorDiv(z+RADIUS+16,16))c.computeSkyLight();
    }

    private static final class Review extends Window {
        final Path output;final long started=System.nanoTime();final BlockTicker accelerated=new BlockTicker(SEED^0x4255524EL);
        Game game;Throwable failure;int frame,stage,stageFrame,fireCallbacks,ashCallbacks,ignitions,firstFireFrame;float seconds;
        boolean streamRequested,mouseDown,cowMoved,cowRetreatIssued;String worldId;Mob cow;long cowId,burnDay;
        float cowStartX,cowStartZ;int beforeCapacity,afterCapacity;double beforeGreenery,afterGreenery;
        int[] ignition,firstIgnited;float[] firstFirePose;Set<Long> burned=new HashSet<>(),newAnimalIds=new TreeSet<>();byte[] savedEcology;long visualReadyAt;
        final Set<Long> initialRoots=new HashSet<>(),initialAnimalIds=new HashSet<>();
        Review(Path p){super("Mineclone living world A B C journey",1280,720,false);output=p;}
        WorldSession session()throws Exception{return (WorldSession)get(game,"session");}
        World world()throws Exception{return session().world();}
        Player player()throws Exception{return (Player)get(game,"player");}
        WorldClock clock()throws Exception{return (WorldClock)get(game,"worldClock");}
        Input input()throws Exception{return (Input)get(game,"input");}
        EcologyRegion region()throws Exception{return session().ecology().regionAt(X,Z);}
        void command(String s)throws Exception{call(game,"executeCommand",new Class<?>[]{String.class},s);}
        void next(int n){stage=n;stageFrame=frame;seconds=0;visualReadyAt=0;}
        void clearToasts()throws Exception{set(game,"commandHelpTimer",0f);set(game,"commandToastTimer",0f);set(game,"commandPanel",new String[0]);}
        void pose(float x,float y,float z,float tx,float ty,float tz)throws Exception{
            Player p=player();p.position.set(x,y,z);p.velocity.zero();p.camera.position.set(x,y+Player.EYE_HEIGHT,z);
            p.camera.yaw=(float)Math.atan2(tx-x,-(tz-z));p.camera.pitch=(float)-Math.atan2(ty-y-Player.EYE_HEIGHT,Math.hypot(tx-x,tz-z));
        }
        void overview()throws Exception{pose(X+29,Y+2,Z+29,X,Y+3,Z);}
        void scarView()throws Exception{set(game,"selectedSlot",1);pose(X+19,Y+24,Z+19,X,Y+.5f,Z);}
        void youngTreeView()throws Exception{
            long focus=burned.stream().filter(p->!initialRoots.contains(p)).sorted().filter(p->{try{return world().getBlock((int)(p>>32),Y+1,(int)(long)p)==BlockType.WOOD;}catch(Exception e){throw new RuntimeException(e);}}).findFirst().orElseThrow();
            int x=(int)(focus>>32),z=(int)focus;set(game,"selectedSlot",1);pose(x+9,Y+9,z+9,x+.5f,Y+2,z+.5f);
            if(visualReadyAt==0)System.out.println("STORIES_GAME youngTreeCapture actualNewRoot="+x+","+(Y+1)+","+z+" recordedBurnedGround=true");
        }
        boolean renderSettled(){if(visualReadyAt==0){visualReadyAt=System.nanoTime();return false;}return System.nanoTime()-visualReadyAt>=2_500_000_000L;}
        int capacity()throws Exception{int n=0;for(var d:SpeciesTable.get().definitions())n+=EcologyModel.effectiveCapacity(region(),d.id());return n;}
        int count(BlockType kind,int low,int high)throws Exception{int n=0;for(int x=X-RADIUS;x<=X+RADIUS;x++)for(int z=Z-RADIUS;z<=Z+RADIUS;z++)for(int y=low;y<=high;y++)if(world().getBlock(x,y,z)==kind)n++;return n;}
        int newTrees()throws Exception{int n=0;for(long p:burned){int x=(int)(p>>32),z=(int)p;if(!initialRoots.contains(p)&&world().getBlock(x,Y+1,z)==BlockType.WOOD)n++;}return n;}
        int recoveredGrass()throws Exception{int n=0;for(long p:burned)if(world().getBlock((int)(p>>32),Y,(int)p)==BlockType.GRASS)n++;return n;}
        int oldRoots()throws Exception{int n=0;for(int[] t:TREES)if(world().getBlock(X+t[0],Y+1,Z+t[1])==BlockType.WOOD)n++;return n;}
        int scars()throws Exception{Object runtime=call(session().ecology(),"scars");return ((List<?>)call(runtime,"scars",new Class<?>[]{long.class},region().key())).size();}
        void observeBurned()throws Exception{for(int x=X-RADIUS;x<=X+RADIUS;x++)for(int z=Z-RADIUS;z<=Z+RADIUS;z++)if(world().getBlock(x,Y,z)==BlockType.DIRT)burned.add(cell(x,z));}
        /** Pick another real empty cell beside remaining ground fuel; placement is always ordinary RMB. */
        int[] ignitionSite()throws Exception{
            for(int[] t:TREES){int x=X+t[0],z=Z+t[1];if(world().getBlock(x,Y+1,z)!=BlockType.WOOD)continue;
                for(int[] o:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})if(world().getBlock(x+o[0],Y+1,z+o[1])==BlockType.AIR)
                    return new int[]{x+o[0],Y+1,z+o[1]};
            }
            for(int r=0;r<=RADIUS;r++)for(int dx=-r;dx<=r;dx++)for(int dz=-r;dz<=r;dz++){
                if(Math.max(Math.abs(dx),Math.abs(dz))!=r)continue;int x=X+dx,z=Z+dz;
                if(world().getBlock(x,Y+1,z)!=BlockType.AIR||world().getBlock(x,Y,z)!=BlockType.GRASS)continue;
                for(int[] o:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})if(world().getBlock(x+o[0],Y+1,z+o[1]).isFlammable())return new int[]{x,Y+1,z};
            }return null;
        }
        void igniteThroughInput()throws Exception{
            if(ignition==null){ignition=ignitionSite();check(ignition!=null,"remaining fuel has no legal ignition site");mouseDown=false;}
            if(mouseDown&&world().getBlock(ignition[0],ignition[1],ignition[2])==BlockType.FIRE){
                if(ignitions==0)firstIgnited=ignition.clone();input().injectMouseButton(GLFW_MOUSE_BUTTON_RIGHT,false);ignitions++;ignition=null;mouseDown=false;return;
            }
            boolean aimed=false;StringBuilder rays=new StringBuilder();
            for(int r=2;r<=3&&!aimed;r++)for(int n=0;n<16&&!aimed;n++){
                double angle=n*Math.PI/8;float px=ignition[0]+.5f+(float)Math.cos(angle)*r,pz=ignition[2]+.5f+(float)Math.sin(angle)*r,py=Y+1.01f;
                if(BodyCollision.overlaps(world(),px,py,pz,Player.WIDTH,Player.HEIGHT)){rays.append(" blockedBody@").append(px).append(',').append(pz);continue;}
                org.joml.Vector3f eye=new org.joml.Vector3f(px,py+Player.EYE_HEIGHT,pz),dir=new org.joml.Vector3f(ignition[0]+.5f,Y+.99f,ignition[2]+.5f).sub(eye);
                var hit=Raycaster.cast(world(),eye,dir.normalize(),5);
                rays.append(" hit=").append(hit==null?"null":hit.x+","+hit.y+","+hit.z+"/"+hit.nx+","+hit.ny+","+hit.nz+" "+world().getBlock(hit.x,hit.y,hit.z));
                if(hit!=null&&hit.x==ignition[0]&&hit.y==Y&&hit.z==ignition[2]&&hit.ny==1){pose(px,py,pz,ignition[0]+.5f,Y+.99f,ignition[2]+.5f);if(ignitions==0)firstFirePose=new float[]{px,py,pz};aimed=true;}
            }
            check(aimed,"actual ignition support has no unobstructed safe under-canopy input ray at "+Arrays.toString(ignition)+rays);
            if(!mouseDown){input().injectMouseButton(GLFW_MOUSE_BUTTON_RIGHT,true);set(game,"swallowMouseUntilUp",false);mouseDown=true;return;}
            if(world().getBlock(ignition[0],ignition[1],ignition[2])!=BlockType.FIRE)return;
            input().injectMouseButton(GLFW_MOUSE_BUTTON_RIGHT,false);ignitions++;ignition=null;mouseDown=false;
        }
        /** No outcome writes: accelerate precisely the production random callbacks at actual cells. */
        void pumpFire(boolean rain)throws Exception{
            for(int pass=0;pass<3;pass++)for(int y=Y+1;y<=Y+12;y++)for(int x=X-RADIUS-1;x<=X+RADIUS+1;x++)for(int z=Z-RADIUS-1;z<=Z+RADIUS+1;z++){
                BlockType b=world().getBlock(x,y,z);
                if(b==BlockType.FIRE){accelerated.apply(world(),x,y,z,rain);fireCallbacks++;}
                else if(rain&&b==BlockType.ASH){accelerated.apply(world(),x,y,z,true);ashCallbacks++;}
            }
        }
        @Override public void update(){try{frame++;check(System.nanoTime()-started<900_000_000_000L,"native forest acceptance timed out at stage "+stage);check(glGetError()==GL_NO_ERROR,"native forest OpenGL error");if(game!=null)seconds+=(float)get(game,"lastDt");tick();}catch(Throwable t){failure=t;t.printStackTrace();glfwSetWindowShouldClose(getHandle(),true);}super.update();}
        Mob[] wolves=new Mob[5];Mob openRabbit,closedRabbit;long wolfId,openId,closedId,absenceDay;
        Lair old,colony;Set<Long> firstSpiders=new HashSet<>(),secondSpiders=new HashSet<>();Map<Long,Float> returnDistances=new HashMap<>();
        boolean physicalReturn,breakHeld;int clears,walkLeg,beforeRabbits,afterRabbits,trailBefore,trailAfter,webs,openDamage,closedDamage;
        float walkSeconds;Set<Long> protectedIds=new TreeSet<>();
        byte[] ecologyBytes()throws Exception{Map<String,byte[]> m=new LinkedHashMap<>();session().ecology().writeSections(m);return m.get(Ecology.SAVE_SECTION);}
        Lair oldRecord()throws Exception{return session().ecology().lairs().lairs(EcologyRegion.key(0,0)).stream().filter(l->l.x()==16&&l.y()==101&&l.z()==16).findFirst().orElse(null);}
        Lair otherRecord()throws Exception{return session().ecology().lairs().lairs(EcologyRegion.key(0,0)).stream().filter(Lair::alive).filter(l->l.x()!=16||l.z()!=16).findFirst().orElse(null);}
        Set<Long> spiderIds(Lair l)throws Exception{Set<Long> ids=new TreeSet<>();for(Mob m:session().entities().mobs)if(!m.dead&&l.bodyIds().contains(m.id)){check(m.persistent()&&m.type==MobType.SPIDER,"unreserved lair body");check(ids.add(m.id),"duplicate spider ID");}return ids;}
        void kill(Mob m)throws Exception{int actor=((com.mineclone.net.Multiplayer)get(game,"net")).participants().get(0).id();check(m.damage(com.mineclone.world.damage.DamageSource.byPlayer(com.mineclone.world.damage.DamageType.MELEE,actor,player().position.x,player().position.y,player().position.z,0),100),"ordinary player damage refused");}
        void oldView()throws Exception{pose(16.5f,101.001f,21.5f,16.5f,101.5f,16.5f);}
        void fieldView()throws Exception{pose(22.5f,115,159,22.5f,101.15f,152);}
        void colonyView()throws Exception{
            float score=Float.POSITIVE_INFINITY;float[] chosen=null;
            for(int dy=-1;dy<=6;dy++)for(int dx=-7;dx<=7;dx++)for(int dz=-7;dz<=7;dz++){
                if(dx*dx+dz*dz<6||dx*dx+dz*dz>64)continue;float x=colony.x()+.5f+dx,y=colony.y()+.001f+dy,z=colony.z()+.5f+dz;
                if(world().getChunkIfExists(Math.floorDiv((int)Math.floor(x),16),Math.floorDiv((int)Math.floor(z),16))==null||BodyCollision.overlaps(world(),x,y,z,.6f,1.8f))continue;
                var eye=new org.joml.Vector3f(x,y+Player.EYE_HEIGHT,z);var dir=new org.joml.Vector3f(colony.x()+.5f,colony.y()+.5f,colony.z()+.5f).sub(eye);float distance=dir.lengthSquared();var hit=Raycaster.cast(world(),eye,dir.normalize(),20);
                if(hit!=null&&hit.x==colony.x()&&hit.y==colony.y()&&hit.z==colony.z()&&distance<score){score=distance;chosen=new float[]{x,y,z};}
            }check(chosen!=null,"new natural cave has no collision-free actual viewing ray");pose(chosen[0],chosen[1],chosen[2],colony.x()+.5f,colony.y()+.5f,colony.z()+.5f);
        }
        int websAt(Lair l)throws Exception{int n=0;for(int dx=-8;dx<=8;dx++)for(int dy=-8;dy<=8;dy++)for(int dz=-8;dz<=8;dz++)if(dx*dx+dy*dy+dz*dz<=64&&world().getBlock(l.x()+dx,l.y()+dy,l.z()+dz)==BlockType.WEB)n++;return n;}
        int damaged(int left)throws Exception{int n=0;for(int x=left;x<left+4;x++)for(int z=150;z<154;z++){check(world().getBlock(x,100,z)==BlockType.FARMLAND&&world().getBlock(x,101,z)==BlockType.CARROTS,"field/support replaced");int meta=Byte.toUnsignedInt(world().getBlockMeta(x,101,z));check(meta<=7,"returned crop has invalid growth metadata "+meta);if(meta<7)n++;}return n;}
        void freezeCrops()throws Exception{world().setScheduledTickHandler((w,x,y,z,b,m,late)->{if(b!=BlockType.WHEAT&&b!=BlockType.CARROTS&&b!=BlockType.POTATOES)World.ScheduledTickHandler.BEHAVIOURS.tick(w,x,y,z,b,m,late);});}
        void clearNight()throws Exception{command("/time set noon");command("/time add 24");command("/time set midnight");oldView();}
        void saveAndReopen()throws Exception{call(game,"saveAll");SaveManager save=(SaveManager)get(game,"save");save.flushAndAwait();savedEcology=save.loadLevel(worldId).extraSections.get(Ecology.SAVE_SECTION);check(Arrays.equals(savedEcology,ecologyBytes()),"save ecology does not reflect actual combined session");call(game,"handleMenuAction",new Class<?>[]{MenuAction.class},MenuAction.of(MenuAction.Kind.MAIN_MENU));check(get(game,"world")==null,"menu did not unload actual world");check((boolean)call(game,"startWorld",new Class<?>[]{String.class},worldId),"combined world did not reopen");}
        void loadValley()throws Exception{ChunkLoader loader=(ChunkLoader)get(game,"loader");for(int cx=-2;cx<=8;cx++)for(int cz=-2;cz<=11;cz++)loader.loadNow(cx,cz);}
        void identities()throws Exception{Set<Long> ids=new HashSet<>();for(Mob m:session().entities().mobs)check(m.id>0&&ids.add(m.id),"duplicate active native identity "+m.id);for(Chunk c:world().getLoadedChunks())for(var m:c.copyPendingMobs())check(ids.add(m.id()),"duplicate active/pending identity "+m.id());for(Lair l:session().ecology().lairs().lairs(EcologyRegion.key(0,0)))check(l.bodyIds().size()<=4&&l.population()<=l.capacity(),"lair exceeded normal finite cap");}
        void savedProtectedBodies()throws Exception{
            SaveManager save=(SaveManager)get(game,"save");Map<Long,com.mineclone.save.SavedMob> bodies=new HashMap<>();Path chunks=Path.of(System.getProperty("mineclone.savesDir"),worldId,"chunks");
            try(var files=Files.list(chunks)){for(Path file:files.toList()){String n=file.getFileName().toString();if(!n.matches("c\\.-?\\d+\\.-?\\d+\\.dat"))continue;String[] at=n.split("\\.");var load=save.readChunk(worldId,Integer.parseInt(at[1]),Integer.parseInt(at[2]));check(load instanceof com.mineclone.save.ChunkLoad.Loaded,"combined saved chunk is unreadable");for(var m:((com.mineclone.save.ChunkLoad.Loaded)load).snapshot().mobs)check(bodies.putIfAbsent(m.id(),m)==null,"duplicate saved finite ID "+m.id());}}
            var eco=EcologyCodec.decode(savedEcology);for(long id:protectedIds){var m=bodies.get(id);check(m!=null&&m.health()>0&&m.persistent()&&eco.reservations().containsKey(id),"protected saved finite body disappeared: "+id);check(clock().entityCounter()>=id,"saved allocator behind protected ID");}
        }
        void tick()throws Exception{
            if(game==null)return;String state=get(game,"state").toString();
            if(get(game,"session")!=null&&stage!=19)set(session(),"spawnTimer",3600f);
            if(cow!=null&&!cow.dead){if(!cowRetreatIssued&&cow.burnDestination!=null){cowRetreatIssued=true;cowStartX=cow.position.x;cowStartZ=cow.position.z;}if(cowRetreatIssued&&Math.hypot(cow.position.x-cowStartX,cow.position.z-cowStartZ)>3)cowMoved=true;}
            if(stage==0&&frame>4){call(game,"createWorld",new Class<?>[]{WorldSettings.class},new WorldSettings("Living world A B C journey",SEED,GameMode.CREATIVE));worldId=(String)get(game,"worldId");next(1);}
            else if(stage==1&&state.equals("PLAYING")){
                if(!streamRequested){command("/weather clear");command("/time set noon");command("/tp 70 112 70");command("/fly");streamRequested=true;next(1);return;}if(seconds<3)return;
                ChunkLoader loader=(ChunkLoader)get(game,"loader");for(int cx=-7;cx<=15;cx++)for(int cz=-7;cz<=17;cz++)loader.loadNow(cx,cz);
                check(!session().ecology().enabled(),"initial valley must precede finite adoption");session().entities().mobs.clear();session().entities().items.clear();for(Chunk c:world().getLoadedChunks()){c.setPendingMobs(List.of());c.modified=true;}
                LivingWorldSaveAcceptance.buildValley(world(),0,0,Y);for(int[] t:TREES)initialRoots.add(cell(X+t[0],Z+t[1]));
                for(int i=0;i<5;i++){wolves[i]=new Mob(MobType.WOLF,55.5f+i,101.001f,163.5f,new Random(SEED+i));wolves[i].onGround=true;session().entities().mobs.add(wolves[i]);}
                command("/ecology on");region().population(com.mineclone.data.ResourceId.of("rabbit"),20);region().population(com.mineclone.data.ResourceId.of("wolf"),5);beforeRabbits=20;wolfId=wolves[4].id;
                cow=new Mob(MobType.COW,X-14.5f,Y+1.001f,Z-10.5f,new Random(SEED));cow.onGround=true;cow.persistent=true;check(session().ecology().claim(cow),"cow finite claim refused");session().entities().mobs.add(cow);cowId=cow.id;protectedIds.add(cowId);protectedIds.add(wolfId);
                for(Mob m:session().entities().mobs)if(!m.dead&&m.persistent()&&m.id>0)initialAnimalIds.add(m.id);
                call(session().ecology(),"refreshHabitat");beforeCapacity=capacity();beforeGreenery=region().averageGreenery();burnDay=Ecology.day(clock().gameTime());check(burnDay==0,"initial story day must be zero");
                ((Inventory)get(game,"inventory")).set(0,ItemStack.of("fire"));set(game,"selectedSlot",0);overview();input().holdKey(GLFW_KEY_F3,true);input().holdKey(GLFW_KEY_G,true);next(2);
            }else if(stage==2&&state.equals("PLAYING")){
                input().holdKey(GLFW_KEY_F3,false);input().holdKey(GLFW_KEY_G,false);if(seconds<1)return;
                check(player().flying&&(boolean)get(game,"showDebug")&&(boolean)get(game,"debugEcology"),"actual F3+G shortcut/flight failed");byte[] before=ecologyBytes();command("/ecology info");check(Arrays.equals(before,ecologyBytes()),"diagnostic command changed ecology after ordinary ticking");
                shot("stories-01-diagnostics-three-stories-day-zero");clearToasts();for(int i=0;i<4;i++)kill(wolves[i]);next(3);
            }else if(stage==3&&state.equals("PLAYING")){
                check(seconds<90,"actual input fire did not burn enough ground");
                if(ignition!=null||count(BlockType.FIRE,Y+1,Y+12)==0){if(burned.size()>=192&&oldRoots()==0){overview();next(4);return;}igniteThroughInput();if(ignition!=null)return;}
                if(ignitions==1&&fireCallbacks==0){clearToasts();pose(firstFirePose[0],firstFirePose[1],firstFirePose[2],firstIgnited[0]+.5f,Y+1.4f,firstIgnited[2]+.5f);if(firstFireFrame==0){firstFireFrame=frame;return;}if(frame-firstFireFrame<20)return;shot("stories-02-real-input-fire");}
                pumpFire(false);observeBurned();
            }else if(stage==4&&state.equals("PLAYING")){
                pumpFire(false);observeBurned();if(count(BlockType.FIRE,Y+1,Y+12)>0){check(seconds<30,"actual flames did not expire");return;}scarView();if(!renderSettled())return;
                call(session().ecology(),"refreshHabitat");afterCapacity=capacity();afterGreenery=region().averageGreenery();check(afterCapacity<beforeCapacity&&afterGreenery<beforeGreenery&&scars()>0&&oldRoots()==0,"actual fire did not persist damage/capacity drop");
                check(region().population(com.mineclone.data.ResourceId.of("wolf"))==1&&region().lossCount(EcologyRegion.LossCause.PLAYER)==4,"four actual wolf deaths were not retired once");
                shot("stories-03-scorched-and-wolf-losses");System.out.println("STORIES_GAME initial PASS F3G=true diagnosticPure=true wolfDeaths=4 originalMatureRootsConsumed=6 scars="+scars()+" sites="+burned.size()+" capacity="+beforeCapacity+"->"+afterCapacity+" actualRMBIgnitions="+ignitions);command("/weather rain");next(5);
            }else if(stage==5&&state.equals("PLAYING")){
                overview();float rain=(float)call(get(game,"atmosphere"),"rain");if(rain<BlockTicker.RAIN_EXTINGUISH){check(seconds<18,"actual rain never arrived");return;}pumpFire(true);if(count(BlockType.ASH,Y+1,Y+12)>0&&seconds<12)return;if(!cowMoved){check(seconds<25,"tracked cow did not physically escape scars");return;}
                check(!cow.dead,"tracked cow died during escape");old=oldRecord();check(old!=null&&old.alive()&&old.population()==6&&old.bodyIds().isEmpty(),"initial heart did not preserve six-member pool");command("/weather clear");command("/time set midnight");oldView();next(6);
            }else if(stage==6&&state.equals("PLAYING")){
                pose(12.5f,101.001f,20.5f,16.5f,101.5f,16.5f);check(seconds<35,"native night did not emit four finite spiders");if(spiderIds(old).size()!=4||seconds<12)return;firstSpiders=spiderIds(old);boolean far=false;
                for(Mob m:session().entities().mobs)if(firstSpiders.contains(m.id)){float d=m.position.distanceSquared(16.5f,101,16.5f);returnDistances.put(m.id,d);far|=d>12.25f;}if(!far)return;shot("stories-04-night-finite-spiders");command("/time set noon");next(7);
            }else if(stage==7&&state.equals("PLAYING")){
                oldView();check(seconds<45,"dawn spiders did not physically navigate home");for(Mob m:session().entities().mobs)if(firstSpiders.contains(m.id)&&m.lairDestination!=null&&returnDistances.get(m.id)>12.25f&&m.position.distanceSquared(16.5f,101,16.5f)<returnDistances.get(m.id)-.25f)physicalReturn=true;
                if(!old.bodyIds().isEmpty()||seconds<1)return;check(physicalReturn&&old.population()==6,"dawn erased or duplicated finite pool without physical return");command("/time set midnight");next(8);
            }else if(stage==8&&state.equals("PLAYING")){
                oldView();check(seconds<15,"second night did not reuse same finite pool");if(spiderIds(old).size()!=4||seconds<2)return;secondSpiders=spiderIds(old);Set<Long> dup=new HashSet<>(firstSpiders);dup.retainAll(secondSpiders);check(dup.isEmpty()&&old.population()==6,"second emission duplicated returned identities");next(9);
            }else if(stage==9&&state.equals("PLAYING")){
                pose(16.5f,103,19.5f,16.5f,101.5f,16.5f);check(seconds<4,"actual held left input did not destroy heart");if(!breakHeld&&frame-stageFrame>2){input().injectMouseButton(GLFW_MOUSE_BUTTON_LEFT,true);breakHeld=true;return;}if(world().getBlock(16,101,16)==BlockType.SPIDER_NEST)return;input().injectMouseButton(GLFW_MOUSE_BUTTON_LEFT,false);if(old.alive())return;
                check(old.destroyedDay()==0&&old.bodyIds().equals(secondSpiders),"heart destruction lost wanderers or day-zero chronology");for(Mob m:session().entities().mobs)if(secondSpiders.contains(m.id))kill(m);next(10);
            }else if(stage==10&&state.equals("PLAYING")&&seconds>1){
                check(old.bodyIds().isEmpty()&&old.population()==2,"ordinary spider deaths did not retire exactly once");System.out.println("STORIES_GAME lair PASS firstNightIDs="+firstSpiders+" secondNightIDs="+secondSpiders+" dawnPhysicalReturn=true actualHeldHeartBreak=true ordinaryWandererDeaths=4 oldPool=2");
                command("/time set noon");set(game,"gameMode",GameMode.SURVIVAL);player().flying=false;pose(8.5f,101.001f,168.5f,19.5f,102.62f,168.5f);input().holdKey(GLFW_KEY_W,true);walkLeg=0;walkSeconds=0;next(11);
            }else if(stage==11&&state.equals("PLAYING")){
                walkSeconds+=(float)get(game,"lastDt");if(walkSeconds<2.3f)return;input().holdKey(GLFW_KEY_W,false);walkSeconds=0;walkLeg++;
                if(walkLeg<20){Player p=player();float target=walkLeg%2==1?5.5f:20.5f;p.camera.yaw=(float)Math.atan2(target-p.position.x,-(168.5f-p.position.z));p.camera.pitch=0;input().holdKey(GLFW_KEY_W,true);return;}
                trailBefore=Trample.pressureAt(world(),13,100,168);check(world().getBlock(13,100,168)==BlockType.GRASS&&world().getBlockMeta(13,100,168)==5&&trailBefore>=16,"actual walking did not deepen trail: "+trailBefore);
                set(game,"gameMode",GameMode.CREATIVE);player().flying=true;pose(13.5f,103,174.5f,13.5f,100.1f,168.5f);System.out.println("STORIES_GAME trail PASS actualW=true legs=20 pressure="+trailBefore+" stage=5 grassUnchanged=true");next(18);
            }else if(stage==18&&state.equals("PLAYING")){pose(13.5f,103,174.5f,13.5f,100.1f,168.5f);if(!renderSettled())return;shot("stories-05-native-walked-trail");clearNight();next(12);
            }else if(stage==12&&state.equals("PLAYING")&&seconds>2){
                oldView();check(!old.alive()&&old.bodyIds().isEmpty()&&old.population()==2&&world().getBlock(16,101,16)==BlockType.AIR,"old territory respawned on a separate cleared night");clears++;
                if(clears<3){clearNight();next(12);return;}check(Ecology.day(clock().gameTime())==3,"three cleared nights did not occupy distinct days");command("/ecology advance 4");command("/time set noon");overview();next(13);
            }else if(stage==13&&state.equals("PLAYING")){
                overview();check(seconds<25,"day-seven plants did not finish ordinary scheduled ticks");if(seconds<3||recoveredGrass()<burned.size()/2||newTrees()==0)return;
                check(Ecology.day(clock().gameTime())==7,"joint A/C chronology did not reach day seven");afterRabbits=region().population(com.mineclone.data.ResourceId.of("rabbit"));check(afterRabbits>beforeRabbits,"actual predator release did not recover rabbits");call(session().ecology(),"refreshHabitat");check(capacity()>afterCapacity,"recovered habitat capacity did not improve");trailAfter=Trample.pressureAt(world(),13,100,168);
                youngTreeView();if(!renderSettled())return;shot("stories-06-day-seven-recovered-forest");System.out.println("STORIES_GAME day7 PASS rabbits="+beforeRabbits+"->"+afterRabbits+" grass="+recoveredGrass()+" naturalYoungRoots="+newTrees()+" trailFadePendingArrival=true clearedNights=3 cowID="+cowId+" cowPhysicalEscape=true");
                freezeCrops();openRabbit=LivingWorldSaveAcceptance.claimRabbit(session(),9.5f,147.5f,20);closedRabbit=LivingWorldSaveAcceptance.claimRabbit(session(),33.5f,146.5f,21);openId=openRabbit.id;closedId=closedRabbit.id;protectedIds.add(openId);protectedIds.add(closedId);initialAnimalIds.add(openId);initialAnimalIds.add(closedId);world().setBlock(9,101,148,BlockType.FENCE_GATE,(byte)4);fieldView();next(14);
            }else if(stage==14&&state.equals("PLAYING")){
                fieldView();if(seconds>.5f){trailAfter=Trample.pressureAt(world(),13,100,168);check(trailAfter<trailBefore,"abandoned actual trail did not fade on ordinary field arrival: "+trailBefore+"->"+trailAfter);}check(seconds<35,"ordinary rabbit did not raid accessible field");openDamage=damaged(8);check(damaged(32)==0&&!openRabbit.dead&&!closedRabbit.dead,"closed field bypassed or story rabbit died");if(seconds<12||openDamage==0)return;
                shot("stories-07-open-raided-fenced-protected");world().setBlock(33,101,148,BlockType.FENCE_GATE,(byte)4);set(closedRabbit,"hungerTimer",0f);next(15);
            }else if(stage==15&&state.equals("PLAYING")){
                fieldView();check(seconds<35,"actual opened gate did not grant rabbit access");closedDamage=damaged(32);if(closedDamage==0||seconds<1)return;shot("stories-08-opened-gate-ordinary-raid");System.out.println("STORIES_GAME raid PASS openDamaged="+openDamage+" protectedBeforeOpen=0 closedAfterRealGate="+closedDamage+" directRaidCalls=0 trailPressure="+trailBefore+"->"+trailAfter+" finiteRabbitIDs="+openId+","+closedId);world().setScheduledTickHandler(null);command("/ecology advance 1");command("/time set midnight");oldView();next(16);
            }else if(stage==16&&state.equals("PLAYING")){
                oldView();check(seconds<25,"other cave did not found actual colony");colony=otherRecord();if(colony==null)return;
                check(Ecology.day(clock().gameTime())==8&&colony.lastDay()==8&&websAt(colony)==0,"new colony birth was not day eight before silk");check(Math.hypot(colony.x()-16,colony.z()-16)>=48&&Math.hypot(colony.x()-22,colony.z()-23)>=32,"colony ignored cleared territory or chest base");check(world().getSkyLight(colony.x()+1,colony.y(),colony.z()+1)==0&&world().getBlockLightWorld(colony.x()+1,colony.y(),colony.z()+1)==0,"new colony is not a real dark cave");
                System.out.println("STORIES_GAME colony PASS newHeart="+colony.x()+","+colony.y()+","+colony.z()+" birthDay=8 birthWebs=0 realCandidateDiscovery=true");command("/ecology advance 1");colonyView();next(17);
            }else if(stage==17&&state.equals("PLAYING")){
                colonyView();if(seconds<2)return;webs=websAt(colony);check(webs>=1&&webs<=2&&Ecology.day(clock().gameTime())==9,"actual first next-day silk missing");if(!renderSettled())return;shot("stories-09-other-cave-first-scheduled-silk");System.out.println("STORIES_GAME silk PASS firstNextDay=9 actualScheduledWebs="+webs);pose(X,Y+1.001f,Z-44,X,Y+1,Z-90);set(session(),"spawnTimer",0f);next(19);
            }else if(stage==19&&state.equals("PLAYING")){
                pose(X,Y+1.001f,Z-44,X,Y+1,Z-90);check(seconds<65,"ordinary finite materializer did not return animals to recovered grove");
                for(Mob m:session().entities().mobs)if(!m.dead&&m.persistent()&&m.type.temper==MobType.Temper.PASSIVE&&!initialAnimalIds.contains(m.id)&&Math.abs(m.position.x-X)<=RADIUS&&Math.abs(m.position.z-Z)<=RADIUS&&world().getBlock((int)Math.floor(m.position.x),Y,(int)Math.floor(m.position.z))==BlockType.GRASS)newAnimalIds.add(m.id);
                if(newAnimalIds.isEmpty())return;protectedIds.addAll(newAnimalIds);System.out.println("STORIES_GAME returned PASS freshFiniteIDs="+newAnimalIds+" excludeEntireInitialCast=true originalCowSurvived=true identityReturnClaimed=false usesOrdinaryCaps=true");overview();next(20);
            }else if(stage==20&&state.equals("PLAYING")&&seconds>1){shot("stories-10-combined-before-save");saveAndReopen();next(21);}
            else if(stage==21&&get(game,"world")!=null){check(session().ecology().enabled()&&Arrays.equals(savedEcology,ecologyBytes()),"initial reopen changed combined ecology records/reservations");if(!player().flying)command("/fly");loadValley();next(22);}
            else if(stage==22&&state.equals("PLAYING")&&seconds>3){
                identities();old=oldRecord();colony=otherRecord();check(old!=null&&!old.alive()&&colony!=null&&colony.alive()&&websAt(colony)==webs&&newTrees()>0,"combined immediate reopen lost story outcomes");savedProtectedBodies();fieldView();clearToasts();shot("stories-11-combined-save-reopen");absenceDay=Ecology.day(clock().gameTime());command("/tp 670 116 70");next(23);
            }else if(stage==23&&state.equals("PLAYING")){
                check(seconds<40,"actual long departure did not unload valley");if(world().getChunkIfExists(4,4)!=null||world().getChunkIfExists(0,0)!=null||world().getChunkIfExists(0,10)!=null)return;
                command("/ecology advance 30");check(Ecology.day(clock().gameTime())==absenceDay+30,"absence did not advance thirty actual owner days");saveAndReopen();next(24);
            }else if(stage==24&&get(game,"world")!=null){check(Arrays.equals(savedEcology,ecologyBytes()),"departure save/reopen changed clock-driven ecology");if(!player().flying)command("/fly");command("/tp 70 112 70");next(25);}
            else if(stage==25&&state.equals("PLAYING")){
                if(seconds<5)return;loadValley();next(26);
            }else if(stage==26&&state.equals("PLAYING")){
                fieldView();if(seconds<3)return;identities();old=oldRecord();colony=otherRecord();check(Ecology.day(clock().gameTime())==absenceDay+30&&old!=null&&!old.alive()&&old.bodyIds().isEmpty()&&colony!=null&&colony.alive(),"thirty-day return lost lair chronology");
                check(recoveredGrass()>=burned.size()/2&&newTrees()>0&&websAt(colony)>0&&websAt(colony)<=32,"thirty-day return lost actual forest/silk");damaged(8);damaged(32);int finalPressure=Trample.pressureAt(world(),13,100,168);BlockType finalGround=world().getBlock(13,100,168);int finalMeta=Byte.toUnsignedInt(world().getBlockMeta(13,100,168));check(finalGround==BlockType.GRASS&&finalMeta==0&&finalPressure==0,"unloaded trail did not regrow on actual field arrival: block="+finalGround+" meta="+finalMeta+" pressure="+finalPressure);System.out.println("STORIES_GAME returnedField PASS supportsAndCropBlocksIntact=true stages0to7=true trailBlock="+finalGround+" trailMeta="+finalMeta+" trailPressure="+finalPressure+" ordinaryArrivalFrames=true");next(27);
            }else if(stage==27&&state.equals("PLAYING")){
                youngTreeView();if(!renderSettled())return;call(game,"saveAll");((SaveManager)get(game,"save")).flushAndAwait();savedEcology=((SaveManager)get(game,"save")).loadLevel(worldId).extraSections.get(Ecology.SAVE_SECTION);savedProtectedBodies();shot("stories-12-thirty-days-away-return");
                System.out.println("STORIES_GAME absence PASS realDepartureBlocks=600 valleyUnloaded=true advanceDays=30 calendar="+absenceDay+"->"+Ecology.day(clock().gameTime())+" protectedFiniteIDs="+protectedIds+" uniqueActivePendingSavedIDs=true actualTrailRegrown=true cropsMayRegrow=true");
                System.out.println("STORIES_GAME PASS nativeFrames="+frame+" sameWorldABC=true actualInputs=true separateNights=3 firstNextDaySilk=true originalCowPhysicalEscape=true combinedSaveReopen=true thirtyDayAbsence=true screenshots="+output);next(28);glfwSetWindowShouldClose(getHandle(),true);
            }
        }
        void shot(String name)throws Exception{
            int width=getWidth(),height=getHeight();var pixels=org.lwjgl.system.MemoryUtil.memAlloc(width*height*4);
            try{glReadPixels(0,0,width,height,GL_RGBA,GL_UNSIGNED_BYTE,pixels);var image=new java.awt.image.BufferedImage(width,height,java.awt.image.BufferedImage.TYPE_INT_RGB);
                for(int y=0;y<height;y++)for(int x=0;x<width;x++){int p=(y*width+x)*4;image.setRGB(x,height-1-y,((pixels.get(p)&255)<<16)|((pixels.get(p+1)&255)<<8)|(pixels.get(p+2)&255));}
                Path file=output.resolve(name+".png");javax.imageio.ImageIO.write(image,"png",file.toFile());System.out.println("STORIES_GAME screenshot="+file+" observer="+player().position);
            }finally{org.lwjgl.system.MemoryUtil.memFree(pixels);}
        }
    }
    public static void main(String[] args)throws Exception{
        Path output=Path.of(args.length==0?"out-test/stories-game":args[0]).toAbsolutePath().normalize();check(output.startsWith(Path.of("out-test").toAbsolutePath().normalize()),"native output must stay below out-test");
        Files.createDirectories(output);System.setProperty("mineclone.savesDir",Files.createTempDirectory(output,"saves-").toString());System.setProperty("mineclone.checkThreadOwnership","true");
        Review window=new Review(output);window.init();boolean normal=false;
        try{window.game=new Game(window,false);((Input)get(window.game,"input")).setGrabAllowed(false);set(window.game,"renderRadius",6);set(window.game,"guiScale",1);set(window.game,"mouseSensitivity",0f);
            window.game.run();normal=true;if(window.failure!=null)throw new AssertionError("Native living-world journey failed",window.failure);check(window.stage==28,"native combined journey did not complete");
        }finally{if(!normal&&window.game!=null)try{call(window.game,"cleanup");}catch(Exception t){t.printStackTrace();}window.destroy();}
    }
}
