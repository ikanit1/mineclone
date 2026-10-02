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

/** Real Game/Input story C. Only existing random fire/ash callbacks are explicitly accelerated. */
public final class BurnScarsGameSmoke {
    static final long SEED = 20261002L;
    static final int X = 32, Y = 86, Z = 32, RADIUS = 24;
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
        int[] ignition;Set<Long> burned=new HashSet<>(),newAnimalIds=new TreeSet<>();byte[] savedEcology;long visualReadyAt;
        final Set<Long> initialRoots=new HashSet<>(),initialAnimalIds=new HashSet<>();
        Review(Path p){super("Mineclone forest fire and recovery story C",1280,720,false);output=p;}
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
            if(visualReadyAt==0)System.out.println("BURN_GAME youngTreeCapture actualNewRoot="+x+","+(Y+1)+","+z+" recordedBurnedGround=true");
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
                input().injectMouseButton(GLFW_MOUSE_BUTTON_RIGHT,false);ignitions++;ignition=null;mouseDown=false;return;
            }
            boolean aimed=false;StringBuilder rays=new StringBuilder();
            for(int r=2;r<=3&&!aimed;r++)for(int n=0;n<16&&!aimed;n++){
                double angle=n*Math.PI/8;float px=ignition[0]+.5f+(float)Math.cos(angle)*r,pz=ignition[2]+.5f+(float)Math.sin(angle)*r,py=Y+1.01f;
                if(BodyCollision.overlaps(world(),px,py,pz,Player.WIDTH,Player.HEIGHT)){rays.append(" blockedBody@").append(px).append(',').append(pz);continue;}
                org.joml.Vector3f eye=new org.joml.Vector3f(px,py+Player.EYE_HEIGHT,pz),dir=new org.joml.Vector3f(ignition[0]+.5f,Y+.99f,ignition[2]+.5f).sub(eye);
                var hit=Raycaster.cast(world(),eye,dir.normalize(),5);
                rays.append(" hit=").append(hit==null?"null":hit.x+","+hit.y+","+hit.z+"/"+hit.nx+","+hit.ny+","+hit.nz+" "+world().getBlock(hit.x,hit.y,hit.z));
                if(hit!=null&&hit.x==ignition[0]&&hit.y==Y&&hit.z==ignition[2]&&hit.ny==1){pose(px,py,pz,ignition[0]+.5f,Y+.99f,ignition[2]+.5f);aimed=true;}
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
        @Override public void update(){try{frame++;check(System.nanoTime()-started<420_000_000_000L,"native forest acceptance timed out at stage "+stage);check(glGetError()==GL_NO_ERROR,"native forest OpenGL error");if(game!=null)seconds+=(float)get(game,"lastDt");tick();}catch(Throwable t){failure=t;t.printStackTrace();glfwSetWindowShouldClose(getHandle(),true);}super.update();}
        void tick()throws Exception{
            if(game==null)return;String state=get(game,"state").toString();
            if(get(game,"session")!=null&&stage<8)set(session(),"spawnTimer",3600f);
            if(cow!=null&&!cow.dead){
                if(!cowRetreatIssued&&get(cow,"burnDestination")!=null){cowRetreatIssued=true;cowStartX=cow.position.x;cowStartZ=cow.position.z;}
                if(cowRetreatIssued&&Math.hypot(cow.position.x-cowStartX,cow.position.z-cowStartZ)>3)cowMoved=true;
            }
            if(stage==0&&frame>4){call(game,"createWorld",new Class<?>[]{WorldSettings.class},new WorldSettings("Forest fire native story C",SEED,GameMode.CREATIVE));worldId=(String)get(game,"worldId");next(1);}
            else if(stage==1&&state.equals("PLAYING")){
                if(!streamRequested){command("/weather clear");command("/time set noon");command("/tp "+X+" "+(Y+2)+" "+Z);command("/fly");streamRequested=true;next(1);return;}
                if(seconds<3)return;boolean loaded=true;for(int cx=-2;cx<=6;cx++)for(int cz=-2;cz<=6;cz++)loaded&=world().getChunkIfExists(cx,cz)!=null;
                if(!loaded){check(seconds<30,"Game did not stream forest/crown chunks");return;}
                check(!Weather.snowsAt(world().biomes.biomeAt(X,Z),Y+2)&&Weather.precipitates(world().biomes.biomeAt(X,Z)),"forest fixture needs a warm rainy biome below snow altitude");
                ChunkLoader loader=(ChunkLoader)get(game,"loader");for(int cx=-7;cx<=11;cx++)for(int cz=-7;cz<=11;cz++)loader.loadNow(cx,cz);
                check(!session().ecology().enabled(),"initial native cast must precede finite adoption");
                session().entities().mobs.clear();session().entities().items.clear();for(Chunk c:world().getLoadedChunks()){c.setPendingMobs(List.of());c.modified=true;}build(world(),X,Y,Z);
                for(int[] t:TREES)initialRoots.add(cell(X+t[0],Z+t[1]));
                cow=new Mob(MobType.COW,X-14.5f,Y+1.001f,Z-10.5f,new Random(SEED));cow.onGround=true;session().entities().mobs.add(cow);
                command("/ecology on");cowId=cow.id;cowStartX=cow.position.x;cowStartZ=cow.position.z;check(cowId>0,"initial finite cow has no ID");
                for(Mob m:session().entities().mobs)if(!m.dead&&m.persistent()&&m.id>0)initialAnimalIds.add(m.id);
                call(session().ecology(),"refreshHabitat");beforeCapacity=capacity();beforeGreenery=region().averageGreenery();burnDay=Ecology.day(clock().gameTime());
                ((Inventory)get(game,"inventory")).set(0,ItemStack.of("fire"));overview();clearToasts();next(2);
            }else if(stage==2&&state.equals("PLAYING")&&seconds>1){shot("burn-01-before");next(3);}
            else if(stage==3&&state.equals("PLAYING")){
                check(seconds<90,"actual forest fuel did not burn into enough scars");
                if(ignition!=null||count(BlockType.FIRE,Y+1,Y+12)==0){
                    if(burned.size()>=192&&oldRoots()==0){overview();next(4);return;}
                    igniteThroughInput();if(ignition!=null)return;
                }
                if(ignitions==1&&fireCallbacks==0){
                    overview();if(firstFireFrame==0){firstFireFrame=frame;return;}if(frame-firstFireFrame<30)return;
                    shot("burn-02-actual-fire");System.out.println("BURN_GAME ignition PASS actualRMB=true firstFire=true acceleratedRandomCallbacks=true");
                }
                pumpFire(false);observeBurned();
            }else if(stage==4&&state.equals("PLAYING")){
                overview();pumpFire(false);observeBurned();
                if(count(BlockType.FIRE,Y+1,Y+12)>0){check(seconds<30,"remaining real flames did not expire");return;}
                scarView();if(!renderSettled())return;
                call(session().ecology(),"refreshHabitat");afterCapacity=capacity();afterGreenery=region().averageGreenery();
                check(oldRoots()==0,"old mature trees were not actually consumed by fire");check(scars()>0&&scars()<=16,"actual fire did not create bounded persisted scars");check(afterCapacity<beforeCapacity&&afterGreenery<beforeGreenery,"actual fire did not lower observed habitat capacity");
                check(cow!=null&&!cow.dead&&cow.id==cowId,"finite fixture cow died instead of leaving the scorched ground");
                shot("burn-03-day-zero-scars");System.out.println("BURN_GAME scorched PASS burnedColumns="+burned.size()+" originalMatureRootsConsumed=6 survivingCrownsAllowed=true scars="+scars()+" capacity="+beforeCapacity+"->"+afterCapacity+" greenery="+beforeGreenery+"->"+afterGreenery+" fireCallbacks="+fireCallbacks+" actualIgnitions="+ignitions);
                command("/weather rain");next(5);
            }else if(stage==5&&state.equals("PLAYING")){
                overview();Object atmosphere=get(game,"atmosphere");float rain=(float)call(atmosphere,"rain");
                if(rain<BlockTicker.RAIN_EXTINGUISH){check(seconds<18,"actual native atmosphere never became rain");return;}
                pumpFire(true);if(count(BlockType.ASH,Y+1,Y+12)>0&&seconds<12)return;
                if(!cowMoved){check(seconds<25,"initial tracked cow did not physically navigate away after the burn refuge was assigned");return;}
                System.out.println("BURN_GAME retreat PASS originalCowID="+cowId+" original="+cowStartX+","+cowStartZ+" now="+cow.position+" physicalMovement=true rain="+rain+" ashCallbacks="+ashCallbacks);
                command("/weather clear");command("/ecology advance 7");clearToasts();next(6);
            }else if(stage==6&&state.equals("PLAYING")){
                overview();if(seconds<3)return;check(Ecology.day(clock().gameTime())==burnDay+7,"owner command did not advance exactly seven days");
                if(recoveredGrass()<burned.size()/2||newTrees()==0){check(seconds<15,"day seven has no real grass and scheduled young trees in the burned footprint");return;}
                youngTreeView();if(!renderSettled())return;
                call(session().ecology(),"refreshHabitat");check(capacity()>afterCapacity,"recovered habitat did not increase capacity");
                shot("burn-04-day-seven-young-forest");System.out.println("BURN_GAME recovery PASS elapsedDays=7 recoveredGrass="+recoveredGrass()+" newScheduledTrees="+newTrees()+" capacity="+capacity()+" exactBurnedFootprint=true");next(7);
            }else if(stage==7&&state.equals("PLAYING")){
                // Returning bodies are materialized from finite populations through the ordinary 2-second path.
                pose(X,Y+1.001f,Z+44,X,Y+1,Z+90);set(session(),"spawnTimer",0f);next(8);
            }else if(stage==8&&state.equals("PLAYING")){
                pose(X,Y+1.001f,Z+44,X,Y+1,Z+90);check(seconds<65,"ordinary finite materializer did not return animals to recovered forest");
                for(Mob m:session().entities().mobs)if(!m.dead&&m.persistent()&&m.type.temper==MobType.Temper.PASSIVE&&!initialAnimalIds.contains(m.id)
                        &&Math.abs(m.position.x-X)<=RADIUS&&Math.abs(m.position.z-Z)<=RADIUS){
                    int bx=(int)Math.floor(m.position.x),bz=(int)Math.floor(m.position.z);
                    if(world().getBlock(bx,Y,bz)==BlockType.GRASS)newAnimalIds.add(m.id);
                }
                if(newAnimalIds.isEmpty())return;System.out.println("BURN_GAME animalReturn PASS finiteMaterializedIDs="+newAnimalIds+" originalCowSurvived="+(!cow.dead)+" identityReturnClaimed=false normalCaps=true");
                overview();next(9);
            }else if(stage==9&&state.equals("PLAYING")&&seconds>.5f){
                shot("burn-05-recovered-animals");call(game,"saveAll");SaveManager save=(SaveManager)get(game,"save");save.flushAndAwait();savedEcology=save.loadLevel(worldId).extraSections.get(Ecology.SAVE_SECTION);
                check(savedEcology!=null,"Game save omitted burn ecology");call(game,"handleMenuAction",new Class<?>[]{MenuAction.class},MenuAction.of(MenuAction.Kind.MAIN_MENU));
                check(get(game,"world")==null,"menu did not unload burned world");check((boolean)call(game,"startWorld",new Class<?>[]{String.class},worldId),"saved forest reopen failed");next(10);
            }else if(stage==10&&get(game,"world")!=null){
                check(session().ecology().enabled(),"reopen lost Living World opt-in");Map<String,byte[]> sections=new LinkedHashMap<>();session().ecology().writeSections(sections);
                check(Arrays.equals(savedEcology,sections.get(Ecology.SAVE_SECTION)),"initial reopen changed saved scars/population/reservations");if(!player().flying)command("/fly");next(11);
            }else if(stage==11&&state.equals("PLAYING")){
                overview();check(seconds<30,"reopened forest bodies did not restore");Set<Long> ids=new HashSet<>();for(Mob m:session().entities().mobs)check(m.id>0&&ids.add(m.id),"active mob ID duplicated after reopen");
                for(Chunk c:world().getLoadedChunks())for(var m:c.copyPendingMobs())check(ids.add(m.id()),"active/pending body ID duplicated after reopen");
                if(!ids.containsAll(newAnimalIds)||!ids.contains(cowId)||seconds<2)return;
                check(scars()>0&&recoveredGrass()>0&&newTrees()>0,"reopen lost recovered plants or scar history");clearToasts();shot("burn-06-saved-forest-reopen");
                System.out.println("BURN_GAME saveReopen PASS scars="+scars()+" originalCowID="+cowId+" returnedFiniteIDs="+newAnimalIds+" uniqueActivePendingIDs=true");
                System.out.println("BURN_GAME PASS captures=6 nativeFrames="+frame+" elapsedDays=7 actualInputIgnition=true productionRandomCallbacksAccelerated=true isolatedSaves="+System.getProperty("mineclone.savesDir"));next(12);glfwSetWindowShouldClose(getHandle(),true);
            }
        }
        void shot(String name)throws Exception{
            int width=getWidth(),height=getHeight();var pixels=org.lwjgl.system.MemoryUtil.memAlloc(width*height*4);
            try{glReadPixels(0,0,width,height,GL_RGBA,GL_UNSIGNED_BYTE,pixels);var image=new java.awt.image.BufferedImage(width,height,java.awt.image.BufferedImage.TYPE_INT_RGB);
                for(int y=0;y<height;y++)for(int x=0;x<width;x++){int p=(y*width+x)*4;image.setRGB(x,height-1-y,((pixels.get(p)&255)<<16)|((pixels.get(p+1)&255)<<8)|(pixels.get(p+2)&255));}
                Path file=output.resolve(name+".png");javax.imageio.ImageIO.write(image,"png",file.toFile());System.out.println("BURN_GAME screenshot="+file+" observer="+player().position);
            }finally{org.lwjgl.system.MemoryUtil.memFree(pixels);}
        }
    }
    public static void main(String[] args)throws Exception{
        Path output=Path.of(args.length==0?"out-test/burn-game":args[0]).toAbsolutePath().normalize();check(output.startsWith(Path.of("out-test").toAbsolutePath().normalize()),"native output must stay below out-test");
        Files.createDirectories(output);System.setProperty("mineclone.savesDir",Files.createTempDirectory(output,"saves-").toString());System.setProperty("mineclone.checkThreadOwnership","true");
        Review window=new Review(output);window.init();boolean normal=false;
        try{window.game=new Game(window,false);((Input)get(window.game,"input")).setGrabAllowed(false);set(window.game,"renderRadius",6);set(window.game,"guiScale",1);set(window.game,"mouseSensitivity",0f);
            window.game.run();normal=true;if(window.failure!=null)throw new AssertionError("Native burn acceptance failed",window.failure);check(window.stage==12,"native forest lifecycle did not complete");
        }finally{if(!normal&&window.game!=null)try{call(window.game,"cleanup");}catch(Exception t){t.printStackTrace();}window.destroy();}
    }
}
