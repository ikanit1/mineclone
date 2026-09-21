package com.mineclone.world;
import java.util.*;

/** Overworld lava: one scheduled wave every 1.5s, three lateral cells, persistent falls. */
public final class LavaSimulator {
    public static final float TICK_INTERVAL = 1.5f;
    private static final int[][] SIDES = {{1,0},{-1,0},{0,1},{0,-1}};
    private static final LinkedHashSet<Long> active = new LinkedHashSet<>();
    private static final Set<Long> scanned = new HashSet<>();
    private static boolean seeded;
    private record Cell(int x,int y,int z) {}
    private LavaSimulator() {}
    public static void reset() { active.clear(); scanned.clear(); seeded=false; }
    public static void forgetChunk(long k) { active.remove(k); scanned.remove(k); }
    public static void activateChunkIfLava(World w,int cx,int cz) {
        Chunk c=w.getChunkIfExists(cx,cz);
        if(c!=null && scanned.add(World.key(cx,cz)) && c.lavaCellCount()>0) active.add(World.key(cx,cz));
    }
    public static void activateAround(World w,int x,int z) {
        int cx=Math.floorDiv(x,16),cz=Math.floorDiv(z,16);
        for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++)
            if(w.getChunkIfExists(cx+dx,cz+dz)!=null) active.add(World.key(cx+dx,cz+dz));
    }
    private static boolean loaded(World w,int x,int y,int z) {
        return y>=0 && y<Chunk.SIZE_Y && w.getChunkIfExists(Math.floorDiv(x,16),Math.floorDiv(z,16))!=null;
    }
    private static int level(World w,int x,int y,int z) { return w.getBlockMeta(x,y,z)&15; }
    private static boolean lava(World w,int x,int y,int z) { return w.getBlock(x,y,z)==BlockType.LAVA; }
    private static boolean fillable(World w,int x,int y,int z) {
        return loaded(w,x,y,z) && w.getBlock(x,y,z)==BlockType.AIR;
    }
    private static int supported(World w,Cell p) {
        if(lava(w,p.x,p.y+1,p.z)) return 8; // falling, never a source
        int best=8;
        for(int[] d:SIDES) if(lava(w,p.x+d[0],p.y,p.z+d[1])) {
            int l=level(w,p.x+d[0],p.y,p.z+d[1]);
            best=Math.min(best,(l>=8?0:l)+2);
        }
        return best==8?-1:best;
    }
    public static void tick(World w) {
        if(!seeded) { seeded=true; for(Chunk c:w.getLoadedChunks()) activateChunkIfLava(w,c.cx,c.cz); }
        List<Cell> wave=new ArrayList<>();
        Iterator<Long> it=active.iterator();
        int chunks=0;
        while(it.hasNext() && chunks++<64) {
            long k=it.next();
            Chunk c=w.getChunkIfExists((int)(k>>32),(int)k);
            if(c!=null && !wave.isEmpty() && wave.size()+c.lavaCellCount()>768) break;
            it.remove();
            if(c==null) continue;
            for(int i=0;i<c.lavaCellCount();i++) {
                int p=c.lavaCellAt(i);
                wave.add(new Cell(c.cx*16+p%16,p/256,c.cz*16+(p/16)%16));
            }
        }
        // All proposals read the same state; newly added cells wait one tick.
        Map<Cell,Integer> changes=new LinkedHashMap<>(), additions=new LinkedHashMap<>();
        for(Cell p:wave) {
            int l=level(w,p.x,p.y,p.z);
            if(l!=0) {
                int wanted=supported(w,p);
                if(wanted!=l) { changes.put(p,wanted); continue; }
            }
            if(fillable(w,p.x,p.y-1,p.z)) {
                additions.put(new Cell(p.x,p.y-1,p.z),8); continue;
            }
            if(lava(w,p.x,p.y-1,p.z) && l>=8) continue;
            int next=(l>=8?0:l)+2;
            if(next>6) continue;
            int best=99;
            int[] costs=new int[4];
            for(int i=0;i<4;i++) {
                costs[i]=dropDistance(w,p.x+SIDES[i][0],p.y,p.z+SIDES[i][1],p.x,p.z,2);
                best=Math.min(best,costs[i]);
            }
            for(int i=0;i<4;i++) {
                if(best<99 && costs[i]!=best) continue;
                Cell n=new Cell(p.x+SIDES[i][0],p.y,p.z+SIDES[i][1]);
                if(fillable(w,n.x,n.y,n.z) || (lava(w,n.x,n.y,n.z)
                        && level(w,n.x,n.y,n.z)>next && level(w,n.x,n.y,n.z)<8))
                    additions.merge(n,next,Math::min);
            }
        }
        for(var e:changes.entrySet()) {
            Cell p=e.getKey(); int l=e.getValue();
            w.setBlock(p.x,p.y,p.z,l<0?BlockType.AIR:BlockType.LAVA,(byte)Math.max(0,l));
        }
        for(var e:additions.entrySet()) {
            Cell p=e.getKey();
            if(!changes.containsKey(p) && (fillable(w,p.x,p.y,p.z)
                    || (lava(w,p.x,p.y,p.z) && level(w,p.x,p.y,p.z)!=0)))
                w.setBlock(p.x,p.y,p.z,BlockType.LAVA,e.getValue().byteValue());
        }
        for(Cell p:wave) {
            if(!lava(w,p.x,p.y,p.z)) continue;
            boolean reacted=false;
            for(int[] d:SIDES) if(FluidThermodynamics.isWater(w.getBlock(p.x+d[0],p.y,p.z+d[1]))) {
                FluidThermodynamics.react(w,p.x,p.y,p.z,p.x+d[0],p.y,p.z+d[1]);
                reacted=true; break;
            }
            if(!reacted && FluidThermodynamics.isWater(w.getBlock(p.x,p.y+1,p.z)))
                FluidThermodynamics.react(w,p.x,p.y,p.z,p.x,p.y+1,p.z);
            else if(!reacted && FluidThermodynamics.isWater(w.getBlock(p.x,p.y-1,p.z)))
                FluidThermodynamics.react(w,p.x,p.y,p.z,p.x,p.y-1,p.z);
        }
    }
    private static int dropDistance(World w,int x,int y,int z,int px,int pz,int depth) {
        if(!fillable(w,x,y,z) && !(loaded(w,x,y,z)&&lava(w,x,y,z)&&level(w,x,y,z)!=0)) return 99;
        if(fillable(w,x,y-1,z)) return 0;
        if(depth==0) return 99;
        int best=99;
        for(int[] d:SIDES) {
            if(x+d[0]==px && z+d[1]==pz) continue;
            best=Math.min(best,1+dropDistance(w,x+d[0],y,z+d[1],x,z,depth-1));
        }
        return best;
    }
}
