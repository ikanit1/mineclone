import com.mineclone.core.Input;
import com.mineclone.core.Window;
import com.mineclone.game.Game;
import com.mineclone.game.Player;
import com.mineclone.game.Raycaster;
import com.mineclone.save.SaveManager;
import com.mineclone.sim.WorldClock;
import com.mineclone.sim.WorldSession;
import com.mineclone.sim.ecology.Ecology;
import com.mineclone.sim.ecology.EcologyRegion;
import com.mineclone.sim.ecology.Lair;
import com.mineclone.sim.ecology.LairSpots;
import com.mineclone.ui.MenuAction;
import com.mineclone.ui.WorldSettings;
import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.GameMode;
import com.mineclone.world.Inventory;
import com.mineclone.world.ItemStack;
import com.mineclone.world.World;
import com.mineclone.world.damage.DamageSource;
import com.mineclone.world.damage.DamageType;
import com.mineclone.world.entity.Mob;
import com.mineclone.world.entity.MobSpawner;
import com.mineclone.world.entity.MobType;
import com.mineclone.world.shape.BodyCollision;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.joml.Vector3f;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

/** Native Game/Input LW-04/05 acceptance in isolated temporary saves. No production hooks. */
public final class LairGameSmoke {
    private static final long SEED=20261002L; // This region's cleared-heart founding delay is eight days.
    private static final int FLOOR=100,Y=FLOOR+1,OLD_X=16,Z=16,OTHER_X=88;
    private static final long REGION=EcologyRegion.key(0,0);

    private static Object get(Object target,String name)throws Exception{
        var f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);
    }
    private static void set(Object target,String name,Object value)throws Exception{
        var f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);
    }
    private static Object call(Object target,String name,Class<?>[] types,Object...args)throws Exception{
        var m=target.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(target,args);
    }
    private static Object call(Object target,String name)throws Exception{return call(target,name,new Class<?>[0]);}
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}

    private static final class Review extends Window {
        final Path output;
        final long started=System.nanoTime();
        Game game;Throwable failure;
        int frame,stage,stageFrame,clearedNights;
        float seconds;
        boolean streamingRequested,returnMovement,breakHeld;
        String worldId;
        Lair old,colony;
        int pool,oldPopulationAfterDeaths,savedWebs;
        long destroyedDay,previousNightDay;
        Set<Long> firstIds=new HashSet<>(),secondIds=new HashSet<>(),savedIds=new HashSet<>();
        final Map<Long,Float> returnDistances=new HashMap<>();
        byte[] savedEcology;
        float colonyViewX,colonyViewY,colonyViewZ;

        Review(Path output){super("Mineclone living spider lairs native acceptance",1280,720,false);this.output=output;}
        void next(int value){stage=value;stageFrame=frame;seconds=0;}
        WorldSession session()throws Exception{return (WorldSession)get(game,"session");}
        World world()throws Exception{return session().world();}
        Player player()throws Exception{return (Player)get(game,"player");}
        Input input()throws Exception{return (Input)get(game,"input");}
        WorldClock clock()throws Exception{return (WorldClock)get(game,"worldClock");}
        void command(String text)throws Exception{call(game,"executeCommand",new Class<?>[]{String.class},text);}
        void clearToasts()throws Exception{set(game,"commandHelpTimer",0f);set(game,"commandToastTimer",0f);set(game,"commandPanel",new String[0]);}
        Lair oldRecord()throws Exception{
            return session().ecology().lairs().lairs(REGION).stream().filter(l->l.x()==OLD_X&&l.y()==Y&&l.z()==Z).findFirst().orElse(null);
        }
        Lair otherRecord()throws Exception{
            return session().ecology().lairs().lairs(REGION).stream().filter(Lair::alive).filter(l->l.x()!=OLD_X||l.z()!=Z).findFirst().orElse(null);
        }
        Set<Long> aliveIds(Lair lair)throws Exception{
            Set<Long> ids=new HashSet<>();
            for(Mob body:session().entities().mobs)if(!body.dead&&lair.bodyIds().contains(body.id)){
                check(body.type==MobType.SPIDER&&body.persistent(),"lair emitted an untracked or temporary body");
                check(ids.add(body.id),"duplicate emitted spider ID");
            }
            return ids;
        }
        void identityAndCaps()throws Exception{
            Set<Long> ids=new HashSet<>();int hostile=0;
            for(Mob mob:session().entities().mobs){check(mob.id>0&&ids.add(mob.id),"duplicate native mob ID");if(!mob.dead&&mob.type.hostile)hostile++;}
            check(hostile<=MobSpawner.HOSTILE_CAP,"hostile global cap exceeded");
            for(Lair lair:session().ecology().lairs().lairs(REGION))check(lair.bodyIds().size()<=4&&lair.population()<=lair.capacity(),"lair finite caps exceeded");
        }
        void pose(float x,float y,float z,float tx,float ty,float tz)throws Exception{
            Player p=player();check(p.flying,"native creative observer lost flight");
            p.position.set(x,y,z);p.velocity.zero();p.camera.position.set(x,y+Player.EYE_HEIGHT,z);
            p.camera.yaw=(float)Math.atan2(tx-x,-(tz-z));
            p.camera.pitch=(float)-Math.atan2(ty-y-Player.EYE_HEIGHT,Math.hypot(tx-x,tz-z));
            check(!BodyCollision.overlaps(world(),x,y,z,.6f,1.8f),"native observer intersects the fixture");
        }
        void oldFront()throws Exception{pose(16.5f,Y+.001f,21.5f,16.5f,Y+.5f,16.5f);}
        int websAt(Lair lair)throws Exception{
            int count=0;
            for(int dx=-8;dx<=8;dx++)for(int dy=-8;dy<=8;dy++)for(int dz=-8;dz<=8;dz++)
                if(dx*dx+dy*dy+dz*dz<=64&&world().getBlock(lair.x()+dx,lair.y()+dy,lair.z()+dz)==BlockType.WEB)count++;
            return count;
        }
        void colonyPose()throws Exception{pose(colonyViewX,colonyViewY,colonyViewZ,colony.x()+.5f,colony.y()+.5f,colony.z()+.5f);}
        void findColonyPose()throws Exception{
            float score=Float.POSITIVE_INFINITY;
            for(int dy=-1;dy<=6;dy++)for(int dx=-7;dx<=7;dx++)for(int dz=-7;dz<=7;dz++){
                if(dx*dx+dz*dz<6||dx*dx+dz*dz>64)continue;
                float x=colony.x()+.5f+dx,y=colony.y()+.001f+dy,z=colony.z()+.5f+dz;
                if(world().getChunkIfExists(Math.floorDiv((int)Math.floor(x),16),Math.floorDiv((int)Math.floor(z),16))==null
                        ||BodyCollision.overlaps(world(),x,y,z,.6f,1.8f))continue;
                Vector3f eye=new Vector3f(x,y+Player.EYE_HEIGHT,z),dir=new Vector3f(colony.x()+.5f,colony.y()+.5f,colony.z()+.5f).sub(eye);
                float distance=dir.lengthSquared();var hit=Raycaster.cast(world(),eye,dir.normalize(),20);
                if(hit==null||hit.x!=colony.x()||hit.y!=colony.y()||hit.z!=colony.z()||distance>=score)continue;
                score=distance;colonyViewX=x;colonyViewY=y;colonyViewZ=z;
            }
            check(Float.isFinite(score),"actual generated colony cave has no collision-free native viewing pose");
        }
        @Override public void update(){
            try{
                frame++;check(System.nanoTime()-started<360_000_000_000L,"native lair acceptance timed out at stage "+stage);
                check(glGetError()==GL_NO_ERROR,"native lair OpenGL error");
                if(game!=null)seconds+=(float)get(game,"lastDt");
                tick();
            }catch(Throwable error){failure=error;error.printStackTrace();glfwSetWindowShouldClose(getHandle(),true);}
            super.update();
        }

        void tick()throws Exception{
            if(game==null)return;
            String state=get(game,"state").toString();
            if(get(game,"session")!=null)set(session(),"spawnTimer",3600f); // Pause unrelated ordinary waves, retain real lair spawning/brains.
            if(stage>=2&&stage<=9&&state.equals("PLAYING")){
                if(stage==3)pose(12.5f,Y+.001f,20.5f,16.5f,Y+.5f,16.5f);
                else if(stage==7)pose(16.5f,103f,19.5f,16.5f,Y+.5f,16.5f);
                else oldFront();
                identityAndCaps();
            }
            if(stage==0&&frame>4){
                call(game,"createWorld",new Class<?>[]{WorldSettings.class},new WorldSettings("Native spider lairs acceptance",SEED,GameMode.CREATIVE));
                worldId=(String)get(game,"worldId");next(1);
            }else if(stage==1&&state.equals("PLAYING")&&frame-stageFrame>15){
                if(!streamingRequested){
                    command("/weather clear");command("/time set noon");command("/tp 48.5 116 40.5");command("/fly");
                    check(player().flying,"creative flight owner command failed");streamingRequested=true;next(1);return;
                }
                if(seconds<3)return;
                boolean loaded=true;for(int cx=0;cx<=7;cx++)for(int cz=-1;cz<=3;cz++)loaded&=world().getChunkIfExists(cx,cz)!=null;
                if(!loaded){check(seconds<30,"Game did not stream the two native caves");return;}
                session().entities().mobs.clear();session().entities().items.clear();
                buildFixture(world());
                ((Inventory)get(game,"inventory")).set(0,null);
                command("/ecology on");oldFront();clearToasts();next(2);
            }else if(stage==2&&state.equals("PLAYING")&&seconds>2){
                old=oldRecord();check(old!=null&&old.alive(),"Game did not discover the actual placed nest");
                check(world().getBlock(OLD_X,Y,Z)==BlockType.SPIDER_NEST,"initial heart vanished");
                pool=old.population();check(pool==6&&old.bodyIds().isEmpty(),"daytime nest did not keep its initial six-member pool");
                int candidates=0;for(Chunk c:world().getLoadedChunks())if(c.cx>=4&&c.cx<=6&&c.cz>=0&&c.cz<=1){
                    byte[] raw=c.extraSection(LairSpots.SECTION);if(raw!=null)candidates+=LairSpots.decode(raw).count();
                }
                if(candidates==0){check(seconds<75,"other real dark cave has no automatically discovered colony candidate");return;}
                shot("lair-01-heart-front");next(3);
            }else if(stage==3&&state.equals("PLAYING")&&seconds>.8f){
                shot("lair-02-heart-oblique");command("/time set midnight");next(4);
            }else if(stage==4&&state.equals("PLAYING")){
                check(seconds<35,"night did not emit four native spiders");
                if(aliveIds(old).size()!=4||seconds<12)return;
                firstIds=aliveIds(old);check(firstIds.equals(old.bodyIds()),"emitted IDs differ from saved pool representations");
                check(old.population()==pool,"materializing four spiders added population");
                boolean far=false;for(Mob mob:session().entities().mobs)if(firstIds.contains(mob.id)){
                    float distance=mob.position.distanceSquared(OLD_X+.5f,Y,Z+.5f);returnDistances.put(mob.id,distance);far|=distance>12.25f;
                }
                if(!far)return;
                shot("lair-03-night-four-spiders");
                System.out.println("LAIR_GAME night PASS pool="+pool+" emitted="+firstIds+" hostileCaps=true outsideReturnRadius=true");
                command("/time set noon");next(5);
            }else if(stage==5&&state.equals("PLAYING")){
                check(seconds<45,"dawn spiders did not physically navigate home");
                for(Mob mob:session().entities().mobs)if(firstIds.contains(mob.id)&&mob.lairDestination!=null){
                    float before=returnDistances.get(mob.id),now=mob.position.distanceSquared(OLD_X+.5f,Y,Z+.5f);
                    if(before>12.25f&&now<before-.25f)returnMovement=true;
                }
                if(!old.bodyIds().isEmpty()||seconds<1)return;
                check(returnMovement,"dawn removal occurred without a tracked body's physical return movement");
                check(aliveIds(old).isEmpty()&&old.population()==pool,"dawn duplicated or consumed the finite pool");
                shot("lair-04-dawn-returned");
                System.out.println("LAIR_GAME dawn PASS trackedPhysicalReturn=true outside=0 pool="+pool);
                command("/time set midnight");next(6);
            }else if(stage==6&&state.equals("PLAYING")){
                check(seconds<15,"second night did not rematerialize the finite pool");
                if(aliveIds(old).size()!=4||seconds<2)return;
                secondIds=aliveIds(old);check(old.population()==pool,"second night changed the same pool");
                Set<Long> both=new HashSet<>(firstIds);both.retainAll(secondIds);check(both.isEmpty(),"returned IDs were duplicated by the second emission");
                System.out.println("LAIR_GAME nextNight PASS samePool="+pool+" emitted="+secondIds);
                pose(16.5f,103f,19.5f,16.5f,Y+.5f,16.5f);next(7);
            }else if(stage==7&&state.equals("PLAYING")){
                check(seconds<4,"held native left mouse did not break the nest through InteractionController");
                if(!breakHeld&&frame-stageFrame>2){input().injectMouseButton(GLFW_MOUSE_BUTTON_LEFT,true);breakHeld=true;return;}
                if(world().getBlock(OLD_X,Y,Z)==BlockType.SPIDER_NEST)return;
                input().injectMouseButton(GLFW_MOUSE_BUTTON_LEFT,false);
                if(old.alive())return;
                check(world().getBlock(OLD_X,Y,Z)==BlockType.AIR,"heart became another block after normal input");
                check(old.bodyIds().equals(secondIds),"heart destruction erased surviving wandering spiders");
                destroyedDay=old.destroyedDay();check(destroyedDay==0,"fixture expected destruction during first calendar day");
                int actor=((com.mineclone.net.Multiplayer)get(game,"net")).participants().get(0).id();
                for(Mob mob:session().entities().mobs)if(secondIds.contains(mob.id))check(mob.damage(
                        DamageSource.byPlayer(DamageType.MELEE,actor,player().position.x,player().position.y,player().position.z,0),100),"normal wanderer damage refused");
                next(8);
            }else if(stage==8&&state.equals("PLAYING")&&seconds>1){
                check(!old.alive()&&old.bodyIds().isEmpty()&&old.population()==pool-4,"wanderer deaths were not retired once");
                for(Mob mob:session().entities().mobs)if(secondIds.contains(mob.id))check(mob.dead&&mob.deathEffectsDone,"Game did not finish actual spider death effects");
                oldPopulationAfterDeaths=old.population();previousNightDay=destroyedDay;startClearedNight();next(9);
            }else if(stage==9&&state.equals("PLAYING")&&seconds>2){
                long day=Ecology.day(clock().gameTime());check(day>previousNightDay,"cleared nights did not use separate calendar days");
                check(!old.alive()&&old.bodyIds().isEmpty()&&world().getBlock(OLD_X,Y,Z)==BlockType.AIR,"old territory respawned after heart destruction");
                check(old.population()==oldPopulationAfterDeaths,"dead heart's pool grew after clearing");
                previousNightDay=day;clearedNights++;
                if(clearedNights<3){startClearedNight();next(9);return;}
                check(otherRecord()==null,"native seed founded before its expected delay");
                shot("lair-05-cleared-third-night");
                System.out.println("LAIR_GAME cleared PASS heldMouseInteraction=true normalDamage=true separateNights=3 oldHeartDead=true noOldRespawn=true");
                command("/ecology advance 5");clearToasts();next(10);
            }else if(stage==10&&state.equals("PLAYING")){
                check(seconds<20,"owner day advance did not found another native cave nest");
                colony=otherRecord();if(colony==null||aliveIds(colony).size()!=4)return;
                check(Math.hypot(colony.x()-OLD_X,colony.z()-Z)>=48,"new nest reused the cleared territory");
                check(Math.hypot(colony.x()-22,colony.z()-23)>=32,"new nest founded near the actual chest base");
                check(world().getSkyLight(colony.x()+1,colony.y(),colony.z()+1)==0&&world().getBlockLightWorld(colony.x()+1,colony.y(),colony.z()+1)==0,"colony is not in a dark cave");
                check(Ecology.day(clock().gameTime())==8&&colony.lastDay()==8,"new colony did not found on day eight");
                check(websAt(colony)==0,"new colony grew silk before the first day after its birth");
                savedIds=aliveIds(colony);
                System.out.println("LAIR_GAME colony PASS ownerAdvanceDays=5 day="+Ecology.day(clock().gameTime())+" newHeart="+colony.x()+","+colony.y()+","+colony.z()+" oldDistance="+Math.hypot(colony.x()-OLD_X,colony.z()-Z)+" avoidsBaseAndObservedOldPath=true emitted="+savedIds);
                findColonyPose();command("/tp "+colonyViewX+" "+colonyViewY+" "+colonyViewZ);
                ((Inventory)get(game,"inventory")).set(0,ItemStack.of("torch"));
                command("/ecology advance 1");clearToasts();
                next(11);
            }else if(stage==11&&state.equals("PLAYING")){
                colonyPose();if(seconds<2)return;
                savedWebs=websAt(colony);
                check(Ecology.day(clock().gameTime())==9&&savedWebs>=1&&savedWebs<=2,"first next-day silk growth must be one or two webs");
                System.out.println("LAIR_GAME webBirth PASS birthDay=8 birthWebs=0 nextDay=9 webs="+savedWebs);
                call(game,"saveAll");SaveManager save=(SaveManager)get(game,"save");save.flushAndAwait();
                savedEcology=save.loadLevel(worldId).extraSections.get(Ecology.SAVE_SECTION);check(savedEcology!=null,"Game save omitted lair ecology");
                call(game,"handleMenuAction",new Class<?>[]{MenuAction.class},MenuAction.of(MenuAction.Kind.MAIN_MENU));
                check(get(game,"world")==null,"native menu did not unload the world");
                check((boolean)call(game,"startWorld",new Class<?>[]{String.class},worldId),"native saved lair world reopen failed");next(12);
            }else if(stage==12&&get(game,"world")!=null){
                check(session().ecology().enabled(),"save reopen lost Living World rule");
                Map<String,byte[]> sections=new LinkedHashMap<>();session().ecology().writeSections(sections);
                check(Arrays.equals(savedEcology,sections.get(Ecology.SAVE_SECTION)),"initial reopen changed saved lair records/body ownership");
                old=oldRecord();colony=otherRecord();check(old!=null&&!old.alive()&&colony!=null&&colony.alive(),"reopen lost dead old/alive new heart state");
                check(colony.bodyIds().equals(savedIds),"reopen changed colony's saved emitted IDs");
                if(!player().flying)command("/fly");next(13);
            }else if(stage==13&&state.equals("PLAYING")){
                check(seconds<25,"Game did not restore saved spiders/chunks");
                colonyPose();clearToasts();identityAndCaps();
                if(!aliveIds(colony).equals(savedIds)||world().getChunkIfExists(1,1)==null||seconds<2)return;
                check(world().getBlock(OLD_X,Y,Z)==BlockType.AIR,"reopen resurrected old heart");
                check(world().getBlock(colony.x(),colony.y(),colony.z())==BlockType.SPIDER_NEST,"reopen lost new physical heart");
                check(Ecology.day(clock().gameTime())==9&&websAt(colony)==savedWebs,"reopen changed the exact first-day silk growth");
                Set<Long> all=new HashSet<>();for(Mob mob:session().entities().mobs)check(all.add(mob.id),"reopen duplicated active body");
                for(Chunk chunk:world().getLoadedChunks())for(var body:chunk.copyPendingMobs())check(all.add(body.id()),"reopen duplicated a pending/active body");
                shot("lair-06-new-colony-after-reopen");
                System.out.println("LAIR_GAME saveReopen PASS oldHeartDead=true colonyAlive=true preservedBodyIds="+savedIds+" noActivePendingDuplicates=true");
                System.out.println("LAIR_GAME PASS captures=6 nativeFrames="+frame+" isolatedSaves="+System.getProperty("mineclone.savesDir"));next(14);glfwSetWindowShouldClose(getHandle(),true);
            }
        }
        void startClearedNight()throws Exception{command("/time set noon");command("/time add 24");command("/time set midnight");clearToasts();}
        void shot(String name)throws Exception{
            int width=getWidth(),height=getHeight();var pixels=org.lwjgl.system.MemoryUtil.memAlloc(width*height*4);
            try{
                glReadPixels(0,0,width,height,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
                var image=new java.awt.image.BufferedImage(width,height,java.awt.image.BufferedImage.TYPE_INT_RGB);
                for(int y=0;y<height;y++)for(int x=0;x<width;x++){
                    int at=(y*width+x)*4;image.setRGB(x,height-1-y,((pixels.get(at)&255)<<16)|((pixels.get(at+1)&255)<<8)|(pixels.get(at+2)&255));
                }
                Path file=output.resolve(name+".png");javax.imageio.ImageIO.write(image,"png",file.toFile());
                System.out.println("LAIR_GAME screenshot="+file+" observer="+player().position+" camera="+player().camera.position+" forward="+player().camera.forward());
            }finally{org.lwjgl.system.MemoryUtil.memFree(pixels);}
        }
    }

    /** Two actual closed cave rooms; the second has only naturally discovered, unlit candidate cells. */
    private static void buildFixture(World world){
        world.batchBlocks(()->{
            for(int centre:new int[]{OLD_X,OTHER_X})for(int x=centre-10;x<=centre+10;x++)for(int z=Z-10;z<=Z+10;z++){
                for(int y=FLOOR;y<=Y+4;y++){
                    boolean wall=x==centre-10||x==centre+10||z==Z-10||z==Z+10;
                    world.setBlock(x,y,z,y==FLOOR||y==Y+4||wall?BlockType.STONE:BlockType.AIR);
                }
            }
            // A lit approach makes the authored old heart readable, without lighting the distant colony cave.
            world.setBlock(14,Y,21,BlockType.TORCH);
            world.setBlock(22,Y,23,BlockType.CHEST);
            world.setBlock(OLD_X,Y,Z,BlockType.SPIDER_NEST);
        });
    }

    /** java -cp 'out;libs/*' tools/LairGameSmoke.java out-test/lair-game */
    public static void main(String[] args)throws Exception{
        Path output=Path.of(args.length>0?args[0]:"out-test/lair-game").toAbsolutePath().normalize();
        check(output.startsWith(Path.of("out-test").toAbsolutePath().normalize()),"native acceptance output must stay under out-test");
        Files.createDirectories(output);Path saves=Files.createTempDirectory(output,"saves-");
        System.setProperty("mineclone.savesDir",saves.toString());System.setProperty("mineclone.checkThreadOwnership","true");
        Review window=new Review(output);window.init();boolean normal=false;
        try{
            window.game=new Game(window,false);((Input)get(window.game,"input")).setGrabAllowed(false);
            set(window.game,"renderRadius",6);set(window.game,"guiScale",1);set(window.game,"mouseSensitivity",0f);
            window.game.run();normal=true;
            if(window.failure!=null)throw new AssertionError("Native lair acceptance failed",window.failure);
            check(window.stage==14,"native lair acceptance did not complete");
        }finally{
            if(!normal&&window.game!=null)try{call(window.game,"cleanup");}catch(Exception failure){failure.printStackTrace();}
            window.destroy();
        }
    }
}
