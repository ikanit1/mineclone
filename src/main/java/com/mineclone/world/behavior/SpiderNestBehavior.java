package com.mineclone.world.behavior;

import com.mineclone.sim.WorldClock;
import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.World;

/** A living nest slowly spins silk through its connected, loaded cave (LW-04/05). */
public final class SpiderNestBehavior implements BlockBehavior {
    public static final int RADIUS = 8, WEBS_PER_DAY = 2, MAX_WEBS = 32, MAX_CATCHUP_DAYS = 60;
    public static final long INTERVAL = WorldClock.TICKS_PER_DAY;
    private static final int SIDE = RADIUS * 2 + 1, CELLS = SIDE * SIDE * SIDE;
    private static final int[][] DIRECTIONS = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};

    @Override public void onPlaced(World world, int x, int y, int z, byte meta) {
        world.scheduleTick(x, y, z, BlockType.SPIDER_NEST, INTERVAL);
    }

    @Override public void scheduledTick(World world, int x, int y, int z, byte meta, long lateBy) {
        Chunk heart = chunk(world, x, y, z);
        if (heart == null || heart.isReadOnly() || world.getBlock(x,y,z) != BlockType.SPIDER_NEST) return;
        // Requeue even while OFF. Existing saved nests resume on the following day after ON.
        onPlaced(world, x, y, z, meta);
        if (!world.livingWorldEnabled()) return;

        boolean[] seen = new boolean[CELLS];
        int[] queue = new int[CELLS], candidates = new int[CELLS];
        int head = 0, tail = 1, count = 0, webs = 0;
        queue[0] = index(0,0,0);
        seen[queue[0]] = true;
        while (head < tail) {
            int cell = queue[head++], dx = cell % SIDE - RADIUS;
            int dz = cell / SIDE % SIDE - RADIUS, dy = cell / (SIDE * SIDE) - RADIUS;
            if (head > 1) {
                BlockType block = world.getBlock(x+dx,y+dy,z+dz);
                if (block == BlockType.WEB) webs++;
                else if (attached(world,x+dx,y+dy,z+dz)) candidates[count++] = cell;
            }
            for (int[] direction : DIRECTIONS) {
                int nx=dx+direction[0], ny=dy+direction[1], nz=dz+direction[2];
                if (nx*nx+ny*ny+nz*nz > RADIUS*RADIUS) continue;
                int next = index(nx,ny,nz);
                if (seen[next]) continue;
                seen[next] = true;
                Chunk c = chunk(world,x+nx,y+ny,z+nz);
                if (c == null || c.isReadOnly()) continue;
                BlockType block = world.getBlock(x+nx,y+ny,z+nz);
                // A solid wall, fluid, plant or unloaded cell never becomes a path to another room.
                if ((block == BlockType.AIR || block == BlockType.WEB)
                        && world.getSkyLight(x+nx,y+ny,z+nz) == 0) queue[tail++] = next;
            }
        }
        long day = world.scheduledTickTime() / INTERVAL;
        long elapsedDays=Math.min(MAX_CATCHUP_DAYS,1+Math.max(0,lateBy)/INTERVAL);
        int budget=(int)Math.min(WEBS_PER_DAY*elapsedDays,Math.max(0,MAX_WEBS-webs));
        for (int n=0; n < budget && count > 0; n++) {
            int best=0;
            long bestScore=Long.MAX_VALUE;
            for (int i=0;i<count;i++) {
                int cell=candidates[i], dx=cell%SIDE-RADIUS;
                int dz=cell/SIDE%SIDE-RADIUS, dy=cell/(SIDE*SIDE)-RADIUS;
                // Prefer silk near the heart, break ties deterministically without session RNG.
                long score=(long)(dx*dx+dy*dy+dz*dz)*65536
                        + (mix(world.seed ^ (long)x*341873128712L ^ (long)z*132897987541L
                        ^ (long)y*524287L ^ day ^ cell) & 65535);
                if (score < bestScore) { best=i; bestScore=score; }
            }
            int cell=candidates[best];
            candidates[best]=candidates[--count];
            world.setBlock(x+cell%SIDE-RADIUS,y+cell/(SIDE*SIDE)-RADIUS,z+cell/SIDE%SIDE-RADIUS,BlockType.WEB);
        }
    }

    private static Chunk chunk(World world,int x,int y,int z) {
        return y < 0 || y >= Chunk.SIZE_Y ? null
                : world.getChunkIfExists(Math.floorDiv(x,Chunk.SIZE_X),Math.floorDiv(z,Chunk.SIZE_Z));
    }

    private static boolean attached(World world,int x,int y,int z) {
        for (int[] d:DIRECTIONS) {
            if (chunk(world,x+d[0],y+d[1],z+d[2]) == null) continue;
            BlockType block=world.getBlock(x+d[0],y+d[1],z+d[2]);
            if (block.solid || block == BlockType.WEB) return true;
        }
        return false;
    }

    private static int index(int x,int y,int z) { return (y+RADIUS)*SIDE*SIDE+(z+RADIUS)*SIDE+x+RADIUS; }
    private static long mix(long value) {
        value=(value^(value>>>30))*0xbf58476d1ce4e5b9L;
        value=(value^(value>>>27))*0x94d049bb133111ebL;
        return value^(value>>>31);
    }
}
